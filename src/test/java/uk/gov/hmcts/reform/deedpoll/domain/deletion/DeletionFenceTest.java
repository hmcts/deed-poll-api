package uk.gov.hmcts.reform.deedpoll.domain.deletion;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.LinkedMultiValueMap;
import uk.gov.hmcts.ccd.sdk.api.EventPayload;
import uk.gov.hmcts.ccd.sdk.api.callback.SubmitResponse;
import uk.gov.hmcts.reform.deedpoll.ccd.CcdCreationAttempts;
import uk.gov.hmcts.reform.deedpoll.ccd.CcdLinkRule;
import uk.gov.hmcts.reform.deedpoll.ccd.CcdLinkingService;
import uk.gov.hmcts.reform.deedpoll.ccd.DeedPollCaseData;
import uk.gov.hmcts.reform.deedpoll.ccd.DeedPollCaseState;
import uk.gov.hmcts.reform.deedpoll.ccd.LinkDeedPollHandler;
import uk.gov.hmcts.reform.deedpoll.config.AbstractPostgresContainerTest;
import uk.gov.hmcts.reform.deedpoll.domain.NotFoundError;
import uk.gov.hmcts.reform.deedpoll.domain.auth.CurrentUser;
import uk.gov.hmcts.reform.deedpoll.domain.auth.Role;
import uk.gov.hmcts.reform.deedpoll.domain.deedpoll.DeedPollPatch;
import uk.gov.hmcts.reform.deedpoll.domain.deedpoll.DeedPollService;
import uk.gov.hmcts.reform.deedpoll.domain.deeds.DeedPreparations;
import uk.gov.hmcts.reform.deedpoll.domain.deeds.DeedTemplate;
import uk.gov.hmcts.reform.deedpoll.domain.deletion.ApplicationDeletionService.DeletionCommand;
import uk.gov.hmcts.reform.deedpoll.domain.documents.DocumentService;
import uk.gov.hmcts.reform.deedpoll.domain.mi.MiGranularity;
import uk.gov.hmcts.reform.deedpoll.domain.mi.MiParameters;
import uk.gov.hmcts.reform.deedpoll.domain.notes.CreateNoteInput;
import uk.gov.hmcts.reform.deedpoll.domain.notes.NoteService;
import uk.gov.hmcts.reform.deedpoll.domain.referrals.JudicialReferralService;
import uk.gov.hmcts.reform.deedpoll.domain.referrals.ReferralService;
import uk.gov.hmcts.reform.deedpoll.notify.NotificationClaim;
import uk.gov.hmcts.reform.deedpoll.notify.NotificationDeliveryTransactions;
import uk.gov.hmcts.reform.deedpoll.repository.DeedPollRepository;
import uk.gov.hmcts.reform.deedpoll.repository.ManagementInformationRepository;
import uk.gov.hmcts.reform.deedpoll.repository.ManagementInformationRepository.ReceivedRow;
import uk.gov.hmcts.reform.deedpoll.repository.MasterEmailRepository;
import uk.gov.hmcts.reform.deedpoll.repository.ReferralRepository;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The deletion fence against real Postgres and real transactions (plan 20261118-dm-application-deletion, Task 4): once
 * a deletion request names a case, every read answers as if it were gone, every write and background job refuses it,
 * and a writer racing admission is refused by whichever of the two commits second -- the application under the case's
 * lock, or V49's deferred trigger.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class DeletionFenceTest extends AbstractPostgresContainerTest {

    private static final String SUBJECT = "deletion-fence-dm";
    private static final CurrentUser DELIVERY_MANAGER =
        CurrentUser.currentUser(List.of(Role.DELIVERY_MANAGER), "Dee Manager", SUBJECT);
    private static final String JUDGE_SUBJECT = "deletion-fence-judge";
    private static final CurrentUser JUDGE =
        CurrentUser.currentUser(List.of(Role.JUDICIAL), "Master Fence", JUDGE_SUBJECT);
    private static final LocalDateTime MODIFIED = LocalDateTime.of(2026, 11, 18, 10, 15, 30, 500_000_000);
    private static final LocalDate RECEIVED = LocalDate.of(2026, 3, 4);
    private static final int TIMEOUT_SECONDS = 20;

    @Autowired
    private ApplicationDeletionService deletions;

    @Autowired
    private DeletionFence fence;

    @Autowired
    private DeedPollService cases;

    @Autowired
    private DeedPollRepository deedPolls;

    @Autowired
    private NoteService notes;

    @Autowired
    private DocumentService documents;

    @Autowired
    private ReferralRepository referrals;

    @Autowired
    private JudicialReferralService judicial;

    @Autowired
    private ReferralService referralService;

    @Autowired
    private DeedPreparations deedPreparations;

    @Autowired
    private ManagementInformationRepository mi;

    @Autowired
    private MasterEmailRepository masterEmails;

    @Autowired
    private NotificationDeliveryTransactions outbox;

    @Autowired
    private CcdLinkingService linking;

    @Autowired
    private CcdCreationAttempts attempts;

    @Autowired
    private LinkDeedPollHandler linkHandler;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactions;

    private final List<String> fixtures = new ArrayList<>();
    private final List<UUID> masters = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        for (String id : fixtures) {
            jdbc.update("DELETE FROM deedpoll_deletion_request WHERE case_id = ?", id);
            jdbc.update("DELETE FROM deedpoll_deedpoll WHERE id = ?", id);
        }
        for (UUID master : masters) {
            jdbc.update("DELETE FROM deedpoll_master WHERE id = ?", master);
        }
        DeletionTestDocumentType.removeIfUnused(jdbc);
    }

    // --- fixtures ---------------------------------------------------------------------------------------------

    private String fixture() {
        String id = UUID.randomUUID().toString();
        fixtures.add(id);
        jdbc.update("""
            INSERT INTO deedpoll_deedpoll (id, "modifiedOn", type, state, "displayReference", "dateReceived")
            VALUES (?, ?, 'Adult'::"DeedPollType", 'Enroled', ?, ?)
            """, id, Timestamp.valueOf(MODIFIED), "fence-" + id, RECEIVED);
        return id;
    }

    private void admit(String caseId) {
        deletions.request(DELIVERY_MANAGER, caseId,
                          new DeletionCommand(UUID.randomUUID(), "CREATED_IN_ERROR", "Logged against the wrong form",
                                              MODIFIED));
    }

    /** A request written directly, past admission's refusals: the state a race leaves behind. */
    private void fenceDirectly(String caseId) {
        jdbc.update("""
            INSERT INTO deedpoll_deletion_request (id, case_id, idempotency_key, reason, note, fingerprint,
                expected_last_changed_on, requested_by_subject, requested_by_name, requested_at, restorable_until,
                status)
            VALUES (gen_random_uuid(), ?, gen_random_uuid(), 'DUPLICATE', 'Fixture fence for a race', repeat('a', 64),
                    now(), ?, 'Dee Manager', now(), now() + interval '14 days', 'Pending')
            """, caseId, SUBJECT);
    }

    private String note(String caseId) {
        String id = UUID.randomUUID().toString();
        jdbc.update("""
            INSERT INTO deedpoll_notes (id, "deedPollId", note, "modifiedOn") VALUES (?, ?, 'A note', now())
            """, id, caseId);
        return id;
    }

    private String document(String caseId) {
        String id = UUID.randomUUID().toString();
        DeletionTestDocumentType.ensure(jdbc);
        jdbc.update("""
            INSERT INTO deedpoll_supportingdocument (id, "deedPollId", direction, name, "documentTypeId",
                "storageKey", "modifiedOn", "storageState", "storageUploadAttempts")
            VALUES (?, ?, 'Incoming', 'Evidence', 'deletion-test-evidence',
                    ?, now(), 'Local', 0)
            """, id, caseId, "files/" + id);
        return id;
    }

    /** An owned stage held by the case as its RECORD owner, with no "caseId": the trigger's other branch. */
    private void recordStage(String caseId) {
        jdbc.update("""
            INSERT INTO deedpoll_ownedstage
              (id, "ownerKind", "ownerId", subject, "operationId", "originalName", "mimeType", "sizeBytes",
               checksum, "createdOn", "expiresOn", state)
            VALUES (?, 'RECORD', ?, 'synthetic-subject', ?, 'synthetic.pdf', 'application/pdf', 3,
                    encode(sha256(decode('010203', 'hex')), 'hex'),
                    CURRENT_TIMESTAMP - interval '2 hours', CURRENT_TIMESTAMP + interval '1 hour', 'Removed')
            """, UUID.randomUUID().toString(), caseId, UUID.randomUUID());
    }

    private UUID master() {
        UUID id = UUID.randomUUID();
        masters.add(id);
        jdbc.update("""
            INSERT INTO deedpoll_master (id, name, email, created_at, created_by_id, created_by_name)
            VALUES (?, 'Master Fence', ?, now(), 'fixture', 'Fixture')
            """, id, id + "@example.com");
        return id;
    }

    /**
     * A referral sent to {@code master} long ago, held by the judge, waiting for a direction.
     */
    private UUID referral(String caseId, UUID master, String owner) {
        return referral(caseId, master, owner, 3);
    }

    private UUID referral(String caseId, UUID master, String owner, int daysAgo) {
        UUID id = UUID.randomUUID();
        LocalDateTime assigned = LocalDateTime.now(ZoneOffset.UTC).minusDays(daysAgo);
        jdbc.update("""
            INSERT INTO deedpoll_referral (id, "deedPollId", "state", "urgency", "claimedBy", "claimedById",
                "claimedAt", "assignedMasterId", "assignedAt", "modifiedOn")
            VALUES (?, ?, 'Claimed'::"ReferralState", 'Normal'::"Urgency", 'Master Fence', ?, ?, ?, ?, now())
            """, id, caseId, owner, Timestamp.valueOf(assigned), master, Timestamp.valueOf(assigned));
        return id;
    }

    private UUID notification(String caseId) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO deedpoll_notification (id, "deedPollId", "requestId", template, "exclusionGroup", recipient,
                personalisation, state, attempts, "nextAttemptAt", "createdBy", "createdOn", "modifiedOn")
            VALUES (?, ?, ?, 'APPLICATION_REJECTED', 'REFERRAL_OUTCOME', 'applicant@example.com', '{}'::jsonb,
                    'Pending', 0, ?, 'Fixture', now(), now())
            """, id, caseId, UUID.randomUUID(), Timestamp.valueOf(LocalDateTime.now(ZoneOffset.UTC).minusMinutes(1)));
        return id;
    }

    private UUID pendingAttempt(String caseId) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO deedpoll_ccdcreationattempt (id, "deedPollId", "operationId", status, "modifiedOn")
            VALUES (?, ?, ?, 'Pending'::"CcdCreationAttemptStatus", now())
            """, id, caseId, UUID.randomUUID());
        return id;
    }

    private long received() {
        return mi.received(MiParameters.of(RECEIVED, RECEIVED, MiGranularity.WEEK, true)).stream()
            .mapToLong(ReceivedRow::received).sum();
    }

    private int count(String sql, Object... arguments) {
        return jdbc.queryForObject(sql, Integer.class, arguments);
    }

    private TransactionTemplate transaction() {
        return new TransactionTemplate(transactions);
    }

    // --- reads ------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("a fenced case reads as gone: the case, list entry, notes, documents, referral, judicial view, MI")
    void readsTreatAFencedCaseAsGone() {
        String id = fixture();
        final String document = document(id);
        note(id);
        UUID referral = referral(id, master(), JUDGE_SUBJECT);
        jdbc.update("UPDATE deedpoll_deedpoll SET \"ccdCaseReference\" = '1777000011112222' WHERE id = ?", id);
        final long before = received();
        assertThat(judicial.referral(referral.toString(), JUDGE)).isNotNull();

        admit(id);

        assertThatThrownBy(() -> cases.findDeedPoll(id)).isInstanceOf(NotFoundError.class);
        assertThat(deedPolls.existsById(id)).isFalse();
        assertThat(cases.findDeedPolls()).noneMatch(found -> id.equals(found.deedPoll().getId()));
        assertThatThrownBy(() -> notes.findNotes(id)).isInstanceOf(NotFoundError.class);
        assertThatThrownBy(() -> documents.findDocument(id, document)).isInstanceOf(NotFoundError.class);
        assertThat(referrals.findById(referral)).isEmpty();
        assertThatThrownBy(() -> judicial.referral(referral.toString(), JUDGE)).isInstanceOf(NotFoundError.class);
        assertThat(received()).isEqualTo(before - 1);
        // The one deliberate exception: CCD's own view, which Retain and Dispose reads through data-store.
        assertThat(deedPolls.findCcdCaseProjection("1777000011112222")).isPresent();
        // The case is still stored, untouched, for the deletion to remove.
        assertThat(count("SELECT count(*) FROM deedpoll_deedpoll WHERE id = ?", id)).isOne();
    }

    @Test
    @DisplayName("a restore lifts the fence: the case, its children and its referral read again, and writes commit")
    void restoreLiftsTheFence() {
        String id = fixture();
        final String document = document(id);
        note(id);
        final UUID referral = referral(id, master(), JUDGE_SUBJECT);
        final long before = received();
        UUID request = deletions.request(DELIVERY_MANAGER, id,
                                         new DeletionCommand(UUID.randomUUID(), "CREATED_IN_ERROR",
                                                             "Logged against the wrong form", MODIFIED))
            .request().id();
        assertThatThrownBy(() -> cases.findDeedPoll(id)).isInstanceOf(NotFoundError.class);

        deletions.restore(DELIVERY_MANAGER, request);

        assertThat(cases.findDeedPoll(id)).isNotNull();
        assertThat(deedPolls.existsById(id)).isTrue();
        assertThat(cases.findDeedPolls()).anyMatch(found -> id.equals(found.deedPoll().getId()));
        assertThat(notes.findNotes(id)).hasSize(1);
        assertThat(documents.findDocument(id, document)).isNotNull();
        assertThat(referrals.findById(referral)).isPresent();
        assertThat(judicial.referral(referral.toString(), JUDGE)).isNotNull();
        assertThat(received()).isEqualTo(before);
        Boolean admitted = transaction().execute(status -> fence.admits(id));
        assertThat(admitted).isTrue();

        cases.updateDeedPoll(id, DeedPollPatch.of(Map.of("newForename", "Alex")));
        notes.createNote(id, new CreateNoteInput("Restored and carrying on"));
        assertThat(count("SELECT count(*) FROM deedpoll_notes WHERE \"deedPollId\" = ?", id)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT \"newForename\" FROM deedpoll_deedpoll WHERE id = ?", String.class, id))
            .isEqualTo("Alex");
    }

    // --- writes -----------------------------------------------------------------------------------------------

    @Test
    @DisplayName("a fenced case refuses writes in an unknown case's words, and linking neither offers nor claims it")
    void writesAreRefused() {
        String id = fixture();
        admit(id);

        assertThatThrownBy(() -> cases.updateDeedPoll(id, DeedPollPatch.of(Map.of("newForename", "Alex"))))
            .isInstanceOf(NotFoundError.class);
        assertThatThrownBy(() -> notes.createNote(id, new CreateNoteInput("Too late")))
            .isInstanceOf(NotFoundError.class);
        assertThat(attempts.dueForLinking(1000)).doesNotContain(id);
        assertThat(linking.link(id)).isEqualTo(CcdLinkingService.Result.NO_SUCH_RECORD);
        assertThat(count("SELECT count(*) FROM deedpoll_ccdcreationattempt WHERE \"deedPollId\" = ?", id)).isZero();
        assertThat(count("SELECT count(*) FROM deedpoll_notes WHERE \"deedPollId\" = ?", id)).isZero();
        Boolean admitted = transaction().execute(status -> fence.admits(id));
        assertThat(admitted).isFalse();
    }

    @Test
    @DisplayName("V49 refuses a fenced case's insert or update at commit, and still lets the case be deleted")
    void databaseRefusesAFencedWriteAtCommit() {
        String id = fixture();
        String note = note(id);
        fenceDirectly(id);

        assertThatThrownBy(() -> transaction().executeWithoutResult(status -> note(id)))
            .hasStackTraceContaining("The application is being deleted")
            .satisfies(refused -> assertThat(DeletionFence.refusedByTrigger(refused)).isTrue());
        assertThat(DeletionFence.refusedByTrigger(new IllegalStateException("The application is being deleted")))
            .isFalse();
        assertThatThrownBy(() -> transaction().executeWithoutResult(status -> jdbc.update(
            "UPDATE deedpoll_notes SET note = 'Changed' WHERE id = ?", note)))
            .hasStackTraceContaining("The application is being deleted");
        assertThatThrownBy(() -> transaction().executeWithoutResult(status -> jdbc.update(
            "UPDATE deedpoll_deedpoll SET \"newForename\" = 'Alex' WHERE id = ?", id)))
            .hasStackTraceContaining("The application is being deleted");
        assertThatThrownBy(() -> transaction().executeWithoutResult(status -> notification(id)))
            .hasStackTraceContaining("The application is being deleted");
        assertThatThrownBy(() -> transaction().executeWithoutResult(status -> pendingAttempt(id)))
            .hasStackTraceContaining("The application is being deleted");
        assertThatThrownBy(() -> transaction().executeWithoutResult(status -> document(id)))
            .hasStackTraceContaining("The application is being deleted");
        assertThatThrownBy(() -> transaction().executeWithoutResult(status -> recordStage(id)))
            .hasStackTraceContaining("The application is being deleted");
        assertThat(count("SELECT count(*) FROM deedpoll_notes WHERE \"deedPollId\" = ?", id)).isOne();

        // An unfenced case is untouched by the trigger.
        String other = fixture();
        transaction().executeWithoutResult(status -> note(other));
        assertThat(count("SELECT count(*) FROM deedpoll_notes WHERE \"deedPollId\" = ?", other)).isOne();

        // DELETE is never refused: removing the case is the deletion's own work.
        transaction().executeWithoutResult(status -> jdbc.update("DELETE FROM deedpoll_deedpoll WHERE id = ?", id));
        assertThat(count("SELECT count(*) FROM deedpoll_deedpoll WHERE id = ?", id)).isZero();
    }

    @Test
    @DisplayName("every fence trigger (V49, V50, V51) names a column its table has, so none resolves a null target")
    void everyFenceTriggerNamesARealColumn() {
        List<Map<String, Object>> triggers = jdbc.queryForList("""
            SELECT c.relname AS "table", encode(t.tgargs, 'escape') AS args
              FROM pg_trigger t JOIN pg_class c ON c.oid = t.tgrelid
             WHERE t.tgname = 'deedpoll_deletion_fence'
            """);
        assertThat(triggers).as("V49's twelve, V50's removed-document tombstone and V51's document deletion request")
            .hasSize(14);
        for (Map<String, Object> trigger : triggers) {
            String table = (String) trigger.get("table");
            String column = ((String) trigger.get("args")).split("\\\\000", -1)[0];
            if (table.equals("deedpoll_ownedstage")) {
                // The one trigger with no argument: the function reads "caseId" and a RECORD owner's "ownerId".
                assertThat(column).as(table).isEmpty();
                continue;
            }
            assertThat(count("SELECT count(*) FROM information_schema.columns WHERE table_name = ? AND column_name = ?",
                             table, column)).as(table + "." + column).isOne();
        }
    }

    // --- background work --------------------------------------------------------------------------------------

    @Test
    @DisplayName("the outbox never offers, claims or starts a fenced case's email, even on a claim taken before")
    void outboxNeverStartsAFencedEmail() {
        String id = fixture();
        UUID claimedFirst = notification(id);
        final UUID waiting = notification(id);
        LocalDateTime cutoff = LocalDateTime.now(ZoneOffset.UTC);
        Optional<NotificationClaim> claim = outbox.claim(claimedFirst, cutoff);
        assertThat(claim).isPresent();

        // The claim committed while admission was reading, so admission did not see it.
        fenceDirectly(id);

        assertThat(outbox.startSend(claim.get())).isFalse();
        assertThat(outbox.candidates(LocalDateTime.now(ZoneOffset.UTC), 1000)).doesNotContain(claimedFirst, waiting);
        assertThat(outbox.claim(waiting, LocalDateTime.now(ZoneOffset.UTC))).isEmpty();
        assertThat(count("SELECT count(*) FROM deedpoll_notification WHERE \"deedPollId\" = ? "
                             + "AND (\"sendStartedAt\" IS NOT NULL OR state <> 'Pending')", id)).isZero();
        assertThat(count("SELECT count(*) FROM deedpoll_notes WHERE \"deedPollId\" = ?", id)).isZero();
    }

    @Test
    @DisplayName("Master reminders, outstanding lists and linking skip a fenced referral and still work for the rest")
    void masterWorkSkipsAFencedReferral() {
        UUID master = master();
        String fenced = fixture();
        String kept = fixture();
        UUID fencedReferral = referral(fenced, master, null);
        UUID keptReferral = referral(kept, master, null);
        admit(fenced);

        List<UUID> reminded = transaction().execute(status ->
            masterEmails.claimDueReminders(LocalDateTime.now(ZoneOffset.UTC), Duration.ofHours(1)));
        assertThat(reminded).filteredOn(master::equals).hasSize(1);
        assertThat(masterEmails.outstandingFor(master)).contains(keptReferral).doesNotContain(fencedReferral);
        assertThat(count("SELECT \"remindersSent\" FROM deedpoll_referral WHERE id = ?", fencedReferral)).isZero();
        assertThat(count("SELECT \"remindersSent\" FROM deedpoll_referral WHERE id = ?", keptReferral)).isPositive();

        int attached = transaction().execute(status -> referrals.attachToLinkedMaster(master, JUDGE_SUBJECT));
        assertThat(attached).isOne();
        assertThat(jdbc.queryForObject("SELECT \"claimedById\" FROM deedpoll_referral WHERE id = ?", String.class,
                                       fencedReferral)).isNull();
    }

    @Test
    @DisplayName("the overdue sweep returns an unfenced assignment to the queue and leaves a fenced one, and commits")
    void unassignmentSkipsAFencedReferral() {
        UUID master = master();
        String fenced = fixture();
        String kept = fixture();
        final UUID fencedReferral = referral(fenced, master, JUDGE_SUBJECT, 30);
        UUID keptReferral = referral(kept, master, JUDGE_SUBJECT, 30);
        admit(fenced);

        referralService.unassignOverdue();

        assertThat(jdbc.queryForObject("SELECT state::text FROM deedpoll_referral WHERE id = ?", String.class,
                                       keptReferral)).isEqualTo("Open");
        assertThat(jdbc.queryForObject("SELECT state::text FROM deedpoll_referral WHERE id = ?", String.class,
                                       fencedReferral)).isEqualTo("Claimed");
        assertThat(count("SELECT count(*) FROM deedpoll_notes WHERE \"deedPollId\" = ?", fenced)).isZero();
    }

    @Test
    @DisplayName("linking a Master's legacy claims and unlinking a Master leave a fenced referral alone")
    void masterLinkingSkipsAFencedReferral() {
        final UUID master = master();
        String fenced = fixture();
        String kept = fixture();
        final UUID fencedLegacy = referral(fenced, null, JUDGE_SUBJECT);
        UUID keptLegacy = referral(kept, null, JUDGE_SUBJECT);
        admit(fenced);

        Integer attached = transaction().execute(status -> referrals.attachLegacyClaims(master, JUDGE_SUBJECT));
        assertThat(attached).isOne();
        assertThat(jdbc.queryForObject("SELECT \"assignedMasterId\" FROM deedpoll_referral WHERE id = ?", UUID.class,
                                       keptLegacy)).isEqualTo(master);
        assertThat(jdbc.queryForObject("SELECT \"assignedMasterId\" FROM deedpoll_referral WHERE id = ?", UUID.class,
                                       fencedLegacy)).isNull();

        String fencedHeld = fixture();
        final UUID heldReferral = referral(fencedHeld, master, JUDGE_SUBJECT);
        admit(fencedHeld);
        Integer detached = transaction().execute(status -> referrals.detachFromUnlinkedMaster(master));
        assertThat(detached).isOne();
        assertThat(jdbc.queryForObject("SELECT \"claimedById\" FROM deedpoll_referral WHERE id = ?", String.class,
                                       keptLegacy)).isNull();
        assertThat(jdbc.queryForObject("SELECT \"claimedById\" FROM deedpoll_referral WHERE id = ?", String.class,
                                       heldReferral)).isEqualTo(JUDGE_SUBJECT);
    }

    @Test
    @DisplayName("deed preparation, and every path sharing its lock, refuses a fenced case before reserving anything")
    void deedPreparationRefusesAFencedCase() {
        String id = fixture();
        admit(id);

        Optional<String> locked = transaction().execute(status -> deedPolls.lockUnfencedForDeedPreparation(id));
        assertThat(locked).isEmpty();
        assertThatThrownBy(() -> deedPreparations.prepare(id, DeedTemplate.ADULT)).isInstanceOf(NotFoundError.class);
        assertThat(count("SELECT count(*) FROM deedpoll_deedpreparation WHERE \"deedPollId\" = ?", id)).isZero();
    }

    // --- races ------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("a patch, a note, a CCD claim and a deed lock waiting on admission's lock are refused once it commits")
    void writersWaitingOnAdmissionAreRefused() throws Exception {
        String id = fixture();
        UUID attempt = pendingAttempt(id);
        ExecutorService pool = Executors.newFixedThreadPool(5);
        try {
            CountDownLatch admitted = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            final Future<?> admission = pool.submit(() -> transaction().executeWithoutResult(status -> {
                admit(id);
                admitted.countDown();
                await(release);
            }));
            assertThat(admitted.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();

            final Future<Object> patch = pool.submit(() -> outcome(
                () -> cases.updateDeedPoll(id, DeedPollPatch.of(Map.of("newForename", "Alex")))));
            final Future<Object> note = pool.submit(() -> outcome(
                () -> notes.createNote(id, new CreateNoteInput("Written during admission"))));
            final Future<Object> claim = pool.submit(() -> outcome(
                () -> attempts.claim(attempt, "fence-worker", Duration.ofMinutes(5))));
            final Future<Object> preparation = pool.submit(() -> outcome(
                () -> transaction().execute(status -> deedPolls.lockUnfencedForDeedPreparation(id))));
            awaitLockWaiters(4);
            release.countDown();
            admission.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

            assertThat(patch.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isInstanceOf(NotFoundError.class);
            // The note asked nothing under the lock, so V49 refused it at commit, and that is recognised as the
            // fence's refusal, which the error handler answers with the same 404 the patch gets.
            assertThat(note.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isInstanceOfSatisfying(
                RuntimeException.class, refused -> assertThat(DeletionFence.refusedByTrigger(refused)).isTrue());
            assertThat(claim.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isEqualTo(OptionalInt.empty());
            assertThat(preparation.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isEqualTo(Optional.empty());
        } finally {
            pool.shutdownNow();
        }
        assertThat(count("SELECT count(*) FROM deedpoll_notes WHERE \"deedPollId\" = ?", id)).isZero();
        assertThat(jdbc.queryForObject("SELECT \"newForename\" FROM deedpoll_deedpoll WHERE id = ?", String.class, id))
            .isNull();
        assertThat(jdbc.queryForObject("SELECT status::text FROM deedpoll_ccdcreationattempt WHERE id = ?",
                                       String.class, attempt)).isEqualTo("Pending");
    }

    @Test
    @DisplayName("admission that waits on a writer sees what it wrote: a note is in the case, a send start refuses")
    void admissionWaitingOnAWriterSeesIt() throws Exception {
        String noted = fixture();
        String sending = fixture();
        UUID email = notification(sending);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            // A note's insert holds the case's key-share lock until it commits.
            CountDownLatch written = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            final Future<?> writer = pool.submit(() -> transaction().executeWithoutResult(status -> {
                note(noted);
                written.countDown();
                await(release);
            }));
            assertThat(written.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
            final Future<Object> admission = pool.submit(() -> outcome(() -> {
                admit(noted);
                return "admitted";
            }));
            awaitLockWaiters(1);
            release.countDown();
            writer.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            assertThat(admission.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isEqualTo("admitted");
            assertThat(count("SELECT count(*) FROM deedpoll_notes WHERE \"deedPollId\" = ?", noted)).isOne();

            // A send start holds the fence's lock while it commits its evidence; admission then refuses.
            CountDownLatch started = new CountDownLatch(1);
            CountDownLatch finish = new CountDownLatch(1);
            final Future<?> sender = pool.submit(() -> transaction().executeWithoutResult(status -> {
                assertThat(fence.admits(sending)).isTrue();
                jdbc.update("UPDATE deedpoll_notification SET \"sendStartedAt\" = now(), \"claimOwner\" = ? "
                                + "WHERE id = ?", UUID.randomUUID(), email);
                started.countDown();
                await(finish);
            }));
            assertThat(started.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
            final Future<Object> refused = pool.submit(() -> outcome(() -> {
                admit(sending);
                return "admitted";
            }));
            awaitLockWaiters(1);
            finish.countDown();
            sender.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            assertThat(refused.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isInstanceOfSatisfying(
                DeletionConflictError.class,
                error -> assertThat(error.refusal()).isEqualTo(DeletionRefusal.EMAIL_SEND_UNRESOLVED));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("a CCD callback for a fenced record binds nothing, and nor does a late one after the case is gone")
    void lateCcdCallbackBindsNothing() {
        String id = fixture();
        pendingAttempt(id);
        jdbc.update("UPDATE deedpoll_ccdcreationattempt SET status = 'Claimed', \"claimedBy\" = 'w', "
                        + "\"leaseExpiresAt\" = now() + interval '5 minutes' WHERE \"deedPollId\" = ?", id);
        fenceDirectly(id);
        long reference = 1_777_000_011_113_333L;

        assertThat(callback(reference, id).getErrors()).containsExactly(CcdLinkRule.Outcome.NO_SUCH_RECORD.refusal());
        assertThat(jdbc.queryForObject("SELECT \"ccdCaseReference\" FROM deedpoll_deedpoll WHERE id = ?",
                                       String.class, id)).isNull();

        jdbc.update("DELETE FROM deedpoll_deedpoll WHERE id = ?", id);
        assertThat(callback(reference, id).getErrors()).containsExactly(CcdLinkRule.Outcome.NO_SUCH_RECORD.refusal());
    }

    private SubmitResponse<DeedPollCaseState> callback(long reference, String deedPollId) {
        DeedPollCaseData data = new DeedPollCaseData();
        data.setDeedPollId(deedPollId);
        return transaction().execute(status -> linkHandler.submit(
            new EventPayload<>(reference, data, new LinkedMultiValueMap<>())));
    }

    // --- helpers ----------------------------------------------------------------------------------------------

    private static Object outcome(Supplier<Object> call) {
        try {
            return call.get();
        } catch (RuntimeException refused) {
            return refused;
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new IllegalStateException("never released");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }

    private void awaitLockWaiters(int count) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
        while (System.nanoTime() < deadline) {
            Integer waiting = jdbc.queryForObject("""
                SELECT count(*) FROM pg_stat_activity
                WHERE wait_event_type = 'Lock' AND datname = current_database()
                """, Integer.class);
            if (waiting != null && waiting >= count) {
                return;
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(interrupted);
            }
        }
        throw new IllegalStateException("the writers never waited on the case lock");
    }
}
