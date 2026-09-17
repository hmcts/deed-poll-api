package uk.gov.hmcts.reform.deedpoll.domain.referrals;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uk.gov.hmcts.reform.deedpoll.domain.ConflictError;
import uk.gov.hmcts.reform.deedpoll.domain.NotFoundError;
import uk.gov.hmcts.reform.deedpoll.domain.auth.CurrentUser;
import uk.gov.hmcts.reform.deedpoll.domain.notes.CreateNoteInput;
import uk.gov.hmcts.reform.deedpoll.domain.notes.NoteService;
import uk.gov.hmcts.reform.deedpoll.entity.AuditClock;
import uk.gov.hmcts.reform.deedpoll.entity.DeedPoll;
import uk.gov.hmcts.reform.deedpoll.entity.Referral;
import uk.gov.hmcts.reform.deedpoll.entity.enums.ReferralState;
import uk.gov.hmcts.reform.deedpoll.repository.DeedPollRepository;
import uk.gov.hmcts.reform.deedpoll.repository.ReferralRepository;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Referrals of a case to the judiciary: raising one, reading them, and pulling one back.
 *
 * <p><strong>Not a port.</strong> There is nothing for this in {@code src/test/resources/reference} -- the
 * source store held a referral inline on the case, one per case, latest-wins, and those columns are still on
 * {@code DeedPoll} and still read-only. So there is no reference service to compare against and none to look
 * for, exactly as for {@code SavedQueryService}.
 *
 * <p><strong>Every operation is scoped to its parent case</strong>, as {@code NoteService}'s are, and for the
 * same reason: {@link ReferralRepository} has no finder by referral id alone, so a cross-case read or write
 * is not expressible rather than merely refused. The parent's existence is checked on the reads too, so a
 * mistyped case id is a 404 rather than an empty list that says the case has no referrals.
 *
 * <p><strong>This service reaches two of the five states.</strong> Create enters {@link ReferralState#Open}
 * and withdraw enters {@link ReferralState#Withdrawn}; {@code Claimed}, {@code Decided} and {@code Closed}
 * arrive with the directions plans, which add transitions here rather than a migration. Both guards ask
 * {@link ReferralState#isTerminal()} rather than naming states, so a transition added later cannot leave one
 * of them out of date:
 *
 * <ul>
 *   <li><strong>At most one live referral per case.</strong> A case may carry many over its lifetime, but
 *       raising a second while one is still in play would put two of the same case in the shared pool, so it
 *       is a {@link ConflictError}. A case whose referrals are all terminal may be referred again.</li>
 *   <li><strong>Withdraw is a transition, not a delete and not an edit.</strong> A withdrawn referral
 *       survives as {@code Withdrawn}, distinct from {@code Closed} -- closed means a direction was
 *       actioned -- and withdrawing an already-terminal one is a {@link ConflictError}. There is no in-place
 *       editing of an open referral: amending one is withdrawing it and raising another, which is why nothing
 *       here takes a patch.</li>
 * </ul>
 *
 * <p><strong>Nothing here writes the case's flat referral columns.</strong> No {@code dateReferred}, no
 * {@code masterName}, no {@code referralNotes}: those stay the ported historical snapshot, read once in the
 * other direction by {@code V7__backfill_referrals.sql}. Whether closing a referral later syncs back into
 * them is the directions plan's question.
 *
 * <p><strong>No role gate, deliberately.</strong> Any authenticated staff caller may raise, read and withdraw
 * a referral -- there is no "may refer" permission, because in the process this replaces anybody who works a
 * case can email a Master. The judicial role is the one that is different, and it is fenced off by not
 * existing yet; when it arrives it gets its own read surface rather than a check bolted on here.
 *
 * <p>The service returns entities, not view models, as {@code NoteService} does. Mapping to DTOs is the
 * controller's job.
 */
@Service
public class ReferralService {

    /**
     * The states a referral can be in and still be in play.
     *
     * <p>Derived from the enum rather than listed, so a sixth state is covered by the one-live-referral guard
     * the moment it is added -- which is the whole reason {@link ReferralState#isTerminal()} exists rather
     * than each guard holding its own set.
     */
    private static final Set<ReferralState> LIVE_STATES = Arrays.stream(ReferralState.values())
        .filter(state -> !state.isTerminal())
        .collect(Collectors.toUnmodifiableSet());

    private final DeedPollRepository deedPolls;
    private final ReferralRepository referrals;
    private final NoteService notes;

    public ReferralService(DeedPollRepository deedPolls, ReferralRepository referrals, NoteService notes) {
        this.deedPolls = deedPolls;
        this.referrals = referrals;
        this.notes = notes;
    }

    /**
     * Raises a referral on a case, unclaimed and in the shared pool.
     *
     * <p><strong>The state is not the caller's to choose</strong> -- {@link CreateReferralInput} cannot carry
     * one -- and neither is a target judge. The referral goes into the pool; who picks it up is a later
     * transition.
     *
     * <p>A case note is written as the acting caseworker, so the referral appears on the timeline the case's
     * own workers read. Written after the referral is stored and inside the same transaction, so a refused
     * referral leaves no note claiming one was raised, and a refused note leaves no referral the timeline
     * never mentions.
     *
     * @throws NotFoundError if there is no such case
     * @throws ConflictError if the case already holds a referral that is not terminal
     */
    @Transactional
    public Referral createReferral(String deedPollId, CreateReferralInput dto, CurrentUser caller) {
        DeedPoll parent = requireCase(deedPollId);
        if (referrals.existsByDeedPollIdAndStateIn(deedPollId, LIVE_STATES)) {
            // 409 rather than 400: nothing about the payload is wrong, and the same request succeeds
            // unchanged once the open referral is closed or withdrawn.
            throw new ConflictError("Case " + deedPollId
                + " already has a referral that has not been closed or withdrawn");
        }

        Referral referral = new Referral();
        referral.setDeedPoll(parent);
        referral.setState(ReferralState.Open);
        referral.setReasons(dto.reasons().stream().map(ReferralReason::name).toList());
        referral.setNotes(dto.notes().orElse(null));
        referral.setUrgency(dto.urgency());
        referral.setUrgencyReason(dto.urgencyReason().orElse(null));
        // Raised by a caseworker, so not migrated: the flag exists to explain why a backfilled referral has
        // no reasons, and setting it here would make an honest empty set look like this one.
        referral.setMigrated(false);
        referral.setCreatedBy(caller.name());
        referral.setModifiedBy(caller.name());

        Referral saved = referrals.save(referral);
        notes.createNote(deedPollId, new CreateNoteInput(ReferralNotes.raised(dto)), caller.noteAuthor());
        return saved;
    }

    /**
     * Every referral on a case, newest first -- the history and the live one together.
     *
     * <p>One list rather than "the current referral" and "the past ones" separately: there is at most one
     * live referral, so a client that wants it reads the first row whose state is not terminal, and a tab
     * showing the history needs the whole list anyway.
     *
     * @throws NotFoundError if there is no such case
     */
    @Transactional(readOnly = true)
    public List<Referral> findReferrals(String deedPollId) {
        requireCase(deedPollId);
        return referrals.findByDeedPollIdOrderByCreatedOnDesc(deedPollId);
    }

    /**
     * One referral of one case.
     *
     * <p>An id that is not a UUID at all is the same 404 rather than a 400, as a saved query's is: it is an id
     * nothing could ever have been stored under, so "not found" is the true answer.
     *
     * @throws NotFoundError if there is no such referral, or it belongs to another case
     */
    @Transactional(readOnly = true)
    public Referral findReferral(String deedPollId, String id) {
        return identifier(id)
            .flatMap(referralId -> referrals.findByIdAndDeedPollId(referralId, deedPollId))
            .orElseThrow(() -> new NotFoundError("Referral " + id + " not found"));
    }

    /**
     * Withdraws a referral: the caseworker pulling it back before it is actioned.
     *
     * <p>Terminal, and deliberately not {@code Closed} -- closed means a direction was actioned, withdrawn
     * means it never was, and a report that could not tell them apart would count abandoned referrals as
     * work done. The row survives, so the case's history still shows that it was raised.
     *
     * <p>Guarded on {@link ReferralState#isTerminal()} rather than on {@code == Open}, so the claimed and
     * decided states the directions plan adds need no change here: a referral a judge is holding is still
     * withdrawable, and one already finished is not.
     *
     * @throws NotFoundError if there is no such referral, or it belongs to another case
     * @throws ConflictError if the referral is already closed or withdrawn
     */
    @Transactional
    public Referral withdrawReferral(String deedPollId, String id, CurrentUser caller) {
        Referral referral = findReferral(deedPollId, id);
        if (referral.getState().isTerminal()) {
            // Not idempotent-and-silent: a second withdrawal is a caseworker acting on a stale screen, and
            // answering 204 would tell them their click did something it did not.
            throw new ConflictError("Referral " + id + " is already " + referral.getState()
                + " and cannot be withdrawn");
        }

        referral.setState(ReferralState.Withdrawn);
        referral.setModifiedBy(caller.name());
        // Stamped here rather than left to @PreUpdate, for the reason WitnessService and SavedQueryService
        // stamp theirs: Hibernate only fires @PreUpdate when its dirty check finds a changed property, and
        // relying on the state change alone would make the audit stamp depend on the transition not being a
        // no-op -- which is exactly the case the guard above is about.
        referral.setModifiedOn(AuditClock.now());

        Referral saved = referrals.save(referral);
        notes.createNote(deedPollId, new CreateNoteInput(ReferralNotes.withdrawn(saved)), caller.noteAuthor());
        return saved;
    }

    private DeedPoll requireCase(String deedPollId) {
        return deedPolls.findById(deedPollId)
            .orElseThrow(() -> new NotFoundError("DeedPoll " + deedPollId + " not found"));
    }

    /** The id as a UUID, or empty for anything that is not one -- which no row can be stored under. */
    private static Optional<UUID> identifier(String id) {
        try {
            return Optional.of(UUID.fromString(id));
        } catch (IllegalArgumentException notAnIdentifier) {
            return Optional.empty();
        }
    }
}
