package uk.gov.hmcts.reform.deedpoll.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import uk.gov.hmcts.reform.deedpoll.entity.enums.DocumentStorageState;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

/**
 * What admitting a permanent document deletion reads under the case's lock: the document itself, locked next, and
 * the three questions admission asks of it (docs/DOCUMENT-DELETION.md, "In-use sources" and "Binary ownership").
 *
 * <p>Plain JDBC, every statement constant and its own statement, so each runs in a fresh snapshot taken after the
 * locks were granted and a use committed while admission waited is seen. The document is read natively, past the
 * entity's restriction, because admission has already answered any request that names it. Nothing here writes; the
 * request and its steps are {@link DocumentDeletionRequestRepository}'s.
 */
@Repository
public class DocumentDeletionAdmissionRepository {

    private final JdbcTemplate jdbc;

    public DocumentDeletionAdmissionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * The document row as admission decides on it. {@link #toString()} prints no locator and no name.
     *
     * @param uploadAttempts how many times its remote upload has been sent ({@code storageUploadAttempts})
     */
    public record LockedDocument(String id, String caseId, String name, long version,
                                 DocumentStorageState storageState, String storageKey, UUID cdamDocumentId,
                                 int uploadAttempts) {

        @Override
        public String toString() {
            return "LockedDocument[id=" + id + ", caseId=" + caseId + ", version=" + version
                + ", storageState=" + storageState + "]";
        }
    }

    /**
     * Locks the case's document {@code FOR UPDATE}, after the case, for the rest of the caller's transaction.
     *
     * @return empty when the case has no such document
     */
    public Optional<LockedDocument> lockDocument(String caseId, String documentId) {
        return jdbc.query("""
            SELECT id, "deedPollId", name, "version", "storageState", "storageKey", "cdamDocumentId",
                   "storageUploadAttempts"
              FROM deedpoll_supportingdocument WHERE id = ? AND "deedPollId" = ? FOR UPDATE
            """, (row, index) -> new LockedDocument(
                row.getString("id"),
                row.getString("deedPollId"),
                row.getString("name"),
                row.getLong("version"),
                DocumentStorageState.valueOf(row.getString("storageState")),
                row.getString("storageKey"),
                row.getObject("cdamDocumentId", UUID.class),
                row.getInt("storageUploadAttempts")), documentId, caseId)
            .stream().findFirst();
    }

    /**
     * Whether another current document row, in any case and fenced or not, holds the same binary: the same
     * {@code cdamDocumentId}, or the same {@code storageKey} where both rows are {@code Local}. Historical references
     * (a V50 tombstone, a consumed stage, audit rows, sent emails, CCD history) are not rows here and do not count.
     */
    public boolean sharesBinary(LockedDocument document) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
            SELECT EXISTS (SELECT 1 FROM deedpoll_supportingdocument other
                WHERE other.id <> ?
                  AND ((CAST(? AS uuid) IS NOT NULL AND other."cdamDocumentId" = CAST(? AS uuid))
                       OR (? = 'Local' AND CAST(? AS text) IS NOT NULL AND other."storageState" = 'Local'
                           AND other."storageKey" = CAST(? AS text))))
            """, Boolean.class, document.id(), document.cdamDocumentId(), document.cdamDocumentId(),
            document.storageState().name(), document.storageKey(), document.storageKey()));
    }

    /**
     * Whether an email still owed on the case ({@code Pending}, claimed or not, its send started or not) carries this
     * document under one of the given personalisation keys: the drain will read its bytes.
     */
    public boolean hasPendingEmailNaming(String caseId, String documentId, Collection<String> documentKeys) {
        if (documentKeys.isEmpty()) {
            return false;
        }
        // A String[] binds as a text[] through the driver's own array support.
        return Boolean.TRUE.equals(jdbc.queryForObject("""
            SELECT EXISTS (SELECT 1 FROM deedpoll_notification n, unnest(CAST(? AS text[])) AS k(name)
                WHERE n."deedPollId" = ? AND n.state = 'Pending' AND n.personalisation ->> k.name = ?)
            """, Boolean.class, documentKeys.toArray(String[]::new), caseId, documentId));
    }

    /**
     * Whether a deed preparation for this document still retains its payload: the deed is not yet verified, and the
     * next forms send reads it back.
     */
    public boolean hasRetainedDeedPreparation(String documentId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
            SELECT EXISTS (SELECT 1 FROM deedpoll_deedpreparation d
                JOIN deedpoll_stagedpayload p ON p.id = d."documentId" WHERE d."documentId" = ?)
            """, Boolean.class, documentId));
    }

    /** Whether an owned staged upload is being promoted into this document and has not yet been consumed. */
    public boolean hasPromotingStage(String documentId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
            SELECT EXISTS (SELECT 1 FROM deedpoll_ownedstage WHERE state = 'Promoting' AND "documentId" = ?)
            """, Boolean.class, documentId));
    }
}
