package uk.gov.hmcts.reform.deedpoll.repository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.PersistenceException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;
import uk.gov.hmcts.reform.deedpoll.config.AbstractPostgresContainerTest;
import uk.gov.hmcts.reform.deedpoll.entity.DeedPoll;
import uk.gov.hmcts.reform.deedpoll.entity.Notification;
import uk.gov.hmcts.reform.deedpoll.entity.Referral;
import uk.gov.hmcts.reform.deedpoll.entity.enums.NotificationFailureVerdict;
import uk.gov.hmcts.reform.deedpoll.entity.enums.NotificationState;
import uk.gov.hmcts.reform.deedpoll.entity.enums.ReferralState;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Asserts the outbox against a real Postgres, because everything load-bearing about it is SQL.
 *
 * <p>Three claims, none of which a fake repository can stand in for:
 *
 * <ul>
 *   <li><strong>The claim is exclusive.</strong> {@link NotificationRepository#claimNextDue} answers 1
 *       once and 0 for ever afterwards, which is the whole multi-replica argument -- and it is the
 *       reason a second drain does not send a second rejection letter. This is the same assertion
 *       {@code ReferralRepositoryTest} makes about {@code releaseStaleClaim}, and the consequence here
 *       is worse, so it is made the same way: run the statement twice and read the second answer.</li>
 *   <li><strong>The unique index refuses a duplicate.</strong> Idempotency is a constraint rather than
 *       a check, so the thing to assert is the database refusing the second row, not application code
 *       declining to write it.</li>
 *   <li><strong>{@code jsonb} round trips and the enum column is a real enum.</strong>
 *       {@code EntityMappingTest} asserts the mapping; this asserts the finders read rows written
 *       through it.</li>
 * </ul>
 *
 * <p>Nothing else in the suite exercises these -- {@code NotificationOutboxTest} mocks this repository
 * -- so a claim that quietly dropped a condition from its {@code WHERE} would pass every one of those
 * while telling an applicant twice that their application was refused.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional
class NotificationRepositoryTest extends AbstractPostgresContainerTest {

    /** A fixed "now", so nothing here turns on how long a container took to start. */
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 6, 1, 12, 0);

    private static final LocalDateTime LEASE_UNTIL = NOW.plusMinutes(5);

    private static final String SERVICE_ACCOUNT = "# svc_HMCTS_LCPT_Dev";

    private static final String TEMPLATE = "APPLICATION_REJECTED";

    private static final String RECIPIENT = "applicant@example.com";

    /**
     * What {@code CaseEmailService} binds: the resolved personalisation as the jsonb column's text.
     */
    private static final String PERSONALISATION = "{\"reference_number\":\"2026/000123\"}";

    @Autowired
    private NotificationRepository notifications;

    @PersistenceContext
    private EntityManager entityManager;

    private DeedPoll persistedCase() {
        DeedPoll deedPoll = new DeedPoll();
        deedPoll.setDateReceived(LocalDateTime.of(2026, 1, 2, 10, 0));
        entityManager.persist(deedPoll);
        return deedPoll;
    }

    private Referral persistedReferral(DeedPoll parent) {
        Referral referral = new Referral();
        referral.setDeedPoll(parent);
        referral.setState(ReferralState.Decided);
        entityManager.persist(referral);
        return referral;
    }

    /**
     * An owed notification on a case of its own, due at {@code nextAttemptAt}.
     *
     * <p>Persisted through the {@link EntityManager} rather than through a service, because enqueueing is
     * a later task's: what is under test here is the statements, and they are indifferent to who wrote
     * the row.
     */
    private Notification owed(LocalDateTime nextAttemptAt) {
        return owed(persistedCase(), null, nextAttemptAt);
    }

    private Notification owed(DeedPoll parent, Referral referral, LocalDateTime nextAttemptAt) {
        Notification notification = new Notification();
        notification.setDeedPollId(parent.getId());
        notification.setReferralId(referral == null ? null : referral.getId());
        notification.setTemplate(TEMPLATE);
        notification.setRecipient("applicant@example.com");
        notification.setPersonalisation(Map.of("reference_number", "2026/000123"));
        notification.setNextAttemptAt(nextAttemptAt);
        notification.setCreatedOn(nextAttemptAt);
        notification.setCreatedBy("A Caseworker");
        entityManager.persist(notification);
        return notification;
    }

    @Test
    @DisplayName("offers the rows that are owed and due, oldest first, and nothing that is not")
    void offersTheRowsThatAreOwedAndDue() {
        final Notification longestWaiting = owed(NOW.minusHours(2));
        final Notification dueJustNow = owed(NOW);
        final Notification backedOff = owed(NOW.plusSeconds(1));
        Notification alreadySent = owed(NOW.minusHours(3));
        alreadySent.setState(NotificationState.Sent);
        Notification givenUpOn = owed(NOW.minusHours(4));
        givenUpOn.setState(NotificationState.Failed);
        givenUpOn.setFailureVerdict(NotificationFailureVerdict.TECHNICAL);
        entityManager.flush();
        entityManager.clear();

        List<UUID> claimable =
            notifications.findClaimable(NotificationState.Pending, NOW, PageRequest.of(0, 10));

        // Oldest due first, so a row that has been through several backoffs is not starved by newly
        // enqueued ones. A second inside the lease is a row another drain may still be sending, and the
        // two terminal rows are the steady state of this table -- a predicate that read only the state
        // would resend every email this service ever sent.
        assertThat(claimable).containsExactly(longestWaiting.getId(), dueJustNow.getId())
            .doesNotContain(backedOff.getId(), alreadySent.getId(), givenUpOn.getId());
    }

    @Test
    @DisplayName("offers no more than the drain asked for, taking the longest-waiting first")
    void offersNoMoreThanTheDrainAskedFor() {
        Notification first = owed(NOW.minusHours(3));
        Notification second = owed(NOW.minusHours(2));
        final Notification third = owed(NOW.minusHours(1));
        entityManager.flush();
        entityManager.clear();

        // A drain runs inside somebody's HTTP request, so the limit is the point rather than a detail: a
        // backlog is emptied over the next few reads and not by one caseworker paying for all of it.
        assertThat(notifications.findClaimable(NotificationState.Pending, NOW, PageRequest.of(0, 2)))
            .containsExactly(first.getId(), second.getId())
            .doesNotContain(third.getId());
    }

    @Test
    @DisplayName("takes a due row once, counting the attempt and moving the lease")
    void takesADueRowOnce() {
        Notification due = owed(NOW.minusMinutes(1));
        entityManager.flush();

        int claimed = notifications.claimNextDue(due.getId(), NOW, LEASE_UNTIL, SERVICE_ACCOUNT);

        assertThat(claimed).isEqualTo(1);
        Notification reloaded = entityManager.find(Notification.class, due.getId());
        // Counted by the claim rather than by the outcome, so an attempt that killed the process is still
        // counted and this row cannot be retried for ever.
        assertThat(reloaded.getAttempts()).isEqualTo(1);
        // The lease: what keeps another drain off this row while this caller is talking to Notify.
        assertThat(reloaded.getNextAttemptAt()).isEqualTo(LEASE_UNTIL);
        assertThat(reloaded.getState())
            .as("claiming is not a state, so the row is still owed until an outcome says otherwise")
            .isEqualTo(NotificationState.Pending);
        assertThat(reloaded.getModifiedBy())
            .as("no person drained, so the service account wrote it")
            .isEqualTo(SERVICE_ACCOUNT);
        assertThat(reloaded.getModifiedOn()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("takes nothing a second time, so two drains send one email between them")
    void takesNothingASecondTime() {
        Notification due = owed(NOW.minusMinutes(1));
        entityManager.flush();

        assertThat(notifications.claimNextDue(due.getId(), NOW, LEASE_UNTIL, SERVICE_ACCOUNT)).isEqualTo(1);
        // The whole multi-replica argument, as a fact about the statement rather than about the outbox: the
        // second caller is told it changed nothing, so it sends nothing. The first claim pushed
        // "nextAttemptAt" to the lease, which is what makes the second statement's own predicate false --
        // there is no lock, and none is needed.
        assertThat(notifications.claimNextDue(due.getId(), NOW, LEASE_UNTIL, SERVICE_ACCOUNT)).isZero();

        entityManager.clear();
        assertThat(entityManager.find(Notification.class, due.getId()).getAttempts())
            .as("one attempt, not two, for one email")
            .isEqualTo(1);
    }

    @Test
    @DisplayName("takes nothing that is backed off, already sent, or already failed")
    void takesNothingItShouldNot() {
        final Notification backedOff = owed(NOW.plusSeconds(1));
        Notification sent = owed(NOW.minusHours(1));
        sent.setState(NotificationState.Sent);
        Notification failed = owed(NOW.minusHours(1));
        failed.setState(NotificationState.Failed);
        failed.setFailureVerdict(NotificationFailureVerdict.TECHNICAL);
        entityManager.flush();

        // Each of these is a row the statement's own predicate has to refuse, because the candidate list and
        // the claim are separated by however long the drain's loop takes -- and in the 'Sent' case, refusing
        // it is the difference between one rejection letter and two.
        assertThat(notifications.claimNextDue(backedOff.getId(), NOW, LEASE_UNTIL, SERVICE_ACCOUNT)).isZero();
        assertThat(notifications.claimNextDue(sent.getId(), NOW, LEASE_UNTIL, SERVICE_ACCOUNT)).isZero();
        assertThat(notifications.claimNextDue(failed.getId(), NOW, LEASE_UNTIL, SERVICE_ACCOUNT)).isZero();

        entityManager.clear();
        assertThat(entityManager.find(Notification.class, backedOff.getId()).getAttempts()).isZero();
        assertThat(entityManager.find(Notification.class, sent.getId()).getAttempts()).isZero();
        assertThat(entityManager.find(Notification.class, failed.getId()).getAttempts()).isZero();
    }

    @Test
    @DisplayName("refuses a second notification of the same template for one referral")
    void refusesASecondNotificationForOneReferral() {
        DeedPoll deedPoll = persistedCase();
        Referral referral = persistedReferral(deedPoll);
        owed(deedPoll, referral, NOW);
        entityManager.flush();

        // The caseworker's double click, and two replicas racing, as one fact about the database: an
        // applicant cannot be told twice that their application was refused. Asserted as the constraint
        // firing rather than as application code declining, because that is where the rule lives -- a
        // check-then-insert has a window between its two statements and this does not.
        owed(deedPoll, referral, NOW);
        assertThatThrownBy(entityManager::flush)
            .isInstanceOf(PersistenceException.class)
            // Named, so the assertion is about this index and not about any duplicate key at all.
            .hasStackTraceContaining("deedpoll_notification_referral_template_key");
    }

    @Test
    @DisplayName("enqueues one owed row, Pending and never attempted")
    void enqueuesOneOwedRow() {
        DeedPoll deedPoll = persistedCase();
        Referral referral = persistedReferral(deedPoll);
        UUID id = UUID.randomUUID();

        // The statement itself, against a real Postgres. Every other test of it mocks this repository, so
        // the jsonb cast, the literals and the ON CONFLICT clause are only ever executed here.
        int enqueued = notifications.enqueue(id, deedPoll.getId(), referral.getId(), TEMPLATE,
                                             RECIPIENT, PERSONALISATION, NOW, "A Caseworker");

        assertThat(enqueued).isOne();
        entityManager.clear();
        Notification written = entityManager.find(Notification.class, id);
        assertThat(written.getState()).isEqualTo(NotificationState.Pending);
        assertThat(written.getAttempts()).isZero();
        assertThat(written.getNextAttemptAt()).isEqualTo(NOW);
        assertThat(written.getPersonalisation()).containsEntry("reference_number", "2026/000123");
    }

    @Test
    @DisplayName("enqueues nothing for a referral and template already owed, answering 0 rather than throwing")
    void enqueuesNothingForARowAlreadyOwed() {
        DeedPoll deedPoll = persistedCase();
        Referral referral = persistedReferral(deedPoll);
        owed(deedPoll, referral, NOW);
        entityManager.flush();

        // The caseworker's double click. ON CONFLICT DO NOTHING rather than a caught exception, so the
        // caller's transaction stays usable -- the second click is a no-op to log, not a failed request.
        int enqueued = notifications.enqueue(UUID.randomUUID(), deedPoll.getId(), referral.getId(), TEMPLATE,
                                             RECIPIENT, PERSONALISATION, NOW, "A Caseworker");

        assertThat(enqueued).isZero();
        entityManager.clear();
        assertThat(notifications.findByDeedPollIdOrderByCreatedOnDesc(deedPoll.getId())).hasSize(1);
    }

    @Test
    @DisplayName("enqueues a new row after a permanent refusal and keeps the failed attempt as history")
    void enqueuesAfterAPermanentRefusal() {
        DeedPoll deedPoll = persistedCase();
        Referral referral = persistedReferral(deedPoll);
        Notification failed = owed(deedPoll, referral, NOW);
        failed.setState(NotificationState.Failed);
        failed.setFailureVerdict(NotificationFailureVerdict.PERMANENT);
        failed.setFailureReason("PERMANENT: 403 the API key is not provisioned");
        entityManager.flush();
        UUID replacementId = UUID.randomUUID();

        int enqueued = notifications.enqueue(replacementId, deedPoll.getId(), referral.getId(), TEMPLATE,
                                             RECIPIENT, PERSONALISATION, NOW.plusMinutes(1), "A Caseworker");

        assertThat(enqueued)
            .as("Notify definitely refused the first request, so there is nothing to duplicate")
            .isOne();

        entityManager.clear();
        List<Notification> onCase = notifications.findByDeedPollIdOrderByCreatedOnDesc(deedPoll.getId());
        assertThat(onCase).extracting(Notification::getId)
            .containsExactly(replacementId, failed.getId());
        assertThat(onCase.get(1).getState()).isEqualTo(NotificationState.Failed);
        assertThat(onCase.get(1).getFailureVerdict()).isEqualTo(NotificationFailureVerdict.PERMANENT);
        assertThat(onCase.getFirst().getState()).isEqualTo(NotificationState.Pending);
    }

    @Test
    @DisplayName("technical, exhausted-temporary and verdict-less failures still block a replacement")
    void uncertainFailuresStillBlockAReplacement() {
        for (FailureFixture fixture : List.of(
            new FailureFixture(NotificationFailureVerdict.TECHNICAL, "TECHNICAL: connection reset"),
            new FailureFixture(NotificationFailureVerdict.TEMPORARY,
                               "Gave up after 5 attempts: TEMPORARY: Notify unavailable"),
            new FailureFixture(null, "written by an older application instance"))) {

            DeedPoll deedPoll = persistedCase();
            Referral referral = persistedReferral(deedPoll);
            Notification failed = owed(deedPoll, referral, NOW);
            failed.setState(NotificationState.Failed);
            failed.setFailureVerdict(fixture.verdict());
            failed.setFailureReason(fixture.reason());
            entityManager.flush();

            assertThat(notifications.enqueue(
                UUID.randomUUID(), deedPoll.getId(), referral.getId(), TEMPLATE,
                RECIPIENT, PERSONALISATION, NOW.plusMinutes(1), "A Caseworker"))
                .as(fixture.reason())
                .isZero();
        }
    }

    @Test
    @DisplayName("allows a second notification with no referral, because the unique index is partial")
    void allowsASecondNotificationWithNoReferral() {
        DeedPoll deedPoll = persistedCase();
        owed(deedPoll, null, NOW);
        owed(deedPoll, null, NOW);

        // Two rows of the same template on the same case, neither naming a referral. The index is partial on
        // "referralId" IS NOT NULL, because a template that is not referral-driven has no referral to be
        // unique by -- and Postgres would not treat two NULLs as equal anyway.
        entityManager.flush();
        entityManager.clear();

        assertThat(notifications.findByDeedPollIdOrderByCreatedOnDesc(deedPoll.getId())).hasSize(2);
    }

    @Test
    @DisplayName("reads one case's notifications, newest first, and never another case's")
    void readsOneCasesNotificationsNewestFirst() {
        DeedPoll deedPoll = persistedCase();
        Notification older = owed(deedPoll, null, NOW.minusDays(2));
        Notification newer = owed(deedPoll, null, NOW.minusDays(1));
        final Notification elsewhere = owed(persistedCase(), null, NOW);
        entityManager.flush();
        entityManager.clear();

        List<Notification> found = notifications.findByDeedPollIdOrderByCreatedOnDesc(deedPoll.getId());

        // Scoped like every other case-keyed finder in this package. The other case's row is the most recent
        // of all, so a finder that dropped its scope would put it first.
        assertThat(found).extracting(Notification::getId)
            .containsExactly(newer.getId(), older.getId())
            .doesNotContain(elsewhere.getId());
        // And the personalisation comes back through jsonb on a row read by a finder rather than by find().
        assertThat(found.getFirst().getPersonalisation()).containsEntry("reference_number", "2026/000123");
    }

    private record FailureFixture(NotificationFailureVerdict verdict, String reason) {
    }
}
