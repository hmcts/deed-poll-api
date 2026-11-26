package uk.gov.hmcts.reform.deedpoll.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import uk.gov.hmcts.reform.deedpoll.domain.documents.deletion.DocumentDeletionReason;
import uk.gov.hmcts.reform.deedpoll.domain.documents.deletion.DocumentDeletionRequest;
import uk.gov.hmcts.reform.deedpoll.domain.documents.deletion.DocumentDeletionStatus;
import uk.gov.hmcts.reform.deedpoll.domain.documents.deletion.DocumentDeletionStep;
import uk.gov.hmcts.reform.deedpoll.domain.documents.deletion.DocumentDeletionStepKind;
import uk.gov.hmcts.reform.deedpoll.domain.documents.deletion.DocumentDeletionStepState;
import uk.gov.hmcts.reform.deedpoll.entity.enums.DocumentStorageState;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * V51's {@code deedpoll_document_deletion_request} and {@code deedpoll_document_deletion_step}: the durable record of
 * a permanent document deletion, the remote steps it still has to confirm, and its receipt.
 *
 * <p>Plain JDBC, as {@link DeletionRequestRepository} is, because the rows are written in short statements whose
 * counts are the answer. Every read here is native, so the request and its document stay reachable after the
 * document's entity restriction hides it: that is the recovery's access, and it reads nothing else.
 */
@Repository
public class DocumentDeletionRequestRepository {

    private static final String COLUMNS = """
        id, case_id, document_id, idempotency_key, reason, document_name, fingerprint, expected_document_version,
        requested_by_subject, requested_by_name, requested_at, status, failure, attempts, owner, lease_until,
        next_attempt_at, completed_at
        """;

    private static final RowMapper<DocumentDeletionRequest> REQUEST = (row, index) -> new DocumentDeletionRequest(
        row.getObject("id", UUID.class),
        row.getString("case_id"),
        row.getString("document_id"),
        row.getObject("idempotency_key", UUID.class),
        Optional.ofNullable(row.getString("reason")).map(DocumentDeletionReason::parse),
        row.getString("document_name"),
        row.getString("fingerprint"),
        row.getLong("expected_document_version"),
        row.getString("requested_by_subject"),
        row.getString("requested_by_name"),
        local(row.getTimestamp("requested_at")),
        DocumentDeletionStatus.valueOf(row.getString("status")),
        row.getString("failure"),
        row.getInt("attempts"),
        row.getObject("owner", UUID.class),
        local(row.getTimestamp("lease_until")),
        local(row.getTimestamp("next_attempt_at")),
        local(row.getTimestamp("completed_at")));

    private static final RowMapper<DocumentDeletionStep> STEP = (row, index) -> new DocumentDeletionStep(
        row.getObject("id", UUID.class),
        row.getObject("request_id", UUID.class),
        DocumentDeletionStepKind.valueOf(row.getString("kind")),
        row.getString("locator"),
        DocumentDeletionStepState.valueOf(row.getString("state")),
        row.getString("outcome"),
        row.getInt("attempts"),
        local(row.getTimestamp("completed_at")));

    private static final RowMapper<FencedDocument> DOCUMENT = (row, index) -> new FencedDocument(
        row.getString("id"),
        row.getString("deedPollId"),
        row.getString("name"),
        row.getLong("version"),
        DocumentStorageState.valueOf(row.getString("storageState")),
        row.getString("storageKey"),
        row.getObject("cdamDocumentId", UUID.class));

    private final JdbcTemplate jdbc;

    public DocumentDeletionRequestRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * What admission writes: the fixed half of a request, which starts {@code InProgress} and due now.
     */
    public record NewDocumentDeletionRequest(UUID id, String caseId, String documentId, UUID idempotencyKey,
                                             DocumentDeletionReason reason, String documentName, String fingerprint,
                                             long expectedDocumentVersion, String requestedBySubject,
                                             String requestedByName, LocalDateTime requestedAt) {

        @Override
        public String toString() {
            return "NewDocumentDeletionRequest[id=" + id + ", caseId=" + caseId + ", documentId=" + documentId + "]";
        }
    }

    /**
     * A document row as the fenced recovery needs it, read natively past the entity restriction. Never the hash
     * token, and {@link #toString()} prints no locator or name.
     */
    public record FencedDocument(String id, String caseId, String name, long version,
                                 DocumentStorageState storageState, String storageKey, UUID cdamDocumentId) {

        @Override
        public String toString() {
            return "FencedDocument[id=" + id + ", caseId=" + caseId + ", version=" + version
                + ", storageState=" + storageState + "]";
        }
    }

