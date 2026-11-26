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

    /** The request with this id, whichever case it is of: the work's own read, never a caller's. */
    public Optional<DocumentDeletionRequest> findById(UUID id) {
        return jdbc.query("select " + COLUMNS + " from deedpoll_document_deletion_request where id = ?",
                          REQUEST, id).stream().findFirst();
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

    /** Whether any request names this document: the fence, as the entity restriction and V51's trigger state it. */
    public boolean fences(String documentId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
            "select exists (select 1 from deedpoll_document_deletion_request where document_id = ?)",
            Boolean.class, documentId));
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

    // --- carrying a request out (Task 4) ------------------------------------------------------------------------

    /**
     * A document's last metadata, for {@code DOCUMENT_DELETED}'s values, locked {@code FOR UPDATE} in the completion's
     * transaction after the case. Read natively past the entity restriction; never the locators or the hash token.
     */
    public record DocumentRecord(String id, String name, String direction, String documentTypeId,
                                 String originalName, String mimeType, Long sizeBytes) {

        @Override
        public String toString() {
            return "DocumentRecord[id=" + id + "]";
        }
    }

    /**
     * Claims this request for {@code owner} until {@code leaseUntil}, if it is {@code InProgress} and nobody else
     * holds an unexpired claim on it, counting the pass.
     *
     * @return whether this call claimed it
     */
    public boolean claim(UUID id, UUID owner, LocalDateTime now, LocalDateTime leaseUntil) {
        return jdbc.update("""
            update deedpoll_document_deletion_request
               set owner = ?, lease_until = ?, attempts = attempts + 1
             where id = ? and status = 'InProgress' and (owner is null or lease_until <= ?)
            """, owner, timestamp(leaseUntil), id, timestamp(now)) == 1;
    }

    /**
     * The recovery sweep's claim: the soonest due {@code InProgress} request nobody holds an unexpired claim on,
     * {@code FOR UPDATE SKIP LOCKED} so pods never share one. Joins the caller's short transaction.
     *
     * @return the claimed request's id, or empty when nothing is due
     */
    public Optional<UUID> claimNext(UUID owner, LocalDateTime now, LocalDateTime leaseUntil) {
        return jdbc.query("""
            with due as (
                select id from deedpoll_document_deletion_request
                 where status = 'InProgress' and next_attempt_at <= ? and (owner is null or lease_until <= ?)
                 order by next_attempt_at, id
                 limit 1
                 for update skip locked)
            update deedpoll_document_deletion_request request
               set owner = ?, lease_until = ?, attempts = request.attempts + 1
              from due where request.id = due.id
            returning request.id
            """, (row, index) -> row.getObject("id", UUID.class), timestamp(now), timestamp(now), owner,
            timestamp(leaseUntil)).stream().findFirst();
    }

    /**
     * Counts a failed local completion (V52), apart from the request's claims, joining the caller's transaction.
     *
     * @return the number of failed completions, this one included
     */
    public int completionFailed(UUID id) {
        return jdbc.queryForObject("""
            update deedpoll_document_deletion_request set completion_attempts = completion_attempts + 1
             where id = ? returning completion_attempts
            """, Integer.class, id);
    }

    /**
     * Whether {@code owner}'s claim on the request is still unexpired, locking the request {@code FOR UPDATE} until
     * the caller's transaction ends.
     */
    public boolean holds(UUID id, UUID owner, LocalDateTime now) {
        return Boolean.TRUE.equals(jdbc.query("""
            select owner = ? and lease_until > ? and status = 'InProgress' as held
              from deedpoll_document_deletion_request where id = ? for update
            """, (row, index) -> row.getBoolean("held"), owner, timestamp(now), id).stream().findFirst()
            .orElse(false));
    }

    /**
     * A step's receipt: {@code Done} with its outcome, from {@code Pending} or {@code Failed}. Written whether or not
     * the writer still holds the claim, because a receipt is a fact about the far side: recording it stops a later
     * worker re-sending a delete whose second answer could prove nothing.
     *
     * <p>Order does not decide it. A worker whose lease lapsed mid-call can hear its {@code 204} after the worker that
     * took the request over has already heard a {@code 404} and failed the step. That {@code 404} proved nothing and
     * this receipt proves the delete, so it replaces the failure; and if no other step is {@code Failed}, the request
     * leaves {@code NeedsAttention} for {@code InProgress}, unclaimed and due now, so the sweep carries it on. The
     * request is locked first, in the order every claimed write takes (request, then step), and a failure and its
     * {@code NeedsAttention} commit together, so this sees either both or neither.
     *
     * @return whether this call recorded it
     */
    public boolean stepDone(UUID stepId, String outcome, LocalDateTime now) {
        jdbc.query("""
            select request.id from deedpoll_document_deletion_request request
              join deedpoll_document_deletion_step step on step.request_id = request.id
             where step.id = ? for update of request
            """, (row, index) -> row.getString("id"), stepId);
        if (jdbc.update("""
            update deedpoll_document_deletion_step
               set state = 'Done', outcome = ?, attempts = attempts + 1, completed_at = ?
             where id = ? and state = 'Pending'
            """, outcome, timestamp(now), stepId) == 1) {
            return true;
        }
        if (jdbc.update("""
            update deedpoll_document_deletion_step
               set state = 'Done', outcome = ?, attempts = attempts + 1, completed_at = ?
             where id = ? and state = 'Failed'
            """, outcome, timestamp(now), stepId) == 0) {
            return false;
        }
        jdbc.update("""
            update deedpoll_document_deletion_request request
               set status = 'InProgress', failure = null, next_attempt_at = ?
             where request.id = (select request_id from deedpoll_document_deletion_step where id = ?)
               and request.status = 'NeedsAttention'
               and not exists (select 1 from deedpoll_document_deletion_step other
                                where other.request_id = request.id and other.state = 'Failed')
            """, timestamp(now), stepId);
        return true;
    }

    /**
     * A try that confirmed nothing; the step stays {@code Pending} with its locator.
     */
    public int stepTried(UUID stepId, String outcome) {
        return jdbc.update("""
            update deedpoll_document_deletion_step set outcome = ?, attempts = attempts + 1
             where id = ? and state = 'Pending'
            """, outcome, stepId);
    }

    /** A refusal another try will not fix; the step keeps its locator for whoever looks at it. */
    public int stepFailed(UUID stepId, String outcome, boolean counted) {
        return jdbc.update("""
            update deedpoll_document_deletion_step
               set state = 'Failed', outcome = ?, attempts = attempts + ?
             where id = ? and state = 'Pending'
            """, outcome, counted ? 1 : 0, stepId);
    }

    /**
     * Gives {@code owner}'s claim up, leaving the request {@code InProgress} (due at {@code nextAttemptAt}) or
     * {@code NeedsAttention} with its failure. Never {@code Completed}: only {@link #complete} writes that.
     *
     * @return whether the claim was still this owner's
     */
    public boolean release(UUID id, UUID owner, DocumentDeletionStatus status, String failure,
                           LocalDateTime nextAttemptAt) {
        if (status == DocumentDeletionStatus.Completed) {
            throw new IllegalArgumentException("Only completion completes a document deletion request");
        }
        return jdbc.update("""
            update deedpoll_document_deletion_request
               set owner = null, lease_until = null, status = ?, failure = ?, next_attempt_at = ?
             where id = ? and owner = ? and status = 'InProgress'
            """, status.name(), status == DocumentDeletionStatus.NeedsAttention ? failure : null,
            status == DocumentDeletionStatus.InProgress ? timestamp(nextAttemptAt) : null, id, owner) == 1;
    }

    /**
     * The case row, {@code FOR NO KEY UPDATE}: the completion's first lock, as admission's order has it.
     */
    public boolean lockCase(String caseId) {
        return !jdbc.query("select id from deedpoll_deedpoll where id = ? for no key update",
                           (row, index) -> row.getString("id"), caseId).isEmpty();
    }

    /**
     * The document's metadata, locked {@code FOR UPDATE}, after the case and before the request.
     */
    public Optional<DocumentRecord> lockDocumentRecord(String caseId, String documentId) {
        return jdbc.query("""
            select id, name, direction::text as direction, "documentTypeId", "originalName", "mimeType", "sizeBytes"
              from deedpoll_supportingdocument where id = ? and "deedPollId" = ? for update
            """, (row, index) -> new DocumentRecord(
                row.getString("id"), row.getString("name"), row.getString("direction"),
                row.getString("documentTypeId"), row.getString("originalName"), row.getString("mimeType"),
                row.getObject("sizeBytes") == null ? null : row.getLong("sizeBytes")),
            documentId, caseId).stream().findFirst();
    }

    /** Deletes the document's row; V49's and V51's fences allow a delete. */
    public int deleteDocument(String caseId, String documentId) {
        return jdbc.update("delete from deedpoll_supportingdocument where id = ? and \"deedPollId\" = ?",
                           documentId, caseId);
    }

    /** How many requests are in this status: a bounded label for the gauge, nothing else. */
    public long countByStatus(DocumentDeletionStatus status) {
        Long found = jdbc.queryForObject("select count(*) from deedpoll_document_deletion_request where status = ?",
                                         Long.class, status.name());
        return found == null ? 0 : found;
    }

    private static Timestamp timestamp(LocalDateTime value) {
        return value == null ? null : Timestamp.valueOf(value);
    }

    private static LocalDateTime local(Timestamp value) {
        return value == null ? null : value.toLocalDateTime();
    }
}
