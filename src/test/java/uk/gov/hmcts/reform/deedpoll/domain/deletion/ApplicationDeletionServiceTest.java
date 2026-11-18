package uk.gov.hmcts.reform.deedpoll.domain.deletion;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import uk.gov.hmcts.reform.deedpoll.config.AbstractPostgresContainerTest;
import uk.gov.hmcts.reform.deedpoll.domain.ConflictError;
import uk.gov.hmcts.reform.deedpoll.domain.FieldError;
import uk.gov.hmcts.reform.deedpoll.domain.ForbiddenError;
import uk.gov.hmcts.reform.deedpoll.domain.InvalidInputError;
import uk.gov.hmcts.reform.deedpoll.domain.NotFoundError;
import uk.gov.hmcts.reform.deedpoll.domain.auth.CurrentUser;
import uk.gov.hmcts.reform.deedpoll.domain.auth.Role;
import uk.gov.hmcts.reform.deedpoll.domain.deedpoll.DeedPollService;
import uk.gov.hmcts.reform.deedpoll.domain.deletion.ApplicationDeletionService.Admission;
import uk.gov.hmcts.reform.deedpoll.domain.deletion.ApplicationDeletionService.DeletionCommand;
import uk.gov.hmcts.reform.deedpoll.repository.DeletionRequestRepository;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link ApplicationDeletionService}'s admission against real Postgres and real transactions: the request, its
 * inventory and its audit event commit together or not at all, every refusal writes nothing, and a replay answers the
 * first request whatever has happened to the case since.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class ApplicationDeletionServiceTest extends AbstractPostgresContainerTest {

    private static final String SUBJECT = "deletion-admission-dm";
    private static final CurrentUser DELIVERY_MANAGER =
        CurrentUser.currentUser(List.of(Role.DELIVERY_MANAGER), "Dee Manager", SUBJECT);
    private static final CurrentUser SECOND_DELIVERY_MANAGER =
        CurrentUser.currentUser(List.of(Role.DELIVERY_MANAGER), "Second Manager", "deletion-admission-dm-2");
    private static final LocalDateTime MODIFIED = LocalDateTime.of(2026, 11, 18, 9, 30, 15, 250_000_000);
    private static final String NOTE = "Logged twice from the same postal application";
    private static final int TIMEOUT_SECONDS = 20;

    @Autowired
    private ApplicationDeletionService deletions;

    @Autowired
    private DeletionRequestRepository requests;

    @Autowired
    private DeedPollService cases;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactions;

    private final List<String> fixtures = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        jdbc.execute("DROP TRIGGER IF EXISTS deletion_test_audit_failure ON deedpoll_audit_event");
        jdbc.execute("DROP FUNCTION IF EXISTS deletion_test_audit_failure()");
        for (String id : fixtures) {
            jdbc.update("DELETE FROM deedpoll_deletion_request WHERE case_id = ?", id);
            jdbc.update("DELETE FROM deedpoll_gazette_notice_batch_case WHERE deed_poll_id = ?", id);
            jdbc.update("DELETE FROM deedpoll_deedpoll WHERE id = ?", id);
        }
        jdbc.update("DELETE FROM deedpoll_gazette_notice_batch WHERE sent_by_id = ?", SUBJECT);
    }

    private String fixture() {
        String id = UUID.randomUUID().toString();
        fixtures.add(id);
        jdbc.update("""
            INSERT INTO deedpoll_deedpoll (id, "modifiedOn", type, state, "displayReference")
            VALUES (?, ?, 'Adult'::"DeedPollType", 'Enroled', ?)
            """, id, Timestamp.valueOf(MODIFIED), "deletion-" + id);
        return id;
    }

    private static DeletionCommand command(UUID key) {
        return new DeletionCommand(key, "DUPLICATE", NOTE, MODIFIED);
    }

    private Admission admit(String caseId, UUID key) {
        return deletions.request(DELIVERY_MANAGER, caseId, command(key));
    }

    private int requestCount(String caseId) {
        return jdbc.queryForObject("SELECT count(*) FROM deedpoll_deletion_request WHERE case_id = ?",
                                   Integer.class, caseId);
    }

    private int stepCount(String caseId) {
        return jdbc.queryForObject("""
            SELECT count(*) FROM deedpoll_deletion_step s JOIN deedpoll_deletion_request r ON r.id = s.request_id
             WHERE r.case_id = ?
            """, Integer.class, caseId);
    }

    private List<Map<String, Object>> requestedEvents(String caseId) {
        return jdbc.queryForList("""
            SELECT actor_id, actor_name, entity_type, entity_id, detail::text AS detail,
                   (SELECT count(*) FROM deedpoll_audit_event_value v WHERE v.event_id = e.id) AS value_rows
              FROM deedpoll_audit_event e WHERE case_id = ? AND action = 'CASE_DELETION_REQUESTED'
            """, caseId);
    }

    private String caseRow(String caseId) {
        // As JSON text: a jsonb or array column reads back as an object with no value equality.
        return jdbc.queryForObject("SELECT row_to_json(d)::text FROM deedpoll_deedpoll d WHERE id = ?",
                                   String.class, caseId);
    }

    private void assertNothingWritten(String caseId, String before) {
        assertThat(requestCount(caseId)).isZero();
        assertThat(stepCount(caseId)).isZero();
        assertThat(requestedEvents(caseId)).isEmpty();
        assertThat(caseRow(caseId)).isEqualTo(before);
    }

    private void insertDocument(String caseId, String state, String storageKey, UUID cdamId, String hashToken,
                                int uploads) {
        jdbc.update("""
            INSERT INTO deedpoll_supportingdocument (id, "deedPollId", direction, name, "documentTypeId",
                "storageKey", "modifiedOn", "storageState", "cdamDocumentId", "cdamHashToken", "storageUploadAttempts")
            VALUES (?, ?, 'Incoming', 'Evidence', (SELECT id FROM deedpoll_supportingdocumenttype ORDER BY id LIMIT 1),
                    ?, now(), ?, ?, ?, ?)
            """, UUID.randomUUID().toString(), caseId, storageKey, state, cdamId, hashToken, uploads);
    }

    private void link(String caseId, String reference) {
        jdbc.update("UPDATE deedpoll_deedpoll SET \"ccdCaseReference\" = ? WHERE id = ?", reference, caseId);
    }

    private static String reference() {
        return String.valueOf(1_000_000_000_000_000L + Math.floorMod(UUID.randomUUID().getMostSignificantBits(),
                                                                       8_999_999_999_999_999L));
    }

    @Test
    @DisplayName("admission writes the request, its inventory and one value-free audit event under the caller")
    void admitsWithInventoryAndAudit() {
        String id = fixture();
        String ccd = reference();
        link(id, ccd);
        insertDocument(id, "Local", "files/" + id, null, null, 0);
        insertDocument(id, "Attached", null, UUID.randomUUID(), null, 1);
        insertDocument(id, "Failed", null, null, null, 1);
        UUID key = UUID.randomUUID();

        Admission result = admit(id, key);

        assertThat(result.admitted()).isTrue();
        DeletionRequest request = result.request();
        assertThat(request.caseId()).isEqualTo(id);
        assertThat(request.idempotencyKey()).isEqualTo(key);
        assertThat(request.reason()).isEqualTo(DeletionReason.DUPLICATE);
        assertThat(request.note().value()).isEqualTo(NOTE);
        assertThat(request.status()).isEqualTo(DeletionStatus.Pending);
        assertThat(request.requestedBySubject()).isEqualTo(SUBJECT);
        assertThat(request.requestedByName()).isEqualTo("Dee Manager");
        assertThat(request.ccdCaseReference()).isEqualTo(ccd);
        assertThat(request.expectedModifiedOn()).isEqualTo(MODIFIED);
        assertThat(request.fingerprint())
            .isEqualTo(DeletionFingerprint.of(DeletionReason.DUPLICATE, DeletionNote.parse(NOTE), MODIFIED));
        assertThat(requests.stepsOf(request.id())).extracting(DeletionStep::kind).containsExactlyInAnyOrder(
            DeletionStepKind.LOCAL_DOCUMENT, DeletionStepKind.CDAM_DOCUMENT, DeletionStepKind.CCD_DISPOSAL);
        assertThat(requests.stepsOf(request.id())).allSatisfy(step -> {
            assertThat(step.state()).isEqualTo(DeletionStepState.Pending);
            assertThat(step.locator()).isNotBlank();
        });
        assertThat(requestedEvents(id)).singleElement().satisfies(event -> {
            assertThat(event).containsEntry("actor_id", SUBJECT).containsEntry("actor_name", "Dee Manager")
                .containsEntry("entity_type", "DeedPoll").containsEntry("entity_id", id)
                .containsEntry("value_rows", 0L);
            assertThat((String) event.get("detail")).contains("\"deletionReason\": \"DUPLICATE\"")
                .contains("\"deletionRequestId\": \"" + request.id() + "\"").contains(NOTE);
        });
        assertThat(caseRow(id)).isNotNull();
        assertThat(deletions.read(DELIVERY_MANAGER, request.id())).isEqualTo(request);
    }

    @Test
    @DisplayName("an unlinked case with no documents has an empty inventory, and the case is left stored")
    void unlinkedCaseHasNoCcdStep() {
        String id = fixture();
        Admission result = admit(id, UUID.randomUUID());
        assertThat(result.request().ccdCaseReference()).isNull();
        assertThat(requests.stepsOf(result.request().id())).isEmpty();
        assertThat(caseRow(id)).contains(id);
    }

    @Test
    @DisplayName("a capability is checked before the case or the body: a non-Delivery Manager learns nothing")
    void everyOtherRoleIsForbiddenFirst() {
        String id = fixture();
        String before = caseRow(id);
        DeletionCommand invalid = new DeletionCommand(null, "nonsense", "", null);
        for (Role role : Role.values()) {
            if (role == Role.DELIVERY_MANAGER) {
                continue;
            }
            CurrentUser caller = CurrentUser.currentUser(List.of(role), "Someone", "someone-" + role);
            assertThatThrownBy(() -> deletions.request(caller, id, command(UUID.randomUUID())))
                .as(role.name()).isInstanceOf(ForbiddenError.class);
            assertThatThrownBy(() -> deletions.request(caller, "no-such-case", invalid))
                .as(role.name()).isInstanceOf(ForbiddenError.class);
            assertThatThrownBy(() -> deletions.read(caller, UUID.randomUUID()))
                .as(role.name()).isInstanceOf(ForbiddenError.class);
        }
        assertNothingWritten(id, before);
    }

    @Test
    @DisplayName("an invalid body is refused field by field, without naming the case or echoing the note")
    void invalidBodyIsRefused() {
        String id = fixture();
        String before = caseRow(id);
        assertThatThrownBy(() -> deletions.request(DELIVERY_MANAGER, id,
                                                   new DeletionCommand(null, "created_in_error", "short", null)))
            .isInstanceOfSatisfying(InvalidInputError.class, error -> {
                assertThat(error.errors()).extracting(FieldError::field).containsExactly(
                    "Idempotency-Key", "reason", "note", "expectedModifiedOn");
                assertThat(error.getMessage()).doesNotContain(id).doesNotContain("short");
            });
        assertNothingWritten(id, before);
    }

    @Test
    @DisplayName("an unknown case is a 404 for a Delivery Manager, and so is an unknown request")
    void unknownIdsAreNotFound() {
        assertThatThrownBy(() -> admit("no-such-case-" + UUID.randomUUID(), UUID.randomUUID()))
            .isInstanceOf(NotFoundError.class);
        assertThatThrownBy(() -> deletions.read(DELIVERY_MANAGER, UUID.randomUUID()))
            .isInstanceOf(NotFoundError.class);
    }

    @Test
    @DisplayName("each refusal is its typed 409 and leaves no request, inventory, audit event or case change")
    void refusalsWriteNothing() {
        refuses(DeletionRefusal.RETAINED_PERMANENTLY,
                id -> jdbc.update("UPDATE deedpoll_deedpoll SET \"retainPermanently\" = true WHERE id = ?", id));
        refuses(DeletionRefusal.GAZETTE_EVIDENCE, id -> {
            UUID batch = UUID.randomUUID();
            jdbc.update("""
                INSERT INTO deedpoll_gazette_notice_batch
                    (id, sent_at, sent_by_id, sent_by_name, request_id, case_count)
                VALUES (?, now(), ?, 'Fixture', ?, 1)
                """, batch, SUBJECT, UUID.randomUUID());
            jdbc.update("""
                INSERT INTO deedpoll_gazette_notice_batch_case (batch_id, deed_poll_id, reference_number)
                VALUES (?, ?, 'reference')
                """, batch, id);
        });
        refuses(DeletionRefusal.DOCUMENT_STORAGE_UNRESOLVED,
                id -> insertDocument(id, "UploadPending", null, null, null, 1));
        refuses(DeletionRefusal.DOCUMENT_STORAGE_UNRESOLVED,
                id -> insertDocument(id, "Uploaded", null, UUID.randomUUID(), "hash", 1));
        refuses(DeletionRefusal.DOCUMENT_STORAGE_UNRESOLVED,
                id -> insertDocument(id, "AttachUncertain", null, UUID.randomUUID(), "hash", 1));
        refuses(DeletionRefusal.DOCUMENT_STORAGE_UNRESOLVED,
                id -> insertDocument(id, "UploadUncertain", null, null, null, 1));
        refuses(DeletionRefusal.CCD_CREATION_UNRESOLVED, id -> attempt(id, "AwaitingConfirmation", false, false));
        refuses(DeletionRefusal.CCD_CREATION_UNRESOLVED, id -> attempt(id, "NeedsRepair", true, false));
        refuses(DeletionRefusal.EMAIL_SEND_UNRESOLVED, id -> notification(id, null, true));
        refuses(DeletionRefusal.EMAIL_SEND_UNRESOLVED,
                id -> notification(id, LocalDateTime.now(ZoneOffset.UTC).plusMinutes(5), false));
    }

    @Test
    @DisplayName("settled work does not block: a spent uncertain upload, a creation proven empty, an expired claim")
    void settledWorkIsAdmitted() {
        String spent = fixture();
        insertDocument(spent, "UploadUncertain", null, null, null, 2);
        assertThat(admit(spent, UUID.randomUUID()).admitted()).isTrue();

        String provenEmpty = fixture();
        attempt(provenEmpty, "NeedsRepair", true, true);
        assertThat(admit(provenEmpty, UUID.randomUUID()).admitted()).isTrue();

        String neverSubmitted = fixture();
        attempt(neverSubmitted, "Pending", false, false);
        assertThat(admit(neverSubmitted, UUID.randomUUID()).admitted()).isTrue();

        String expiredClaim = fixture();
        notification(expiredClaim, LocalDateTime.now(ZoneOffset.UTC).minusMinutes(5), false);
        assertThat(admit(expiredClaim, UUID.randomUUID()).admitted()).isTrue();
    }

    @Test
    @DisplayName("a stale confirmation is refused before anything is written; the current timestamp is admitted")
    void staleConfirmationIsRefused() {
        String id = fixture();
        String before = caseRow(id);
        DeletionCommand stale = new DeletionCommand(UUID.randomUUID(), "DUPLICATE", NOTE, MODIFIED.minusSeconds(1));
        assertThatThrownBy(() -> deletions.request(DELIVERY_MANAGER, id, stale))
            .isInstanceOfSatisfying(DeletionConflictError.class,
                                    error -> assertThat(error.refusal()).isEqualTo(DeletionRefusal.STALE_CONFIRMATION));
        assertNothingWritten(id, before);
        // The same instant to the microsecond is the same confirmation: the column keeps milliseconds.
        DeletionCommand finer = new DeletionCommand(UUID.randomUUID(), "DUPLICATE", NOTE, MODIFIED.plusNanos(400_000));
        assertThat(deletions.request(DELIVERY_MANAGER, id, finer).admitted()).isTrue();
    }

    @Test
    @DisplayName("an audit failure rolls the request and its inventory back")
    void auditFailureRollsBack() {
        String id = fixture();
        insertDocument(id, "Local", "files/" + id, null, null, 0);
        final String before = caseRow(id);
        jdbc.execute("""
            CREATE FUNCTION deletion_test_audit_failure() RETURNS trigger LANGUAGE plpgsql AS $$
            BEGIN
                IF NEW.action = 'CASE_DELETION_REQUESTED' THEN
                    RAISE EXCEPTION 'synthetic audit failure';
                END IF;
                RETURN NEW;
            END;
            $$
            """);
        jdbc.execute("""
            CREATE TRIGGER deletion_test_audit_failure BEFORE INSERT ON deedpoll_audit_event
                FOR EACH ROW EXECUTE FUNCTION deletion_test_audit_failure()
            """);
        assertThatThrownBy(() -> admit(id, UUID.randomUUID())).isNotInstanceOf(ConflictError.class);
        assertNothingWritten(id, before);
    }

    @Test
    @DisplayName("replays answer the first request, even after the case is gone, and never write a second")
    void replays() {
        String id = fixture();
        UUID key = UUID.randomUUID();
        DeletionRequest first = admit(id, key).request();

        Admission same = admit(id, key);
        assertThat(same.admitted()).isFalse();
        assertThat(same.request().id()).isEqualTo(first.id());

        Admission secondKey = deletions.request(SECOND_DELIVERY_MANAGER, id,
            new DeletionCommand(UUID.randomUUID(), "DUPLICATE", "A different note, same reason", MODIFIED));
        assertThat(secondKey.admitted()).isFalse();
        assertThat(secondKey.request().id()).isEqualTo(first.id());
        assertThat(secondKey.request().requestedBySubject()).isEqualTo(SUBJECT);
        assertThat(secondKey.request().note().value()).isEqualTo(NOTE);

        assertThatThrownBy(() -> deletions.request(DELIVERY_MANAGER, id,
                new DeletionCommand(key, "DUPLICATE", "Same key, different words", MODIFIED)))
            .isInstanceOfSatisfying(DeletionConflictError.class,
                                    error -> assertThat(error.refusal()).isEqualTo(DeletionRefusal.KEY_REUSED));
        assertThatThrownBy(() -> deletions.request(DELIVERY_MANAGER, id,
                new DeletionCommand(UUID.randomUUID(), "CREATED_IN_ERROR", NOTE, MODIFIED)))
            .isInstanceOfSatisfying(DeletionConflictError.class,
                                    error -> assertThat(error.refusal()).isEqualTo(DeletionRefusal.CONFLICTING_REASON));

        // The case can no longer change after admission (V49's fence refuses it), and a replay never compares the
        // confirmation with it anyway.
        assertThatThrownBy(() -> jdbc.update("UPDATE deedpoll_deedpoll SET \"modifiedOn\" = now() WHERE id = ?", id))
            .hasMessageContaining("The application is being deleted");
        assertThat(admit(id, key).request().id()).isEqualTo(first.id());

        // And it is physically deleted: the tombstone still answers.
        jdbc.update("DELETE FROM deedpoll_deedpoll WHERE id = ?", id);
        assertThat(admit(id, key).request().id()).isEqualTo(first.id());
        assertThat(admit(id, UUID.randomUUID()).request().id()).isEqualTo(first.id());
        assertThat(deletions.read(DELIVERY_MANAGER, first.id()).caseId()).isEqualTo(id);

        assertThat(requestCount(id)).isOne();
        assertThat(requestedEvents(id)).hasSize(1);
    }

    @Test
    @DisplayName("deleting a duplicate admits only that case: the application it repeats is untouched")
    void duplicateNeverTouchesTheRetainedCase() {
        String kept = fixture();
        String duplicate = fixture();
        String keptBefore = caseRow(kept);
        admit(duplicate, UUID.randomUUID());
        assertNothingWritten(kept, keptBefore);
        assertThat(requestCount(duplicate)).isOne();
    }

    @Test
    @DisplayName("the short-form deletion refuses a case with a deletion request")
    void shortFormDeletionRefusesARequestedCase() {
        String id = fixture();
        admit(id, UUID.randomUUID());
        assertThatThrownBy(() -> cases.removeDeedPoll(id)).isInstanceOf(ConflictError.class)
            .hasMessageContaining("deletion request");
        assertThat(caseRow(id)).contains(id);
    }

    @Test
    @DisplayName("simultaneous admissions under two keys write one request: same reason replays, other is 409")
    void simultaneousAdmissions() throws Exception {
        String sameReason = fixture();
        List<Object> outcomes = race(sameReason, "DUPLICATE", "DUPLICATE");
        assertThat(outcomes).hasSize(2).allSatisfy(outcome -> assertThat(outcome).isInstanceOf(Admission.class));
        assertThat(outcomes).extracting(outcome -> ((Admission) outcome).admitted())
            .containsExactlyInAnyOrder(true, false);
        assertThat(outcomes).extracting(outcome -> ((Admission) outcome).request().id()).containsOnly(
            requests.findByCase(sameReason).orElseThrow().id());
        assertThat(requestedEvents(sameReason)).hasSize(1);

        String otherReason = fixture();
        List<Object> mixed = race(otherReason, "DUPLICATE", "CREATED_IN_ERROR");
        assertThat(mixed).filteredOn(outcome -> outcome instanceof Admission).hasSize(1);
        assertThat(mixed).filteredOn(outcome -> outcome instanceof DeletionConflictError conflict
            && conflict.refusal() == DeletionRefusal.CONFLICTING_REASON).hasSize(1);
        assertThat(requestCount(otherReason)).isOne();
        assertThat(requestedEvents(otherReason)).hasSize(1);
    }

    /** Holds the case lock until both admissions are waiting on it, then lets them go in whichever order. */
    private List<Object> race(String caseId, String firstReason, String secondReason) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<Object>> futures = new ArrayList<>();
            new TransactionTemplate(transactions).executeWithoutResult(status -> {
                jdbc.queryForList("SELECT id FROM deedpoll_deedpoll WHERE id = ? FOR UPDATE", caseId);
                for (String reason : List.of(firstReason, secondReason)) {
                    futures.add(pool.submit(() -> {
                        try {
                            return deletions.request(DELIVERY_MANAGER, caseId,
                                                     new DeletionCommand(UUID.randomUUID(), reason, NOTE, MODIFIED));
                        } catch (RuntimeException refused) {
                            return refused;
                        }
                    }));
                }
                awaitLockWaiters(2);
            });
            List<Object> outcomes = new ArrayList<>();
            for (Future<Object> future : futures) {
                outcomes.add(future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            }
            return outcomes;
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * Counts every backend waiting on a lock, not the statement: the first FOR UPDATE waiter waits on the holder's
     * transaction id and the next on the tuple, and pg_stat_activity can still show the earlier statement's text.
     */
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
        throw new IllegalStateException("the admissions never waited on the case lock: " + jdbc.queryForList(
            "SELECT state, wait_event_type, wait_event FROM pg_stat_activity WHERE datname = current_database()"));
    }

    private void refuses(DeletionRefusal expected, Consumer<String> arrange) {
        String id = fixture();
        arrange.accept(id);
        String before = caseRow(id);
        assertThatThrownBy(() -> admit(id, UUID.randomUUID())).as(expected.name())
            .isInstanceOfSatisfying(DeletionConflictError.class, error -> {
                assertThat(error.refusal()).isEqualTo(expected);
                assertThat(error.getMessage()).isEqualTo(expected.message()).doesNotContain(id);
            });
        assertNothingWritten(id, before);
    }

    private void attempt(String caseId, String status, boolean reached, boolean provenNothing) {
        jdbc.update("""
            INSERT INTO deedpoll_ccdcreationattempt (id, "deedPollId", "operationId", status, "modifiedOn",
                "submissionMayHaveReached", "provenNothingCreated")
            VALUES (?, ?, ?, ?::"CcdCreationAttemptStatus", now(), ?, ?)
            """, UUID.randomUUID(), caseId, UUID.randomUUID(), status, reached, provenNothing);
    }

    private void notification(String caseId, LocalDateTime claimedUntil, boolean sendStarted) {
        jdbc.update("""
            INSERT INTO deedpoll_notification (id, "deedPollId", "requestId", template, "exclusionGroup", recipient,
                personalisation, state, attempts, "nextAttemptAt", "claimOwner", "sendStartedAt", "createdBy",
                "createdOn", "modifiedOn")
            VALUES (?, ?, ?, 'APPLICATION_REJECTED', 'REFERRAL_OUTCOME', 'applicant@example.com', '{}'::jsonb,
                    'Pending', 1, ?, ?, ?, 'Fixture', now(), now())
            """, UUID.randomUUID(), caseId, UUID.randomUUID(),
            Timestamp.valueOf(claimedUntil == null ? LocalDateTime.now(ZoneOffset.UTC) : claimedUntil),
            claimedUntil == null && !sendStarted ? null : UUID.randomUUID(),
            sendStarted ? Timestamp.valueOf(LocalDateTime.now(ZoneOffset.UTC)) : null);
    }
}
