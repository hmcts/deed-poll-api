package uk.gov.hmcts.reform.deedpoll.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * What admitting an application deletion reads under the case lock: the case's confirmation facts, the refusals'
 * questions and the cleanup inventory. Plain JDBC, every statement constant, so nothing goes through the persistence
 * context and a fact read here is the committed one.
 *
 * <p>Nothing here writes. The request and its inventory are {@link DeletionRequestRepository}'s.
 */
@Repository
public class DeletionAdmissionRepository {

    private final JdbcTemplate jdbc;

    public DeletionAdmissionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** The case facts admission decides on, read with the case row locked FOR UPDATE. */
    public record LockedCase(String id, boolean retainPermanently, String ccdCaseReference) {
    }

    /** One supporting document, reduced to where its bytes are. Never logged: the key is a way back to them. */
    public record DocumentLocation(String id, String storageState, String storageKey, String cdamDocumentId) {

        @Override
        public String toString() {
            return "DocumentLocation[id=" + id + ", storageState=" + storageState + "]";
        }
    }

    /**
     * Locks the case row {@code FOR UPDATE}, as {@code DeedPollRepository.lockForDeletion} does, and reads what
     * admission needs from it in the same statement.
     *
     * @return empty when there is no such case
     */
    public Optional<LockedCase> lock(String caseId) {
        return jdbc.query("""
            SELECT id, "retainPermanently", "ccdCaseReference"
              FROM deedpoll_deedpoll WHERE id = ? FOR UPDATE
            """, (row, index) -> new LockedCase(row.getString("id"),
                                                row.getBoolean("retainPermanently"),
                                                row.getString("ccdCaseReference")), caseId)
            .stream().findFirst();
    }

    /**
     * Whether a supporting document's remote storage is in flight or may still land: claimed for an upload, uploaded
     * and not attached, an attach whose answer was lost, or an uncertain upload that may still be sent again. An
     * uncertain upload that has used both of its sends is settled (nothing resends it, and a copy that landed is never
     * attached, so it expires on CDAM's upload TTL), as are {@code Local}, {@code Attached} and {@code Failed}.
     */
    public boolean hasUnresolvedDocumentStorage(String caseId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
            SELECT EXISTS (SELECT 1 FROM deedpoll_supportingdocument
                WHERE "deedPollId" = ?
                  AND ("storageState" IN ('UploadPending', 'Uploaded', 'AttachUncertain')
                       OR ("storageState" = 'UploadUncertain' AND "storageUploadAttempts" < 2)))
            """, Boolean.class, caseId));
    }

    /**
     * Whether the case's CCD creation is still in flight or may have created a CCD case nobody has bound: an attempt
     * that is claimed or awaiting confirmation, linked or not, or an unlinked case's attempt whose submission may have
     * reached CCD without proof that nothing was created.
     *
     * <p>A claimed or awaiting attempt blocks even on a linked case, because the binding commits inside data-store's
     * callback before the worker or reconciliation moves the attempt to {@code Linked}: admitted, that later move
     * would be refused by V49's fence at commit, and the attempt would stay first in reconciliation's order and stop
     * every sweep. Otherwise a linked case's binding is the answer.
     */
    public boolean hasUnresolvedCcdCreation(String caseId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
            SELECT EXISTS (SELECT 1 FROM deedpoll_ccdcreationattempt a
                JOIN deedpoll_deedpoll d ON d.id = a."deedPollId"
                WHERE a."deedPollId" = ?
                  AND (a.status IN ('Claimed', 'AwaitingConfirmation')
                       OR (d."ccdCaseReference" IS NULL AND a.status <> 'Linked'
                           AND a."submissionMayHaveReached" AND NOT a."provenNothingCreated")))
            """, Boolean.class, caseId));
    }

    /**
     * Whether an email about the case is owed with its send started (an unknown outcome) or held by a live claim, so a
     * worker may be about to send it or has sent it unrecorded.
     */
    public boolean hasUnsettledSend(String caseId, LocalDateTime now) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
            SELECT EXISTS (SELECT 1 FROM deedpoll_notification
                WHERE "deedPollId" = ? AND state = 'Pending'
                  AND ("sendStartedAt" IS NOT NULL OR ("claimOwner" IS NOT NULL AND "nextAttemptAt" > ?)))
            """, Boolean.class, caseId, Timestamp.valueOf(now)));
    }

    /**
     * Whether a permanent document deletion on the case (V51) is admitted and not {@code Completed}: still
     * {@code InProgress} or {@code NeedsAttention}. Read under the case's {@code FOR UPDATE} lock, which document
     * deletion admission also takes, so a request either committed first and is seen here or meets this request's
     * fence. Its document is fenced from every JPA read but still listed by {@link #documents}, and its bytes may or
     * may not be gone, so the application waits for it rather than inventorying a document in that state.
     */
    public boolean hasUnresolvedDocumentDeletion(String caseId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
            SELECT EXISTS (SELECT 1 FROM deedpoll_document_deletion_request
                            WHERE case_id = ? AND status <> 'Completed')
            """, Boolean.class, caseId));
    }

    /**
     * The documents of the case whose permanent deletion completed (V51's receipts), by id. Completion deleted each
     * one's row and confirmed every remote step, binary included, so none needs cleaning up again, and none is a
     * caseworker's soft removal waiting for the dm-store team's purge.
     */
    public List<String> permanentlyDeletedDocuments(String caseId) {
        return jdbc.queryForList("""
            SELECT document_id FROM deedpoll_document_deletion_request
             WHERE case_id = ? AND status = 'Completed' ORDER BY document_id
            """, String.class, caseId);
    }

    /** Every supporting document on the case, by id. */
    public List<DocumentLocation> documents(String caseId) {
        return jdbc.query("""
            SELECT id, "storageState", "storageKey", "cdamDocumentId"::text AS cdam
              FROM deedpoll_supportingdocument WHERE "deedPollId" = ? ORDER BY id
            """, (row, index) -> new DocumentLocation(row.getString("id"), row.getString("storageState"),
                                                      row.getString("storageKey"), row.getString("cdam")), caseId);
    }

    /** The owned stages that name the case, as a record admission's owner or a promotion's target, by id. */
    public List<String> ownedStages(String caseId) {
        return jdbc.queryForList("""
            SELECT id::text FROM deedpoll_ownedstage
             WHERE ("ownerKind" = 'RECORD' AND "ownerId" = ?) OR "caseId" = ? ORDER BY id
            """, String.class, caseId, caseId);
    }
}
