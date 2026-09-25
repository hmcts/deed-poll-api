package uk.gov.hmcts.reform.deedpoll.notify;

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
import uk.gov.hmcts.reform.deedpoll.domain.deedpoll.DeedPollPatch;
import uk.gov.hmcts.reform.deedpoll.domain.deedpoll.DeedPollService;
import uk.gov.hmcts.reform.deedpoll.entity.DeedPollNote;
import uk.gov.hmcts.reform.deedpoll.entity.Notification;
import uk.gov.hmcts.reform.deedpoll.entity.enums.DeedPollState;
import uk.gov.hmcts.reform.deedpoll.entity.enums.NotificationFailureVerdict;
import uk.gov.hmcts.reform.deedpoll.entity.enums.NotificationState;
import uk.gov.hmcts.reform.deedpoll.repository.DeedPollNoteRepository;
import uk.gov.hmcts.reform.deedpoll.repository.DeedPollRepository;
import uk.gov.hmcts.reform.deedpoll.repository.NotificationRepository;
import uk.gov.hmcts.reform.deedpoll.repository.ReferralRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * That one compose journey owes one email, against a real Postgres and through the real service.
 *
 * <p><strong>Why this needs a database and committed transactions.</strong> The mechanism is two unique indexes
 * and a defined order of reading them, and {@code CaseEmailServiceTest} states that order with the repository
 * mocked -- which proves the branches without proving that Postgres refuses the row they are branching on.
 * {@code NotificationRepositoryTest} proves the statements, and it is {@code @Transactional}, so nothing there can
 * ask what <em>two committed sends</em> leave behind. This class is the join: the chase sent twice, and what the
 * outbox and the case's timeline say afterwards.
 *
 * <p><strong>The chase is the fixture throughout, and that is the point rather than convenience.</strong> Its rows
 * carry no {@code referralId}, so they sit outside V15's partial index entirely: the request id is the only thing
 * between one caseworker's double-click and two emails to one applicant. V15's own rule -- one email of an
 * exclusion group per referral, and a contradictory template refused -- is unchanged by V19 and is proved where it
 * was: {@code NotificationRepositoryTest.enqueuesNothingForTheCounterpartTemplate} against the statement, and
 * {@code CaseworkerEmailControllerTest} over HTTP.
 *
 * <p>Nothing here drains, so Notify is never called: what is under test is what a send owes, not what is
 * delivered.
 *
 * <p>Not {@code @Transactional}, for {@code ConfirmationConcurrencyTest}'s reason -- the claims are about what
 * separate committed transactions leave behind.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class SendIdempotencyTest extends AbstractPostgresContainerTest {

    private static final int TIMEOUT_SECONDS = 60;

    private static final String APPLICANT_EMAIL = "applicant@example.com";

    private static final String REQUIRED = "Your birth certificate.";

    private static final CurrentUser CASEWORKER =
        CurrentUser.currentUser(List.of(), "A Caseworker", "caseworker-1");

    @Autowired
    private CaseEmailService emails;

    @Autowired
    private DeedPollService deedPollService;

    @Autowired
    private DeedPollRepository deedPolls;

    @Autowired
    private NotificationRepository notifications;

    @Autowired
    private DeedPollNoteRepository notes;

    @Autowired
    private ReferralRepository referrals;

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
    @DisplayName("one journey sent twice owes one email, and puts one marker on the timeline")
    void oneJourneySentTwiceOwesOneEmail() {
        String caseId = chasedCase();
        UUID journey = UUID.randomUUID();

        SendReceipt first = send(caseId, journey, REQUIRED);
        SendReceipt again = send(caseId, journey, REQUIRED);

        assertThat(first.enqueued()).isTrue();
        // Not a failure: the caseworker's screen was out of date rather than wrong, and telling them the email had
        // failed would invite a third attempt.
        assertThat(again.enqueued()).isFalse();
        assertThat(again.id()).isEqualTo(first.id());
        assertThat(again.recipient()).isEqualTo(APPLICANT_EMAIL);
        assertThat(notifications.findAll()).singleElement()
            .satisfies(row -> assertThat(row.getRequestId()).isEqualTo(journey));
        // One email owed, so the Notes grid says it once. A second marker would leave the case reading as though
        // the applicant had been written to twice, and notes are never revised.
        assertThat(chaseNotes(caseId)).hasSize(1);
    }

    @Test
    @DisplayName("two journeys chase twice, because a case can still be waiting after one reminder")
    void twoJourneysChaseTwice() {
        String caseId = chasedCase();

        SendReceipt first = send(caseId, UUID.randomUUID(), REQUIRED);
        SendReceipt second = send(caseId, UUID.randomUUID(), "Your birth certificate, again, please.");

        // Decision 5, end to end. Neither key nor payload is shared, and neither has to be: the caseworker decided
        // to ask again, and a database that refused them would have made the reminder impossible.
        assertThat(second.enqueued()).isTrue();
        assertThat(second.id()).isNotEqualTo(first.id());
        assertThat(notifications.findByDeedPollIdOrderByCreatedOnDesc(caseId))
            .as("newest first, and both attempts kept")
            .hasSize(2);
        assertThat(chaseNotes(caseId)).hasSize(2);
    }

    @Test
    @DisplayName("409s one journey reused for text the caseworker edited, and owes only the first email")
    void refusesOneJourneyReusedForEditedText() {
        String caseId = chasedCase();
        UUID journey = UUID.randomUUID();
        SendReceipt first = send(caseId, journey, REQUIRED);

        assertThatThrownBy(() -> send(caseId, journey, "Your birth certificate and your marriage certificate."))
            .isInstanceOf(ConflictError.class)
            .hasMessage(CaseEmailService.REQUEST_ALREADY_USED);

        // The first email stands and the second was never owed. Answering with the first row instead would have
        // told the caseworker their edit had gone out.
        assertThat(notifications.findAll()).singleElement().satisfies(row -> {
            assertThat(row.getId()).isEqualTo(first.id());
            assertThat(row.getPersonalisation())
                .containsEntry(EmailSlot.DOCUMENTS_REQUIRED.placeholder(), REQUIRED);
        });
        assertThat(chaseNotes(caseId)).hasSize(1);
    }

    @Test
    @DisplayName("the same key on another case is that case's own email, because the key is scoped to the case")
    void theSameKeyOnAnotherCaseIsItsOwnEmail() {
        String one = chasedCase();
        String another = chasedCase();
        UUID sameKeyForBoth = UUID.randomUUID();

        assertThat(send(one, sameKeyForBoth, REQUIRED).enqueued()).isTrue();
        // A caller cannot make one case's chosen UUID collide with another case's row -- which, unscoped, would
        // answer this send with a notification belonging to a different applicant.
        assertThat(send(another, sameKeyForBoth, REQUIRED).enqueued()).isTrue();

        assertThat(notifications.findByDeedPollIdOrderByCreatedOnDesc(one)).hasSize(1);
        assertThat(notifications.findByDeedPollIdOrderByCreatedOnDesc(another)).hasSize(1);
    }

    @Test
    @DisplayName("a permanently refused attempt answers its own journey again, and a new journey replaces it")
    void permanentlyRefusedAttemptAnswersItsOwnJourneyAgain() {
        String caseId = chasedCase();
        UUID journey = UUID.randomUUID();
        SendReceipt refused = send(caseId, journey, REQUIRED);
        failPermanently(refused.id());

        // V19's index is not partial on the verdict, deliberately: retrying the same journey has to go on
        // answering with the failed row, or a client whose POST timed out would write a second email for an
        // attempt that had already been recorded as refused.
        SendReceipt retried = send(caseId, journey, REQUIRED);
        assertThat(retried.enqueued()).isFalse();
        assertThat(retried.id()).isEqualTo(refused.id());
        assertThat(notifications.count()).isOne();

        // And the corrected send is a new journey, which is what the caseworker's next compose produces -- the
        // refused attempt surviving beside it as history.
        SendReceipt corrected = send(caseId, UUID.randomUUID(), REQUIRED);
        assertThat(corrected.enqueued()).isTrue();
        assertThat(notifications.findByDeedPollIdOrderByCreatedOnDesc(caseId))
            .extracting(Notification::getId)
            .containsExactly(corrected.id(), refused.id());
        assertThat(notifications.findById(refused.id()).orElseThrow().getState())
            .isEqualTo(NotificationState.Failed);
    }

    @Test
    @DisplayName("two replicas sending one journey at once owe one email between them")
    void twoReplicasSendingOneJourneyOweOneEmail() throws Exception {
        String caseId = chasedCase();
        UUID journey = UUID.randomUUID();

        List<Object> outcomes = sendConcurrently(caseId, journey);

        // Neither is refused: the loser's insert blocks on the winner's until it commits, then answers 0 rows, and
        // the row it collided with is the row it answers with. A DataIntegrityViolationException reaching either
        // caseworker would be the same fact as a 500 for a request that was merely redundant.
        assertThat(outcomes).allSatisfy(outcome -> assertThat(outcome).isInstanceOf(SendReceipt.class));
        List<SendReceipt> receipts = outcomes.stream().map(SendReceipt.class::cast).toList();
        assertThat(receipts).extracting(SendReceipt::id)
            .as("both callers are told about the same email")
            .containsOnly(receipts.getFirst().id());
        assertThat(receipts).extracting(SendReceipt::enqueued)
            .as("exactly one of them wrote it")
            .containsExactlyInAnyOrder(true, false);
        assertThat(notifications.findAll()).singleElement()
            .satisfies(row -> assertThat(row.getRequestId()).isEqualTo(journey));
        // And one marker, for the reason the sequential case gives: the note is written only on a fresh enqueue,
        // in the same transaction, so the loser's rollback-free no-op writes none.
        assertThat(chaseNotes(caseId)).hasSize(1);
    }

    /**
     * Sends the same journey from two threads whose transactions are both open before either enqueues.
     *
     * <p>A barrier rather than latches, because the schedule that matters here is "both transactions started, then
     * both insert": the loser has to reach the insert while the winner's is uncommitted, which is what makes
     * Postgres block it rather than simply refuse it against a committed row. Either interleaving satisfies the
     * assertions, which is deliberate -- the claim is that no schedule produces two emails.
     *
     * @return what each send returned, or what it threw, in the order the threads were started
     */
    private List<Object> sendConcurrently(String caseId, UUID journey) throws Exception {
        CyclicBarrier bothStarted = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Object> outcomes = new ArrayList<>();
        try {
            Future<Object> first = pool.submit(() -> sendAfter(caseId, journey, bothStarted));
            Future<Object> second = pool.submit(() -> sendAfter(caseId, journey, bothStarted));
            outcomes.add(first.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            outcomes.add(second.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
        return outcomes;
    }

    /** One transaction: wait for the other to have started, then send. */
    private Object sendAfter(String caseId, UUID journey, CyclicBarrier bothStarted) {
        try {
            return new TransactionTemplate(transactionManager).execute(status -> {
                arriveAt(bothStarted);
                return emails.send(caseId, EmailTemplate.CHASE_MISSING_DOCUMENTS,
                                   Map.of(EmailSlot.DOCUMENTS_REQUIRED.placeholder(), REQUIRED), journey,
                                   CASEWORKER);
            });
        } catch (RuntimeException refused) {
            return refused;
        }
    }

    private static void arriveAt(CyclicBarrier barrier) {
        try {
            barrier.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        } catch (BrokenBarrierException | TimeoutException never) {
            throw new IllegalStateException("the other send never started its transaction", never);
        }
    }

    private SendReceipt send(String caseId, UUID journey, String documentsRequired) {
        return emails.send(caseId, EmailTemplate.CHASE_MISSING_DOCUMENTS,
                           Map.of(EmailSlot.DOCUMENTS_REQUIRED.placeholder(), documentsRequired), journey,
                           CASEWORKER);
    }

    /**
     * A case the office is waiting on documents for, produced the way the write path produces one.
     *
     * <p>{@code awaitingFurtherDocsInfo} rather than {@code state}: the state is derived by the cascade and is not
     * settable, so writing it directly would be a fixture this service could never have made -- and the assertion
     * below is what says the cascade agreed.
     */
    private String chasedCase() {
        String caseId = deedPollService.createDeedPoll(DeedPollPatch.of(Map.of(
                "dateReceived", "2026-07-01T00:00:00.000Z",
                "applicantEmail", APPLICANT_EMAIL,
                "oldForename", "Alex",
                "oldSurname", "Okonjo",
                "onlineApplicationReference", "DP-2026-0042",
                "awaitingFurtherDocsInfo", "2026-07-20T00:00:00.000Z")))
            .deedPoll()
            .getId();
        assertThat(deedPolls.findById(caseId).orElseThrow().getState())
            .isEqualTo(DeedPollState.AwaitingFurtherDocs);
        return caseId;
    }

    /** The row as a permanent Notify refusal leaves it: nothing was sent, and the attempt is history. */
    private void failPermanently(UUID id) {
        new TransactionTemplate(transactionManager).execute(status -> {
            Notification row = notifications.findById(id).orElseThrow();
            row.setState(NotificationState.Failed);
            row.setFailureVerdict(NotificationFailureVerdict.PERMANENT);
            row.setFailureReason("PERMANENT: GOV.UK Notify refused the send with status 400");
            return notifications.save(row);
        });
    }

    /**
     * The notes on a case that a chase wrote.
     *
     * <p>Filtered rather than counted, for {@code CaseworkerEmailControllerTest}'s reason: creating the case
     * writes notes of its own, so "the notes on this case" is never only the email's. Matched against the exact
     * string the composer produces rather than by looking for words in prose.
     */
    private List<DeedPollNote> chaseNotes(String caseId) {
        String sent = EmailNotes.sent(EmailTemplate.CHASE_MISSING_DOCUMENTS);
        return notes.findByDeedPollIdOrderByCreatedOnDesc(caseId).stream()
            .filter(note -> sent.equals(note.getNote()))
            .toList();
    }
}