    /**
     * The request, unless the document already has one or the case already used the key.
     *
     * <p>{@code ON CONFLICT DO NOTHING} with no target, so either unique constraint answers with a count of zero and
     * the transaction stays usable to read the winner; neither raises.
     *
     * @return whether this call wrote it
     */
    public boolean insert(NewDocumentDeletionRequest request) {
        return jdbc.update("""
            insert into deedpoll_document_deletion_request (id, case_id, document_id, idempotency_key, reason,
                document_name, fingerprint, expected_document_version, requested_by_subject, requested_by_name,
                requested_at, status, next_attempt_at)
            values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'InProgress', ?)
            on conflict do nothing
            """, request.id(), request.caseId(), request.documentId(), request.idempotencyKey(),
            request.reason().value(), request.documentName(), request.fingerprint(),
            request.expectedDocumentVersion(), request.requestedBySubject(), request.requestedByName(),
            timestamp(request.requestedAt()), timestamp(request.requestedAt())) == 1;
    }

    /** The request with this id, but only as the named case's: a status read is case-scoped. */
    public Optional<DocumentDeletionRequest> findByCaseAndId(String caseId, UUID id) {
        return jdbc.query("select " + COLUMNS + " from deedpoll_document_deletion_request where case_id = ? and id = ?",
                          REQUEST, caseId, id).stream().findFirst();
    }

    /** The case's request under this key, whichever document it named: a replay's lookup. */
    public Optional<DocumentDeletionRequest> findByCaseAndKey(String caseId, UUID idempotencyKey) {
        return jdbc.query("select " + COLUMNS
                              + " from deedpoll_document_deletion_request where case_id = ? and idempotency_key = ?",
                          REQUEST, caseId, idempotencyKey).stream().findFirst();
    }

    /** The document's one request, if it has one. */
    public Optional<DocumentDeletionRequest> findByDocument(String documentId) {
        return jdbc.query("select " + COLUMNS + " from deedpoll_document_deletion_request where document_id = ?",
                          REQUEST, documentId).stream().findFirst();
    }

    /**
     * The request, locked {@code FOR UPDATE} until the caller's transaction ends, after the case and document.
     */
    public Optional<DocumentDeletionRequest> lockById(UUID id) {
        return jdbc.query("select " + COLUMNS + " from deedpoll_document_deletion_request where id = ? for update",
                          REQUEST, id).stream().findFirst();
    }

    /** Whether any request names this document: the fence, as the entity restriction and V51's trigger state it. */
    public boolean fences(String documentId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
            "select exists (select 1 from deedpoll_document_deletion_request where document_id = ?)",
            Boolean.class, documentId));
    }

    /** The document's row, read past the entity restriction, while it still exists. */
    public Optional<FencedDocument> fencedDocument(String caseId, String documentId) {
        return jdbc.query("""
            select id, "deedPollId", name, "version", "storageState", "storageKey", "cdamDocumentId"
              from deedpoll_supportingdocument where id = ? and "deedPollId" = ?
            """, DOCUMENT, documentId, caseId).stream().findFirst();
    }

    /**
     * One remote step, {@link DocumentDeletionStepState#Pending}, holding the locator it removes.
     */
    public void insertStep(UUID id, UUID requestId, DocumentDeletionStepKind kind, String locator) {
        jdbc.update("""
            insert into deedpoll_document_deletion_step (id, request_id, kind, locator, state)
            values (?, ?, ?, ?, 'Pending')
            """, id, requestId, kind.name(), locator);
    }

    /** The request's steps, in the order they run. */
    public List<DocumentDeletionStep> stepsOf(UUID requestId) {
        return jdbc.query("""
            select id, request_id, kind, locator, state, outcome, attempts, completed_at
              from deedpoll_document_deletion_step where request_id = ?
             order by case kind when 'CCD_REFERENCE' then 0 when 'CDAM_BINARY' then 1 else 2 end
            """, STEP, requestId);
    }

    /**
     * The receipt: the request {@link DocumentDeletionStatus#Completed}, unclaimed, with its reason and document
     * name cleared and its confirmed steps' locators erased, only if every step is {@code Done}. Joins the caller's
     * completion transaction, which has already copied the reason into the audit event and the case note.
     *
     * @return whether this call completed it; false when it was already complete, is gone, or has a step left
     */
    public boolean complete(UUID id, LocalDateTime now) {
        int updated = jdbc.update("""
            update deedpoll_document_deletion_request
               set status = 'Completed', reason = null, document_name = null, failure = null, owner = null,
                   lease_until = null, next_attempt_at = null, completed_at = ?
             where id = ? and status <> 'Completed'
               and not exists (select 1 from deedpoll_document_deletion_step step
                                where step.request_id = deedpoll_document_deletion_request.id
                                  and step.state <> 'Done')
            """, timestamp(now), id);
        if (updated == 1) {
            jdbc.update("update deedpoll_document_deletion_step set locator = null where request_id = ?", id);
        }
        return updated == 1;
    }

    private static Timestamp timestamp(LocalDateTime value) {
        return value == null ? null : Timestamp.valueOf(value);
    }

    private static LocalDateTime local(Timestamp value) {
        return value == null ? null : value.toLocalDateTime();
    }
}
