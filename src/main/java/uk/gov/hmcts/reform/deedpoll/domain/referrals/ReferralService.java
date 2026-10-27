package uk.gov.hmcts.reform.deedpoll.domain.referrals;

import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uk.gov.hmcts.reform.deedpoll.domain.ConflictError;
import uk.gov.hmcts.reform.deedpoll.domain.ForbiddenError;
import uk.gov.hmcts.reform.deedpoll.domain.NotFoundError;
import uk.gov.hmcts.reform.deedpoll.domain.UnauthorizedError;
import uk.gov.hmcts.reform.deedpoll.domain.audit.AuditAction;
import uk.gov.hmcts.reform.deedpoll.domain.audit.AuditActor;
import uk.gov.hmcts.reform.deedpoll.domain.audit.AuditEntities;
import uk.gov.hmcts.reform.deedpoll.domain.audit.AuditEntry;
import uk.gov.hmcts.reform.deedpoll.domain.audit.AuditTrail;
import uk.gov.hmcts.reform.deedpoll.domain.audit.AuditValue;
import uk.gov.hmcts.reform.deedpoll.domain.auth.CurrentUser;
import uk.gov.hmcts.reform.deedpoll.domain.auth.Role;
import uk.gov.hmcts.reform.deedpoll.domain.notes.CreateNoteInput;
import uk.gov.hmcts.reform.deedpoll.domain.notes.NoteAuthor;
import uk.gov.hmcts.reform.deedpoll.domain.notes.NoteService;
import uk.gov.hmcts.reform.deedpoll.domain.settings.ProcessSettingsService;
import uk.gov.hmcts.reform.deedpoll.entity.AuditClock;
import uk.gov.hmcts.reform.deedpoll.entity.DeedPoll;
import uk.gov.hmcts.reform.deedpoll.entity.Referral;
import uk.gov.hmcts.reform.deedpoll.entity.enums.ReferralState;
import uk.gov.hmcts.reform.deedpoll.repository.DeedPollRepository;
import uk.gov.hmcts.reform.deedpoll.repository.ReferralRepository;
import uk.gov.hmcts.reform.deedpoll.repository.StaleClaim;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Referrals of a case to the judiciary: raising one, reading them, pulling one back, closing a returned one,
 * recording what the applicant answered when the office asked them whether to proceed, and the judicial
 * transitions -- claiming one out of the shared pool, recording a direction on one, and the inactivity sweep that
 * gives a forgotten claim back to it.
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
 * <p><strong>Every operation that writes a referral reads it under a row lock, and every operation that only
 * displays one does not.</strong> Two things make that the rule rather than a precaution -- a {@code save} here
 * writes the whole row, because this entity has no dirty-column tracking, and every guard below is a
 * read-check-write -- and {@link #findReferralForUpdate} carries both arguments. The confirmation columns are what
 * made it necessary: they are written by the two operations that are ungated on {@code state}, so they are the
 * ones a transition that read a moment too early would silently write back to null. <strong>The lock is half of
 * that rule and the row being re-read under it is the other half</strong>, because a JPA query hands back an
 * instance already loaded in the transaction and throws the locked row away -- which
 * {@link #findReferralForUpdate} is also where the answer to lives.
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
 * lapses after {@link ProcessSettingsService#referralClaimExpiry()} of the holding judge's inactivity, and there is no
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
 * <p><strong>Every successful transition writes exactly one case note, and the only write here that is not a
 * transition writes one too.</strong>
 * Raise, withdraw, {@link #claim}, {@link #decide}, {@link #close} and {@link #recordConfirmation} each call
 * {@code notes.createNote} once,
 * with prose from {@link ReferralNotes} and {@code caller.noteAuthor()} as the author -- the person who caused
 * the transition, which on a claim or a direction is the judge. {@link #reclaimStaleClaims()} is the one
 * exception to the authorship rule, not to the counting rule: it writes one note per released claim as
 * {@link NoteAuthor#NONE}, because no person did it. Each note is created after the referral is saved and
 * inside the same transaction, so there is no successful transition without its note and no note for one that
 * was refused or rolled back. {@link #touch} writes none -- a read is not a transition -- and neither does the
 * backfill migration, which does not revisit history. {@link #recordConfirmation} writes one although it moves no
 * state, because the applicant's answer is an event on the case and the grid is the only place it would appear.
 * The first three referral plans deliberately noted only
 * raise, withdraw and a lapse, on the grounds that a column already carried the rest;
 * {@code docs/plans/referrals-api-5-lifecycle-case-notes.md} supersedes that, because a row states where a
 * referral is <em>now</em> and the grid is the only place that says when each step happened.
 *
 * <p><strong>Every transition also refreshes the case's denormalised {@code currentReferralState}</strong>, in
 * the transition's own transaction, so a case never persists with a referral in one state and its column naming
 * another. The rule and the reason it is a recompute rather than a push are on {@link CurrentReferralState};
 * {@link #reclaimStaleClaims()} does it set-based because it is the one site that moves many referrals at once.
 * {@link #touch} does not do it, and that is not an omission: it moves {@code lastTouchedAt} and never
 * {@code state}, so there is nothing for the column to have changed to. {@link #recordConfirmation} does not
 * either, for the same reason -- and there is deliberately no ninth case state for "awaiting confirmation": a
 * case's state is derived from its date columns by a verbatim port whose member order is the Dynamics option-set
 * integers, so the wait is recorded on the referral and surfaced alongside the state rather than inside it.
 *
 * <p><strong>{@link #recordConfirmation} refreshes the other denormalised column instead</strong> --
 * {@code awaitingConfirmationSince}, which is how a case leaves the waiting list -- through
 * {@link #refreshAwaitingConfirmation}, on the same recompute-from-truth terms and in the same transaction. That
 * one is public rather than private because the write that starts the wait is the email send, in another package.
 *
 * <p><strong>Not a port either: every caller's write records one audit event</strong>, in its own transaction, so
 * a transition whose event cannot be recorded does not commit. It names the referral columns the write set, with
 * their before and after, and the state it moved between. {@link #touch} records none, because it is a judge reading,
 * and {@link #reclaimStaleClaims()} none, because no person did it and its note already says so.
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

    /**
     * Dates in a refusal a caseworker reads, so the day rather than the instant -- the same format
     * {@link ReferralNotes} writes into the timeline they are reading it beside.
     *
     * <p>{@link Locale#UK} rather than the default, for the reason {@code ResolvedValues} pins its own: {@code
     * MMMM} names the month in {@code Locale.getDefault(FORMAT)}, so an image whose default locale is not
     * English would put a French or German month into an English refusal sentence -- and into the case notes
     * beside it -- with nothing failing to say so.
     */
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.UK);

    private final DeedPollRepository deedPolls;
    private final ReferralRepository referrals;
    private final NoteService notes;
    private final ProcessSettingsService settings;
    private final AuditTrail audit;

    /**
     * For one thing only: bringing a referral loaded earlier in the transaction up to the row the lock now
     * protects -- see {@link #findReferralForUpdate}. Nothing here queries, writes or flushes through it.
     */
    private final EntityManager entityManager;

    public ReferralService(DeedPollRepository deedPolls,
                           ReferralRepository referrals,
                           NoteService notes,
                           ProcessSettingsService settings,
                           EntityManager entityManager,
                           AuditTrail audit) {
        this.deedPolls = deedPolls;
        this.referrals = referrals;
        this.notes = notes;
        this.settings = settings;
        this.entityManager = entityManager;
        this.audit = audit;
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
        audit(AuditAction.REFERRAL_CREATED, caller, deedPollId, saved, null, List.of(
            AuditValue.created("state", saved.getState()),
            AuditValue.created("reasons", String.join(",", saved.getReasons())),
            AuditValue.created("notes", saved.getNotes()),
            AuditValue.created("urgency", saved.getUrgency()),
            AuditValue.created("urgencyReason", saved.getUrgencyReason())));
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
     * The same lookup as {@link #findReferral}, with the row locked for the rest of the transaction.
     *
     * <p><strong>Every operation here that writes the referral reads it through this one; the reads read it
     * through {@link #findReferral}.</strong> Two independent reasons converge on that rule, and either alone
     * would be enough:
     *
     * <ul>
     *   <li><strong>A save writes the whole row.</strong> Hibernate has no dirty-column tracking on
     *       {@link Referral} -- the fact {@link DeedPollRepository#writeCurrentReferralState} is written around
     *       -- so a transition that read the row unlocked and then saved it would write every column back as it
     *       read them, discarding whatever another transaction committed on that row in between. The confirmation
     *       columns are the ones most exposed to it, because they are written by the two operations that are
     *       ungated on {@code state} and so can run beside any transition: a {@link #close} or a
     *       {@link #withdrawReferral} that read before {@link #recordConfirmation} committed would put
     *       {@code confirmationOutcome} back to null, leaving a case note announcing an answer the row no longer
     *       holds and a recompute that has already taken the case off the waiting list; one that read before
     *       {@code CaseEmailService.send} committed would undo the stamp, leaving the applicant an email the
     *       referral denies was ever sent and the answer to it refused as never asked for.</li>
     *   <li><strong>Every guard here is a read-check-write.</strong> {@link #recordConfirmation} promises to
     *       refuse a second answer, {@link #close} to refuse anything but {@code Decided},
     *       {@link #withdrawReferral} to refuse what cannot be withdrawn, {@link #claim} to refuse what is not
     *       {@code Open} and {@link #requireHeldBy} to refuse a referral the caller does not hold. A decision
     *       taken on an unlocked read is a decision about a row that may have moved before it is written.
     *       Reading under the lock makes each guard and its write one indivisible step, and the second
     *       transaction re-reads the committed row when the lock is released -- so its guard is built on the
     *       first write rather than around it, which is
     *       {@link DeedPollRepository#findByIdForUpdate}'s argument for the patch path applied here.</li>
     * </ul>
     *
     * <p>{@link ReferralRepository#findByIdAndDeedPollIdForUpdate} carries the SQL-level reasoning: why the
     * lookup must not join the case table, and why the shared read must not take this lock instead. It is read
     * <strong>before</strong> anything writes the case, which keeps the one lock order every write path here
     * uses -- referral first, case last -- that
     * {@link DeedPollRepository#lockForConfirmationRecompute} explains.
     *
     * <p><strong>The lock is not enough on its own, which is what the {@code refresh} below is for.</strong> A JPA
     * query returns the instance already in the persistence context when there is one, and <em>discards the row it
     * just read</em> -- it upgrades the recorded lock mode and nothing else. So a caller that has already loaded
     * this referral in this transaction gets its own earlier snapshot back, and the two halves above both fail on
     * it: the guard decides on the pre-lock row, and the save writes every column back from it, which is exactly
     * the overwrite the lock was taken to prevent. <strong>{@code JudicialReferralService} is that caller and the
     * only one</strong> -- {@code visible()} loads the referral and its case to answer the visibility question a
     * judicial URL needs before {@link #claim}, {@link #touch} or {@link #decide} is reached -- so without this the
     * judicial surface is the one route on which the lock is decoration. {@code ConfirmationConcurrencyTest} holds
     * that schedule: it fails on the stamp with the {@code refresh} removed, and on nothing else.
     *
     * <p>{@code refresh} rather than detaching the earlier copy, so the transition mutates the <em>same</em>
     * instance the caller is holding and a judicial response cannot render a row that has moved underneath it. It
     * costs one {@code SELECT} on the write paths and none on the reads, and it cannot fail on a deleted row: this
     * transaction holds the lock by the time it runs, so nothing can remove the row the finder just returned.
     *
     * <p>Refuses in the same words for the same reasons -- an id nothing was stored under, an id that is not a
     * UUID, and a referral of another case are one 404 -- so locking changes when the row is read and nothing
     * about what the caller is told.
     */
    private Referral findReferralForUpdate(String deedPollId, String id) {
        Referral locked = identifier(id)
            .flatMap(referralId -> referrals.findByIdAndDeedPollIdForUpdate(referralId, deedPollId))
            .orElseThrow(() -> new NotFoundError("Referral " + id + " not found"));
        // Read the paragraph above before removing this: the row is locked from here, and this is what makes the
        // loaded copy of it say what the locked row says.
        entityManager.refresh(locked);
        return locked;
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
        Referral referral = findReferralForUpdate(deedPollId, id);
        if (!referral.getState().canBeWithdrawn()) {
            // Not idempotent-and-silent: a second withdrawal is a caseworker acting on a stale screen, and
            // answering 204 would tell them their click did something it did not.
            throw new ConflictError(refusedWithdrawal(id, referral.getState()));
        }

        final ReferralState fromState = referral.getState();
        referral.setState(ReferralState.Withdrawn);
        referral.setModifiedBy(caller.name());
        // Stamped here rather than left to @PreUpdate, for the reason WitnessService and SavedQueryService
        // stamp theirs: Hibernate only fires @PreUpdate when its dirty check finds a changed property, and
        // relying on the state change alone would make the audit stamp depend on the transition not being a
        // no-op -- which is exactly the case the guard above is about.
        referral.setModifiedOn(AuditClock.now());

        Referral saved = referrals.save(referral);
        audit(AuditAction.REFERRAL_WITHDRAWN, caller, deedPollId, saved, fromState,
              List.of(AuditValue.of("state", fromState, saved.getState())));
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
     * <p><strong>The claim belongs to the caller's IDAM subject, and their display name is only written
     * alongside it.</strong> {@code claimedById} is what every later ownership check reads -- {@link #touch},
     * {@link #decide} and the judicial surface's visibility rule -- so a judge keeps their claim across an IDAM
     * rename and cannot reach another judge's by sharing a display name. The subject is required before the
     * sweep, which is the first thing here that writes anything: a caller IDAM has not identified cannot own a
     * referral, so they must not be able to change one either.
     *
     * @throws ForbiddenError    if the caller does not hold {@link Role#JUDICIAL}
     * @throws UnauthorizedError if the caller carries no stable subject, so the claim would have no owner
     * @throws NotFoundError     if there is no such referral, or it belongs to another case
     * @throws ConflictError     if the referral is not {@code Open}
     */
    @Transactional
    public Referral claim(String deedPollId, String id, CurrentUser caller) {
        caller.requireAnyRole(Role.JUDICIAL);
        // Before the sweep as well as before the lookup, because the sweep writes: an unidentified caller has
        // nothing to own the claim with, and refusing them here is what makes that a refusal before any state
        // change rather than a null owner stored on a row.
        final String owner = caller.requireUserId();
        // Before the lookup, not after: the referral this caller is asking for may be the stale one, and
        // reading it first would load the row that is about to change underneath.
        reclaimStaleClaims();

        Referral referral = findReferralForUpdate(deedPollId, id);
        if (referral.getState() != ReferralState.Open) {
            throw new ConflictError("Referral " + id + " is " + referral.getState()
                + " and can only be claimed while it is " + ReferralState.Open);
        }

        LocalDateTime now = AuditClock.now();
        final ReferralState fromState = referral.getState();
        referral.setState(ReferralState.Claimed);
        // The subject owns it; the name is what a caseworker reading the case sees. Both are written here and
        // only one of them is ever compared again.
        referral.setClaimedById(owner);
        referral.setClaimedBy(caller.name());
        referral.setClaimedAt(now);
        // Stamped at the claim as well as at every later interaction, so a referral picked up and never
        // touched again lapses two hours after it was picked up rather than never.
        referral.setLastTouchedAt(now);
        referral.setModifiedBy(caller.name());
        referral.setModifiedOn(now);

        Referral saved = referrals.save(referral);
        audit(AuditAction.REFERRAL_CLAIMED, caller, deedPollId, saved, fromState,
              List.of(AuditValue.of("state", fromState, saved.getState())));
        // The claiming judge, not the service account: a person did this, and noteAuthor() is what puts their
        // name in createdBy -- which is why the prose says "claimed by a Master" rather than naming them
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
     * <p><strong>Only the judge holding it may touch it</strong>, and the check is by the subject the claim was
     * made under -- see {@link #requireHeldBy}. A referral that has been withdrawn, decided, or taken back by the
     * sweep is no longer held by anybody, so a judge who left the page open gets a conflict rather than silently
     * reviving a claim the pool has already re-offered.
     *
     * @throws ForbiddenError    if the caller does not hold {@link Role#JUDICIAL}
     * @throws UnauthorizedError if the caller carries no stable subject, so they can hold nothing
     * @throws NotFoundError     if there is no such referral, or it belongs to another case
     * @throws ConflictError     if the referral is not currently claimed by this caller
     */
    @Transactional
    public Referral touch(String deedPollId, String id, CurrentUser caller) {
        caller.requireAnyRole(Role.JUDICIAL);
        Referral referral = requireHeldBy(findReferralForUpdate(deedPollId, id), id, caller);

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
     * {@code claimedById} is their subject -- and that single check is what two other rules rely on rather than
     * implementing themselves:
     *
     * <ul>
     *   <li>A referral a caseworker withdrew is {@code Withdrawn}, so a judge who had it open and submits a
     *       direction afterwards gets a conflict rather than writing a direction onto a referral that was
     *       pulled back.</li>
     *   <li>A referral the sweep took back is {@code Open} with no {@code claimedById}, so the same is true of a
     *       judge whose claim lapsed while they were reading. The referral may by then be another judge's, and
     *       the two of them must not both be able to direct it.</li>
     * </ul>
     *
     * <p><strong>One transition, not a draft and a submit.</strong> The check-your-answers page and the
     * "Signed" attestation are the admin's, so what arrives here is one confirmed direction and it is recorded
     * atomically. {@code decisionDate} and {@code decidedBy} come from the clock and the token, never from the
     * payload -- see {@link DirectionInput}.
     *
     * <p><strong>The direction is signed by the same subject that owns the claim</strong>, because
     * {@link #requireHeldBy} has already established the two are the same: {@code decidedById} is the caller's
     * subject and {@code decidedBy} their display name, and it is {@code decidedById} the judicial surface reads
     * when its author comes back to re-read the direction.
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
     * @throws ForbiddenError    if the caller does not hold {@link Role#JUDICIAL}
     * @throws UnauthorizedError if the caller carries no stable subject, so they can hold nothing
     * @throws NotFoundError     if there is no such referral, or it belongs to another case
     * @throws ConflictError     if the referral is not currently claimed by this caller
     */
    @Transactional
    public Referral decide(String deedPollId, String id, DirectionInput direction, CurrentUser caller) {
        caller.requireAnyRole(Role.JUDICIAL);
        Referral referral = requireHeldBy(findReferralForUpdate(deedPollId, id), id, caller);

        LocalDateTime now = AuditClock.now();
        final ReferralState fromState = referral.getState();
        final List<AuditValue> before = List.of(
            AuditValue.of("decision", referral.getDecision(), direction.decision()),
            AuditValue.of("directionsText", referral.getDirectionsText(), direction.directionsText().orElse(null)),
            AuditValue.of("publishToGazette", referral.getPublishToGazette(), direction.publishToGazette()));
        referral.setState(ReferralState.Decided);
        referral.setDecision(direction.decision());
        referral.setDirectionsText(direction.directionsText().orElse(null));
        referral.setPublishToGazette(direction.publishToGazette());
        referral.setDecisionDate(now);
        // The judge's own name from the token, so the record says who directed it rather than who last saved
        // the row -- which the closing caseworker becomes a moment later. And their subject alongside it, which
        // requireHeldBy has just established is the subject holding the claim: the name signs the direction and
        // the subject is what lets its author read it back.
        referral.setDecidedById(caller.requireUserId());
        referral.setDecidedBy(caller.name());
        referral.setLastTouchedAt(now);
        referral.setModifiedBy(caller.name());
        referral.setModifiedOn(now);

        Referral saved = referrals.save(referral);
        List<AuditValue> values = new ArrayList<>(List.of(AuditValue.of("state", fromState, saved.getState())));
        values.addAll(before);
        audit(AuditAction.REFERRAL_DIRECTED, caller, deedPollId, saved, fromState, values);
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
     * The judge who directed a referral replacing that direction, while the office has not yet closed it (business
     * decision 5 of the 2026-10-19 plan).
     *
     * <p><strong>Permitted only on a {@code Decided} referral whose {@code decidedById} is the caller.</strong>
     * Anything this judge may not see under the lock -- another judge's direction, a namesake's, and above all a
     * referral the office closed or withdrew a moment ago -- is the judicial surface's own 404, in its words
     * ({@link #invisibleToJudge}). That is what settles a close racing a revise: whichever takes the row lock second
     * re-reads the first's committed row, so a revise after a close is a 404, as any later request would be, and a
     * close after a revise actions the revised direction. Anything the judge <em>can</em> see and that is not a
     * direction of theirs -- the pool, or their own claim -- is a 409: they are looking at it, and a 404 would be a
     * lie they cannot act on.
     *
     * <p>It replaces the decision, {@code directionsText} and {@code publishToGazette}, moves {@code decisionDate}
     * to now and keeps {@code decidedBy}/{@code decidedById}: the direction is still the same judge's, signed as it
     * was first given. {@code directionChangedAt} is set, and
     * nothing ever clears it: it is the flag that tells the caseworker the direction they may already be acting on
     * has changed, and it is a fact about the referral's history rather than its current state. The claim's own
     * columns stay as they were.
     *
     * <p><strong>The first direction stays on record.</strong> This is a new {@code REFERRAL_DIRECTION_REVISED}
     * event carrying old and new values of the same four fields {@link #decide} records, and the earlier
     * {@code REFERRAL_DIRECTED} event is never touched; the trail is append-only. One case note, authored by the
     * judge and composed like {@link #decide}'s, so the timeline says when the direction changed. Both read-models
     * are recomputed in this transaction (design decision 10): the state stays {@code Decided}, and the confirmation
     * columns are left alone, but each recompute is the rule rather than an assumption about what this write did.
     *
     * <p>No email and no Gazette change: a revised {@code publishToGazette} changes the referral only, and the
     * caseworker acts on the flag as on any direction.
     *
     * @throws ForbiddenError    if the caller does not hold {@link Role#JUDICIAL}
     * @throws UnauthorizedError if the caller carries no stable subject, so they can have directed nothing
     * @throws NotFoundError     if there is no such referral, it belongs to another case, or this judge may not see
     *                           it under the lock
     * @throws ConflictError     if this judge may see it but it is not a direction of theirs
     */
    @Transactional
    public Referral reviseDirection(String deedPollId, String id, DirectionInput direction, CurrentUser caller) {
        caller.requireAnyRole(Role.JUDICIAL);
        Referral referral = requireDirectedBy(findReferralForUpdate(deedPollId, id), id, "revised", caller);

        LocalDateTime now = AuditClock.now();
        final ReferralState fromState = referral.getState();
        final List<AuditValue> values = List.of(
            AuditValue.of("state", fromState, fromState),
            AuditValue.of("decision", referral.getDecision(), direction.decision()),
            AuditValue.of("directionsText", referral.getDirectionsText(), direction.directionsText().orElse(null)),
            AuditValue.of("publishToGazette", referral.getPublishToGazette(), direction.publishToGazette()));
        referral.setDecision(direction.decision());
        referral.setDirectionsText(direction.directionsText().orElse(null));
        referral.setPublishToGazette(direction.publishToGazette());
        referral.setDecisionDate(now);
        // decidedBy and decidedById are left alone: requireDirectedBy has established the subject is this caller's,
        // and the signature is the one the direction was first given under.
        referral.setDirectionChangedAt(now);
        referral.setLastTouchedAt(now);
        referral.setModifiedBy(caller.name());
        referral.setModifiedOn(now);

        Referral saved = referrals.save(referral);
        audit(AuditAction.REFERRAL_DIRECTION_REVISED, caller, deedPollId, saved, fromState, values);
        notes.createNote(deedPollId,
                         new CreateNoteInput(ReferralNotes.directionRevised(saved)),
                         caller.noteAuthor());
        refreshCurrentReferralState(deedPollId);
        refreshAwaitingConfirmation(deedPollId);
        return saved;
    }

    /**
     * The judge who directed a referral taking that direction back, so that they can give a new one with
     * {@link #decide} (design decision 6 of the 2026-10-19 plan).
     *
     * <p>The same precondition as {@link #reviseDirection}, decided the same way under the same lock: {@code Decided}
     * and directed by this caller, a 404 for anything this judge may not see -- including a referral the office has
     * just closed -- and a 409 for what they can see and did not direct.
     *
     * <p>{@code Decided -> Claimed}, held by the same judge: {@code claimedById} and {@code claimedBy} are left as
     * they were, {@code claimedAt} and {@code lastTouchedAt} restart now. The decision, {@code directionsText},
     * {@code publishToGazette}, {@code decisionDate}, {@code decidedBy} and {@code decidedById} are cleared, because
     * a claim carries no direction. {@code directionChangedAt} is set and never cleared.
     *
     * <p><strong>The consequence to keep visible:</strong> a withdrawn direction is a live claim again, so
     * {@link #reclaimStaleClaims()} returns it to the shared pool after
     * {@link ProcessSettingsService#referralClaimExpiry()} of inactivity, exactly as it would any other claim. That
     * is decided, not an oversight; the sweep is unchanged.
     *
     * <p>One {@code REFERRAL_DIRECTION_WITHDRAWN} event with old and new values of the state, decision,
     * {@code directionsText} and {@code publishToGazette}, as {@link #decide} records, and one case note authored by
     * the judge, composed from the direction being withdrawn. Both read-models are recomputed in this transaction:
     * the case leaves the "returned -- action needed" queue, and the confirmation columns are left as they are.
     *
     * @throws ForbiddenError    if the caller does not hold {@link Role#JUDICIAL}
     * @throws UnauthorizedError if the caller carries no stable subject, so they can have directed nothing
     * @throws NotFoundError     if there is no such referral, it belongs to another case, or this judge may not see
     *                           it under the lock
     * @throws ConflictError     if this judge may see it but it is not a direction of theirs
     */
    @Transactional
    public Referral withdrawDirection(String deedPollId, String id, CurrentUser caller) {
        caller.requireAnyRole(Role.JUDICIAL);
        Referral referral = requireDirectedBy(findReferralForUpdate(deedPollId, id), id, "withdrawn", caller);

        LocalDateTime now = AuditClock.now();
        final ReferralState fromState = referral.getState();
        final List<AuditValue> values = List.of(
            AuditValue.of("state", fromState, ReferralState.Claimed),
            AuditValue.of("decision", referral.getDecision(), null),
            AuditValue.of("directionsText", referral.getDirectionsText(), null),
            AuditValue.of("publishToGazette", referral.getPublishToGazette(), null));
        // Composed before the columns are cleared: the note says which direction is no longer the judge's answer.
        final String note = ReferralNotes.directionWithdrawn(referral);
        referral.setState(ReferralState.Claimed);
        referral.setDecision(null);
        referral.setDirectionsText(null);
        referral.setPublishToGazette(null);
        referral.setDecisionDate(null);
        referral.setDecidedBy(null);
        referral.setDecidedById(null);
        // claimedById and claimedBy are left alone: visibleTo has established the direction is this caller's, and
        // decide required the same subject to hold the claim it directed.
        referral.setClaimedAt(now);
        referral.setLastTouchedAt(now);
        referral.setDirectionChangedAt(now);
        referral.setModifiedBy(caller.name());
        referral.setModifiedOn(now);

        Referral saved = referrals.save(referral);
        audit(AuditAction.REFERRAL_DIRECTION_WITHDRAWN, caller, deedPollId, saved, fromState, values);
        notes.createNote(deedPollId, new CreateNoteInput(note), caller.noteAuthor());
        refreshCurrentReferralState(deedPollId);
        refreshAwaitingConfirmation(deedPollId);
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
        Referral referral = findReferralForUpdate(deedPollId, id);
        if (referral.getState() != ReferralState.Decided) {
            throw new ConflictError("Referral " + id + " is " + referral.getState()
                + " and can only be closed once it is " + ReferralState.Decided);
        }

        final ReferralState fromState = referral.getState();
        referral.setState(ReferralState.Closed);
        referral.setModifiedBy(caller.name());
        // Stamped rather than left to @PreUpdate, for the reason withdraw's is: Hibernate fires @PreUpdate
        // only when its dirty check finds a changed property.
        referral.setModifiedOn(AuditClock.now());

        Referral saved = referrals.save(referral);
        audit(AuditAction.REFERRAL_CLOSED, caller, deedPollId, saved, fromState,
              List.of(AuditValue.of("state", fromState, saved.getState())));
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
     * Records what the applicant answered when they were asked whether they still want the deed enrolled.
     *
     * <p>The other half of the exchange {@code REQUEST_CONFIRMATION_TO_PROCEED} starts: the send stamps
     * {@code confirmationRequestedAt} in the transaction that owes the email, and this writes the answer, when it
     * was recorded and who recorded it. <strong>Not a state transition</strong> -- the referral stays exactly where
     * it was, which is why nothing here touches {@code state} and why this is the one write in this service that
     * does not recompute the case's {@code currentReferralState}: there is nothing for that column to have changed
     * to.
     *
     * <p>It does recompute the case's {@code awaitingConfirmationSince}, in this transaction, because that column
     * is the answer to "is this case still waiting?" and this is the write that makes it no. A recording that
     * committed without it would leave the case in the waiting list for good, which is the one failure the list
     * exists to prevent.
     *
     * <p><strong>It does not move the case on either, and that is a decision rather than an omission.</strong>
     * {@code Confirmed} records that the applicant wants to proceed; it does not set {@code dateEnroled}, clear
     * {@code dateReturned} or otherwise reach into the date cascade, which is derived from those columns by a
     * verbatim port. The caseworker then does whatever the process requires through the ordinary case edit, audited
     * as their action rather than as a side effect of this one.
     *
     * <p><strong>Two refusals, each about a different mistake, and neither of them silent.</strong> An outcome
     * already recorded is a {@link ConflictError} naming it and when, because the second caseworker needs to know
     * the first already acted rather than quietly overwriting them -- there is no last-write-wins here and no
     * idempotent second success. A referral that was never asked to confirm is also a {@link ConflictError}:
     * recording an answer nobody asked for is a mis-click on the wrong case rather than a valid state, and
     * {@code confirmationRequestedAt} being null is what says so. The already-recorded check comes first because it
     * is the more specific fact; an outcome cannot exist without a request date through any path this service has.
     *
     * <p><strong>Both refusals are read under a row lock</strong> --
     * {@link ReferralRepository#findByIdAndDeedPollIdForUpdate} -- because a refusal decided on an unlocked read is
     * a read-check-write: two caseworkers recording at the same moment would both see no outcome, both pass, and
     * the second write would overwrite the first answer while both case notes survived. "Idempotent by refusal"
     * only means anything if the second caller is refused rather than served last. It is the same lock every write
     * here takes and for the second of the two reasons {@link #findReferralForUpdate} gives as well: a transition
     * that read this row before the answer committed would put the outcome back to null when it saved, because a
     * save writes every column. The lock is on the referral row
     * and on nothing else: the case's own lock is taken at the tail by {@link #refreshAwaitingConfirmation}, which
     * says why it is weaker than this one and why its position in the transaction carries no weight.
     *
     * <p><strong>No role gate</strong>, exactly as raising, withdrawing and closing have none: in the process this
     * replaces, whoever works the case reads the applicant's reply out of the shared inbox.
     *
     * <p>One case note, authored by the recording caseworker, written after the referral is saved and inside the
     * same transaction -- so a refused recording leaves no note claiming an answer arrived, and the author is the
     * person who read the reply rather than the service account. {@code confirmationRecordedBy} carries the same
     * name on the row; there is no subject column beside it, because nothing compares it.
     *
     * @throws NotFoundError if there is no such referral, or it belongs to another case
     * @throws ConflictError if an outcome is already recorded, or the applicant was never asked to confirm
     */
    @Transactional
    public Referral recordConfirmation(String deedPollId, String id, ConfirmationInput input, CurrentUser caller) {
        Referral referral = findReferralForUpdate(deedPollId, id);
        if (referral.getConfirmationOutcome() != null) {
            throw new ConflictError(alreadyConfirmed(id, referral));
        }
        if (referral.getConfirmationRequestedAt() == null) {
            throw new ConflictError("Referral " + id + " has not been asked to confirm whether the applicant"
                + " wants to proceed, so there is no answer to record");
        }

        LocalDateTime now = AuditClock.now();
        referral.setConfirmationOutcome(input.outcome());
        // When the caseworker recorded it, from the clock -- never when the applicant replied, which nothing here
        // knows.
        referral.setConfirmationReceivedAt(now);
        // From the token, never from the payload: an author a body could name is one anybody could forge.
        referral.setConfirmationRecordedBy(caller.name());
        referral.setModifiedBy(caller.name());
        // Stamped rather than left to @PreUpdate, for the reason withdraw's and close's are: Hibernate fires
        // @PreUpdate only when its dirty check finds a changed property.
        referral.setModifiedOn(now);

        Referral saved = referrals.save(referral);
        audit(AuditAction.REFERRAL_CONFIRMED, caller, deedPollId, saved, saved.getState(),
              List.of(AuditValue.created("confirmationOutcome", saved.getConfirmationOutcome())),
              saved.getConfirmationOutcome().name());
        // Composed from the saved referral rather than from the input, so the note reads the outcome as it was
        // stored -- and written inside this transaction, so a rolled-back recording leaves no note announcing one.
        notes.createNote(deedPollId,
                         new CreateNoteInput(ReferralNotes.confirmationRecorded(saved, input.note())),
                         caller.noteAuthor());
        // The case leaves the waiting list here, and by recompute rather than by writing null: the answer is
        // "nothing on this case is still outstanding", which the referral table knows and this method only
        // believes. See refreshAwaitingConfirmation.
        refreshAwaitingConfirmation(deedPollId);
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
     * <p>The window is {@link ProcessSettingsService#referralClaimExpiry()} and is never hard-coded: it is a
     * Delivery Manager setting ({@code /caseworker/process-settings}), seeded at two hours, so correcting the guess
     * about how judges work needs no release. It is read once per call, uncached, before anything is released, so
     * a missing settings row fails the sweep before it changes anything, and a change committed while a sweep runs
     * applies from the next one.
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
        LocalDateTime cutoff = now.minus(settings.referralClaimExpiry());

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

    private void audit(AuditAction action, CurrentUser caller, String deedPollId, Referral saved,
                       ReferralState fromState, List<AuditValue> values) {
        audit(action, caller, deedPollId, saved, fromState, values, null);
    }

    /**
     * Records one caller's write to a referral: the columns it set, their values, the state transition if it made
     * one, and for a confirmation the applicant's answer as its outcome.
     */
    private void audit(AuditAction action, CurrentUser caller, String deedPollId, Referral saved,
                       ReferralState fromState, List<AuditValue> values, String outcome) {
        AuditEntry entry = AuditEntry.of(action, AuditActor.of(caller), AuditEntities.REFERRAL,
                                         saved.getId().toString(), deedPollId)
            .withFields(values.stream().map(AuditValue::field).toList());
        if (fromState != saved.getState()) {
            if (fromState != null) {
                entry = entry.withDetail("fromState", fromState.name());
            }
            entry = entry.withDetail("toState", saved.getState().name());
        }
        if (outcome != null) {
            entry = entry.withDetail("outcome", outcome);
        }
        audit.record(entry, values);
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
     * Recomputes one case's denormalised {@code awaitingConfirmationSince} from the referral table.
     *
     * <p>Two sites change the answer and both call this in their own transaction: {@link #recordConfirmation},
     * where the answer arriving ends the wait, and {@code CaseEmailService.send}, where sending ④ starts one.
     * <strong>Public for that second caller and for no other reason</strong> -- the recompute is one rule with
     * one implementation, and the alternative was the same three lines written out in the notify package, which
     * is exactly how a read-model and its truth drift apart.
     *
     * <p>A recompute rather than a push of what the caller just did, for the reason
     * {@link ConfirmationWait} gives in full: "this case is waiting for nothing now" is a fact about every
     * referral of the case, and a site that wrote its own stamp or its own null would be wrong the moment a case
     * carried a second waiting referral.
     *
     * <p><strong>The case row is locked before the referral table is read, and that is what makes the recompute
     * right when two answers arrive at once.</strong> A case really can hold more than one outstanding request --
     * {@link ConfirmationWait} says why, and recording is ungated on referral state -- so two recordings on one
     * case are two transactions writing two different referral rows and then this one column. Without the lock
     * each reads the outstanding set before the other commits, each still sees the other referral as unanswered,
     * and whichever writes last leaves {@code awaitingConfirmationSince} pointing at a referral that has been
     * answered. Nothing would ever repair it: these two sites are the only writers, and there is no third answer
     * coming, so the case would sit in the waiting list for good -- the exact failure the list exists to prevent.
     * Taking the lock first serialises the pair, and the second transaction's read then sees the first's answer
     * <em>and</em> its own pending one, so it writes NULL.
     *
     * <p><strong>{@code FOR NO KEY UPDATE}, and deliberately not the patch path's {@code FOR UPDATE}</strong> --
     * the difference between queueing and aborting. Both callers arrive here having already inserted a row that
     * references the case -- a case note here, an outbox row in the notify package -- and a foreign key is
     * enforced with a {@code KEY SHARE} lock on the parent, the one strength {@code FOR UPDATE} conflicts with. Two
     * recordings, or a send and a recording, would each hold that key-share and each wait for the other to release
     * it, and Postgres would abort one. {@link DeedPollRepository#lockForConfirmationRecompute} carries the full
     * argument.
     *
     * <p>The lock is still taken <strong>after</strong> the caller has written its referral row and before this
     * reads the table, which is the one order every write path here uses -- referral first, case last -- and the
     * reason {@link ReferralRepository#findByIdAndDeedPollIdForUpdate} must not join the case table. A case lock
     * hoisted in front of a referral write would be a second lock order on the same pair of rows, which deadlocks
     * whatever strength it asks for.
     *
     * <p>The loaded row is not read and not written through: {@code writeAwaitingConfirmationSince} is still a
     * statement over one column, for the reason its own javadoc gives.
     *
     * @param deedPollId the case whose column to bring into step
     */
    @Transactional
    public void refreshAwaitingConfirmation(String deedPollId) {
        // For the lock alone: the row it returns is not read, and a case that has gone answers 0 below exactly as
        // it did before.
        deedPolls.lockForConfirmationRecompute(deedPollId);
        deedPolls.writeAwaitingConfirmationSince(
            deedPollId,
            ConfirmationWait.earliestOutstanding(
                referrals.findOutstandingConfirmationRequestsByDeedPollId(deedPollId)).orElse(null));
    }

    /**
     * The referral, if this caller is the judge currently holding it.
     *
     * <p>Both halves matter and neither is enough alone: the state says a claim exists, and
     * {@code claimedById} says whose it is. Kept in one place because the direction transition asks the same
     * question, and a second copy of it that drifted would let one judge write on another's referral.
     *
     * <p><strong>The subject and not the display name</strong>, since
     * {@code V14__referral_owner_subjects.sql}. Two judges can share a display name and one judge's can change,
     * so comparing names let the first pair direct each other's referrals and locked the second out of their own.
     * The caller goes through {@link CurrentUser#requireUserId()} rather than reading the component, for the
     * reason {@code SavedQueryPermissions} does: a caller with no subject is refused outright instead of being
     * compared against a null owner, which would make every unidentified caller the holder of every
     * pre-migration claim at once. A claim made before that migration therefore has no owner and is held by
     * nobody -- it fails closed, and the inactivity sweep returns it to the pool as it would any other.
     *
     * <p>The refusal still names the caller's <em>display name</em>, because it is read by a person and a
     * subject would tell them nothing. It says the same thing it always did: this is not yours.
     */
    private static Referral requireHeldBy(Referral referral, String id, CurrentUser caller) {
        if (referral.getState() != ReferralState.Claimed
            || !caller.requireUserId().equals(referral.getClaimedById())) {
            throw new ConflictError("Referral " + id + " is not claimed by " + caller.name()
                + " -- it is " + referral.getState());
        }
        return referral;
    }

    /**
     * The referral, if it is a direction this caller gave and the office has not yet closed.
     *
     * <p>Asked under the lock, of the row the lock protects, so visibility is decided again here rather than
     * trusted from the judicial surface's unlocked read: a referral closed between that read and this one is
     * invisible now, and the answer is {@link #invisibleToJudge}'s 404. {@link JudicialReferralService#visibleTo} is
     * the rule rather than a copy of it, so the two cannot disagree. A visible referral that is not this judge's
     * direction is the 409, naming what was attempted ({@code "revised"} or {@code "withdrawn"}).
     */
    private static Referral requireDirectedBy(Referral referral, String id, String attempted, CurrentUser caller) {
        if (!JudicialReferralService.visibleTo(referral, caller)) {
            throw invisibleToJudge(id);
        }
        // Visible and Decided means decidedById is this caller's subject: that is what visibleTo admits.
        if (referral.getState() != ReferralState.Decided) {
            throw new ConflictError("Referral " + id + " is " + referral.getState()
                + " and its direction can only be " + attempted + " while it is " + ReferralState.Decided
                + " by the judge who gave it");
        }
        return referral;
    }

    /**
     * The judicial surface's one refusal for a referral a judge may not see, in the words an unknown id gets.
     *
     * <p>The same text as the lookup's own 404, so a judge cannot tell a referral closed under them from one that
     * never existed, exactly as {@code JudicialReferralService.visible} promises.
     */
    private static NotFoundError invisibleToJudge(String id) {
        return new NotFoundError("Referral " + id + " not found");
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

    /**
     * Why a second recording was refused: what is already on the referral, and when it was recorded.
     *
     * <p>Named and dated rather than a bare "already recorded", because the caseworker being refused has read the
     * applicant's reply and may be looking at a different answer from the one stored -- and their next action
     * depends on which. A date with no outcome, or an outcome with no date, would each leave them to go and read
     * the row themselves.
     *
     * <p>Who recorded it is deliberately not quoted. The two of them work the same cases and the referral carries
     * {@code confirmationRecordedBy} for anybody who needs it; naming a colleague in a refusal turns a correction
     * into an accusation.
     */
    private static String alreadyConfirmed(String id, Referral referral) {
        LocalDateTime recorded = referral.getConfirmationReceivedAt();
        // The date is written with the outcome, so a row holding one without the other is not reachable through
        // this service -- and a refusal that threw while composing itself would answer 500 for a state it is
        // supposed to explain, so the null says so instead.
        String when = recorded == null ? "a date that was not recorded" : DAY.format(recorded);
        return "Referral " + id + " already has a confirmation outcome of " + referral.getConfirmationOutcome()
            + ", recorded on " + when
            + ". Recording a second answer would overwrite it, so it is refused";
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
