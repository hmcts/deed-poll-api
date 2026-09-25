package uk.gov.hmcts.reform.deedpoll.domain.referrals;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import uk.gov.hmcts.reform.deedpoll.config.AbstractPostgresContainerTest;
import uk.gov.hmcts.reform.deedpoll.domain.ConflictError;
import uk.gov.hmcts.reform.deedpoll.domain.auth.CurrentUser;
import uk.gov.hmcts.reform.deedpoll.domain.auth.Role;
import uk.gov.hmcts.reform.deedpoll.entity.DeedPoll;
import uk.gov.hmcts.reform.deedpoll.entity.DeedPollNote;
import uk.gov.hmcts.reform.deedpoll.entity.Referral;
import uk.gov.hmcts.reform.deedpoll.entity.enums.ConfirmationOutcome;
import uk.gov.hmcts.reform.deedpoll.entity.enums.ReferralState;
import uk.gov.hmcts.reform.deedpoll.repository.DeedPollNoteRepository;
import uk.gov.hmcts.reform.deedpoll.repository.DeedPollRepository;
import uk.gov.hmcts.reform.deedpoll.repository.NotificationRepository;
import uk.gov.hmcts.reform.deedpoll.repository.ReferralRepository;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Asserts that two applicants' answers arriving at the same moment leave the referral and the case right.
 *
 * <p><strong>Why this needs its own test against a real database</strong>, for the reason
 * {@code DeedPollConcurrentUpdateTest} needs one: every other test of this path runs one transaction at a time,
 * and a single-threaded test passes whether or not the rows are locked. Both defects below first appear in an
 * environment where two caseworkers are working the same case, and neither raises anything -- both callers are
 * answered as though they had succeeded.
 *
 * <p>The three are different mistakes on different rows:
 *
 * <ul>
 *   <li><strong>The refusal.</strong> {@link ReferralService#recordConfirmation} promises to refuse a second
 *       answer rather than overwrite the first. Read without a lock that promise is a read-check-write: both
 *       callers see no outcome, both pass the guard, and the second {@code UPDATE} -- which waits for the first
 *       to commit and then writes the snapshot it took before it -- stores its own answer over the other's while
 *       both case notes survive, so the timeline says the applicant answered twice and the row says something a
 *       third caseworker cannot reconcile with either note.</li>
 *   <li><strong>The waiting list.</strong> A case can hold more than one outstanding request --
 *       {@link ConfirmationWait} has the route -- so two answers are two transactions writing two different
 *       referral rows and then one column on the case. Recomputing that column from a read taken before the
 *       lock, each misses the other's answer and the last writer leaves
 *       {@code awaitingConfirmationSince} naming a referral that has been answered. Nothing repairs it: these
 *       two sites are the column's only writers and no third answer is coming, so the case sits in the waiting
 *       list for good -- the one failure the list exists to prevent.</li>
 *   <li><strong>The columns a transition never meant to touch.</strong> Recording is ungated on the referral's
 *       state and so is the send, so either can be running while a caseworker closes or withdraws the same
 *       referral -- and a {@code save} writes the whole row, because Hibernate has no dirty-column tracking on
 *       {@link uk.gov.hmcts.reform.deedpoll.entity.Referral}. A transition that read the row a moment before the
 *       answer or the stamp committed therefore writes that column back to NULL while everything else about it
 *       survives: the case note, the outbox row, the case's recomputed read-model. Nothing raises, both callers
 *       are told they succeeded, and the row afterwards contradicts both.</li>
 * </ul>
 *
 * <p>Removing the lock from {@code ReferralRepository.findByIdAndDeedPollIdForUpdate} fails the first and both
 * tests of the third, and so does returning any one transition to the unlocked finder; removing the lock
 * {@link ReferralService#refreshAwaitingConfirmation} takes on the case fails the second.
 *
 * <p><strong>The fourth test is the third mistake reached the one way the lock alone does not stop it</strong>, and
 * it is here rather than beside the others because the schedule is the judicial surface's rather than two
 * caseworkers'. {@code JudicialReferralService} resolves the referral without a lock to answer its visibility
 * question before it calls a transition, so the transition's locked read finds that instance already in the
 * persistence context -- and a JPA query hands back the loaded instance and discards the row it read. Taking the
 * lock is therefore not enough on that one route: the guard decides on the pre-lock snapshot and the save writes
 * every column back from it. Removing the {@code refresh} from
 * {@code ReferralService.findReferralForUpdate} fails that test and no other.
 *
 * <p>The third test is about that second lock's <em>strength</em> rather than its absence, and it fails in the
 * opposite direction: strengthening it to the patch path's {@code FOR UPDATE} makes Postgres abort one of two
 * transactions that would otherwise queue. {@code DeedPollRepository.lockForConfirmationRecompute} has the
 * argument.
 *
 * <p>The fourth race of this feature -- an answer recorded while a replacement ④ is being sent -- is not here
 * because it needs no schedule to prove: the send's stamp is a single conditional statement, so
 * {@code ReferralRepositoryTest} asserting that it refuses an answered row and leaves every column alone is the
 * whole of it, exactly as the sweep's conditional release is proved there rather than with two threads.
 *
 * <p>Not {@code @Transactional}: the point is what two separate committed transactions leave behind.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class ConfirmationConcurrencyTest extends AbstractPostgresContainerTest {

    private static final int TIMEOUT_SECONDS = 60;

    /** How long the first transaction stays open after the second has said it is on its way to the row. */
    private static final long OVERLAP_MILLIS = 500;

    private static final CurrentUser ADA =
        CurrentUser.currentUser(List.of(), "Ada Caseworker", "caseworker-1");

    private static final CurrentUser BO =
        CurrentUser.currentUser(List.of(), "Bo Caseworker", "caseworker-2");

    /** A judge, for the one schedule here that goes through the judicial surface's read-then-transition shape. */
    private static final CurrentUser MARION =
        CurrentUser.currentUser(List.of(Role.JUDICIAL), "Marion Master", "judge-1");

    @Autowired
    private ReferralService service;

    @Autowired
    private DeedPollRepository deedPolls;

    @Autowired
    private ReferralRepository referrals;

    @Autowired
    private DeedPollNoteRepository notes;

    @Autowired
    private NotificationRepository notifications;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    @AfterEach
    void clearTables() {
        notifications.deleteAll();
        notes.deleteAll();
        referrals.deleteAll();
        deedPolls.deleteAll();
    }

    @Test
    @DisplayName("two answers on one referral: the second is refused, and the first's answer is what is stored")
    void refusesTheSecondAnswerOnOneReferral() throws Exception {
        String caseId = storedCase();
        String referralId = askedReferral(caseId, LocalDateTime.of(2026, 5, 3, 9, 30));

        List<Throwable> failures = recordConcurrently(
            () -> service.recordConfirmation(caseId, referralId, confirmation("Confirmed"), ADA),
            () -> service.recordConfirmation(caseId, referralId, confirmation("Withdrawn"), BO));

        // The first caller wrote and committed while holding the row, so the second's own read could not happen
        // until there was an answer for it to be refused by.
        assertThat(failures.getFirst()).isNull();
        assertThat(failures.getLast())
            .isInstanceOf(ConflictError.class)
            .hasMessageContaining(ConfirmationOutcome.Confirmed.name());

        Referral stored = referrals.findById(UUID.fromString(referralId)).orElseThrow();
        // Not the later writer's, which is the whole claim: Withdrawn means the applicant wants a refund and
        // Confirmed means they want the deed enrolled, so a silent overwrite is the difference between enrolling
        // a case and refunding it.
        assertThat(stored.getConfirmationOutcome()).isEqualTo(ConfirmationOutcome.Confirmed);
        assertThat(stored.getConfirmationRecordedBy()).isEqualTo("Ada Caseworker");
        // One note, because the refused caller's transaction rolled back with it. Two notes on a row carrying one
        // outcome is what a caseworker reading the timeline afterwards cannot make sense of.
        List<DeedPollNote> written = notes.findByDeedPollIdOrderByCreatedOnDesc(caseId);
        assertThat(written).hasSize(1);
        assertThat(written.getFirst().getNote()).contains(ConfirmationOutcome.Confirmed.name());
        // And the case leaves the waiting list, because the one request on it has been answered.
        assertThat(deedPolls.findById(caseId).orElseThrow().getAwaitingConfirmationSince()).isNull();
    }

    @Test
    @DisplayName("two answers on two of one case's referrals: the case leaves the waiting list")
    void leavesTheWaitingListWhenBothOutstandingAnswersArriveAtOnce() throws Exception {
        String caseId = storedCase();
        LocalDateTime earlier = LocalDateTime.of(2026, 4, 1, 8, 0);
        LocalDateTime later = LocalDateTime.of(2026, 6, 20, 16, 45);
        // Two outstanding requests on one case, as the office produces them: the first referral is closed with its
        // question unanswered, the case is referred and refused again, and the second is asked in its turn.
        String first = askedReferral(caseId, earlier);
        String second = askedReferral(caseId, later);
        writeWait(caseId, earlier);

        List<Throwable> failures = recordConcurrently(
            () -> service.recordConfirmation(caseId, first, confirmation("Confirmed"), ADA),
            () -> service.recordConfirmation(caseId, second, confirmation("Withdrawn"), BO));

        // Both are legitimate: two different referrals, two different questions, two different answers.
        assertThat(failures).containsOnlyNulls();
        assertThat(referrals.findById(UUID.fromString(first)).orElseThrow().getConfirmationOutcome())
            .isEqualTo(ConfirmationOutcome.Confirmed);
        assertThat(referrals.findById(UUID.fromString(second)).orElseThrow().getConfirmationOutcome())
            .isEqualTo(ConfirmationOutcome.Withdrawn);
        // The column both of them recomputed. Read before the case's lock, each recompute sees the other referral
        // still unanswered and writes its date, so the case stays in the waiting list with nothing outstanding on
        // it and no further answer able to take it out.
        assertThat(deedPolls.findById(caseId).orElseThrow().getAwaitingConfirmationSince()).isNull();
        assertThat(notes.findByDeedPollIdOrderByCreatedOnDesc(caseId)).hasSize(2);
    }

    @Test
    @DisplayName("a referral closed at the same moment keeps the answer that was just recorded on it")
    void keepsTheAnswerWhenTheReferralIsClosedAtTheSameMoment() throws Exception {
        String caseId = storedCase();
        LocalDateTime asked = LocalDateTime.of(2026, 5, 3, 9, 30);
        // Decided, so the office's own move on it is close() -- which is the ordinary thing to be doing with a
        // returned direction while the applicant's reply is being read out of the shared inbox.
        String referralId = storedReferral(caseId, ReferralState.Decided, asked);
        writeWait(caseId, asked);

        List<Throwable> failures = recordConcurrently(
            () -> service.recordConfirmation(caseId, referralId, confirmation("Confirmed"), ADA),
            () -> service.close(caseId, referralId, BO));

        // Neither is in conflict with the other: one records an answer and the other actions a direction, and
        // recording is deliberately ungated on the referral's state.
        assertThat(failures).containsOnlyNulls();
        Referral stored = referrals.findById(UUID.fromString(referralId)).orElseThrow();
        // The claim of this test. A transition that read this row before the answer committed would write every
        // column back as it read them -- Hibernate has no dirty-column tracking here -- so the outcome would
        // return to NULL behind a case note announcing it, and the case would be waiting for a reply it has
        // already had with no second answer coming to take it off the list.
        assertThat(stored.getConfirmationOutcome()).isEqualTo(ConfirmationOutcome.Confirmed);
        assertThat(stored.getConfirmationRecordedBy()).isEqualTo("Ada Caseworker");
        assertThat(stored.getConfirmationRequestedAt()).isEqualTo(asked);
        // And the closure is not lost either: it read the committed row and its own transition stands on it.
        assertThat(stored.getState()).isEqualTo(ReferralState.Closed);
        assertThat(deedPolls.findById(caseId).orElseThrow().getAwaitingConfirmationSince()).isNull();
        assertThat(notes.findByDeedPollIdOrderByCreatedOnDesc(caseId)).hasSize(2);
    }

    @Test
    @DisplayName("a referral closed at the same moment keeps the stamp the send that asked put on it")
    void keepsTheSendsStampWhenTheReferralIsClosedAtTheSameMoment() throws Exception {
        String caseId = storedCase();
        LocalDateTime asked = LocalDateTime.of(2026, 5, 3, 9, 30);
        // Never asked yet: the send about to run is what asks.
        String referralId = storedReferral(caseId, ReferralState.Decided, null);

        List<Throwable> failures = recordConcurrently(
            // CaseEmailService.send's three steps in its order -- the outbox row, the clock, the case's
            // read-model -- written here rather than through the service because this test needs a schedule
            // inside that transaction and not a Notify stub.
            () -> {
                notifications.enqueue(UUID.randomUUID(), caseId, UUID.randomUUID(),
                                      UUID.fromString(referralId), "REQUEST_CONFIRMATION_TO_PROCEED",
                                      "REFERRAL_OUTCOME", "applicant@example.com", "{}", asked, ADA.name());
                referrals.startConfirmationClockByIdAndDeedPollId(
                    UUID.fromString(referralId), caseId, asked, ADA.name());
                service.refreshAwaitingConfirmation(caseId);
            },
            () -> service.close(caseId, referralId, BO));

        // Including that the closure is not aborted: it asks for the referral row while the send holds the
        // key-share its outbox row's foreign key took on that same row, so it queues rather than deadlocking.
        assertThat(failures).containsOnlyNulls();
        Referral stored = referrals.findById(UUID.fromString(referralId)).orElseThrow();
        // The send's own narrow statement cannot lose a column, but a closure that read before it committed and
        // saved afterwards would write the stamp back to NULL -- leaving the applicant an email the referral
        // denies was ever sent, the case waiting in the list on the strength of a recompute the send made, and
        // the answer when it arrives refused as never asked for.
        assertThat(stored.getConfirmationRequestedAt()).isEqualTo(asked);
        assertThat(stored.getState()).isEqualTo(ReferralState.Closed);
        assertThat(deedPolls.findById(caseId).orElseThrow().getAwaitingConfirmationSince()).isEqualTo(asked);
    }

    @Test
    @DisplayName("a direction recorded through the judicial surface's read keeps a stamp committed in between")
    void keepsTheSendsStampWhenTheJudicialSurfaceResolvedTheReferralFirst() throws Exception {
        String caseId = storedCase();
        LocalDateTime asked = LocalDateTime.of(2026, 5, 3, 9, 30);
        // Claimed by this judge and never asked to confirm: the send about to run is what asks.
        String referralId = claimedReferral(caseId);

        List<Throwable> failures = transitionAfterAnUnlockedRead(
            referralId,
            () -> service.decide(caseId, referralId, direction(), MARION),
            () -> referrals.startConfirmationClockByIdAndDeedPollId(
                UUID.fromString(referralId), caseId, asked, ADA.name()));

        assertThat(failures).containsOnlyNulls();
        Referral stored = referrals.findById(UUID.fromString(referralId)).orElseThrow();
        // The claim of this test, and it is about the persistence context rather than the row lock: the judicial
        // surface resolves the referral without a lock to answer its visibility question, so the transition's own
        // locked read finds that instance already loaded. Taking the lock is not enough on its own -- the loaded
        // copy has to be brought up to the row the lock now protects, or the save writes every column back from
        // the pre-send snapshot and the stamp returns to NULL behind an email that has already been enqueued.
        assertThat(stored.getConfirmationRequestedAt()).isEqualTo(asked);
        // And the direction itself is not lost either: the transition stands on the row the lock gave it.
        assertThat(stored.getState()).isEqualTo(ReferralState.Decided);
        assertThat(stored.getDecidedById()).isEqualTo(MARION.userId());
    }

    @Test
    @DisplayName("a recording and a send both recompute the wait, rather than Postgres aborting one of them")
    void queuesTheRecomputesBehindTheForeignKeyLocksItsCallersHold() throws Exception {
        String caseId = storedCase();
        LocalDateTime asked = LocalDateTime.of(2026, 5, 3, 9, 30);
        String referralId = askedReferral(caseId, asked);
        writeWait(caseId, asked);

        // Both writers of the column reach the recompute having already inserted a row that references the case:
        // recordConfirmation a case note, CaseEmailService.send an outbox row. Postgres enforces each of those
        // foreign keys by taking a KEY SHARE lock on the case row -- which conflicts with exactly one requested
        // strength, FOR UPDATE. So with the patch path's lock here, each of these transactions waits for the
        // other's key-share to be released and the deadlock detector aborts one; the caseworker whose recording or
        // send it was gets a 500 for a request that was never in conflict with anything.
        //
        // The child row is written by the test rather than by the service because the schedule needs a pause
        // between the insert and the recompute, and both production callers do exactly this pair in this order.
        List<Throwable> failures = bothRecomputeAfterWritingTheirRow(
            caseId,
            () -> notes.saveAndFlush(note(caseId, "The applicant's reply, read out of the shared inbox.")),
            () -> notifications.enqueue(UUID.randomUUID(), caseId, UUID.randomUUID(),
                                       UUID.fromString(referralId), "REQUEST_CONFIRMATION_TO_PROCEED",
                                       "REFERRAL_OUTCOME", "applicant@example.com", "{}",
                                       LocalDateTime.of(2026, 5, 3, 9, 30), BO.name()));

        assertThat(failures)
            .as("neither transaction is in conflict with the other: they write different rows and read the same one")
            .containsOnlyNulls();
        // And the column still says what the one outstanding request says, from whichever of them wrote it last:
        // queueing is only worth having if the answer at the end of it is right.
        assertThat(deedPolls.findById(caseId).orElseThrow().getAwaitingConfirmationSince()).isEqualTo(asked);
    }

    /** A note on the case, which is the row a recording writes before it recomputes. */
    private DeedPollNote note(String deedPollId, String text) {
        DeedPollNote written = new DeedPollNote();
        written.setDeedPoll(deedPolls.findById(deedPollId).orElseThrow());
        written.setNote(text);
        written.setCreatedBy(ADA.name());
        return written;
    }

    /**
     * Runs two transactions that each write their row referencing the case and then recompute the case's wait,
     * with both inserts committed to nothing and complete before either recompute starts.
     *
     * <p>A barrier rather than the latches and sleeps above, because here both halves are observable: the schedule
     * that matters is "both key-share locks held, then both recomputes", and each thread can say when it has
     * written its row. Neither transaction commits until both have been through, so the second recompute is
     * genuinely asking for a lock the first still holds.
     *
     * @return what each transaction threw, in the order the work was given, with null for one that committed
     */
    private List<Throwable> bothRecomputeAfterWritingTheirRow(String caseId, Runnable first, Runnable second)
        throws Exception {
        CyclicBarrier bothWritten = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        // A list that takes nulls, as above: a transaction that committed contributes one, which is the outcome
        // this test is asserting for both of them.
        List<Throwable> failures = new ArrayList<>();
        try {
            Future<Throwable> firstWrite = pool.submit(() -> recomputeAfter(caseId, first, bothWritten));
            Future<Throwable> secondWrite = pool.submit(() -> recomputeAfter(caseId, second, bothWritten));
            failures.add(firstWrite.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            failures.add(secondWrite.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
        return failures;
    }

    /** One transaction: write the row referencing the case, wait for the other to have written its own, recompute. */
    private Throwable recomputeAfter(String caseId, Runnable childRow, CyclicBarrier bothWritten) {
        return attempt(() -> new TransactionTemplate(transactionManager).execute(status -> {
            childRow.run();
            arriveAt(bothWritten);
            service.refreshAwaitingConfirmation(caseId);
            return null;
        }));
    }

    private static void arriveAt(CyclicBarrier barrier) {
        try {
            barrier.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        } catch (BrokenBarrierException | TimeoutException never) {
            throw new IllegalStateException("the other transaction never wrote its row", never);
        }
    }

    /**
     * Runs one transaction in the judicial surface's shape -- resolve the referral without a lock, then perform the
     * transition -- with another transaction committing on that same row in between.
     *
     * <p>The schedule is the one {@code JudicialReferralService} produces and no caseworker path does:
     * {@code visible()} loads the referral and its case to answer the visibility question, and only then calls
     * {@code ReferralService.claim}, {@code touch} or {@code decide}, which take the row lock. So the transition's
     * locked read happens with that referral already in the transaction's persistence context, and anything
     * committed in the gap is a write the transition must not undo.
     *
     * <p>Latches rather than sleeps, in both directions: the writer does not start until the unlocked read has
     * happened, and the transition does not start until the writer has committed. Neither ordering can slip on a
     * slow machine, and neither transaction is ever waiting on a lock the other holds -- which is the point, since
     * this test is about the persistence context and not about the lock.
     *
     * @return what each transaction threw, judicial first, with null for one that committed
     */
    private List<Throwable> transitionAfterAnUnlockedRead(String referralId, Runnable transition, Runnable between)
        throws Exception {
        CountDownLatch resolved = new CountDownLatch(1);
        CountDownLatch committed = new CountDownLatch(1);
        List<Throwable> failures = new ArrayList<>();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Throwable> judicial = pool.submit(() -> attempt(() ->
                new TransactionTemplate(transactionManager).execute(status -> {
                    // JudicialReferralService.visible(), which is the whole reason this schedule exists.
                    referrals.findWithCaseById(UUID.fromString(referralId)).orElseThrow();
                    resolved.countDown();
                    await(committed);
                    transition.run();
                    return null;
                })));

            Future<Throwable> writer = pool.submit(() -> {
                await(resolved);
                Throwable failure = attempt(() ->
                    new TransactionTemplate(transactionManager).execute(status -> {
                        between.run();
                        return null;
                    }));
                committed.countDown();
                return failure;
            });

            failures.add(judicial.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            failures.add(writer.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
        return failures;
    }

    /** A case with nothing on it but the date the reference allocation needs. */
    private String storedCase() {
        DeedPoll deedPoll = new DeedPoll();
        deedPoll.setDateReceived(LocalDateTime.of(2026, 1, 2, 10, 0));
        return deedPolls.save(deedPoll).getId();
    }

    /**
     * A referral of that case the office has asked to confirm, with no answer on it yet.
     *
     * <p>{@code Closed} because recording is ungated on the referral's state and the office may finish with the
     * referral before the applicant replies -- which is also what lets one case carry two outstanding requests.
     */
    private String askedReferral(String deedPollId, LocalDateTime askedAt) {
        return storedReferral(deedPollId, ReferralState.Closed, askedAt);
    }

    /**
     * A referral of that case in the given state, asked to confirm on the given date or never asked at all.
     *
     * <p>The state is a parameter for the two tests below, which need a referral the office can still
     * <em>close</em>: closing is what a caseworker does to a {@code Decided} referral, and it is the transition
     * most likely to be running while the applicant's answer arrives.
     */
    private String storedReferral(String deedPollId, ReferralState state, LocalDateTime askedAt) {
        Referral referral = new Referral();
        referral.setDeedPoll(deedPolls.findById(deedPollId).orElseThrow());
        referral.setState(state);
        referral.setConfirmationRequestedAt(askedAt);
        return referrals.save(referral).getId().toString();
    }

    /** The case's read-model as the send that asked would have left it: the earliest outstanding request. */
    private void writeWait(String deedPollId, LocalDateTime since) {
        new TransactionTemplate(transactionManager).execute(status -> {
            deedPolls.writeAwaitingConfirmationSince(deedPollId, since);
            return null;
        });
    }

    /**
     * A referral of that case held by {@link #MARION}, never asked to confirm, so a direction is the next move on
     * it -- which is the transition the judicial surface reaches through an unlocked read of this row.
     */
    private String claimedReferral(String deedPollId) {
        LocalDateTime picked = LocalDateTime.of(2026, 5, 1, 9, 0);
        Referral referral = new Referral();
        referral.setDeedPoll(deedPolls.findById(deedPollId).orElseThrow());
        referral.setState(ReferralState.Claimed);
        // The subject, because that is what requireHeldBy compares; the name is attribution beside it.
        referral.setClaimedById(MARION.userId());
        referral.setClaimedBy(MARION.name());
        referral.setClaimedAt(picked);
        referral.setLastTouchedAt(picked);
        return referrals.save(referral).getId().toString();
    }

    /** The shortest direction the schema accepts, since this test is about the row rather than about the words. */
    private static DirectionInput direction() {
        return ReferralSchemas.parseDirection(Map.of(
            "decision", "Approved",
            "directionsText", "Enrol the deed.",
            "publishToGazette", true));
    }

    private static ConfirmationInput confirmation(String outcome) {
        return ReferralSchemas.parseConfirmation(Map.of("outcome", (Object) outcome));
    }

    /**
     * Runs two recordings from two threads, on the schedule that loses a write: both transactions read before
     * either commits, and the second commits last.
     *
     * <p>{@code DeedPollConcurrentUpdateTest.applyConcurrently}'s schedule, and it is the same argument. The
     * first records and then waits to be told the second is on its way before holding its transaction open for
     * {@code OVERLAP_MILLIS}; the second waits for that signal, so it never starts before the first has read and
     * written, and holds on for twice as long, so it commits last. Waiting on a signal rather than sleeping blind
     * is what keeps that order on a slow machine; the sleeps cover only the gap between "about to read" and "at
     * the row", which no signal can report.
     *
     * @return what each recording threw, in the order they were given, with null for one that succeeded -- so a
     *     refusal is an assertion here rather than a worker's exception surfacing as a failure of the test
     */
    private List<Throwable> recordConcurrently(Runnable first, Runnable second) throws Exception {
        CountDownLatch firstRecorded = new CountDownLatch(1);
        CountDownLatch secondStarting = new CountDownLatch(1);
        List<Throwable> failures = new ArrayList<>();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Throwable> firstWrite = pool.submit(() -> attempt(() ->
                new TransactionTemplate(transactionManager).execute(status -> {
                    first.run();
                    firstRecorded.countDown();
                    await(secondStarting);
                    sleep(OVERLAP_MILLIS);
                    return null;
                })));

            Future<Throwable> secondWrite = pool.submit(() -> {
                await(firstRecorded);
                secondStarting.countDown();
                return attempt(() -> new TransactionTemplate(transactionManager).execute(status -> {
                    second.run();
                    sleep(2 * OVERLAP_MILLIS);
                    return null;
                }));
            });

            failures.add(firstWrite.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            failures.add(secondWrite.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
        return failures;
    }

    /**
     * What the work threw, or null: a refused recording is an expected outcome here, not a broken worker.
     */
    private static Throwable attempt(Runnable work) {
        try {
            work.run();
            return null;
        } catch (RuntimeException refused) {
            return refused;
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new IllegalStateException("the other recording never got as far as signalling");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }
}
