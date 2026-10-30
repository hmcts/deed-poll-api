package uk.gov.hmcts.reform.deedpoll.domain.referrals;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uk.gov.hmcts.reform.deedpoll.domain.FieldError;
import uk.gov.hmcts.reform.deedpoll.domain.ForbiddenError;
import uk.gov.hmcts.reform.deedpoll.domain.InvalidInputError;
import uk.gov.hmcts.reform.deedpoll.domain.auth.Capability;
import uk.gov.hmcts.reform.deedpoll.domain.auth.CurrentUser;
import uk.gov.hmcts.reform.deedpoll.domain.deedpoll.CasePaging;
import uk.gov.hmcts.reform.deedpoll.domain.masters.Master;
import uk.gov.hmcts.reform.deedpoll.domain.settings.BankHolidays;
import uk.gov.hmcts.reform.deedpoll.domain.settings.ProcessSettingsService;
import uk.gov.hmcts.reform.deedpoll.domain.settings.ReferralWindowTimes;
import uk.gov.hmcts.reform.deedpoll.entity.AuditClock;
import uk.gov.hmcts.reform.deedpoll.entity.Referral;
import uk.gov.hmcts.reform.deedpoll.entity.enums.ReferralState;
import uk.gov.hmcts.reform.deedpoll.entity.enums.Urgency;
import uk.gov.hmcts.reform.deedpoll.repository.MasterRepository;
import uk.gov.hmcts.reform.deedpoll.repository.MasterWindowCount;
import uk.gov.hmcts.reform.deedpoll.repository.ReferralRepository;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
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

    private final ReferralRepository referrals;
    private final MasterRepository masters;
    private final ProcessSettingsService settings;
    private final BankHolidays bankHolidays;

    public ReferralQueue(ReferralRepository referrals, MasterRepository masters, ProcessSettingsService settings,
                         BankHolidays bankHolidays) {
        this.referrals = referrals;
        this.masters = masters;
        this.settings = settings;
        this.bankHolidays = bankHolidays;
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
            sent = referrals.countByWindowDateAndWindowSlot(open.date(), open.slot());
            byMaster = referrals.countSentByMasterInWindow(open.date(), open.slot()).stream()
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
