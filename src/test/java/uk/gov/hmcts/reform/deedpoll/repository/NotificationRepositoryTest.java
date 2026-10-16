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
 *   <li><strong>The unique index refuses a duplicate, and since V15 it refuses the other email of the
 *       same exclusion group too.</strong> Idempotency and mutual exclusion are both a constraint rather
 *       than a check, so the thing to assert is the database refusing the second row, not application
 *       code declining to write it -- and the column default that carries the backfill and a rolling
 *       deployment is only ever executed here.</li>
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

    /**
     * The other email of {@link #GROUP}, which one referral may have instead of but never as well as.
     */
    private static final String COUNTERPART_TEMPLATE = "REQUEST_CONFIRMATION_TO_PROCEED";

    /** The exclusion group both referral-driven templates share, and what V15's unique index is keyed on. */
    private static final String GROUP = "REFERRAL_OUTCOME";

    /**
     * A group of one, standing in for a template that is nobody's alternative.
     *
     * <p>A literal that matches no {@code EmailExclusionGroup} member, deliberately, because the claim is about
     * the index rather than about today's enum: a template alone in its group must behave exactly as it did when
     * the key was the template. It used to be spelled {@code FORMS_FOR_COMPLETION}, which V20 has since given a
     * case-scoped index of its own -- so a real member here would make this test's subject a different rule.
     */
    private static final String LONE_GROUP = "A_LATER_GROUP";

    /**
     * The case-driven template, whose rows carry no {@code referralId}.
     *
     * <p>The fixture for everything about V19, because it is the template V15's key cannot touch: its rows sit
     * outside that partial index entirely, so the request id is the only thing standing between one compose
     * journey and two emails.
     */
    private static final String CHASE_TEMPLATE = "CHASE_MISSING_DOCUMENTS";

    /** Its own exclusion group, which no index acts on -- a repeat chase is legitimate. */
    private static final String CHASE_GROUP = "DOCUMENT_CHASE";

    /**
     * The adult forms email, whose rows carry no {@code referralId} and <em>are</em> keyed on the case.
     *
     * <p>The fixture for everything about V20, and the contrast with {@link #CHASE_TEMPLATE}: both are
     * case-driven, both sit outside V15's index, and only one of them is something a case may have twice.
     */
    private static final String FORMS_ADULT_TEMPLATE = "FORMS_FOR_COMPLETION_ADULT";

    /** The minor forms email, which the same case must never also be sent. */
    private static final String FORMS_MINOR_TEMPLATE = "FORMS_FOR_COMPLETION_MINOR";

    /** The group the two share, and the one V20's partial index names in SQL. */
    private static final String FORMS_GROUP = "FORMS_FOR_COMPLETION";

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
        // A request of its own, because V19's key is ("deedPollId", "requestId") and several tests here put two
        // rows on one case. A shared constant would make those collide on a key none of them is about.
        notification.setRequestId(UUID.randomUUID());
        notification.setReferralId(referral == null ? null : referral.getId());
        notification.setTemplate(TEMPLATE);
        notification.setExclusionGroup(GROUP);
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

        int claimed = notifications.claimNextDue(due.getId(), NOW, LEASE_UNTIL, SERVICE_ACCOUNT, UUID.randomUUID(), 8);

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

        assertThat(notifications.claimNextDue(due.getId(), NOW, LEASE_UNTIL, SERVICE_ACCOUNT,
                                               UUID.randomUUID(), 8)).isEqualTo(1);
        // The whole multi-replica argument, as a fact about the statement rather than about the outbox: the
        // second caller is told it changed nothing, so it sends nothing. The first claim pushed
        // "nextAttemptAt" to the lease, which is what makes the second statement's own predicate false --
        // there is no lock, and none is needed.
        assertThat(notifications.claimNextDue(due.getId(), NOW, LEASE_UNTIL, SERVICE_ACCOUNT,
                                               UUID.randomUUID(), 8)).isZero();

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
        assertThat(notifications.claimNextDue(backedOff.getId(), NOW, LEASE_UNTIL, SERVICE_ACCOUNT,
                                               UUID.randomUUID(), 8)).isZero();
        assertThat(notifications.claimNextDue(sent.getId(), NOW, LEASE_UNTIL, SERVICE_ACCOUNT,
                                               UUID.randomUUID(), 8)).isZero();
        assertThat(notifications.claimNextDue(failed.getId(), NOW, LEASE_UNTIL, SERVICE_ACCOUNT,
                                               UUID.randomUUID(), 8)).isZero();

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
            // Named, so the assertion is about this index and not about any duplicate key at all. Renamed by
            // V15 along with the key it is built on: an index called "..._template_key" that is keyed on the
            // exclusion group would be a name a reader has to check the definition to distrust.
            .hasStackTraceContaining("deedpoll_notification_referral_exclusion_group_key");
    }

    @Test
    @DisplayName("enqueues one owed row, Pending and never attempted")
    void enqueuesOneOwedRow() {
        DeedPoll deedPoll = persistedCase();
        Referral referral = persistedReferral(deedPoll);
        UUID id = UUID.randomUUID();

        // The statement itself, against a real Postgres. Every other test of it mocks this repository, so
        // the jsonb cast, the literals and the ON CONFLICT clause are only ever executed here.
        int enqueued = notifications.enqueue(id, deedPoll.getId(), UUID.randomUUID(), referral.getId(), TEMPLATE,
                                             GROUP, RECIPIENT, PERSONALISATION, NOW, "A Caseworker");

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
        int enqueued = notifications.enqueue(UUID.randomUUID(), deedPoll.getId(), UUID.randomUUID(),
                                             referral.getId(), TEMPLATE, GROUP, RECIPIENT, PERSONALISATION,
                                             NOW, "A Caseworker");

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

        int enqueued = notifications.enqueue(replacementId, deedPoll.getId(), UUID.randomUUID(), referral.getId(),
                                             TEMPLATE, GROUP, RECIPIENT, PERSONALISATION,
                                             NOW.plusMinutes(1), "A Caseworker");

        assertThat(enqueued)
            .as("Notify definitely refused the first request, so there is nothing to duplicate")
            .isOne();

        entityManager.clear();
        List<Notification> onCase = notifications.findByDeedPollIdOrderByCreatedOnDesc(deedPoll.getId());
        Notification replacement = onCase.getFirst();
        replacement.setState(NotificationState.Sent);
        replacement.setSubject("Your deed poll application");
        replacement.setBody("Dear Zoë,\n\nYour application was refused.");
        entityManager.flush();
        entityManager.clear();
        onCase = notifications.findByDeedPollIdOrderByCreatedOnDesc(deedPoll.getId());

        assertThat(onCase).extracting(Notification::getId)
            .containsExactly(replacementId, failed.getId());
        assertThat(onCase.get(1).getState()).isEqualTo(NotificationState.Failed);
        assertThat(onCase.get(1).getFailureVerdict()).isEqualTo(NotificationFailureVerdict.PERMANENT);
        assertThat(onCase.get(1).getSubject()).isNull();
        assertThat(onCase.get(1).getBody()).isNull();
        assertThat(onCase.getFirst().getState()).isEqualTo(NotificationState.Sent);
        assertThat(onCase.getFirst().getSubject()).isEqualTo("Your deed poll application");
        assertThat(onCase.getFirst().getBody()).isEqualTo("Dear Zoë,\n\nYour application was refused.");
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
                UUID.randomUUID(), deedPoll.getId(), UUID.randomUUID(), referral.getId(), TEMPLATE, GROUP,
                RECIPIENT, PERSONALISATION, NOW.plusMinutes(1), "A Caseworker"))
                .as(fixture.reason())
                .isZero();
        }
    }

    @Test
    @DisplayName("enqueues nothing for the other email of the group, so the two cannot contradict each other")
    void enqueuesNothingForTheCounterpartTemplate() {
        DeedPoll deedPoll = persistedCase();
        Referral referral = persistedReferral(deedPoll);
        owed(deedPoll, referral, NOW);
        entityManager.flush();

        // Decision 3, as the fact about Postgres that the whole mechanism rests on. The old key --
        // ("referralId", "template") -- would have permitted this row: one email telling the applicant that
        // enrolment is refused and a second asking whether they still want the deed enrolled.
        int enqueued = notifications.enqueue(UUID.randomUUID(), deedPoll.getId(), UUID.randomUUID(),
                                             referral.getId(), COUNTERPART_TEMPLATE, GROUP, RECIPIENT,
                                             PERSONALISATION, NOW.plusMinutes(1), "A Caseworker");

        assertThat(enqueued).isZero();
        entityManager.clear();
        assertThat(notifications.findByDeedPollIdOrderByCreatedOnDesc(deedPoll.getId()))
            .singleElement()
            .satisfies(row -> assertThat(row.getTemplate()).isEqualTo(TEMPLATE));
    }

    @Test
    @DisplayName("finds the row occupying the index, and never a permanently refused one")
    void findsTheRowOccupyingTheIndex() {
        DeedPoll deedPoll = persistedCase();
        Referral referral = persistedReferral(deedPoll);
        Notification refused = owed(deedPoll, referral, NOW);
        refused.setState(NotificationState.Failed);
        refused.setFailureVerdict(NotificationFailureVerdict.PERMANENT);
        entityManager.flush();

        // Availability and the 409 both ask this, so it has to answer with the index's own definition of a
        // blocker rather than with "any row for this referral". A permanent refusal is Notify saying it sent
        // nothing, and notify-1 deliberately allows a replacement -- so it must hide neither template.
        assertThat(notifications.findBlocking(deedPoll.getId(), referral.getId(), GROUP)).isEmpty();

        UUID replacementId = UUID.randomUUID();
        assertThat(notifications.enqueue(replacementId, deedPoll.getId(), UUID.randomUUID(), referral.getId(),
                                         COUNTERPART_TEMPLATE, GROUP, RECIPIENT, PERSONALISATION,
                                         NOW.plusMinutes(1), "A Caseworker"))
            .isOne();
        entityManager.clear();

        // And once a live row exists it is what the caller reads the template off, which is how a second click
        // is told apart from a request for the email that contradicts it.
        assertThat(notifications.findBlocking(deedPoll.getId(), referral.getId(), GROUP))
            .get()
            .satisfies(row -> {
                assertThat(row.getId()).isEqualTo(replacementId);
                assertThat(row.getTemplate()).isEqualTo(COUNTERPART_TEMPLATE);
            });
        // Case-scoped like every other finder here, and keyed on the group: another group's rows are not this
        // group's business.
        assertThat(notifications.findBlocking(deedPoll.getId(), referral.getId(), LONE_GROUP)).isEmpty();
        assertThat(notifications.findBlocking(persistedCase().getId(), referral.getId(), GROUP)).isEmpty();
    }

    @Test
    @DisplayName("a template alone in its group behaves exactly as it did before the re-keying")
    void loneGroupBehavesAsBefore() {
        DeedPoll deedPoll = persistedCase();
        Referral referral = persistedReferral(deedPoll);
        owed(deedPoll, referral, NOW);
        entityManager.flush();

        // A group of one makes ("referralId", "exclusionGroup") and the old ("referralId", "template") the same
        // key, so widening the index generalises the existing rule rather than adding a second one beside it:
        // an email in nobody else's group is unaffected by one that has already gone out.
        assertThat(notifications.enqueue(UUID.randomUUID(), deedPoll.getId(), UUID.randomUUID(), referral.getId(),
                                         "A_LATER_TEMPLATE", LONE_GROUP, RECIPIENT, PERSONALISATION, NOW,
                                         "A Caseworker"))
            .as("another group's slot is free")
            .isOne();
        assertThat(notifications.enqueue(UUID.randomUUID(), deedPoll.getId(), UUID.randomUUID(), referral.getId(),
                                         "A_LATER_TEMPLATE", LONE_GROUP, RECIPIENT, PERSONALISATION,
                                         NOW.plusMinutes(1), "A Caseworker"))
            .as("and then it is taken, once, exactly as the template key used to do it")
            .isZero();

        entityManager.clear();
        assertThat(notifications.findByDeedPollIdOrderByCreatedOnDesc(deedPoll.getId())).hasSize(2);
    }

    @Test
    @DisplayName("a row written without naming the group gets REFERRAL_OUTCOME, which is V15's backfill")
    void theColumnDefaultBackfillsAndCarriesAnOlderInstance() {
        DeedPoll deedPoll = persistedCase();
        Referral referral = persistedReferral(deedPoll);
        UUID id = UUID.randomUUID();

        // The same database default does two jobs in V15, and this is both of them: it gave every row that
        // existed before the migration the group its template belongs to -- APPLICATION_REJECTED is the only
        // template that has ever been enqueued -- and it keeps an application instance that predates V15 able
        // to insert while old and new pods overlap. An INSERT that does not name the column is exactly what
        // such an instance issues.
        entityManager.createNativeQuery("""
            INSERT INTO "deedpoll_notification" (
                        "id", "deedPollId", "referralId", "template", "recipient", "personalisation",
                        "state", "attempts", "nextAttemptAt", "createdBy", "createdOn", "modifiedOn")
                 VALUES (:id, :deedPollId, :referralId, :template, :recipient, CAST(:personalisation AS jsonb),
                        'Pending', 0, :now, :createdBy, :now, :now)
            """)
            .setParameter("id", id)
            .setParameter("deedPollId", deedPoll.getId())
            .setParameter("referralId", referral.getId())
            .setParameter("template", TEMPLATE)
            .setParameter("recipient", RECIPIENT)
            .setParameter("personalisation", PERSONALISATION)
            .setParameter("now", NOW)
            .setParameter("createdBy", "An Older Instance")
            .executeUpdate();
        entityManager.clear();

        assertThat(entityManager.find(Notification.class, id).getExclusionGroup()).isEqualTo(GROUP);
        // And such a row occupies the slot, so the email it contradicts is refused even though the instance
        // that wrote it had never heard of a group.
        assertThat(notifications.enqueue(UUID.randomUUID(), deedPoll.getId(), UUID.randomUUID(), referral.getId(),
                                         COUNTERPART_TEMPLATE, GROUP, RECIPIENT, PERSONALISATION,
                                         NOW.plusMinutes(1), "A Caseworker"))
            .isZero();
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
    @DisplayName("enqueues nothing for a request this case has already sent, answering 0 rather than throwing")
    void enqueuesNothingForARequestAlreadySent() {
        DeedPoll deedPoll = persistedCase();
        UUID requestId = UUID.randomUUID();

        assertThat(notifications.enqueue(UUID.randomUUID(), deedPoll.getId(), requestId, null, CHASE_TEMPLATE,
                                        CHASE_GROUP, RECIPIENT, PERSONALISATION, NOW, "A Caseworker"))
            .isOne();
        // The double-click, on a template the referral key cannot help with: a chase's rows carry no referralId,
        // so V19's ("deedPollId", "requestId") is the only thing between one journey and two emails. ON CONFLICT
        // DO NOTHING with no target is what makes this a 0 rather than a DataIntegrityViolationException that
        // would mark the caseworker's transaction rollback-only.
        assertThat(notifications.enqueue(UUID.randomUUID(), deedPoll.getId(), requestId, null, CHASE_TEMPLATE,
                                        CHASE_GROUP, RECIPIENT, PERSONALISATION, NOW.plusMinutes(1),
                                        "A Caseworker"))
            .isZero();

        entityManager.clear();
        assertThat(notifications.findByDeedPollIdOrderByCreatedOnDesc(deedPoll.getId())).hasSize(1);
    }

    @Test
    @DisplayName("counts a template's rows on a case in the last day, less permanent refusals, towards the cap")
    void countsTowardsTheDailyCap() {
        DeedPoll deedPoll = persistedCase();
        final DeedPoll otherCase = persistedCase();
        final LocalDateTime since = NOW.minusHours(24);
        enqueueChase(deedPoll, NOW.minusHours(1));
        enqueueChase(deedPoll, NOW.minusHours(23));
        // Each of these is outside the cap, for its own reason: older than a day, permanently refused, another
        // template, another case.
        enqueueChase(deedPoll, NOW.minusHours(25));
        UUID refusedId = enqueueChase(deedPoll, NOW.minusMinutes(5));
        Notification refused = notifications.findById(refusedId).orElseThrow();
        refused.setState(NotificationState.Failed);
        refused.setFailureVerdict(NotificationFailureVerdict.PERMANENT);
        assertThat(notifications.enqueue(UUID.randomUUID(), deedPoll.getId(), UUID.randomUUID(), null,
                                         FORMS_ADULT_TEMPLATE, FORMS_GROUP, RECIPIENT, PERSONALISATION, NOW,
                                         "A Caseworker"))
            .isOne();
        enqueueChase(otherCase, NOW);
        entityManager.flush();

        assertThat(notifications.countTowardsDailyCap(deedPoll.getId(), CHASE_TEMPLATE, since)).isEqualTo(2);

        // A failure Notify did not definitely refuse may have reached the applicant, so it still counts.
        // Read again: each enqueue above clears the persistence context, so the first copy is detached.
        notifications.findById(refusedId).orElseThrow().setFailureVerdict(NotificationFailureVerdict.TECHNICAL);
        entityManager.flush();
        assertThat(notifications.countTowardsDailyCap(deedPoll.getId(), CHASE_TEMPLATE, since)).isEqualTo(3);
    }

    private UUID enqueueChase(DeedPoll deedPoll, LocalDateTime createdOn) {
        UUID id = UUID.randomUUID();
        assertThat(notifications.enqueue(id, deedPoll.getId(), UUID.randomUUID(), null, CHASE_TEMPLATE,
                                         CHASE_GROUP, RECIPIENT, PERSONALISATION, createdOn, "A Caseworker"))
            .isOne();
        return id;
    }

    @Test
    @DisplayName("enqueues a second chase under a request of its own, because a reminder is legitimate")
    void enqueuesASecondChaseUnderItsOwnRequest() {
        DeedPoll deedPoll = persistedCase();

        // Decision 5, as a fact about the two keys together. A case can sit in AwaitingFurtherDocs after one
        // reminder, so the database must not make a second chase impossible -- and a key on the payload or on a
        // time window would have done exactly that.
        assertThat(notifications.enqueue(UUID.randomUUID(), deedPoll.getId(), UUID.randomUUID(), null,
                                        CHASE_TEMPLATE, CHASE_GROUP, RECIPIENT, PERSONALISATION, NOW,
                                        "A Caseworker"))
            .isOne();
        assertThat(notifications.enqueue(UUID.randomUUID(), deedPoll.getId(), UUID.randomUUID(), null,
                                        CHASE_TEMPLATE, CHASE_GROUP, RECIPIENT, PERSONALISATION,
                                        NOW.plusDays(7), "A Caseworker"))
            .as("a second compose journey a week later, which is a reminder rather than a duplicate")
            .isOne();

        entityManager.clear();
        assertThat(notifications.findByDeedPollIdOrderByCreatedOnDesc(deedPoll.getId())).hasSize(2);
    }

    @Test
    @DisplayName("the same request id on another case does not collide, because the key is scoped to the case")
    void theSameRequestIdOnAnotherCaseDoesNotCollide() {
        DeedPoll one = persistedCase();
        DeedPoll another = persistedCase();
        UUID requestId = UUID.randomUUID();

        // The safety property the scope buys. The key is a value the caller chose, so a global unique index would
        // let one client's UUID turn an unrelated case's send into a conflict -- or worse, into an idempotent
        // "already owed" answer naming a notification for a different applicant.
        assertThat(notifications.enqueue(UUID.randomUUID(), one.getId(), requestId, null, CHASE_TEMPLATE,
                                        CHASE_GROUP, RECIPIENT, PERSONALISATION, NOW, "A Caseworker"))
            .isOne();
        assertThat(notifications.enqueue(UUID.randomUUID(), another.getId(), requestId, null, CHASE_TEMPLATE,
                                        CHASE_GROUP, RECIPIENT, PERSONALISATION, NOW, "A Caseworker"))
            .isOne();

        entityManager.clear();
        assertThat(notifications.findByDeedPollIdOrderByCreatedOnDesc(one.getId())).hasSize(1);
        assertThat(notifications.findByDeedPollIdOrderByCreatedOnDesc(another.getId())).hasSize(1);
    }

    @Test
    @DisplayName("a permanently refused row keeps its request id, so the same request does not replace it")
    void permanentlyRefusedRowKeepsItsRequestId() {
        DeedPoll deedPoll = persistedCase();
        UUID requestId = UUID.randomUUID();
        Notification refused = owed(deedPoll, null, NOW);
        refused.setRequestId(requestId);
        refused.setTemplate(CHASE_TEMPLATE);
        refused.setExclusionGroup(CHASE_GROUP);
        refused.setState(NotificationState.Failed);
        refused.setFailureVerdict(NotificationFailureVerdict.PERMANENT);
        entityManager.flush();

        // V19's index is deliberately NOT partial on the failure verdict, unlike V12's and V15's predicate. A
        // retry of the same request must go on answering with the failed row: the caseworker's next move is to
        // compose a corrected email, which is a new journey, and the failed attempt has to survive as history.
        assertThat(notifications.enqueue(UUID.randomUUID(), deedPoll.getId(), requestId, null, CHASE_TEMPLATE,
                                        CHASE_GROUP, RECIPIENT, PERSONALISATION, NOW.plusMinutes(1),
                                        "A Caseworker"))
            .isZero();
        // And the new journey is what gets through, which is the other half of the same rule.
        assertThat(notifications.enqueue(UUID.randomUUID(), deedPoll.getId(), UUID.randomUUID(), null,
                                        CHASE_TEMPLATE, CHASE_GROUP, RECIPIENT, PERSONALISATION,
                                        NOW.plusMinutes(2), "A Caseworker"))
            .isOne();

        entityManager.clear();
        assertThat(notifications.findByDeedPollIdOrderByCreatedOnDesc(deedPoll.getId())).hasSize(2);
        assertThat(entityManager.find(Notification.class, refused.getId()).getState())
            .as("the refused attempt survives as history rather than being replaced")
            .isEqualTo(NotificationState.Failed);
    }

    @Test
    @DisplayName("finds this case's row for a request id, and never another case's row for the same one")
    void findsThisCasesRowForARequestId() {
        DeedPoll deedPoll = persistedCase();
        DeedPoll elsewhere = persistedCase();
        UUID requestId = UUID.randomUUID();
        Notification mine = owed(deedPoll, null, NOW);
        mine.setRequestId(requestId);
        Notification theirs = owed(elsewhere, null, NOW);
        theirs.setRequestId(requestId);
        entityManager.flush();
        entityManager.clear();

        // The read the service resolves a zero-row enqueue with, and it has to be scoped: an unscoped one would
        // answer a caseworker's retry with a notification belonging to a different applicant.
        assertThat(notifications.findByDeedPollIdAndRequestId(deedPoll.getId(), requestId))
            .get()
            .satisfies(row -> assertThat(row.getId()).isEqualTo(mine.getId()));
        assertThat(notifications.findByDeedPollIdAndRequestId(elsewhere.getId(), requestId))
            .get()
            .satisfies(row -> assertThat(row.getId()).isEqualTo(theirs.getId()));
        assertThat(notifications.findByDeedPollIdAndRequestId(deedPoll.getId(), UUID.randomUUID())).isEmpty();
    }

    @Test
    @DisplayName("refuses the other forms email on one case, because an applicant swears one deed")
    void refusesTheOtherFormsEmailOnOneCase() {
        DeedPoll deedPoll = persistedCase();

        // V20's whole reason. Neither forms email is referral-driven, so both rows carry a null "referralId" and
        // V15's partial index never sees them -- and a case sent both would have an applicant holding LOC020 and
        // LOC022, two different legal instruments to sign in front of witnesses, of which at most one is the deed
        // the court will seal. Different request ids, so V19's key is not what refuses the second.
        assertThat(notifications.enqueue(UUID.randomUUID(), deedPoll.getId(), UUID.randomUUID(), null,
                                        FORMS_ADULT_TEMPLATE, FORMS_GROUP, RECIPIENT, PERSONALISATION, NOW,
                                        "A Caseworker"))
            .isOne();
        assertThat(notifications.enqueue(UUID.randomUUID(), deedPoll.getId(), UUID.randomUUID(), null,
                                        FORMS_MINOR_TEMPLATE, FORMS_GROUP, RECIPIENT, PERSONALISATION,
                                        NOW.plusDays(1), "A Caseworker"))
            .as("the other email of the group, a day later and under a journey of its own")
            .isZero();
        // And the same email again, which is the idempotent case rather than the contradictory one: still one row.
        assertThat(notifications.enqueue(UUID.randomUUID(), deedPoll.getId(), UUID.randomUUID(), null,
                                        FORMS_ADULT_TEMPLATE, FORMS_GROUP, RECIPIENT, PERSONALISATION,
                                        NOW.plusDays(2), "A Caseworker"))
            .isZero();

        entityManager.clear();
        assertThat(notifications.findByDeedPollIdOrderByCreatedOnDesc(deedPoll.getId())).hasSize(1);
        // A different case is untouched, because the key is scoped to the case and to nothing wider.
        assertThat(notifications.enqueue(UUID.randomUUID(), persistedCase().getId(), UUID.randomUUID(), null,
                                        FORMS_MINOR_TEMPLATE, FORMS_GROUP, RECIPIENT, PERSONALISATION, NOW,
                                        "A Caseworker"))
            .isOne();
    }

    @Test
    @DisplayName("leaves the case's forms slot free after a permanent refusal, so a corrected send replaces it")
    void permanentlyRefusedFormsEmailDoesNotBlockAReplacement() {
        DeedPoll deedPoll = persistedCase();
        Notification refused = owed(deedPoll, null, NOW);
        refused.setTemplate(FORMS_ADULT_TEMPLATE);
        refused.setExclusionGroup(FORMS_GROUP);
        refused.setState(NotificationState.Failed);
        refused.setFailureVerdict(NotificationFailureVerdict.PERMANENT);
        entityManager.flush();

        // V12's predicate, carried into V20 verbatim: Notify definitely sent nothing, so the applicant has no deed
        // and the slot is free. That is notify-1 task 7's rule, and it has to hold for the case-scoped key too --
        // otherwise every send made while the template was unprovisioned would lock a case out of its own deed.
        assertThat(notifications.enqueue(UUID.randomUUID(), deedPoll.getId(), UUID.randomUUID(), null,
                                        FORMS_ADULT_TEMPLATE, FORMS_GROUP, RECIPIENT, PERSONALISATION,
                                        NOW.plusMinutes(1), "A Caseworker"))
            .isOne();

        entityManager.clear();
        assertThat(notifications.findByDeedPollIdOrderByCreatedOnDesc(deedPoll.getId())).hasSize(2);
    }

    @Test
    @DisplayName("finds the row occupying a case's forms slot, never a permanently refused one or another case's")
    void findsTheRowOccupyingTheCasesGroup() {
        DeedPoll deedPoll = persistedCase();
        DeedPoll elsewhere = persistedCase();
        Notification occupant = owed(deedPoll, null, NOW);
        occupant.setTemplate(FORMS_ADULT_TEMPLATE);
        occupant.setExclusionGroup(FORMS_GROUP);
        Notification theirs = owed(elsewhere, null, NOW);
        theirs.setTemplate(FORMS_MINOR_TEMPLATE);
        theirs.setExclusionGroup(FORMS_GROUP);
        entityManager.flush();
        entityManager.clear();

        // The read that turns a zero-row enqueue into a 409 naming the other email. Scoped to the case, and
        // repeating V20's predicate character for character -- a mismatch would leave the service unable to
        // explain a refusal it caused, which is a 500 rather than an answer.
        assertThat(notifications.findBlockingForCase(deedPoll.getId(), FORMS_GROUP))
            .get()
            .satisfies(row -> assertThat(row.getId()).isEqualTo(occupant.getId()));
        assertThat(notifications.findBlockingForCase(elsewhere.getId(), FORMS_GROUP))
            .get()
            .satisfies(row -> assertThat(row.getId()).isEqualTo(theirs.getId()));
        // Another group on the same case is not this group's business.
        assertThat(notifications.findBlockingForCase(deedPoll.getId(), CHASE_GROUP)).isEmpty();

        // And a permanently refused row does not occupy the slot, so the availability check offers the email
        // again rather than reporting a blocker the enqueue would have let through.
        Notification stored = entityManager.find(Notification.class, occupant.getId());
        stored.setState(NotificationState.Failed);
        stored.setFailureVerdict(NotificationFailureVerdict.PERMANENT);
        entityManager.flush();
        entityManager.clear();

        assertThat(notifications.findBlockingForCase(deedPoll.getId(), FORMS_GROUP)).isEmpty();
    }

    @Test
    @DisplayName("V20 leaves a referral-driven row alone, so a case can have a refusal email and a forms email")
    void theCaseScopedKeyIgnoresAReferralDrivenRow() {
        DeedPoll deedPoll = persistedCase();
        Referral referral = persistedReferral(deedPoll);
        owed(deedPoll, referral, NOW);
        entityManager.flush();

        // V20 is partial on "referralId" IS NULL and names only the groups that are exclusive per case, so the two
        // keys act on disjoint sets of rows. A case that has been told its application was refused is a different
        // thing from a case that has been sent its forms, and neither excludes the other.
        assertThat(notifications.enqueue(UUID.randomUUID(), deedPoll.getId(), UUID.randomUUID(), null,
                                        FORMS_ADULT_TEMPLATE, FORMS_GROUP, RECIPIENT, PERSONALISATION, NOW,
                                        "A Caseworker"))
            .isOne();
        // And the referral's own slot is still keyed on the referral, not on the case.
        assertThat(notifications.findBlockingForCase(deedPoll.getId(), GROUP))
            .as("the refusal row names a referral, so V20's index does not see it")
            .isEmpty();

        entityManager.clear();
        assertThat(notifications.findByDeedPollIdOrderByCreatedOnDesc(deedPoll.getId())).hasSize(2);
    }

    @Test
    @DisplayName("a row written without naming the request id gets one of its own, which is V19's backfill default")
    void theRequestIdDefaultCarriesAnOlderInstance() {
        DeedPoll deedPoll = persistedCase();

        // The rolling-deployment half of V19, and the only place the default is ever executed. An application
        // instance that predates this migration issues an INSERT that does not name the column at all -- exactly
        // as one predating V15 omits "exclusionGroup" -- so without the default every send from an old pod would
        // violate NOT NULL for as long as old and new pods overlap. Two rows, because the value also has to be
        // unique per row within the case: a constant default would make the second of them collide with the first.
        List<UUID> ids = List.of(UUID.randomUUID(), UUID.randomUUID());
        for (UUID id : ids) {
            entityManager.createNativeQuery("""
                INSERT INTO "deedpoll_notification" (
                            "id", "deedPollId", "template", "recipient", "personalisation",
                            "state", "attempts", "nextAttemptAt", "createdBy", "createdOn", "modifiedOn")
                     VALUES (:id, :deedPollId, :template, :recipient, CAST(:personalisation AS jsonb),
                            'Pending', 0, :now, :createdBy, :now, :now)
                """)
                .setParameter("id", id)
                .setParameter("deedPollId", deedPoll.getId())
                .setParameter("template", CHASE_TEMPLATE)
                .setParameter("recipient", RECIPIENT)
                .setParameter("personalisation", PERSONALISATION)
                .setParameter("now", NOW)
                .setParameter("createdBy", "An Older Instance")
                .executeUpdate();
        }
        entityManager.clear();

        List<UUID> written = ids.stream()
            .map(id -> entityManager.find(Notification.class, id).getRequestId())
            .toList();
        assertThat(written).doesNotContainNull().doesNotHaveDuplicates();
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
