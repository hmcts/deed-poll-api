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
import uk.gov.hmcts.reform.deedpoll.domain.NotFoundError;
import uk.gov.hmcts.reform.deedpoll.domain.auth.CurrentUser;
import uk.gov.hmcts.reform.deedpoll.domain.auth.Role;
import uk.gov.hmcts.reform.deedpoll.entity.DeedPoll;
import uk.gov.hmcts.reform.deedpoll.entity.Referral;
import uk.gov.hmcts.reform.deedpoll.entity.enums.MasterDecision;
import uk.gov.hmcts.reform.deedpoll.entity.enums.ReferralState;
import uk.gov.hmcts.reform.deedpoll.repository.DeedPollNoteRepository;
import uk.gov.hmcts.reform.deedpoll.repository.DeedPollRepository;
import uk.gov.hmcts.reform.deedpoll.repository.ReferralRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A caseworker closing a referral while the judge who directed it revises the direction: the second waits for the
 * first, and the outcome is whatever that order means (design decision 10 of the 2026-10-19 plan).
 *
 * <p>Against a real database for {@code ConfirmationConcurrencyTest}'s reason: one transaction at a time passes
 * whether or not the row is locked. Both schedules go through the judicial surface's own shape -- an unlocked read to
 * answer the visibility question, then the transition's locked read -- because that is the route on which a revise
 * can pass visibility on a row that a close then finishes before the revise reaches the lock.
 *
 * <ul>
 *   <li><strong>Close first.</strong> The revise passes {@code visible()} on the still-{@code Decided} row, queues on
 *       the lock, and on the committed {@code Closed} row is the same 404 any later request gets. Nothing is
 *       revised, and the trail and the timeline say only that the direction was actioned.</li>
 *   <li><strong>Revise first.</strong> The close queues, then closes the revised direction, so what the office
 *       actioned is what the row and the flag say the judge last directed.</li>
 *   <li><strong>Close, then withdraw.</strong> As close-first: the withdraw is a 404 and the actioned direction is
 *       left intact.</li>
 *   <li><strong>Withdraw first.</strong> The close queues and finds a {@code Claimed} referral with no direction,
 *       so it is a conflict: the office cannot action a direction the judge has just withdrawn.</li>
 * </ul>
 *
 * <p>Not {@code @Transactional}: the point is what two separate committed transactions leave behind.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class DirectionRevisionConcurrencyTest extends AbstractPostgresContainerTest {

    private static final int TIMEOUT_SECONDS = 60;

    /** How long the first transaction holds the row after the second has said it is on its way to it. */
    private static final long OVERLAP_MILLIS = 500;

    private static final CurrentUser ADA =
        CurrentUser.currentUser(List.of(), "Ada Caseworker", "caseworker-1");

    private static final CurrentUser MARION =
        CurrentUser.currentUser(List.of(Role.JUDICIAL), "Marion Master", "judge-1");

    @Autowired
    private ReferralService service;

    @Autowired
    private JudicialReferralService judicial;

    @Autowired
    private DeedPollRepository deedPolls;

    @Autowired
    private ReferralRepository referrals;

    @Autowired
    private DeedPollNoteRepository notes;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    @AfterEach
    void clearTables() {
        notes.deleteAll();
        referrals.deleteAll();
        deedPolls.deleteAll();
    }

    @Test
    @DisplayName("a close that commits first makes the waiting revise a 404, and revises nothing")
    void closeFirst() throws Exception {
        String caseId = storedCase();
        String referralId = directedReferral(caseId);

        Outcome outcome = second(
            () -> service.close(caseId, referralId, ADA),
            () -> judicial.revise(referralId, refusal(), MARION));

        assertThat(outcome.first()).isNull();
        assertThat(outcome.second())
            .isInstanceOf(NotFoundError.class)
            .hasMessage("Referral " + referralId + " not found");
        assertThat(outcome.secondWaited()).isTrue();

        Referral stored = referrals.findById(UUID.fromString(referralId)).orElseThrow();
        assertThat(stored.getState()).isEqualTo(ReferralState.Closed);
        // The direction the office actioned, untouched by the revise that arrived too late.
        assertThat(stored.getDecision()).isEqualTo(MasterDecision.Approved);
        assertThat(stored.getPublishToGazette()).isTrue();
        assertThat(stored.getDirectionChangedAt()).isNull();
        assertThat(notes.findByDeedPollIdOrderByCreatedOnDesc(caseId))
            .singleElement()
            .satisfies(note -> assertThat(note.getNote()).contains("marked as actioned"));
        assertThat(deedPolls.findById(caseId).orElseThrow().getCurrentReferralState()).isNull();
    }

    @Test
    @DisplayName("a revise that commits first is what the waiting close then actions")
    void reviseFirst() throws Exception {
        String caseId = storedCase();
        String referralId = directedReferral(caseId);

        Outcome outcome = second(
            () -> judicial.revise(referralId, refusal(), MARION),
            () -> service.close(caseId, referralId, ADA));

        assertThat(outcome.first()).isNull();
        assertThat(outcome.second()).isNull();
        assertThat(outcome.secondWaited()).isTrue();

        Referral stored = referrals.findById(UUID.fromString(referralId)).orElseThrow();
        assertThat(stored.getState()).isEqualTo(ReferralState.Closed);
        assertThat(stored.getDecision()).isEqualTo(MasterDecision.Rejected);
        assertThat(stored.getPublishToGazette()).isFalse();
        assertThat(stored.getDirectionChangedAt()).isNotNull();
        assertThat(notes.findByDeedPollIdOrderByCreatedOnDesc(caseId))
            .extracting(note -> note.getNote().contains("revised") ? "revised" : "actioned")
            .containsExactlyInAnyOrder("revised", "actioned");
        assertThat(deedPolls.findById(caseId).orElseThrow().getCurrentReferralState()).isNull();
    }

    @Test
    @DisplayName("a close that commits first makes the waiting withdraw a 404, and the direction stays as actioned")
    void closeFirstThenWithdraw() throws Exception {
        String caseId = storedCase();
        String referralId = directedReferral(caseId);

        Outcome outcome = second(
            () -> service.close(caseId, referralId, ADA),
            () -> judicial.withdraw(referralId, MARION));

        assertThat(outcome.first()).isNull();
        assertThat(outcome.second())
            .isInstanceOf(NotFoundError.class)
            .hasMessage("Referral " + referralId + " not found");
        assertThat(outcome.secondWaited()).isTrue();

        Referral stored = referrals.findById(UUID.fromString(referralId)).orElseThrow();
        assertThat(stored.getState()).isEqualTo(ReferralState.Closed);
        assertThat(stored.getDecision()).isEqualTo(MasterDecision.Approved);
        assertThat(stored.getDecidedById()).isEqualTo(MARION.userId());
        assertThat(stored.getDirectionChangedAt()).isNull();
        assertThat(notes.findByDeedPollIdOrderByCreatedOnDesc(caseId))
            .singleElement()
            .satisfies(note -> assertThat(note.getNote()).contains("marked as actioned"));
    }

    @Test
    @DisplayName("a withdraw that commits first leaves nothing to action, so the waiting close is a conflict")
    void withdrawFirst() throws Exception {
        String caseId = storedCase();
        String referralId = directedReferral(caseId);

        Outcome outcome = second(
            () -> judicial.withdraw(referralId, MARION),
            () -> service.close(caseId, referralId, ADA));

        assertThat(outcome.first()).isNull();
        assertThat(outcome.second()).isInstanceOf(ConflictError.class);
        assertThat(outcome.secondWaited()).isTrue();

        // The office must not have actioned a direction the judge had already withdrawn.
        Referral stored = referrals.findById(UUID.fromString(referralId)).orElseThrow();
        assertThat(stored.getState()).isEqualTo(ReferralState.Claimed);
        assertThat(stored.getClaimedById()).isEqualTo(MARION.userId());
        assertThat(stored.getDecision()).isNull();
        assertThat(stored.getDirectionsText()).isNull();
        assertThat(stored.getPublishToGazette()).isNull();
        assertThat(stored.getDecidedById()).isNull();
        assertThat(stored.getDirectionChangedAt()).isNotNull();
        assertThat(notes.findByDeedPollIdOrderByCreatedOnDesc(caseId))
            .singleElement()
            .satisfies(note -> assertThat(note.getNote()).contains("Judicial direction withdrawn"));
    }

    /** What each transaction threw, in the order given, and whether the second finished after the first committed. */
    private record Outcome(Throwable first, Throwable second, boolean secondWaited) {
    }

    /**
     * Runs {@code first} in a transaction that holds the row until {@code second} has started, then commits; runs
     * {@code second} once {@code first} has written.
     *
     * <p>{@code ConfirmationConcurrencyTest.recordConcurrently}'s schedule. The second is called outside any
     * transaction of the test's, because each service method under test opens its own, as a request does.
     */
    private Outcome second(Runnable first, Runnable second) throws Exception {
        CountDownLatch firstWrote = new CountDownLatch(1);
        CountDownLatch secondStarting = new CountDownLatch(1);
        long[] committedAt = new long[1];
        long[] finishedAt = new long[1];
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Throwable> firstWrite = pool.submit(() -> {
                Throwable failure = attempt(() -> new TransactionTemplate(transactionManager).execute(status -> {
                    first.run();
                    firstWrote.countDown();
                    await(secondStarting);
                    sleep(OVERLAP_MILLIS);
                    return null;
                }));
                committedAt[0] = System.nanoTime();
                return failure;
            });

            Future<Throwable> secondWrite = pool.submit(() -> {
                await(firstWrote);
                secondStarting.countDown();
                Throwable failure = attempt(second);
                finishedAt[0] = System.nanoTime();
                return failure;
            });

            Throwable firstFailure = firstWrite.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            Throwable secondFailure = secondWrite.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            return new Outcome(firstFailure, secondFailure, finishedAt[0] >= committedAt[0]);
        } finally {
            pool.shutdownNow();
        }
    }

    private String storedCase() {
        DeedPoll deedPoll = new DeedPoll();
        deedPoll.setDateReceived(LocalDateTime.of(2026, 1, 2, 10, 0));
        return deedPolls.save(deedPoll).getId();
    }

    /** A referral Marion claimed and directed: approved, with publication. */
    private String directedReferral(String deedPollId) {
        LocalDateTime picked = LocalDateTime.of(2026, 5, 1, 9, 0);
        Referral referral = new Referral();
        referral.setDeedPoll(deedPolls.findById(deedPollId).orElseThrow());
        referral.setState(ReferralState.Decided);
        referral.setClaimedById(MARION.userId());
        referral.setClaimedBy(MARION.name());
        referral.setClaimedAt(picked);
        referral.setLastTouchedAt(picked);
        referral.setDecision(MasterDecision.Approved);
        referral.setDirectionsText("Enrol the deed.");
        referral.setPublishToGazette(true);
        referral.setDecisionDate(picked.plusHours(1));
        referral.setDecidedById(MARION.userId());
        referral.setDecidedBy(MARION.name());
        return referrals.save(referral).getId().toString();
    }

    private static DirectionInput refusal() {
        return ReferralSchemas.parseDirection(Map.of(
            "decision", "Rejected",
            "directionsText", "Refused on reflection.",
            "publishToGazette", false));
    }

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
                throw new IllegalStateException("the other transaction never got as far as signalling");
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
