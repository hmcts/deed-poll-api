package uk.gov.hmcts.reform.deedpoll.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import uk.gov.hmcts.reform.deedpoll.domain.deletion.DeletionStepOutcome;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * The statements of an application deletion's local finalisation (plan 20261118-dm-application-deletion, Task 6):
 * the case and what it owns removed by {@code DELETE} only, the case's audit values disposed through V36's controlled
 * function, and the request turned into its tombstone.
 *
 * <p>Plain JDBC over the tables themselves, never the entity layer: a fenced case is invisible to every JPA read, and
 * V49 refuses an {@code UPDATE} of the case or a child, so nothing here may load, change or save one. Every statement
 * joins the caller's transaction, which is the worker's owned one: either all of it commits or none does.
 */
@Repository
public class DeletionFinalisationRepository {

    private final JdbcTemplate jdbc;

    public DeletionFinalisationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Locks the case row {@code FOR UPDATE}, as admission did, so nothing that waits on it can run alongside.
     *
     * @return whether the case is still stored; a case already gone is the outcome finalisation is after
     */
    public boolean lockCase(String caseId) {
        return !jdbc.queryForList("SELECT id FROM deedpoll_deedpoll WHERE id = ? FOR UPDATE", String.class, caseId)
            .isEmpty();
    }

    /**
     * Whether the SDK still holds a local copy of the linked case ({@code ccd.case_data}).
     */
    public boolean ccdCopyPresent(String reference) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM ccd.case_data WHERE reference = ?)", Boolean.class,
            Long.parseLong(reference)));
    }

    /**
     * Whether any staged or deed preparation bytes are retained for the case: recovery evidence admission refused,
     * which nothing since may have written (V49) but which is asked again rather than assumed.
     */
    public boolean hasRetainedPayload(String caseId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
            SELECT EXISTS (SELECT 1 FROM deedpoll_deedpreparation d
                             JOIN deedpoll_stagedpayload p ON p.id = d."documentId"
                            WHERE d."deedPollId" = ?)
                OR EXISTS (SELECT 1 FROM deedpoll_ownedstage s
                             JOIN deedpoll_stagedpayload p ON p.id = s.id
                            WHERE (s."ownerKind" = 'RECORD' AND s."ownerId" = ?) OR s."caseId" = ?)
            """, Boolean.class, caseId, caseId, caseId));
    }

    /**
     * Removes one owned stage the inventory lists. V29's evidence trigger guards {@code UPDATE} only.
     *
     * @return 1, or 0 if it was already gone
     */
    public int deleteOwnedStage(String stageId) {
        return jdbc.update("DELETE FROM deedpoll_ownedstage WHERE id = ?", stageId);
    }

    /** Owned stages that still name the case, which the inventory did not list: none, if the fence held. */
    public int remainingOwnedStages(String caseId) {
        Integer count = jdbc.queryForObject("""
            SELECT count(*) FROM deedpoll_ownedstage WHERE ("ownerKind" = 'RECORD' AND "ownerId" = ?) OR "caseId" = ?
            """, Integer.class, caseId, caseId);
        return count == null ? 0 : count;
    }

    /**
     * Deletes the case row. Its notes, witnesses, documents, referrals and their notifications, CCD creation attempts
     * and deed preparation go by their cascades; Gazette evidence restricts, and a retained deed payload's trigger
     * refuses, either of which fails the caller's transaction.
     *
     * @return 1, or 0 if it was already gone
     */
    public int deleteCase(String caseId) {
        return jdbc.update("DELETE FROM deedpoll_deedpoll WHERE id = ?", caseId);
    }

    /** Disposes of every before/after value row of the case's audit events, keeping the events (V36). */
    public int disposeAuditValues(String caseId) {
        Integer disposed = jdbc.queryForObject("SELECT deedpoll_audit_dispose_case_values(?)", Integer.class, caseId);
        return disposed == null ? 0 : disposed;
    }

    /** Records the listed owned-stage lines done, with the outcome their removal verified. */
    public void ownedStageDone(UUID stepId, DeletionStepOutcome outcome, LocalDateTime now) {
        jdbc.update("""
            UPDATE deedpoll_deletion_step SET state = 'Done', outcome = ?, completed_at = ?
             WHERE id = ? AND state = 'Pending'
            """, outcome.name(), Timestamp.valueOf(now), stepId);
    }

    /**
     * Erases what the tombstone no longer needs: every line's locator and item id. Each line keeps its kind, outcome
     * code and completion time. Only {@code Done} lines may lose their locator (V48), so a line still open fails here.
     */
    public void eraseInventory(UUID requestId) {
        jdbc.update("""
            UPDATE deedpoll_deletion_step SET locator = NULL, item_id = NULL WHERE request_id = ?
            """, requestId);
    }

    /**
     * Completes the request: {@code Completed}, its completion time, no claim and no failure. V48's trigger refuses any
     * later change to it.
     *
     * @return 1, or 0 if it was no longer the owner's
     */
    public int complete(UUID requestId, UUID owner, LocalDateTime now) {
        return jdbc.update("""
            UPDATE deedpoll_deletion_request
               SET status = 'Completed', completed_at = ?, owner = NULL, lease_until = NULL, failure = NULL,
                   next_attempt_at = NULL
             WHERE id = ? AND owner = ? AND status <> 'Completed'
            """, Timestamp.valueOf(now), requestId, owner);
    }
}
