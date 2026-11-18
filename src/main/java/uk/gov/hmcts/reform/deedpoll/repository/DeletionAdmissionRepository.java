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
    public record LockedCase(String id, LocalDateTime modifiedOn, boolean retainPermanently,
                             String ccdCaseReference) {
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
            SELECT id, "modifiedOn", "retainPermanently", "ccdCaseReference"
              FROM deedpoll_deedpoll WHERE id = ? FOR UPDATE
            """, (row, index) -> new LockedCase(row.getString("id"),
                                                row.getTimestamp("modifiedOn").toLocalDateTime(),
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
     * Whether an unlinked case's CCD creation may have created a CCD case nobody has bound: an attempt that is claimed
     * or awaiting confirmation, or any unlinked attempt whose submission may have reached CCD without proof that
     * nothing was created. A case that is linked answers false: its binding is the answer.
     */
    public boolean hasUnresolvedCcdCreation(String caseId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
            SELECT EXISTS (SELECT 1 FROM deedpoll_ccdcreationattempt a
                JOIN deedpoll_deedpoll d ON d.id = a."deedPollId"
                WHERE a."deedPollId" = ? AND d."ccdCaseReference" IS NULL
                  AND (a.status IN ('Claimed', 'AwaitingConfirmation')
                       OR (a.status <> 'Linked' AND a."submissionMayHaveReached"
                           AND NOT a."provenNothingCreated")))
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
