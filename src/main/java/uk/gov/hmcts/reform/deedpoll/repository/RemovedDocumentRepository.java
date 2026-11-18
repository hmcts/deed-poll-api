package uk.gov.hmcts.reform.deedpoll.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;

/**
 * The tombstones a caseworker's document removal leaves (V50's {@code deedpoll_removed_document}): where the removed
 * document's bytes were, kept until the case itself is deleted, so an application deletion can still list them.
 *
 * <p>A removal's own unlink or CDAM soft delete runs best effort after its commit, and a CDAM soft delete always
 * leaves the document in dm-store. Without this row nothing would know either copy was there. Plain JDBC, joining the
 * removal's transaction. The locator is a way back to the bytes: never logged or published.
 */
@Repository
public class RemovedDocumentRepository {

    private final JdbcTemplate jdbc;

    public RemovedDocumentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** One tombstone, reduced to where the bytes were. */
    public record RemovedDocument(String id, String storageState, String locator) {

        @Override
        public String toString() {
            return "RemovedDocument[id=" + id + ", storageState=" + storageState + "]";
        }
    }

    /**
     * Records a removed document, in the caller's transaction.
     *
     * @param storageState {@code Local} or {@code Attached}
     * @param locator      the storage key or the CDAM document id
     */
    public void record(String documentId, String caseId, String storageState, String locator, LocalDateTime now) {
        jdbc.update("""
            INSERT INTO deedpoll_removed_document (id, case_id, storage_state, locator, removed_at)
            VALUES (?, ?, ?, ?, ?)
            """, documentId, caseId, storageState, locator, Timestamp.valueOf(now));
    }

    /** Every tombstone of the case, by id. */
    public List<RemovedDocument> ofCase(String caseId) {
        return jdbc.query("""
            SELECT id, storage_state, locator FROM deedpoll_removed_document WHERE case_id = ? ORDER BY id
            """, (row, index) -> new RemovedDocument(row.getString("id"), row.getString("storage_state"),
                                                     row.getString("locator")), caseId);
    }
}
