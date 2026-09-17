package uk.gov.hmcts.reform.deedpoll.domain.referrals;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uk.gov.hmcts.reform.deedpoll.domain.ConflictError;
import uk.gov.hmcts.reform.deedpoll.domain.ForbiddenError;
import uk.gov.hmcts.reform.deedpoll.domain.NotFoundError;
import uk.gov.hmcts.reform.deedpoll.domain.auth.CurrentUser;
import uk.gov.hmcts.reform.deedpoll.domain.auth.Role;
import uk.gov.hmcts.reform.deedpoll.domain.notes.CreateNoteInput;
import uk.gov.hmcts.reform.deedpoll.domain.notes.NoteAuthor;
import uk.gov.hmcts.reform.deedpoll.domain.notes.NoteService;
import uk.gov.hmcts.reform.deedpoll.entity.AuditClock;
import uk.gov.hmcts.reform.deedpoll.entity.DeedPoll;
import uk.gov.hmcts.reform.deedpoll.entity.Referral;
import uk.gov.hmcts.reform.deedpoll.entity.enums.ReferralState;
import uk.gov.hmcts.reform.deedpoll.repository.DeedPollRepository;
import uk.gov.hmcts.reform.deedpoll.repository.ReferralRepository;
import uk.gov.hmcts.reform.deedpoll.repository.StaleClaim;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Referrals of a case to the judiciary: raising one, reading them, pulling one back, and the judicial
 * transitions -- claiming one out of the shared pool, and the inactivity sweep that gives a forgotten claim
 * back to it.
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
 * <p><strong>The state machine lives here, and the judicial HTTP surface does not.</strong> Create enters
 * {@link ReferralState#Open}, withdraw enters {@link ReferralState#Withdrawn}, and {@link #claim} enters
 * {@link ReferralState#Claimed}; {@code Decided} and {@code Closed} follow with the direction. {@link #claim}
 * has no route in this service -- the judicial pool, the read-through and the endpoints that call it are the
 * judicial-surface plan's -- so it is exercised by tests holding a {@code CurrentUser} rather than a token.
 * Keeping the transition beside the columns it mutates is deliberate: one place owns how a referral changes
 * state, and a transition needs a role claim rather than a real judicial identity to be provable.
 *
 * <p>The two long-standing guards ask {@link ReferralState#isTerminal()} rather than naming states, so a
 * transition added later cannot leave one of them out of date:
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
 * <p><strong>No role gate on the caseworker operations, deliberately.</strong> Any authenticated staff caller
 * may raise, read and withdraw a referral -- there is no "may refer" permission, because in the process this
 * replaces anybody who works a case can email a Master. The judicial transitions are the ones that are
 * different: {@link #claim} and {@link #touch} demand {@link Role#JUDICIAL}, which IDAM does not issue yet, so
 * they fail closed for every token in existence today while being fully testable.
 *
 * <p><strong>{@link #reclaimStaleClaims()} is a mutating read, on purpose. Do not "fix" it.</strong> A claim
 * lapses after {@link ReferralProperties#claimExpiry()} of the holding judge's inactivity, and there is no
 * scheduling in this service to notice that on a timer -- no {@code @EnableScheduling}, no injectable clock,
 * only {@link AuditClock}. Rather than introduce one, lapses are computed and taken back as a side effect of
 * loading the judicial pool, which is the most-polled judicial page, and defensively at the top of
 * {@link #claim} so a lapsed claim cannot block a new one. Three things follow, and each is a decision rather
 * than an accident:
 *
 * <ul>
 *   <li><strong>It is race-safe without a lock.</strong> Each release is one conditional {@code UPDATE} that
 *       re-checks the state and the cutoff, so two replicas loading the pool at the same instant release a
 *       given referral once between them and the timeline gets exactly one lapse note. The database settles
 *       it; a distributed lock would be solving a race that does not exist.</li>
 *   <li><strong>The note may lag the window by one pool load</strong>, which in practice is minutes. The only
 *       case that goes unnoted indefinitely is a lapse on a referral nobody ever looks at again, where the
 *       note has no reader either.</li>
 *   <li><strong>A timed sweep is a clean follow-up, not a correction</strong> -- the same statement fired by a
 *       timer -- and it is deliberately out of scope.</li>
 * </ul>
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
    private final ReferralProperties properties;

    public ReferralService(DeedPollRepository deedPolls,
                           ReferralRepository referrals,
                           NoteService notes,
                           ReferralProperties properties) {
        this.deedPolls = deedPolls;
        this.referrals = referrals;
        this.notes = notes;
        this.properties = properties;
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

    /**
     * A judge taking a referral out of the shared pool: it becomes theirs and drops off everyone else's.
     *
     * <p>Lapsed claims are taken back first, so a referral a judge picked up and abandoned three hours ago is
     * claimable by the next judge who tries rather than by the next judge who happens to load the pool.
     *
     * <p><strong>Only an {@code Open} referral can be claimed, and there is no silent re-claim.</strong> A
     * referral already {@code Claimed} is a conflict even for the judge already holding it: two judges each
     * shown "claimed by me" for different reasons is a worse outcome than one of them being told plainly that
     * somebody got there first. A judge who is still working keeps their claim by interacting with it, which
     * refreshes {@code lastTouchedAt} -- see {@link #touch}.
     *
     * <p><strong>No case note.</strong> A claim is legible from the referral's own row -- {@code claimedBy}
     * and {@code claimedAt} -- so a note would be a second copy of a fact already on the record. The sweep
     * writes one because a lapse erases the fact it is about.
     *
     * @throws ForbiddenError if the caller does not hold {@link Role#JUDICIAL}
     * @throws NotFoundError  if there is no such referral, or it belongs to another case
     * @throws ConflictError  if the referral is not {@code Open}
     */
    @Transactional
    public Referral claim(String deedPollId, String id, CurrentUser caller) {
        caller.requireAnyRole(Role.JUDICIAL);
        // Before the lookup, not after: the referral this caller is asking for may be the stale one, and
        // reading it first would load the row that is about to change underneath.
        reclaimStaleClaims();

        Referral referral = findReferral(deedPollId, id);
        if (referral.getState() != ReferralState.Open) {
            throw new ConflictError("Referral " + id + " is " + referral.getState()
                + " and can only be claimed while it is " + ReferralState.Open);
        }

        LocalDateTime now = AuditClock.now();
        referral.setState(ReferralState.Claimed);
        referral.setClaimedBy(caller.name());
        referral.setClaimedAt(now);
        // Stamped at the claim as well as at every later interaction, so a referral picked up and never
        // touched again lapses two hours after it was picked up rather than never.
        referral.setLastTouchedAt(now);
        referral.setModifiedBy(caller.name());
        referral.setModifiedOn(now);
        return referrals.save(referral);
    }

    /**
     * Records that the holding judge is still working on a referral, so the sweep leaves it alone.
     *
     * <p>The counterpart to the expiry being measured from the last interaction rather than from the claim:
     * every judicial read and write of a held referral goes through here, which is what lets a judge spend a
     * morning on one set of papers without losing it. The judicial surface calls it; {@link #claim} stamps the
     * same column itself, because there is no claim to refresh until it has made one.
     *
     * <p><strong>Only the judge holding it may touch it</strong>, and the check is by the name the claim was
     * made under. A referral that has been withdrawn, decided, or taken back by the sweep is no longer held by
     * anybody, so a judge who left the page open gets a conflict rather than silently reviving a claim the
     * pool has already re-offered.
     *
     * @throws ForbiddenError if the caller does not hold {@link Role#JUDICIAL}
     * @throws NotFoundError  if there is no such referral, or it belongs to another case
     * @throws ConflictError  if the referral is not currently claimed by this caller
     */
    @Transactional
    public Referral touch(String deedPollId, String id, CurrentUser caller) {
        caller.requireAnyRole(Role.JUDICIAL);
        Referral referral = requireHeldBy(findReferral(deedPollId, id), id, caller);

        LocalDateTime now = AuditClock.now();
        referral.setLastTouchedAt(now);
        // The judge's own name in the audit columns: they did write this row, and leaving modifiedBy on the
        // previous writer while modifiedOn moved would attribute their touch to a caseworker.
        referral.setModifiedBy(caller.name());
        referral.setModifiedOn(now);
        return referrals.save(referral);
    }

    /**
     * Gives every lapsed claim back to the shared pool, and says on each case's timeline that it happened.
     *
     * <p><strong>Read the class javadoc before changing this.</strong> That a read mutates, that there is no
     * lock, and that the note can lag the window are all decided; the reasoning is there rather than here so
     * that it is found by somebody reading the service rather than only by somebody already reading this
     * method.
     *
     * <p>The window is {@link ReferralProperties#claimExpiry()} and is never hard-coded: two hours is a guess
     * about how judges will work, and the point of the configuration key is that correcting the guess is a
     * deployment value rather than a release.
     *
     * <p>Each candidate is released by its own conditional statement, and a candidate whose statement changes
     * nothing gets no note -- that is the case where the judge touched it, a caseworker withdrew it, or
     * another replica released it in the moment between the two queries.
     *
     * @return how many claims this call released, which is the number of notes it wrote
     */
    @Transactional
    public int reclaimStaleClaims() {
        LocalDateTime now = AuditClock.now();
        LocalDateTime cutoff = now.minus(properties.claimExpiry());

        int reclaimed = 0;
        for (StaleClaim stale : referrals.findStaleClaims(ReferralState.Claimed, cutoff)) {
            // The same cutoff the candidates were found with, not a freshly computed one: the two have to be
            // the same instant or a candidate could be released against a window it did not qualify under.
            if (referrals.releaseStaleClaim(stale.id(), cutoff, now, CurrentUser.SERVICE_ACCOUNT_NAME) == 0) {
                continue;
            }
            reclaimed++;
            // NoteAuthor.NONE: no person did this, so it is attributed to the service account rather than to
            // whichever caseworker or judge happened to trigger the load that noticed it.
            notes.createNote(stale.deedPollId(),
                             new CreateNoteInput(ReferralNotes.claimLapsed(stale.claimedBy())),
                             NoteAuthor.NONE);
        }
        return reclaimed;
    }

    /**
     * The referral, if this caller is the judge currently holding it.
     *
     * <p>Both halves matter and neither is enough alone: the state says a claim exists, and the name says it
     * is this caller's. Kept in one place because the direction transition asks the same question, and a
     * second copy of it that drifted would let one judge write on another's referral.
     */
    private static Referral requireHeldBy(Referral referral, String id, CurrentUser caller) {
        if (referral.getState() != ReferralState.Claimed
            || !caller.name().equals(referral.getClaimedBy())) {
            throw new ConflictError("Referral " + id + " is not claimed by " + caller.name()
                + " -- it is " + referral.getState());
        }
        return referral;
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
