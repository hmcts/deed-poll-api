package uk.gov.hmcts.reform.deedpoll.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * The two statements {@code DeletionFence} asks, kept apart because the order between them is the whole point: the
 * case's lock, then the request, each in a statement of its own.
 */
@Repository
public class DeletionFenceRepository {

    private final JdbcTemplate jdbc;

    public DeletionFenceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Takes the case row {@code FOR KEY SHARE} for the rest of the caller's transaction: the weakest row lock, and one
     * that admission's {@code FOR UPDATE} conflicts with. It conflicts with no other writer's lock.
     *
     * @return whether the case exists
     */
    public boolean shareCase(String caseId) {
        List<String> found = jdbc.queryForList(
            "SELECT id FROM deedpoll_deedpoll WHERE id = ? FOR KEY SHARE", String.class, caseId);
        return !found.isEmpty();
    }

    /** Whether a deletion request names the case: a fresh statement, so a request committed before it is seen. */
    public boolean hasRequest(String caseId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM deedpoll_deletion_request WHERE case_id = ?)", Boolean.class, caseId));
    }
}
