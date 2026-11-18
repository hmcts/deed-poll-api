package uk.gov.hmcts.reform.deedpoll.domain.deletion;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import uk.gov.hmcts.reform.deedpoll.ccd.CcdProperties;
import uk.gov.hmcts.reform.deedpoll.ccd.DeedPollRetainAndDisposePolicy;
import uk.gov.hmcts.reform.deedpoll.config.AbstractPostgresContainerTest;
import uk.gov.hmcts.reform.deedpoll.domain.audit.AuditAction;
import uk.gov.hmcts.reform.deedpoll.domain.audit.AuditActor;
import uk.gov.hmcts.reform.deedpoll.domain.audit.AuditEntities;
import uk.gov.hmcts.reform.deedpoll.domain.audit.AuditEntry;
import uk.gov.hmcts.reform.deedpoll.domain.audit.AuditTrail;
import uk.gov.hmcts.reform.deedpoll.domain.audit.AuditValue;
import uk.gov.hmcts.reform.deedpoll.domain.auth.CurrentUser;
import uk.gov.hmcts.reform.deedpoll.domain.auth.Role;
import uk.gov.hmcts.reform.deedpoll.domain.deletion.ApplicationDeletionService.Admission;
import uk.gov.hmcts.reform.deedpoll.domain.deletion.ApplicationDeletionService.DeletionCommand;
import uk.gov.hmcts.reform.deedpoll.entity.AuditClock;
import uk.gov.hmcts.reform.deedpoll.repository.DeletionRequestRepository;
import uk.gov.hmcts.reform.deedpoll.repository.DeletionWorkRepository;
import uk.gov.hmcts.reform.deedpoll.storage.LocalFilesystemDocumentStore;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Local finalisation of an application deletion against real Postgres (plan 20261118-dm-application-deletion, Task 6):
 * a populated application is removed with every child, owned stage, binary and SDK copy, its audit values are disposed
 * and its events kept, one {@code CASE_DELETED} is recorded under the original Delivery Manager, and the request
 * becomes a tombstone. Refusals and failures roll it all back and leave the request resumable.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class DeletionFinalisationTest extends AbstractPostgresContainerTest {

    private static final CurrentUser DELIVERY_MANAGER =
        CurrentUser.currentUser(List.of(Role.DELIVERY_MANAGER), "Dee Manager", "finalisation-dm");
    private static final LocalDateTime MODIFIED = LocalDateTime.of(2026, 11, 18, 9, 30, 15, 250_000_000);
    private static final String NOTE = "Duplicate of an application keyed in the same morning";
    private static final Duration POLL = Duration.ofHours(1);

    @Autowired
    private ApplicationDeletionService deletions;

    @Autowired
    private DeletionRequestRepository requests;

    @Autowired
    private DeletionWorkRepository work;

    @Autowired
    private DeletionFinalisation finalisation;

    @Autowired
    private DeedPollRetainAndDisposePolicy policy;

    @Autowired
    private AuditTrail audit;

    @Autowired
    private CcdProperties ccd;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactions;

    @TempDir
    private Path documents;

    private DeletionCleanup cleanup;
    private final List<String> fixtures = new ArrayList<>();
    private final List<Long> ccdCases = new ArrayList<>();
    private final List<UUID> masters = new ArrayList<>();

    @BeforeEach
    void setUp() {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("store", new LocalFilesystemDocumentStore(documents.toString()));
        cleanup = new DeletionCleanup(work, requests, beans.getBeanProvider(LocalFilesystemDocumentStore.class),
            transactions, new DeletionCleanupProperties(false, Duration.ofMinutes(5), 10, Duration.ofMinutes(5), 3,
                                                        Duration.ofMinutes(1), POLL), finalisation, AuditClock::now);
        jdbc.update("""
            update deedpoll_deletion_request set next_attempt_at = next_attempt_at + interval '100 years'
             where status in ('Pending', 'InProgress', 'AwaitingDisposal')
            """);
    }

    @AfterEach
    void cleanUp() {
        jdbc.execute("DROP TRIGGER IF EXISTS finalisation_test_failure ON deedpoll_audit_event");
        jdbc.execute("DROP TRIGGER IF EXISTS finalisation_test_refusal ON deedpoll_deedpoll");
        jdbc.execute("DROP FUNCTION IF EXISTS finalisation_test_failure()");
        jdbc.execute("DROP FUNCTION IF EXISTS finalisation_test_refusal()");
        for (String id : fixtures) {
            jdbc.update("DELETE FROM deedpoll_deletion_request WHERE case_id = ?", id);
            jdbc.update("DELETE FROM deedpoll_ownedstage WHERE \"ownerId\" = ? OR \"caseId\" = ?", id, id);
            jdbc.update("DELETE FROM deedpoll_deedpoll WHERE id = ?", id);
        }
        for (Long reference : ccdCases) {
            jdbc.update("DELETE FROM ccd.case_data WHERE reference = ?", reference);
        }
        for (UUID master : masters) {
            jdbc.update("DELETE FROM deedpoll_master_email WHERE master_id = ?", master);
            jdbc.update("DELETE FROM deedpoll_master WHERE id = ?", master);
        }
    }

    // --- the journeys -----------------------------------------------------------------------------------------------

    @Test
    @DisplayName("a populated, migrated, unlinked application is removed whole; its events and the other case survive")
    void populatedUnlinked() throws IOException {
        String id = populated(true);
        String survivor = populated(false);
        String key = localDocument(id);
        final String stage = ownedStage(id);
        UUID master = master();
        UUID referral = referral(id, master);
        UUID kept = referral(survivor, master);
        final UUID email = masterEmail(master, referral, kept);
        final List<Map<String, Object>> counters = jdbc.queryForList("SELECT * FROM deedpoll_referencecounter");
        final DeletionRequest request = admit(id, "DUPLICATE");

        assertThat(process()).hasValueSatisfying(made -> assertThat(made.status()).isEqualTo(DeletionStatus.Completed));

        assertThat(documents.resolve(key)).doesNotExist();
        for (String table : List.of("deedpoll_deedpoll\" WHERE id", "deedpoll_notes\" WHERE \"deedPollId\"",
                                    "deedpoll_witness\" WHERE \"deedPollId\"",
                                    "deedpoll_supportingdocument\" WHERE \"deedPollId\"",
                                    "deedpoll_referral\" WHERE \"deedPollId\"",
                                    "deedpoll_notification\" WHERE \"deedPollId\"",
                                    "deedpoll_ccdcreationattempt\" WHERE \"deedPollId\"")) {
            assertThat(count("SELECT count(*) FROM \"" + table + " = ?", id)).as(table).isZero();
        }
        assertThat(count("SELECT count(*) FROM deedpoll_ownedstage WHERE id = ?", stage)).isZero();
        assertThat(count("SELECT count(*) FROM deedpoll_audit_event_value v JOIN deedpoll_audit_event e "
                             + "ON e.id = v.event_id WHERE e.case_id = ?", id)).as("values disposed").isZero();
        assertThat(jdbc.queryForList("SELECT action FROM deedpoll_audit_event WHERE case_id = ? ORDER BY id",
                                     String.class, id))
            .as("every event kept, and one completion").containsExactly("CASE_UPDATED", "CASE_DELETION_REQUESTED",
                                                                        "CASE_DELETED");
        Map<String, Object> deleted = jdbc.queryForMap("""
            SELECT actor_id, actor_name, fields, detail::text AS detail FROM deedpoll_audit_event
             WHERE case_id = ? AND action = 'CASE_DELETED'
            """, id);
        assertThat(deleted).containsEntry("actor_id", DELIVERY_MANAGER.userId())
            .containsEntry("actor_name", "Dee Manager");
        assertThat((String) deleted.get("detail")).contains("\"deletionReason\": \"DUPLICATE\"")
            .contains("\"deletionRequestId\": \"" + request.id() + "\"").doesNotContain(NOTE);
        assertThat(count("SELECT count(*) FROM deedpoll_audit_event_value v JOIN deedpoll_audit_event e "
                             + "ON e.id = v.event_id WHERE e.case_id = ?", survivor)).as("not another case's").isOne();

        assertThat(count("SELECT count(*) FROM deedpoll_deedpoll WHERE id = ?", survivor)).isOne();
        assertThat(count("SELECT count(*) FROM deedpoll_notes WHERE \"deedPollId\" = ?", survivor)).isOne();
        assertThat(count("SELECT count(*) FROM deedpoll_referral WHERE id = ?", kept)).isOne();
        assertThat(count("SELECT count(*) FROM deedpoll_master_email WHERE id = ?", email))
            .as("the shared Master email is kept").isOne();
        assertThat(jdbc.queryForList("SELECT * FROM deedpoll_referencecounter")).as("no counter moves")
            .isEqualTo(counters);

        DeletionRequest tombstone = reread(request);
        assertThat(tombstone.status()).isEqualTo(DeletionStatus.Completed);
        assertThat(tombstone.completedAt()).isNotNull();
        assertThat(tombstone.reason()).isEqualTo(DeletionReason.DUPLICATE);
        assertThat(tombstone.note().value()).isEqualTo(NOTE);
        assertThat(tombstone.requestedBySubject()).isEqualTo(DELIVERY_MANAGER.userId());
        assertThat(tombstone.owner()).isNull();
        assertThat(tombstone.failure()).isNull();
        assertThat(jdbc.queryForList("""
            SELECT kind || ':' || state || ':' || outcome || ':' || coalesce(locator, '-') || ':'
                   || coalesce(item_id, '-') FROM deedpoll_deletion_step WHERE request_id = ?
            """, String.class, request.id()))
            .as("outcome codes only").containsExactlyInAnyOrder("LOCAL_DOCUMENT:Done:DELETED:-:-",
                                                                "OWNED_STAGE:Done:DELETED:-:-");
    }

    @Test
    @DisplayName("a linked case completes only after Retain and Dispose, with the SDK's copy gone")
    void linked() throws IOException {
        String id = populated(false);
        final long reference = link(id);
        localDocument(id);
        cdamDocument(id);
        final DeletionRequest request = admit(id, "CREATED_IN_ERROR");

        assertThat(process()).hasValueSatisfying(made ->
            assertThat(made.status()).isEqualTo(DeletionStatus.InProgress));
        assertThat(count("SELECT count(*) FROM deedpoll_deedpoll WHERE id = ?", id)).as("not before CCD").isOne();

        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            policy.dispose(reference);
            jdbc.update("DELETE FROM ccd.case_data WHERE reference = ?", reference);
        });
        assertThat(process()).hasValueSatisfying(made -> assertThat(made.status()).isEqualTo(DeletionStatus.Completed));

        assertThat(count("SELECT count(*) FROM deedpoll_deedpoll WHERE id = ?", id)).isZero();
        assertThat(count("SELECT count(*) FROM ccd.case_data WHERE reference = ?", reference)).isZero();
        assertThat(reread(request).ccdCaseReference()).as("the tombstone names the CCD case")
            .isEqualTo(String.valueOf(reference));
        assertThat(jdbc.queryForList("SELECT kind || ':' || outcome FROM deedpoll_deletion_step WHERE request_id = ?",
                                     String.class, request.id()))
            .containsExactlyInAnyOrder("LOCAL_DOCUMENT:DELETED", "CDAM_DOCUMENT:DISPOSED_BY_CCD",
                                       "CCD_DISPOSAL:DISPOSED_BY_CCD");
        assertThat(count("SELECT count(*) FROM deedpoll_audit_event WHERE case_id = ? AND action = 'CASE_DELETED'",
                         id)).isOne();
    }

    @Test
    @DisplayName("the SDK still holding its copy refuses finalisation: nothing local is removed")
    void ccdCopyPresent() {
        String id = populated(false);
        final long reference = link(id);
        DeletionRequest request = admit(id, "CREATED_IN_ERROR");
        // Recorded done without the SDK's delete: only a bug or a hand edit could leave this.
        jdbc.update("""
            UPDATE deedpoll_deletion_step SET state = 'Done', outcome = 'DISPOSED_BY_CCD', completed_at = now()
             WHERE request_id = ?
            """, request.id());

        assertThat(process()).hasValueSatisfying(made ->
            assertThat(made.failure()).isEqualTo(DeletionFailure.CCD_COPY_PRESENT));

        assertThat(reread(request).status()).isEqualTo(DeletionStatus.NeedsAttention);
        assertThat(count("SELECT count(*) FROM deedpoll_deedpoll WHERE id = ?", id)).isOne();
        assertThat(count("SELECT count(*) FROM ccd.case_data WHERE reference = ?", reference)).isOne();
        assertNoCompletion(id);
        assertThat(count("SELECT count(*) FROM deedpoll_audit_event_value v JOIN deedpoll_audit_event e "
                             + "ON e.id = v.event_id WHERE e.case_id = ?", id)).as("values kept").isOne();
    }

    @Test
    @DisplayName("an audit failure rolls finalisation back; a retry after external completion records one completion")
    void auditFailureThenRetry() throws IOException {
        String id = populated(false);
        final String key = localDocument(id);
        final DeletionRequest request = admit(id, "CREATED_IN_ERROR");
        jdbc.execute("""
            CREATE FUNCTION finalisation_test_failure() RETURNS trigger LANGUAGE plpgsql AS $$
            BEGIN
                IF NEW.action = 'CASE_DELETED' THEN
                    RAISE EXCEPTION 'synthetic audit failure';
                END IF;
                RETURN NEW;
            END;
            $$
            """);
        jdbc.execute("""
            CREATE TRIGGER finalisation_test_failure BEFORE INSERT ON deedpoll_audit_event
                FOR EACH ROW EXECUTE FUNCTION finalisation_test_failure()
            """);

        assertThat(process()).hasValueSatisfying(made -> {
            assertThat(made.status()).isEqualTo(DeletionStatus.InProgress);
            assertThat(made.failure()).isNull();
        });
        assertThat(documents.resolve(key)).as("the external step stays done").doesNotExist();
        assertThat(count("SELECT count(*) FROM deedpoll_deedpoll WHERE id = ?", id)).isOne();
        assertThat(count("SELECT count(*) FROM deedpoll_audit_event_value v JOIN deedpoll_audit_event e "
                             + "ON e.id = v.event_id WHERE e.case_id = ?", id)).as("values kept").isOne();
        assertNoCompletion(id);
        assertThat(reread(request).status()).isEqualTo(DeletionStatus.InProgress);
        assertThat(jdbc.queryForObject("SELECT locator FROM deedpoll_deletion_step WHERE request_id = ?",
                                       String.class, request.id())).as("the inventory is kept").isEqualTo(key);

        jdbc.execute("DROP TRIGGER finalisation_test_failure ON deedpoll_audit_event");
        assertThat(process()).as("backed off").isEmpty();
        due(request);
        assertThat(process()).hasValueSatisfying(made -> assertThat(made.status()).isEqualTo(DeletionStatus.Completed));
        assertThat(count("SELECT count(*) FROM deedpoll_audit_event WHERE case_id = ? AND action = 'CASE_DELETED'",
                         id)).isOne();
        assertThat(jdbc.queryForObject("SELECT outcome FROM deedpoll_deletion_step WHERE request_id = ?",
                                       String.class, request.id())).as("not redone").isEqualTo("DELETED");
    }

    @Test
    @DisplayName("the database refusing the delete is NeedsAttention, with nothing removed")
    void databaseRefusal() {
        String id = populated(false);
        final DeletionRequest request = admit(id, "CREATED_IN_ERROR");
        jdbc.execute("""
            CREATE FUNCTION finalisation_test_refusal() RETURNS trigger LANGUAGE plpgsql AS $$
            BEGIN
                RAISE EXCEPTION 'synthetic restriction' USING ERRCODE = '23503';
            END;
            $$
            """);
        jdbc.execute("""
            CREATE TRIGGER finalisation_test_refusal BEFORE DELETE ON deedpoll_deedpoll
                FOR EACH ROW EXECUTE FUNCTION finalisation_test_refusal()
            """);

        assertThat(process()).hasValueSatisfying(made ->
            assertThat(made.failure()).isEqualTo(DeletionFailure.LOCAL_FINALISATION_REFUSED));
        assertThat(reread(request).status()).isEqualTo(DeletionStatus.NeedsAttention);
        assertThat(reread(request).failure()).isEqualTo("LOCAL_FINALISATION_REFUSED");
        assertThat(count("SELECT count(*) FROM deedpoll_deedpoll WHERE id = ?", id)).isOne();
        assertNoCompletion(id);
    }

    @Test
    @DisplayName("a worker whose lease lapsed finalises nothing; the one that took over completes it once")
    void leaseLost() {
        String id = populated(false);
        DeletionRequest request = admit(id, "CREATED_IN_ERROR");
        UUID first = UUID.randomUUID();
        assertThat(cleanup.claim(first)).contains(request.id());
        jdbc.update("UPDATE deedpoll_deletion_request SET lease_until = ? WHERE id = ?",
                    Timestamp.valueOf(AuditClock.now().minusSeconds(1)), request.id());
        UUID second = UUID.randomUUID();
        assertThat(cleanup.claim(second)).contains(request.id());

        assertThat(cleanup.process(request.id(), first)).isEmpty();
        assertThat(count("SELECT count(*) FROM deedpoll_deedpoll WHERE id = ?", id)).isOne();
        assertThat(cleanup.process(request.id(), second)).hasValueSatisfying(made ->
            assertThat(made.status()).isEqualTo(DeletionStatus.Completed));
        assertThat(cleanup.process(request.id(), second)).as("a completed request is held by nobody").isEmpty();
        assertThat(count("SELECT count(*) FROM deedpoll_audit_event WHERE case_id = ? AND action = 'CASE_DELETED'",
                         id)).isOne();
    }

    @Test
    @DisplayName("a replay of the same key after completion answers the tombstone and deletes nothing more")
    void replayAfterCompletion() {
        String id = populated(false);
        UUID key = UUID.randomUUID();
        DeletionRequest request = deletions.request(DELIVERY_MANAGER, id,
            new DeletionCommand(key, "CREATED_IN_ERROR", NOTE, MODIFIED)).request();
        assertThat(process()).isPresent();

        Admission replay = deletions.request(DELIVERY_MANAGER, id,
            new DeletionCommand(key, "CREATED_IN_ERROR", NOTE, MODIFIED));

        assertThat(replay.admitted()).isFalse();
        assertThat(replay.request().id()).isEqualTo(request.id());
        assertThat(replay.request().status()).isEqualTo(DeletionStatus.Completed);
        assertThat(count("SELECT count(*) FROM deedpoll_audit_event WHERE case_id = ?", id)).isEqualTo(3);
    }

    // --- fixtures ---------------------------------------------------------------------------------------------------

    private Optional<DeletionProgress.Decision> process() {
        UUID owner = UUID.randomUUID();
        return cleanup.claim(owner).flatMap(claimed -> cleanup.process(claimed, owner));
    }

    private void assertNoCompletion(String caseId) {
        assertThat(count("SELECT count(*) FROM deedpoll_audit_event WHERE case_id = ? AND action = 'CASE_DELETED'",
                         caseId)).isZero();
    }

    private DeletionRequest admit(String caseId, String reason) {
        return deletions.request(DELIVERY_MANAGER, caseId,
                                 new DeletionCommand(UUID.randomUUID(), reason, NOTE, MODIFIED)).request();
    }

    private DeletionRequest reread(DeletionRequest request) {
        return requests.findById(request.id()).orElseThrow();
    }

    private void due(DeletionRequest request) {
        jdbc.update("UPDATE deedpoll_deletion_request SET next_attempt_at = ? WHERE id = ?",
                    Timestamp.valueOf(AuditClock.now().minusSeconds(1)), request.id());
    }

    private int count(String sql, Object... arguments) {
        return jdbc.queryForObject(sql, Integer.class, arguments);
    }

    /**
     * A case with a note, a witness, an unclaimed email and a settled CCD creation attempt, and one audit event
     * carrying a value row; {@code migrated} as the Power App import marked its records.
     */
    private String populated(boolean migrated) {
        String id = UUID.randomUUID().toString();
        fixtures.add(id);
        jdbc.update("""
            INSERT INTO deedpoll_deedpoll (id, "modifiedOn", type, state, "displayReference", "migratedRecord")
            VALUES (?, ?, 'Adult'::"DeedPollType", 'Enroled', ?, ?)
            """, id, Timestamp.valueOf(MODIFIED), "final-" + id, migrated);
        jdbc.update("""
            INSERT INTO deedpoll_notes (id, "deedPollId", note, "modifiedOn") VALUES (?, ?, 'A note', now())
            """, UUID.randomUUID().toString(), id);
        jdbc.update("""
            INSERT INTO deedpoll_witness
                (id, "deedPollId", "witnessName", "witnessAddress", "witnessOccupation", declarant, "modifiedOn")
            VALUES (?, ?, 'Witness', 'Address', 'Occupation', false, now())
            """, UUID.randomUUID().toString(), id);
        jdbc.update("""
            INSERT INTO deedpoll_notification (id, "deedPollId", "requestId", template, "exclusionGroup", recipient,
                personalisation, state, attempts, "nextAttemptAt", "createdBy", "createdOn", "modifiedOn")
            VALUES (?, ?, ?, 'APPLICATION_REJECTED', 'REFERRAL_OUTCOME', 'applicant@example.com', '{}'::jsonb,
                    'Pending', 0, ?, 'Fixture', now(), now())
            """, UUID.randomUUID(), id, UUID.randomUUID(), Timestamp.valueOf(LocalDateTime.now(ZoneOffset.UTC)));
        jdbc.update("""
            INSERT INTO deedpoll_ccdcreationattempt (id, "deedPollId", "operationId", status, "modifiedOn",
                "submissionMayHaveReached", "provenNothingCreated")
            VALUES (?, ?, ?, 'Pending'::"CcdCreationAttemptStatus", now(), false, false)
            """, UUID.randomUUID(), id, UUID.randomUUID());
        new TransactionTemplate(transactions).executeWithoutResult(status -> audit.record(
            AuditEntry.of(AuditAction.CASE_UPDATED, AuditActor.of(DELIVERY_MANAGER), AuditEntities.CASE, id, id)
                .withFields(List.of("newForename")),
            List.of(AuditValue.of("newForename", "Old", "New"))));
        return id;
    }

    private String localDocument(String caseId) throws IOException {
        String key = UUID.randomUUID().toString();
        jdbc.update("""
            INSERT INTO deedpoll_supportingdocument (id, "deedPollId", direction, name, "documentTypeId",
                "storageKey", "modifiedOn", "storageState")
            VALUES (?, ?, 'Incoming', 'Evidence', (SELECT id FROM deedpoll_supportingdocumenttype ORDER BY id LIMIT 1),
                    ?, now(), 'Local')
            """, UUID.randomUUID().toString(), caseId, key);
        Files.writeString(documents.resolve(key), "applicant evidence");
        return key;
    }

    private void cdamDocument(String caseId) {
        jdbc.update("""
            INSERT INTO deedpoll_supportingdocument (id, "deedPollId", direction, name, "documentTypeId",
                "modifiedOn", "storageState", "cdamDocumentId")
            VALUES (?, ?, 'Incoming', 'Evidence', (SELECT id FROM deedpoll_supportingdocumenttype ORDER BY id LIMIT 1),
                    now(), 'Attached', ?)
            """, UUID.randomUUID().toString(), caseId, UUID.randomUUID());
    }

    /** An owned stage that was consumed by a promotion onto the case: no bytes left, so admission lists it. */
    private String ownedStage(String caseId) {
        String id = UUID.randomUUID().toString();
        jdbc.update("""
            INSERT INTO deedpoll_ownedstage
              (id, "ownerKind", "ownerId", subject, "operationId", "originalName", "mimeType", "sizeBytes",
               checksum, "createdOn", "expiresOn", state)
            VALUES (?, 'RECORD', ?, 'synthetic-subject', ?, 'synthetic.pdf', 'application/pdf', 3,
                    encode(sha256(decode('010203', 'hex')), 'hex'),
                    CURRENT_TIMESTAMP - interval '2 hours', CURRENT_TIMESTAMP + interval '1 hour', 'Removed')
            """, id, caseId, UUID.randomUUID());
        return id;
    }

    private long link(String caseId) {
        long reference = 1_000_000_000_000_000L + Math.floorMod(UUID.randomUUID().getMostSignificantBits(),
                                                                  8_999_999_999_999_999L);
        ccdCases.add(reference);
        jdbc.update("UPDATE deedpoll_deedpoll SET \"ccdCaseReference\" = ? WHERE id = ?", String.valueOf(reference),
                    caseId);
        jdbc.update("""
            INSERT INTO ccd.case_data (reference, id, security_classification, jurisdiction, case_type_id, state, data)
            VALUES (?, ?, 'PUBLIC', ?, ?, 'Registered', '{}')
            """, reference, reference, ccd.jurisdictionId(), ccd.caseTypeId());
        return reference;
    }

    private UUID master() {
        UUID id = UUID.randomUUID();
        masters.add(id);
        jdbc.update("""
            INSERT INTO deedpoll_master (id, name, email, created_at, created_by_id, created_by_name)
            VALUES (?, 'Master Final', ?, now(), 'fixture', 'Fixture')
            """, id, id + "@example.com");
        return id;
    }

    private UUID referral(String caseId, UUID master) {
        UUID id = UUID.randomUUID();
        Timestamp assigned = Timestamp.valueOf(LocalDateTime.now(ZoneOffset.UTC).minusDays(1));
        jdbc.update("""
            INSERT INTO deedpoll_referral (id, "deedPollId", "state", "urgency", "claimedBy", "claimedById",
                "claimedAt", "assignedMasterId", "assignedAt", "modifiedOn")
            VALUES (?, ?, 'Claimed'::"ReferralState", 'Normal'::"Urgency", 'Master Final', 'judge', ?, ?, ?, now())
            """, id, caseId, assigned, master, assigned);
        return id;
    }

    private UUID masterEmail(UUID master, UUID... referrals) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO deedpoll_master_email (id, master_id, kind, period_key, referral_ids, state, created_at)
            VALUES (?, ?, 'REMINDER', ?, ?, 'Sent', ?)
            """, id, master, "final-" + id, referrals, LocalDateTime.now());
        return id;
    }
}
