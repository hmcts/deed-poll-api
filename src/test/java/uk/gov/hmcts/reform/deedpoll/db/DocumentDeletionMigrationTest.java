package uk.gov.hmcts.reform.deedpoll.db;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import uk.gov.hmcts.reform.deedpoll.config.AbstractPostgresContainerTest;
import uk.gov.hmcts.reform.deedpoll.domain.deletion.DeletionTestDocumentType;
import uk.gov.hmcts.reform.deedpoll.domain.documents.deletion.DocumentDeletionFingerprint;
import uk.gov.hmcts.reform.deedpoll.domain.documents.deletion.DocumentDeletionReason;
import uk.gov.hmcts.reform.deedpoll.domain.documents.deletion.DocumentDeletionRequest;
import uk.gov.hmcts.reform.deedpoll.domain.documents.deletion.DocumentDeletionStatus;
import uk.gov.hmcts.reform.deedpoll.domain.documents.deletion.DocumentDeletionStep;
import uk.gov.hmcts.reform.deedpoll.domain.documents.deletion.DocumentDeletionStepKind;
import uk.gov.hmcts.reform.deedpoll.domain.documents.deletion.DocumentDeletionStepState;
import uk.gov.hmcts.reform.deedpoll.entity.DeedPollSupportingDocument;
import uk.gov.hmcts.reform.deedpoll.repository.DeedPollSupportingDocumentRepository;
import uk.gov.hmcts.reform.deedpoll.repository.DocumentDeletionRequestRepository;
import uk.gov.hmcts.reform.deedpoll.repository.DocumentDeletionRequestRepository.DocumentRecord;
import uk.gov.hmcts.reform.deedpoll.repository.DocumentDeletionRequestRepository.NewDocumentDeletionRequest;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code V51__document_deletion_requests.sql} and {@link DocumentDeletionRequestRepository}, against real Postgres:
 * the request's constraints and uniqueness, its immutability and receipt, the document's version, and the fence.
 *
 * <p>Not transactional: each statement commits on its own (so a deferred trigger fires at the statement's end), and
 * a commit-time refusal is observed through a {@link TransactionTemplate}. Every test mints its own cases and removes
 * them afterwards; the requests and steps cascade with them.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class DocumentDeletionMigrationTest extends AbstractPostgresContainerTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 26, 9, 30, 15, 250_000_000);
    private static final DocumentDeletionReason REASON = DocumentDeletionReason.parse("Uploaded in error");
    private static final String ASTRAL = new String(Character.toChars(0x1F4C4));
    private static final String FENCE_REFUSAL = "The document is being deleted";

    @Autowired
    private DocumentDeletionRequestRepository requests;

    @Autowired
    private DeedPollSupportingDocumentRepository documents;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactions;

    private final List<String> cases = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        for (String id : cases) {
            jdbc.update("DELETE FROM deedpoll_deletion_request WHERE case_id = ?", id);
            jdbc.update("DELETE FROM deedpoll_deedpoll WHERE id = ?", id);
        }
        DeletionTestDocumentType.removeIfUnused(jdbc);
    }

    // --- fixtures ---------------------------------------------------------------------------------------------

    private String newCase() {
        String id = UUID.randomUUID().toString();
        cases.add(id);
        jdbc.update("""
            INSERT INTO deedpoll_deedpoll (id, "modifiedOn", type, state, "displayReference", "dateReceived")
            VALUES (?, now(), 'Adult'::"DeedPollType", 'Enroled', ?, now())
            """, id, "docdel-" + id);
        return id;
    }

    private String document(String caseId) {
        String id = UUID.randomUUID().toString();
        jdbc.update("""
            INSERT INTO deedpoll_supportingdocument (id, "deedPollId", direction, name, "documentTypeId",
                "storageKey", "modifiedOn", "storageState", "storageUploadAttempts")
            VALUES (?, ?, 'Incoming', 'Evidence', ?, ?, now(), 'Local', 0)
            """, id, caseId, DeletionTestDocumentType.ensure(jdbc), "files/" + id);
        return id;
    }

    private static NewDocumentDeletionRequest request(String caseId, String documentId, UUID key) {
        return new NewDocumentDeletionRequest(UUID.randomUUID(), caseId, documentId, key, REASON, "Evidence",
                                              DocumentDeletionFingerprint.of(documentId, 1, REASON), 1,
                                              "staff-subject", "Casey Worker", NOW);
    }

    private NewDocumentDeletionRequest admitted(String caseId, String documentId) {
        NewDocumentDeletionRequest request = request(caseId, documentId, UUID.randomUUID());
        assertThat(requests.insert(request)).isTrue();
        return request;
    }

    private int insertRaw(String caseId, String reason, String name, String status) {
        return jdbc.update("""
            INSERT INTO deedpoll_document_deletion_request (id, case_id, document_id, idempotency_key, reason,
                document_name, fingerprint, expected_document_version, requested_by_subject, requested_by_name,
                requested_at, status)
            VALUES (?, ?, ?, ?, ?, ?, ?, 1, 'staff-subject', 'Casey Worker', ?, ?)
            """, UUID.randomUUID(), caseId, UUID.randomUUID().toString(), UUID.randomUUID(), reason, name,
            "0".repeat(64), Timestamp.valueOf(NOW), status);
    }

    private long version(String documentId) {
        return jdbc.queryForObject("SELECT \"version\" FROM deedpoll_supportingdocument WHERE id = ?", Long.class,
                                   documentId);
    }

    private TransactionTemplate transaction() {
        return new TransactionTemplate(transactions);
    }

    // --- the request ------------------------------------------------------------------------------------------

    @Test
    @DisplayName("a request round-trips InProgress and due now, found by id, key and document, and prints no words")
    void roundTrips() {
        String caseId = newCase();
        String documentId = document(caseId);
        NewDocumentDeletionRequest admitted = admitted(caseId, documentId);

        DocumentDeletionRequest stored = requests.findByCaseAndId(caseId, admitted.id()).orElseThrow();
        assertThat(stored.documentId()).isEqualTo(documentId);
        assertThat(stored.idempotencyKey()).isEqualTo(admitted.idempotencyKey());
        assertThat(stored.reason()).contains(REASON);
        assertThat(stored.documentName()).isEqualTo("Evidence");
        assertThat(stored.fingerprint()).isEqualTo(admitted.fingerprint());
        assertThat(stored.expectedDocumentVersion()).isOne();
        assertThat(stored.requestedBySubject()).isEqualTo("staff-subject");
        assertThat(stored.requestedByName()).isEqualTo("Casey Worker");
        assertThat(stored.requestedAt()).isEqualTo(NOW);
        assertThat(stored.status()).isEqualTo(DocumentDeletionStatus.InProgress);
        assertThat(stored.nextAttemptAt()).isEqualTo(NOW);
        assertThat(stored.attempts()).isZero();
        assertThat(stored.owner()).isNull();
        assertThat(stored.leaseUntil()).isNull();
        assertThat(stored.completedAt()).isNull();
        assertThat(stored.failure()).isNull();
        assertThat(requests.findByCaseAndKey(caseId, admitted.idempotencyKey())).contains(stored);
        assertThat(requests.findByDocument(documentId)).contains(stored);

        // Case-scoped: another case's id never answers for this request.
        assertThat(requests.findByCaseAndId(newCase(), admitted.id())).isEmpty();
        assertThat(requests.findByCaseAndKey(newCase(), admitted.idempotencyKey())).isEmpty();

        assertThat(stored.toString()).doesNotContain("Uploaded", "Evidence", "staff-subject", "Casey");
        assertThat(admitted.toString()).doesNotContain("Uploaded", "Evidence", "staff-subject", "Casey");
    }

    @Test
    @DisplayName("one request per document whatever its key, and one per key within a case; another case may reuse it")
    void uniqueness() {
        String caseId = newCase();
        String documentId = document(caseId);
        UUID key = UUID.randomUUID();
        NewDocumentDeletionRequest first = request(caseId, documentId, key);
        assertThat(requests.insert(first)).isTrue();

        assertThat(requests.insert(request(caseId, documentId, key))).isFalse();
        assertThat(requests.insert(request(caseId, documentId, UUID.randomUUID()))).isFalse();
        assertThat(requests.insert(request(caseId, document(caseId), key))).isFalse();
        assertThat(requests.findByDocument(documentId).orElseThrow().id()).isEqualTo(first.id());

        String other = newCase();
        assertThat(requests.insert(request(other, document(other), key))).isTrue();

        // Raised, not swallowed, by a statement that names no conflict.
        assertThatThrownBy(() -> jdbc.update("""
            INSERT INTO deedpoll_document_deletion_request (id, case_id, document_id, idempotency_key, reason,
                document_name, fingerprint, expected_document_version, requested_by_subject, requested_by_name,
                requested_at, status)
            VALUES (?, ?, ?, ?, 'Duplicate', 'Evidence', ?, 1, 's', 'n', now(), 'InProgress')
            """, UUID.randomUUID(), caseId, documentId, UUID.randomUUID(), "0".repeat(64)))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("the database checks the reason as DocumentDeletionReason does, as far as Postgres can")
    void reasonConstraint() {
        String caseId = newCase();
        String[] refused = {"", " Duplicate", "Duplicate ", "a".repeat(501), "Two\nlines", "A\ttab", "C1\u0085",
            "Delete\u007F", "Line\u2028separator", "Right\u202Eoverride", "Isolate\u2066here",
            "Mark\u200Fright", "Arabic\u061Cmark"};
        for (String reason : refused) {
            assertThatThrownBy(() -> insertRaw(caseId, reason, "Evidence", "InProgress"))
                .as(reason.length() + " characters").isInstanceOf(DataIntegrityViolationException.class);
        }
        assertThat(insertRaw(caseId, "x", "Evidence", "InProgress")).isOne();
        assertThat(insertRaw(caseId, ASTRAL.repeat(500), "Evidence", "InProgress")).isOne();
        String text = "Caf\u00E9 <b>&amp;</b> \u65E5\u672C"; // escaped: non-ASCII text
        assertThat(insertRaw(caseId, text, "Evidence", "InProgress")).isOne();
        // Every value the parser accepts is one the database accepts.
        assertThat(insertRaw(caseId, DocumentDeletionReason.parse(" \u3000Duplicate ").value(), "Evidence",
                             "InProgress")).isOne();
    }

    @Test
    @DisplayName("an open request holds its reason and name; the status and failure vocabularies are closed")
    void heldAndClosedVocabularies() {
        String caseId = newCase();
        assertThatThrownBy(() -> insertRaw(caseId, null, "Evidence", "InProgress"))
            .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("held");
        assertThatThrownBy(() -> insertRaw(caseId, "Duplicate", null, "NeedsAttention"))
            .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("held");
        assertThatThrownBy(() -> insertRaw(caseId, "Duplicate", "Evidence", "Pending"))
            .isInstanceOf(DataIntegrityViolationException.class);
        // Completed needs its completion time, which the raw insert does not give.
        assertThatThrownBy(() -> insertRaw(caseId, null, null, "Completed"))
            .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("completion");
        assertThat(insertRaw(caseId, "Duplicate", "Evidence", "NeedsAttention")).isOne();

        NewDocumentDeletionRequest admitted = admitted(caseId, document(caseId));
        assertThatThrownBy(() -> jdbc.update(
            "UPDATE deedpoll_document_deletion_request SET failure = 'not a code', status = 'NeedsAttention' "
                + "WHERE id = ?", admitted.id())).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(
            "UPDATE deedpoll_document_deletion_request SET failure = 'CDAM_PERMISSION' WHERE id = ?", admitted.id()))
            .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("failure_status");
        assertThatThrownBy(() -> jdbc.update(
            "UPDATE deedpoll_document_deletion_request SET owner = gen_random_uuid() WHERE id = ?", admitted.id()))
            .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("claim");
        assertThat(jdbc.update("""
            UPDATE deedpoll_document_deletion_request SET status = 'NeedsAttention', failure = 'CDAM_PERMISSION',
                   attempts = attempts + 1 WHERE id = ?
            """, admitted.id())).isOne();
    }

    @Test
    @DisplayName("admission's half is immutable, the reason stays until completion, and a receipt is frozen")
    void immutabilityAndReceipt() {
        String caseId = newCase();
        NewDocumentDeletionRequest admitted = admitted(caseId, document(caseId));
        UUID ccd = UUID.randomUUID();
        UUID cdam = UUID.randomUUID();
        requests.insertStep(ccd, admitted.id(), DocumentDeletionStepKind.CCD_REFERENCE, "1234567890123456");
        requests.insertStep(cdam, admitted.id(), DocumentDeletionStepKind.CDAM_BINARY, UUID.randomUUID().toString());

        for (String change : new String[] {"document_id = 'another'", "idempotency_key = gen_random_uuid()",
            "fingerprint = repeat('b', 64)", "expected_document_version = 2", "requested_by_subject = 'other'",
            "requested_by_name = 'Somebody Else'", "requested_at = now()", "reason = 'Another reason'",
            "document_name = 'Other'", "reason = null, document_name = null"}) {
            assertThatThrownBy(() -> jdbc.update(
                "UPDATE deedpoll_document_deletion_request SET " + change + " WHERE id = ?", admitted.id()))
                .as(change).hasStackTraceContaining("cannot be rewritten");
        }

        // A step left unconfirmed: no receipt, and nothing cleared.
        assertThat(requests.complete(admitted.id(), NOW.plusMinutes(1))).isFalse();
        jdbc.update("UPDATE deedpoll_document_deletion_step SET state = 'Done', completed_at = now() WHERE id = ?",
                    ccd);
        assertThat(requests.complete(admitted.id(), NOW.plusMinutes(1))).isFalse();
        assertThat(requests.findByCaseAndId(caseId, admitted.id()).orElseThrow().reason()).contains(REASON);
        assertThat(requests.stepsOf(admitted.id())).extracting(DocumentDeletionStep::locator).doesNotContainNull();

        jdbc.update("UPDATE deedpoll_document_deletion_step SET state = 'Done', completed_at = now() WHERE id = ?",
                    cdam);
        assertThat(requests.complete(admitted.id(), NOW.plusMinutes(1))).isTrue();
        assertThat(requests.complete(admitted.id(), NOW.plusMinutes(2))).isFalse();

        DocumentDeletionRequest receipt = requests.findByCaseAndId(caseId, admitted.id()).orElseThrow();
        assertThat(receipt.status()).isEqualTo(DocumentDeletionStatus.Completed);
        assertThat(receipt.completedAt()).isEqualTo(NOW.plusMinutes(1));
        assertThat(receipt.reason()).isEmpty();
        assertThat(receipt.documentName()).isNull();
        assertThat(receipt.fingerprint()).isEqualTo(admitted.fingerprint());
        assertThat(receipt.requestedBySubject()).isEqualTo("staff-subject");
        assertThat(requests.stepsOf(admitted.id()))
            .extracting(DocumentDeletionStep::kind, DocumentDeletionStep::state, DocumentDeletionStep::locator)
            .containsExactly(
                org.assertj.core.groups.Tuple.tuple(DocumentDeletionStepKind.CCD_REFERENCE,
                                                    DocumentDeletionStepState.Done, null),
                org.assertj.core.groups.Tuple.tuple(DocumentDeletionStepKind.CDAM_BINARY,
                                                    DocumentDeletionStepState.Done, null));

        assertThatThrownBy(() -> jdbc.update(
            "UPDATE deedpoll_document_deletion_request SET attempts = attempts + 1 WHERE id = ?", admitted.id()))
            .hasStackTraceContaining("cannot be rewritten");
        assertThatThrownBy(() -> jdbc.update(
            "UPDATE deedpoll_document_deletion_request SET status = 'InProgress', completed_at = null WHERE id = ?",
            admitted.id())).hasStackTraceContaining("cannot be rewritten");
    }

    @Test
    @DisplayName("a step keeps its locator until Done, one of each kind per request, from a closed vocabulary")
    void stepConstraints() {
        String caseId = newCase();
        NewDocumentDeletionRequest admitted = admitted(caseId, document(caseId));
        requests.insertStep(UUID.randomUUID(), admitted.id(), DocumentDeletionStepKind.LOCAL_FILE, "files/a");

        assertThatThrownBy(() -> requests.insertStep(UUID.randomUUID(), admitted.id(),
                                                     DocumentDeletionStepKind.LOCAL_FILE, "files/b"))
            .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> requests.insertStep(UUID.randomUUID(), admitted.id(),
                                                     DocumentDeletionStepKind.CDAM_BINARY, null))
            .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("locator_held");
        assertThatThrownBy(() -> jdbc.update("""
            INSERT INTO deedpoll_document_deletion_step (id, request_id, kind, locator, state)
            VALUES (gen_random_uuid(), ?, 'SOFT_DELETE', 'x', 'Pending')
            """, admitted.id())).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(
            "UPDATE deedpoll_document_deletion_step SET state = 'Done' WHERE request_id = ?", admitted.id()))
            .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("completion");
        assertThat(requests.stepsOf(admitted.id())).singleElement().satisfies(step -> {
            assertThat(step.toString()).doesNotContain("files/a");
            assertThat(step.state()).isEqualTo(DocumentDeletionStepState.Pending);
        });
    }

    @Test
    @DisplayName("requests and steps cascade with their case, and outlive their document")
    void cascadesWithTheCase() {
        String caseId = newCase();
        String documentId = document(caseId);
        NewDocumentDeletionRequest admitted = admitted(caseId, documentId);
        requests.insertStep(UUID.randomUUID(), admitted.id(), DocumentDeletionStepKind.LOCAL_FILE, "files/x");

        assertThat(jdbc.update("DELETE FROM deedpoll_supportingdocument WHERE id = ?", documentId)).isOne();
        assertThat(requests.findByDocument(documentId)).isPresent();
        assertThat(requests.lockDocumentRecord(caseId, documentId)).isEmpty();

        assertThat(jdbc.update("DELETE FROM deedpoll_deedpoll WHERE id = ?", caseId)).isOne();
        assertThat(requests.findByDocument(documentId)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM deedpoll_document_deletion_step WHERE request_id = ?",
                                       Integer.class, admitted.id())).isZero();
    }

    // --- the version ------------------------------------------------------------------------------------------

    @Test
    @DisplayName("the version starts at 1 and moves only when a reviewed column changes, whoever writes it")
    void versionCountsReviewedChanges() {
        String caseId = newCase();
        String documentId = document(caseId);
        assertThat(version(documentId)).isOne();

        jdbc.update("UPDATE deedpoll_supportingdocument SET \"modifiedOn\" = now() WHERE id = ?", documentId);
        jdbc.update("UPDATE deedpoll_supportingdocument SET name = 'Evidence' WHERE id = ?", documentId);
        assertThat(version(documentId)).as("unchanged reviewed values").isOne();

        String[] changes = {"name = 'Passport'", "direction = 'Outgoing'", "\"storageKey\" = 'files/other'",
            "\"originalName\" = 'passport.pdf'", "\"mimeType\" = 'application/pdf'", "\"sizeBytes\" = 10",
            "\"storageState\" = 'Attached', \"cdamDocumentId\" = gen_random_uuid()",
            "\"cdamDocumentId\" = gen_random_uuid()"};
        long expected = 1;
        for (String change : changes) {
            jdbc.update("UPDATE deedpoll_supportingdocument SET " + change + " WHERE id = ?", documentId);
            assertThat(version(documentId)).as(change).isEqualTo(++expected);
        }
        // Nobody can set it by hand, up or down.
        jdbc.update("UPDATE deedpoll_supportingdocument SET \"version\" = 1 WHERE id = ?", documentId);
        assertThat(version(documentId)).isEqualTo(expected);

        DeedPollSupportingDocument entity = documents.findByIdAndDeedPollId(documentId, caseId).orElseThrow();
        assertThat(entity.getVersion()).isEqualTo(expected);
        // A JPA write that changes a reviewed column moves it; Hibernate never writes the column itself.
        entity.setName("Renamed");
        DeedPollSupportingDocument written = documents.saveAndFlush(entity);
        assertThat(version(documentId)).isEqualTo(expected + 1);
        assertThat(written.getVersion()).as("read back after the write").isEqualTo(expected + 1);
    }

    @Test
    @DisplayName("V51's column gives every existing document version 1, with or without a file, and nothing else")
    void existingRowsStartAtVersionOne() {
        String migration;
        try (var stream = new ClassPathResource("db/migration/V51__document_deletion_requests.sql").getInputStream()) {
            migration = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        int start = migration.indexOf("ALTER TABLE deedpoll_supportingdocument");
        String alter = migration.substring(start, migration.indexOf(';', start) + 1)
            .replace("ALTER TABLE deedpoll_supportingdocument", "ALTER TABLE \"v51_proof\".deedpoll_supportingdocument")
            .replace("deedpoll_supportingdocument_version_check", "v51_proof_version_check");

        TransactionTemplate rolledBack = transaction();
        rolledBack.executeWithoutResult(status -> {
            jdbc.execute("CREATE SCHEMA \"v51_proof\"");
            jdbc.execute("""
                CREATE TABLE "v51_proof".deedpoll_supportingdocument (
                     LIKE public.deedpoll_supportingdocument INCLUDING DEFAULTS)
                """);
            jdbc.execute("ALTER TABLE \"v51_proof\".deedpoll_supportingdocument DROP COLUMN \"version\"");
            jdbc.update("""
                INSERT INTO "v51_proof".deedpoll_supportingdocument (id, "deedPollId", direction, name,
                    "documentTypeId", "storageKey", "createdOn", "modifiedOn")
                VALUES ('with-file', 'case', 'Incoming', 'Evidence', 'type', 'files/a', now(), now()),
                       ('metadata-only', 'case', 'Outgoing', 'Letter', 'type', null, now(), now())
                """);

            jdbc.execute(alter);

            assertThat(jdbc.queryForList(
                "SELECT \"version\" FROM \"v51_proof\".deedpoll_supportingdocument", Long.class))
                .containsExactly(1L, 1L);
            assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM \"v51_proof\".deedpoll_supportingdocument WHERE \"storageKey\" = 'files/a'",
                Integer.class)).isOne();
            status.setRollbackOnly();
        });
    }

    // --- the fence --------------------------------------------------------------------------------------------

    @Test
    @DisplayName("a document a request names reads as gone through JPA, its neighbours do not, and recovery sees it")
    void fenceHidesTheDocumentOnly() {
        String caseId = newCase();
        String fenced = document(caseId);
        final String neighbour = document(caseId);
        admitted(caseId, fenced);

        assertThat(documents.findByIdAndDeedPollId(fenced, caseId)).isEmpty();
        assertThat(documents.findById(fenced)).isEmpty();
        assertThat(documents.findByIdAndDeedPollId(neighbour, caseId)).isPresent();
        assertThat(documents.findByDeedPollIdOrderByCreatedOnAsc(caseId))
            .extracting(DeedPollSupportingDocument::getId).containsExactly(neighbour);

        assertThat(requests.fences(fenced)).isTrue();
        assertThat(requests.fences(neighbour)).isFalse();
        DocumentRecord recovery = requests.lockDocumentRecord(caseId, fenced).orElseThrow();
        assertThat(recovery.id()).isEqualTo(fenced);
        assertThat(recovery.toString()).doesNotContain("files/", "Evidence");
        assertThat(requests.lockDocumentRecord(newCase(), fenced)).as("case-scoped").isEmpty();
    }

    @Test
    @DisplayName("at commit, the fence refuses a write to the named document and an email naming it; DELETE passes")
    void fenceBackstopAtCommit() {
        String caseId = newCase();
        String fenced = document(caseId);
        final String neighbour = document(caseId);
        admitted(caseId, fenced);

        assertThatThrownBy(() -> transaction().executeWithoutResult(status -> jdbc.update(
            "UPDATE deedpoll_supportingdocument SET \"modifiedOn\" = now() WHERE id = ?", fenced)))
            .hasStackTraceContaining(FENCE_REFUSAL);
        assertThatThrownBy(() -> transaction().executeWithoutResult(status -> email(caseId, fenced)))
            .hasStackTraceContaining(FENCE_REFUSAL);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM deedpoll_notification WHERE \"deedPollId\" = ?",
                                       Integer.class, caseId)).isZero();

        // The neighbour, and an email naming it, are untouched by the fence.
        transaction().executeWithoutResult(status -> {
            jdbc.update("UPDATE deedpoll_supportingdocument SET name = 'Still here' WHERE id = ?", neighbour);
            email(caseId, neighbour);
        });
        // An email on another case naming the same id string is not this case's document.
        String other = newCase();
        transaction().executeWithoutResult(status -> email(other, fenced));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM deedpoll_notification WHERE \"deedPollId\" IN (?, ?)",
                                       Integer.class, caseId, other)).isEqualTo(2);

        transaction().executeWithoutResult(status -> jdbc.update(
            "DELETE FROM deedpoll_supportingdocument WHERE id = ?", fenced));
        assertThat(requests.lockDocumentRecord(caseId, fenced)).isEmpty();
    }

    @Test
    @DisplayName("an application deletion's fence refuses a new document deletion request at commit")
    void applicationFenceRefusesANewRequest() {
        String caseId = newCase();
        String documentId = document(caseId);
        jdbc.update("""
            INSERT INTO deedpoll_deletion_request (id, case_id, idempotency_key, reason, note, fingerprint,
                expected_last_changed_on, requested_by_subject, requested_by_name, requested_at, restorable_until,
                status)
            VALUES (gen_random_uuid(), ?, gen_random_uuid(), 'DUPLICATE', 'Fixture fence for a race', repeat('a', 64),
                    now(), 'dm-subject', 'Dee Manager', now(), now() + interval '14 days', 'Pending')
            """, caseId);

        assertThatThrownBy(() -> transaction().executeWithoutResult(
            status -> requests.insert(request(caseId, documentId, UUID.randomUUID()))))
            .hasStackTraceContaining("The application is being deleted");
        assertThat(requests.findByDocument(documentId)).isEmpty();
    }

    private void email(String caseId, String documentId) {
        jdbc.update("""
            INSERT INTO deedpoll_notification (id, "deedPollId", "requestId", template, "exclusionGroup", recipient,
                personalisation, state, attempts, "nextAttemptAt", "createdBy", "createdOn", "modifiedOn")
            VALUES (gen_random_uuid(), ?, gen_random_uuid(), 'FORMS_FOR_COMPLETION_ADULT', 'FORMS_FOR_COMPLETION',
                    'applicant@example.com', jsonb_build_object('deed_document', ?::text, 'first_name', 'A'),
                    'Pending', 0, now(), 'Fixture', now(), now())
            """, caseId, documentId);
    }
}
