package uk.gov.hmcts.reform.deedpoll.domain.referrals;

import org.hibernate.Hibernate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uk.gov.hmcts.reform.deedpoll.domain.ConflictError;
import uk.gov.hmcts.reform.deedpoll.domain.FieldError;
import uk.gov.hmcts.reform.deedpoll.domain.ForbiddenError;
import uk.gov.hmcts.reform.deedpoll.domain.InvalidInputError;
import uk.gov.hmcts.reform.deedpoll.domain.auth.Capability;
import uk.gov.hmcts.reform.deedpoll.domain.auth.CurrentUser;
import uk.gov.hmcts.reform.deedpoll.domain.deedpoll.CasePaging;
import uk.gov.hmcts.reform.deedpoll.domain.masters.Master;
import uk.gov.hmcts.reform.deedpoll.domain.masters.MasterEmailTransactions;
import uk.gov.hmcts.reform.deedpoll.domain.settings.BankHolidays;
import uk.gov.hmcts.reform.deedpoll.domain.settings.ProcessSettingsService;
import uk.gov.hmcts.reform.deedpoll.domain.settings.ReferralWindowTimes;
import uk.gov.hmcts.reform.deedpoll.entity.AuditClock;
import uk.gov.hmcts.reform.deedpoll.entity.Referral;
import uk.gov.hmcts.reform.deedpoll.entity.enums.ReferralState;
import uk.gov.hmcts.reform.deedpoll.entity.enums.Urgency;
import uk.gov.hmcts.reform.deedpoll.entity.enums.WindowSlot;
import uk.gov.hmcts.reform.deedpoll.repository.MasterRepository;
import uk.gov.hmcts.reform.deedpoll.repository.MasterWindowCount;
import uk.gov.hmcts.reform.deedpoll.repository.ReferralRepository;
import uk.gov.hmcts.reform.deedpoll.repository.ReferralWindowLocks;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * The referral queue (plan F1): the referrals waiting to be sent to a Master, or those sent and not yet directed,
 * across every case, with the referral windows a Team leader or Delivery Manager sends them in.
 *
 * <p><strong>{@link Capability#ALLOCATE_REFERRALS} first</strong>, before the request's own parameters are read and
 * before any query, so a caseworker without it is a 403 that has read nothing (decision F-2).
 *
 * <p>Not {@code ReferralService}, which is about one case's referral; this is about every case at once, which is why
 * its repository reads are the listed exceptions to {@code ReferralRepository}'s scoping rule.
 */
@Service
public class ReferralQueue {

    /** The query parameter, and the field a refusal names. */
    public static final String STATE = "state";

    /** The states the queue lists: waiting to be sent, and sent and not yet directed. */
    public static final List<ReferralState> STATES = List.of(ReferralState.Open, ReferralState.Claimed);

    static final String INVALID_STATE = "Referral queue state is not valid";

    /**
     * Every 409 on an {@code allocations[i].referralId}, whatever the referral is instead.
     */
    static final String NO_LONGER_WAITING = "This referral is no longer waiting to be sent";

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm", Locale.UK);

    /**
     * {@code ReferralService}'s day format, with the weekday: the next window can be days away.
     */
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", Locale.UK);

    private final ReferralRepository referrals;
    private final MasterRepository masters;
    private final ProcessSettingsService settings;
    private final BankHolidays bankHolidays;
    private final ReferralService referralService;
    private final ReferralWindowLocks windowLocks;
    private final MasterEmailTransactions masterEmails;

    public ReferralQueue(ReferralRepository referrals, MasterRepository masters, ProcessSettingsService settings,
                         BankHolidays bankHolidays, ReferralService referralService, ReferralWindowLocks windowLocks,
                         MasterEmailTransactions masterEmails) {
        this.referrals = referrals;
        this.masters = masters;
        this.settings = settings;
        this.bankHolidays = bankHolidays;
        this.referralService = referralService;
        this.windowLocks = windowLocks;
        this.masterEmails = masterEmails;
    }

    /**
     * One page of the queue.
     *
     * @param state {@code Open} (the default, when absent or blank) or {@code Claimed}, matched exactly
     * @param page the 1-based page, as {@link CasePaging#parse} reads it
     * @param size the page size, as {@link CasePaging#parse} reads it, defaulting to {@link CasePaging#DEFAULT_SIZE}
     * @throws ForbiddenError    without {@link Capability#ALLOCATE_REFERRALS}, before anything else
     * @throws InvalidInputError for a state other than those two, or paging {@link CasePaging} refuses
     */
    @Transactional(readOnly = true)
    public ReferralQueuePage list(CurrentUser caller, String state, String page, String size) {
        caller.require(Capability.ALLOCATE_REFERRALS);
        ReferralState wanted = parseState(state);
        CasePaging paging = CasePaging.parse(page, size);
        return list(wanted, paging, AuditClock.now().toInstant(ZoneOffset.UTC));
    }

    /**
     * {@link #list} at a given moment, past the gate, so a test can say which window it falls in.
     */
    ReferralQueuePage list(ReferralState state, CasePaging paging, Instant now) {
        // Unsorted: the order is the query's own, and a Sort here would be appended after it.
        PageRequest request = PageRequest.of(paging.index(), paging.size());
        Page<Referral> found = state == ReferralState.Open
            ? referrals.findQueue(ReferralState.Open, Urgency.Urgent, request)
            : referrals.findSent(ReferralState.Claimed, request);
        return ReferralQueuePage.of(found.getContent(), paging, found.getTotalElements(), windowAt(now));
    }

    /**
     * A batch send (plan F1, decisions F-3 to F-5): each named referral, waiting in the queue, sent to the Master
     * named beside it, in the window open now. One transaction, and <strong>any refusal writes nothing</strong>.
     *
     * <p>In this order, each refusal before anything the next step reads:
     * <ol>
     *   <li>{@link Capability#ALLOCATE_REFERRALS} (403), before any field of the body is read;</li>
     *   <li>the body's shape ({@link ReferralSchemas#parseSend}, 400 naming the entry);</li>
     *   <li>a window must be open now (409, naming when the next one opens);</li>
     *   <li>the window's lock ({@link ReferralWindowLocks}), then the referrals' rows in id order, then the Masters'
     *       in id order -- the one order every send takes, so two cannot deadlock, and the referral-before-Master
     *       order of {@code ReferralService.assign}; once all of them are held the window must still be open
     *       (the same 409), because a send can wait on any of them across the window's close;</li>
     *   <li>each referral must be {@code Open} (409 on {@code allocations[i].referralId}), except that one already
     *       {@code Claimed} by the same Master in this same window is a <strong>replay</strong>, skipped rather than
     *       refused, so a sender whose answer was lost can press Send again;</li>
     *   <li>each Master named for a new one must exist and be available (400 on {@code allocations[i].masterId});</li>
     *   <li>the referrals already sent in the window (decision A-2: in any state since but withdrawn) plus the new
     *       ones must not exceed {@link ProcessSettingsService#windowCap()} (409, the room left and the excess in
     *       words).</li>
     * </ol>
     * Then {@link ReferralService#send} applies each new one, and each Master given at least one is enqueued one
     * {@code SENT} email for the send in this transaction ({@link MasterEmailTransactions#enqueueSent}), which the
     * Master email drain sends after the commit. The lock makes the cap hold under concurrency: a second send in the
     * window waits for the first to commit, and counts what it sent.
     *
     * @throws ForbiddenError    without {@link Capability#ALLOCATE_REFERRALS}, before anything else
     * @throws InvalidInputError for a body of the wrong shape, or a Master who is unknown or unavailable
     * @throws ConflictError     outside a window, for a referral not waiting to be sent, or past the cap
     */
    @Transactional
    public ReferralSend send(CurrentUser caller, Map<String, Object> body) {
        caller.require(Capability.ALLOCATE_REFERRALS);
        List<ReferralAllocation> allocations =
            ReferralSchemas.parseSend(body, ProcessSettingsService.MAX_WINDOW_CAP);
        return send(caller, allocations, AuditClock::now);
    }

    /**
     * {@link #send} at a given moment (UTC wall time, as {@code AuditClock} gives it), past the gate and the shape,
     * so a test can say which window it falls in.
     */
    ReferralSend send(CurrentUser caller, List<ReferralAllocation> allocations, LocalDateTime now) {
        return send(caller, allocations, () -> now);
    }

    /**
     * {@link #send} reading {@code clock} twice: once to know which window's lock to take, and again once that lock and
     * every referral's and Master's row are held, because any of them can be waited on across the window's close. A
     * send that has them all only after its window has closed is refused as any send outside a window is, and the
     * second reading is the moment it is stamped with.
     */
    ReferralSend send(CurrentUser caller, List<ReferralAllocation> allocations, Supplier<LocalDateTime> clock) {
        ReferralWindowTimes times = settings.referralWindows();
        ReferralWindow window = openWindow(clock.get(), times);
        windowLocks.lockForSending(window.date(), window.slot());

        Map<UUID, Referral> locked = referrals.findByIdInForSending(
                allocations.stream().map(ReferralAllocation::referralId).toList()).stream()
            .collect(Collectors.toMap(Referral::getId, Function.identity()));
        List<Integer> fresh = new ArrayList<>();
        List<FieldError> notWaiting = new ArrayList<>();
        for (int i = 0; i < allocations.size(); i++) {
            ReferralAllocation allocation = allocations.get(i);
            Referral referral = locked.get(allocation.referralId());
            if (referral != null && referral.getState() == ReferralState.Open) {
                fresh.add(i);
            } else if (referral == null || !isReplay(referral, allocation, window)) {
                notWaiting.add(new FieldError(entry(i, ReferralSchemas.REFERRAL_ID), NO_LONGER_WAITING));
            }
        }

        Map<UUID, MasterRepository.Assignee> assignees = new HashMap<>();
        fresh.stream()
            .map(i -> allocations.get(i).masterId())
            .distinct()
            // As PostgreSQL orders a uuid, so the order is the same whichever side states it.
            .sorted(Comparator.comparing(UUID::toString))
            .forEach(id -> masters.findAssigneeForUpdate(id)
                .filter(MasterRepository.Assignee::available)
                .ifPresent(assignee -> assignees.put(id, assignee)));

        // Every lock this send waits on is held now, so the window is asked again only here, and this reading is
        // the moment it is stamped with: a send that waited across the window's close is refused, not sent late.
        LocalDateTime now = clock.get();
        Instant at = now.toInstant(ZoneOffset.UTC);
        if (!window.equals(openWindow(now, times))) {
            throw new ConflictError(closed(at, times));
        }
        if (!notWaiting.isEmpty()) {
            throw new ConflictError(NO_LONGER_WAITING, notWaiting);
        }
        List<FieldError> unavailable = fresh.stream()
            .filter(i -> !assignees.containsKey(allocations.get(i).masterId()))
            .map(i -> new FieldError(entry(i, ReferralSchemas.MASTER_ID), ReferralSchemas.SELECT_A_MASTER))
            .toList();
        if (!unavailable.isEmpty()) {
            throw new InvalidInputError(ReferralSchemas.SELECT_A_MASTER, unavailable);
        }

        if (!fresh.isEmpty()) {
            long sent = referrals.countByWindowDateAndWindowSlot(window.date(), window.slot(), ReferralState.Withdrawn);
            int cap = settings.windowCap();
            if (sent + fresh.size() > cap) {
                throw new ConflictError(full(window, Math.max(0, cap - sent), fresh.size()));
            }
        }

        Map<UUID, List<UUID>> sentTo = new LinkedHashMap<>();
        for (int i : fresh) {
            ReferralAllocation allocation = allocations.get(i);
            referralService.send(locked.get(allocation.referralId()), allocation.masterId(),
                                 assignees.get(allocation.masterId()), window, caller, now);
            sentTo.computeIfAbsent(allocation.masterId(), master -> new ArrayList<>()).add(allocation.referralId());
        }
        // One email per Master for this send, however many referrals it gave them (F-6); none for a replay.
        masterEmails.enqueueSent(UUID.randomUUID(), sentTo);
        List<Referral> named = allocations.stream().map(allocation -> locked.get(allocation.referralId())).toList();
        // The controller maps each case's reference and names after this transaction has closed.
        named.forEach(referral -> Hibernate.initialize(referral.getDeedPoll()));
        return new ReferralSend(named, windowAt(at));
    }

    /** The window open at that moment, or the 409 naming when the next one opens. */
    private ReferralWindow openWindow(LocalDateTime now, ReferralWindowTimes times) {
        Instant at = now.toInstant(ZoneOffset.UTC);
        return ReferralWindows.openAt(at, times, bankHolidays::isWorkingDay)
            .orElseThrow(() -> new ConflictError(closed(at, times)));
    }

    /** Already sent to this Master in this window: the same send again, whose answer was lost. */
    private static boolean isReplay(Referral referral, ReferralAllocation allocation, ReferralWindow window) {
        return referral.getState() == ReferralState.Claimed
            && allocation.masterId().equals(referral.getAssignedMasterId())
            && window.date().equals(referral.getWindowDate())
            && window.slot() == referral.getWindowSlot();
    }

    private static String entry(int index, String field) {
        return ReferralSchemas.ALLOCATIONS + "[" + index + "]." + field;
    }

    /** "Referrals can only be sent during a referral window. The next one opens at 14:00 today." */
    private String closed(Instant at, ReferralWindowTimes times) {
        ReferralWindow next = ReferralWindows.nextToOpen(at, times, bankHolidays::isWorkingDay);
        return closed(at, next, ReferralWindows.opensAt(next, times));
    }

    /**
     * The window-closed refusal, naming when {@code next} opens, in {@code Europe/London}: "today", "tomorrow" or the
     * weekday and date.
     */
    static String closed(Instant at, ReferralWindow next, Instant opensAt) {
        LocalDate today = at.atZone(ReferralWindowTimes.ZONE).toLocalDate();
        ZonedDateTime opens = opensAt.atZone(ReferralWindowTimes.ZONE);
        String day;
        if (next.date().equals(today)) {
            day = "today";
        } else if (next.date().equals(today.plusDays(1))) {
            day = "tomorrow";
        } else {
            day = "on " + DAY.format(next.date());
        }
        return "Referrals can only be sent during a referral window. The next one opens at " + TIME.format(opens)
            + " " + day + ".";
    }

    /**
     * "Only 3 more referrals can be sent this morning. Remove 2 and try again.", or with no room at all, "No more
     * referrals can be sent this morning."
     */
    static String full(ReferralWindow window, long room, int requested) {
        String when = window.slot() == WindowSlot.AM ? "this morning" : "this afternoon";
        if (room == 0) {
            return "No more referrals can be sent " + when + ".";
        }
        long excess = requested - room;
        return "Only " + room + " more " + (room == 1 ? "referral" : "referrals") + " can be sent " + when
            + ". Remove " + excess + " and try again.";
    }

    /**
     * The windows at a moment, the cap and the current window's counts: one count of every referral sent in it and
     * one grouped count by Master, both in the caller's transaction.
     */
    ReferralQueueWindow windowAt(Instant now) {
        ReferralWindowTimes times = settings.referralWindows();
        Optional<ReferralWindow> current = ReferralWindows.openAt(now, times, bankHolidays::isWorkingDay);
        ReferralWindow next = ReferralWindows.nextToOpen(now, times, bankHolidays::isWorkingDay);

        long sent = 0;
        Map<UUID, Long> byMaster = Map.of();
        if (current.isPresent()) {
            ReferralWindow open = current.get();
            sent = referrals.countByWindowDateAndWindowSlot(open.date(), open.slot(), ReferralState.Withdrawn);
            byMaster = referrals.countSentByMasterInWindow(open.date(), open.slot(), ReferralState.Withdrawn).stream()
                .collect(Collectors.toUnmodifiableMap(MasterWindowCount::masterId, MasterWindowCount::assigned));
        }
        Map<UUID, Long> counts = byMaster;
        List<ReferralQueueWindow.SentToMaster> available = masters.list().stream()
            .filter(Master::available)
            .map(master -> {
                UUID id = UUID.fromString(master.id());
                return new ReferralQueueWindow.SentToMaster(id, master.name(), counts.getOrDefault(id, 0L));
            })
            .toList();

        return new ReferralQueueWindow(
            current.orElse(null),
            current.map(open -> ReferralWindows.closesAt(open, times)).orElse(null),
            next,
            ReferralWindows.opensAt(next, times),
            settings.windowCap(),
            sent,
            available);
    }

    /**
     * {@code Open} when absent or blank, {@code Claimed}, or a 400 on {@link #STATE}.
     */
    static ReferralState parseState(String raw) {
        if (raw == null || raw.isBlank()) {
            return ReferralState.Open;
        }
        return STATES.stream()
            .filter(state -> state.name().equals(raw.trim()))
            .findFirst()
            .orElseThrow(() -> new InvalidInputError(INVALID_STATE, List.of(
                new FieldError(STATE, STATE + " is " + ReferralState.Open + " or " + ReferralState.Claimed))));
    }
}
