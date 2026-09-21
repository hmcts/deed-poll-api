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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Referrals of a case to the judiciary: raising one, reading them, pulling one back, closing a returned one,
 * and the judicial transitions -- claiming one out of the shared pool, recording a direction on one, and the
 * inactivity sweep that gives a forgotten claim back to it.
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
 * <p><strong>The whole state machine lives here, and the judicial HTTP surface does not.</strong> Create enters
 * {@link ReferralState#Open}, {@link #claim} enters {@link ReferralState#Claimed}, {@link #decide} enters
 * {@link ReferralState#Decided}, {@link #close} enters {@link ReferralState#Closed}, and withdraw enters
 * {@link ReferralState#Withdrawn} from {@code Open} or {@code Claimed}. {@link #claim} and {@link #decide} have no
 * route in this service -- the judicial pool, the read-through and the endpoints that call them are the
 * judicial-surface plan's -- so they are exercised by tests holding a {@code CurrentUser} rather than a token.
 * Keeping each transition beside the columns it mutates is deliberate: one place owns how a referral changes
 * state, and a transition needs a role claim rather than a real judicial identity to be provable.
 *
 * <p>The two long-standing guards each ask the enum a named question rather than listing states, so a
 * transition added later cannot leave one of them out of date -- and <strong>they ask two different
 * questions, which is the point</strong>:
 *
 * <ul>
 *   <li><strong>At most one live referral per case</strong>, asking {@link ReferralState#isTerminal()}. A case
 *       may carry many over its lifetime, but raising a second while one is still in play would put two of the
 *       same case in the shared pool, so it is a {@link ConflictError}. A case whose referrals are all terminal
 *       may be referred again.</li>
 *   <li><strong>Withdraw is a transition, not a delete and not an edit</strong>, asking
 *       {@link ReferralState#canBeWithdrawn()}. A withdrawn referral survives as {@code Withdrawn}, distinct
 *       from {@code Closed} -- closed means a direction was actioned -- and withdrawing anything that is not
 *       {@code Open} or {@code Claimed} is a {@link ConflictError}. There is no in-place editing of an open
 *       referral: amending one is withdrawing it and raising another, which is why nothing here takes a
 *       patch.</li>
 * </ul>
 *
 * <p>{@code Decided} is the state where those two questions differ, and it is why there are two of them: a
 * decided referral is non-terminal, so the case still holds it and may not raise a second, and yet it is not
 * withdrawable, because a judge has recorded a direction that the office is obliged to action rather than
 * discard. {@link #close} is the office's move on it. See {@link ReferralState#canBeWithdrawn()} for what
 * narrowing {@code isTerminal()} instead would have broken.
 *
 * <p><strong>Nothing here writes the case's flat referral columns, closing included.</strong> No
 * {@code dateReferred}, no {@code masterName}, no {@code masterDecision}, no {@code referralNotes}: those stay
 * the ported historical snapshot, read once in the other direction by {@code V7__backfill_referrals.sql}. The
 * live direction is on the referral, and syncing it back was considered and refused -- the case columns would
 * then say two different things over time, the backfilled snapshot and whichever referral was latest, which is
 * the single-referral-per-case model this feature replaced.
 *
 * <p><strong>No role gate on the caseworker operations, deliberately.</strong> Any authenticated staff caller
 * may raise, read, withdraw and close a referral -- there is no "may refer" permission and no "may close" one,
 * because in the process this replaces anybody who works a case can email a Master and action the reply. The
 * judicial transitions are the ones that are different: {@link #claim}, {@link #touch} and {@link #decide}
 * demand {@link Role#JUDICIAL}, which IDAM does not issue yet, so they fail closed for every token in
 * existence today while being fully testable. The split is not decoration -- a judge does not close a referral
 * and a caseworker does not decide one, because closing is the acknowledgement that the direction has been
 * actioned by the office that has to action it.
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
 * <p><strong>Every transition also refreshes the case's denormalised {@code currentReferralState}</strong>, in
 * the transition's own transaction, so a case never persists with a referral in one state and its column naming
 * another. The rule and the reason it is a recompute rather than a push are on {@link CurrentReferralState};
 * {@link #reclaimStaleClaims()} does it set-based because it is the one site that moves many referrals at once.
 * {@link #touch} does not do it, and that is not an omission: it moves {@code lastTouchedAt} and never
 * {@code state}, so there is nothing for the column to have changed to.
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
        refreshCurrentReferralState(deedPollId);
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
     * <p>Guarded on {@link ReferralState#canBeWithdrawn()} rather than on {@code == Open} or on
     * {@link ReferralState#isTerminal()}: a referral a judge is merely <em>holding</em> is still withdrawable,
     * because the office may need to correct or cancel it while the papers are out and the transition safely
     * voids the claim -- the judge is no longer holding a {@code Claimed} referral, so {@link #decide} refuses
     * their late submission. <strong>One whose direction has come back is not withdrawable</strong>: the
     * business rule starts when the judge records a direction, not when they claim the work, and the office's
     * move on a {@code Decided} referral is {@link #close}.
     *
     * <p>The two refusals therefore say different things, and deliberately not in the same words. A
     * {@code Closed} or {@code Withdrawn} referral is already finished; a {@code Decided} one is not finished
     * at all, so calling it "already {@code Decided}" would read as though the caseworker were repeating
     * themselves rather than being told that a direction is waiting for them.
     *
     * @throws NotFoundError if there is no such referral, or it belongs to another case
     * @throws ConflictError if the referral is decided, closed or withdrawn
     */
    @Transactional
    public Referral withdrawReferral(String deedPollId, String id, CurrentUser caller) {
        Referral referral = findReferral(deedPollId, id);
        if (!referral.getState().canBeWithdrawn()) {
            // Not idempotent-and-silent: a second withdrawal is a caseworker acting on a stale screen, and
            // answering 204 would tell them their click did something it did not.
            throw new ConflictError(refusedWithdrawal(id, referral.getState()));
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
        // Withdrawn is terminal, so this normally writes NULL: the case has no referral in play again, and is
        // referable again. A push that set the case to Withdrawn would be the drift CurrentReferralState warns of.
        refreshCurrentReferralState(deedPollId);
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
     * <p><strong>One case note, authored by the claiming judge.</strong> The referral's own row says who holds
     * it <em>now</em>, and the Notes grid is the only place that says when each step happened -- so without this
     * a caseworker reading the timeline saw a referral leave and come back with nothing in between. Written
     * after the claim is stored and inside the same transaction, so a refused claim leaves no note saying the
     * judiciary has the papers. See {@link ReferralNotes} and
     * {@code docs/plans/referrals-api-5-lifecycle-case-notes.md}, which supersede the earlier reasoning that a
     * claim needed no note because a column already carried it.
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

        Referral saved = referrals.save(referral);
        // The claiming judge, not the service account: a person did this, and noteAuthor() is what puts their
        // name in createdBy -- which is why the prose says "claimed by the judiciary" rather than naming them
        // a second time.
        notes.createNote(deedPollId, new CreateNoteInput(ReferralNotes.claimed(saved)), caller.noteAuthor());
        // After the sweep above, not instead of it: the sweep may have moved this very case's column to Open a
        // moment ago, and this recompute is what takes it on to Claimed.
        refreshCurrentReferralState(deedPollId);
        return saved;
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
     * The holding judge recording their direction: what they decided, what they directed, and whether the
     * change is to be published in the London Gazette.
     *
     * <p><strong>Permitted only on a referral this caller currently holds</strong> -- state {@code Claimed} and
     * {@code claimedBy} is them -- and that single check is what two other rules rely on rather than
     * implementing themselves:
     *
     * <ul>
     *   <li>A referral a caseworker withdrew is {@code Withdrawn}, so a judge who had it open and submits a
     *       direction afterwards gets a conflict rather than writing a direction onto a referral that was
     *       pulled back.</li>
     *   <li>A referral the sweep took back is {@code Open} with no {@code claimedBy}, so the same is true of a
     *       judge whose claim lapsed while they were reading. The referral may by then be another judge's, and
     *       the two of them must not both be able to direct it.</li>
     * </ul>
     *
     * <p><strong>One transition, not a draft and a submit.</strong> The check-your-answers page and the
     * "Signed" attestation are the admin's, so what arrives here is one confirmed direction and it is recorded
     * atomically. {@code decisionDate} and {@code decidedBy} come from the clock and the token, never from the
     * payload -- see {@link DirectionInput}.
     *
     * <p>{@code lastTouchedAt} is refreshed even though the referral is leaving {@code Claimed} and the sweep
     * will never look at it again. It costs nothing and it keeps the column meaning "when a judge last did
     * something with this", which is what a caseworker asking how long the judiciary had it reads.
     *
     * <p><strong>One case note, authored by the deciding judge</strong>, for the reason {@link #claim} writes
     * one: the timeline is where a caseworker reads <em>when</em> the direction came back. It is a summary and
     * not a copy -- the decision and the Gazette answer, and a pointer to the referral for the direction itself
     * -- because {@code directionsText} may be twice as long as a note may be, so quoting it would make a valid
     * direction fail the note it triggers. {@link ReferralNotes#directionRecorded} owns that reasoning.
     *
     * @throws ForbiddenError if the caller does not hold {@link Role#JUDICIAL}
     * @throws NotFoundError  if there is no such referral, or it belongs to another case
     * @throws ConflictError  if the referral is not currently claimed by this caller
     */
    @Transactional
    public Referral decide(String deedPollId, String id, DirectionInput direction, CurrentUser caller) {
        caller.requireAnyRole(Role.JUDICIAL);
        Referral referral = requireHeldBy(findReferral(deedPollId, id), id, caller);

        LocalDateTime now = AuditClock.now();
        referral.setState(ReferralState.Decided);
        referral.setDecision(direction.decision());
        referral.setDirectionsText(direction.directionsText().orElse(null));
        referral.setPublishToGazette(direction.publishToGazette());
        referral.setDecisionDate(now);
        // The judge's own name from the token, so the record says who directed it rather than who last saved
        // the row -- which the closing caseworker becomes a moment later.
        referral.setDecidedBy(caller.name());
        referral.setLastTouchedAt(now);
        referral.setModifiedBy(caller.name());
        referral.setModifiedOn(now);

        Referral saved = referrals.save(referral);
        // Composed from the saved referral rather than from the input, so the note reads the decision and the
        // Gazette answer as they were stored -- and is written inside this transaction, so a rolled-back
        // direction leaves no note announcing one.
        notes.createNote(deedPollId,
                         new CreateNoteInput(ReferralNotes.directionRecorded(saved)),
                         caller.noteAuthor());
        // The transition the dashboard's "returned -- action needed" count is drawn from: this is what puts the
        // case into that queue, by way of its column reading Decided.
        refreshCurrentReferralState(deedPollId);
        return saved;
    }

    /**
     * The caseworker acknowledging that a returned direction has been actioned.
     *
     * <p>{@code Decided -> Closed}, and this is the transition that drops the referral off the "returned --
     * action needed" queue, because that queue is "state = Decided". So closing is not bookkeeping: it is the
     * caseworker saying they have done what the direction required, which is why <strong>a judge does not
     * close and a caseworker does not decide</strong>.
     *
     * <p><strong>Any authenticated caseworker, with no role gate</strong>, exactly as raising and withdrawing
     * are. There is no "may close" permission for the same reason there is no "may refer" one: in the process
     * this replaces, whoever works the case actions the Master's reply.
     *
     * <p>Only a {@code Decided} referral can be closed, and every other state is a conflict rather than a
     * silent success. This one guard names a single state rather than asking the enum a question, because the
     * question would have exactly one true answer: closing an {@code Open} or {@code Claimed} referral would
     * take it out of the pool with no direction on it, and closing a {@code Withdrawn} one would relabel a
     * referral that was abandoned as one that was actioned. It is the exact complement of withdrawal's
     * {@link ReferralState#canBeWithdrawn()} over the live states -- between them, every live referral has one
     * caseworker move and no live referral has both.
     *
     * <p>Close clears nothing and reverses nothing. The direction stays exactly as the judge recorded it, and
     * <strong>nothing is written back to the case's flat {@code masterDecision} and {@code masterDirections}
     * columns</strong>: those are the pre-migration snapshot, and syncing them would make the case say two
     * different things over time and re-introduce the one-referral-per-case model this feature replaced.
     *
     * <p><strong>One case note, authored by the closing caseworker.</strong> The one lifecycle step no column
     * records after the fact: this transition writes {@code Closed} and the case's
     * {@code currentReferralState} back to null, so without the note the timeline would show a direction
     * arriving and then nothing, and "has anyone actioned this?" would have no answer on the case. Written
     * after the closure is stored and inside the same transaction, so a refused closure leaves no note saying
     * the direction was actioned. The earlier reasoning -- that the row's own {@code Closed} said everything a
     * note could -- is superseded by {@code docs/plans/referrals-api-5-lifecycle-case-notes.md}, which found
     * that it says only where the referral is now and never when the office acted.
     *
     * @throws NotFoundError if there is no such referral, or it belongs to another case
     * @throws ConflictError if the referral is not {@code Decided}
     */
    @Transactional
    public Referral close(String deedPollId, String id, CurrentUser caller) {
        Referral referral = findReferral(deedPollId, id);
        if (referral.getState() != ReferralState.Decided) {
            throw new ConflictError("Referral " + id + " is " + referral.getState()
                + " and can only be closed once it is " + ReferralState.Decided);
        }

        referral.setState(ReferralState.Closed);
        referral.setModifiedBy(caller.name());
        // Stamped rather than left to @PreUpdate, for the reason withdraw's is: Hibernate fires @PreUpdate
        // only when its dirty check finds a changed property.
        referral.setModifiedOn(AuditClock.now());

        Referral saved = referrals.save(referral);
        // The caseworker who actioned the direction, not the judge who gave it: decidedBy on the row still says
        // who directed it, and this note's createdBy is who carried it out.
        notes.createNote(deedPollId,
                         new CreateNoteInput(ReferralNotes.directionActioned(saved)),
                         caller.noteAuthor());
        // Closed is terminal, so this writes NULL and the case leaves the "returned -- action needed" queue.
        // Setting the column to Closed here is the specific mistake CurrentReferralState is written against: it
        // would leave every actioned case in that queue for good.
        refreshCurrentReferralState(deedPollId);
        return saved;
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
     * <p><strong>The case columns are refreshed by one set-based statement at the end, not by the per-case
     * recompute in the loop.</strong> This is the one transition that moves many referrals, so its read-model
     * counterpart is one statement over the whole set for the same reason the releases are conditional: only a
     * single statement is atomic, and a per-row recompute would also be a query pair per reclaimed claim.
     * Exactly the cases this call released are refreshed -- not every candidate -- because a candidate whose
     * release answered 0 belongs to another replica, which refreshes its case itself.
     *
     * @return how many claims this call released, which is the number of notes it wrote
     */
    @Transactional
    public int reclaimStaleClaims() {
        LocalDateTime now = AuditClock.now();
        LocalDateTime cutoff = now.minus(properties.claimExpiry());

        // Insertion-ordered and de-duplicated: one case can hold only one claim, so this is normally one entry
        // per release, and the set is what the single closing statement is keyed on.
        Set<String> reclaimedCases = new LinkedHashSet<>();
        int reclaimed = 0;
        for (StaleClaim stale : referrals.findStaleClaims(ReferralState.Claimed, cutoff)) {
            // The same cutoff the candidates were found with, not a freshly computed one: the two have to be
            // the same instant or a candidate could be released against a window it did not qualify under.
            if (referrals.releaseStaleClaim(stale.id(), cutoff, now, CurrentUser.SERVICE_ACCOUNT_NAME) == 0) {
                continue;
            }
            reclaimed++;
            reclaimedCases.add(stale.deedPollId());
            // NoteAuthor.NONE: no person did this, so it is attributed to the service account rather than to
            // whichever caseworker or judge happened to trigger the load that noticed it.
            notes.createNote(stale.deedPollId(),
                             new CreateNoteInput(ReferralNotes.claimLapsed(stale.claimedBy())),
                             NoteAuthor.NONE);
        }

        if (!reclaimedCases.isEmpty()) {
            deedPolls.refreshCurrentReferralStates(reclaimedCases, LIVE_STATES);
        }
        return reclaimed;
    }

    /**
     * Recomputes one case's denormalised current referral state from the referral table.
     *
     * <p>Called at the tail of every transition that changes a referral's state, inside that transition's
     * transaction. It reads the states of the case's referrals and writes the one that is still in play, or
     * NULL. <strong>Why it recomputes rather than being told what the transition did is on
     * {@link CurrentReferralState}, and it is the point of the whole mechanism</strong> -- read that before
     * replacing this with an argument.
     */
    private void refreshCurrentReferralState(String deedPollId) {
        deedPolls.writeCurrentReferralState(
            deedPollId,
            CurrentReferralState.of(referrals.findStatesByDeedPollId(deedPollId)).orElse(null));
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

    /**
     * Why a withdrawal was refused, in the words the state deserves.
     *
     * <p>Two sentences rather than one, because the two refusals are different facts and the caseworker's next
     * action differs: a finished referral needs nothing doing, and a {@code Decided} one needs the direction
     * actioning and then {@link #close}. Reusing the "already X" wording for a non-terminal state would tell a
     * caseworker looking at a returned direction that they had already withdrawn it.
     */
    private static String refusedWithdrawal(String id, ReferralState state) {
        if (state == ReferralState.Decided) {
            return "Referral " + id + " is " + state
                + " -- a direction has been recorded on it and must be actioned and the referral closed,"
                + " rather than withdrawn";
        }
        return "Referral " + id + " is already " + state + " and cannot be withdrawn";
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
