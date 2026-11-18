package uk.gov.hmcts.reform.deedpoll.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import uk.gov.hmcts.reform.deedpoll.domain.audit.AuditAction;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * When anything about a case last changed: the later of its own {@code modifiedOn} and its {@code lastChangedOn}
 * marker (V50).
 *
 * <p>{@code modifiedOn} moves only when the case row is written. A note, a witness, a document, a referral, an email
 * or a Master's direction leaves it alone, but every such write records an {@code AuditTrail} event on the case in its
 * own transaction, and V50's trigger, deferred to that transaction's commit, moves the marker under the case row's
 * lock to at least a millisecond past both its last value and {@code modifiedOn}. Reads ({@link AuditAction#isRead})
 * do not move it: opening the case to review it must not make the review stale.
 *
 * <p><strong>A commit-ordered value, not a writer's clock.</strong> The moves are serialised by the row lock and each
 * strictly increases the value, so a write that commits after a review changes it, whenever its own times were
 * stamped and whatever the pods' clocks say. Admission reads it after taking the case row {@code FOR UPDATE}, so a
 * write that commits meanwhile either moved it first or waits and is refused by V49's fence.
 *
 * <p>Published as the case detail's {@code lastChangedOn} ({@code DeedPollService.findDeedPollForReview}) and compared
 * by application deletion ({@code ApplicationDeletionService.refuseIfIneligible}, which calls {@link #of} in its own
 * statement after {@code DeletionAdmissionRepository.lock} has taken the case lock). A change committed after the
 * Delivery Manager opened the case therefore refuses the deletion. Both sides call {@link #of}, so they cannot
 * disagree.
 */
@Repository
public class CaseLastChanged {

    /**
     * {@code greatest} ignores the null marker of a case with no audited write yet.
     */
    private static final String SQL = "SELECT greatest(d.\"modifiedOn\", d.\"lastChangedOn\") AS last_changed"
        + " FROM deedpoll_deedpoll d WHERE d.id = ?";

    private final JdbcTemplate jdbc;

    public CaseLastChanged(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * The case's last change, or empty when there is no such case. Read it <em>before</em> the case it describes, so a
     * write landing between the two reads makes the value older than what was shown, which refuses a deletion rather
     * than admitting one over a change nobody saw.
     */
    public Optional<LocalDateTime> of(String caseId) {
        return jdbc.query(SQL, (row, index) -> row.getTimestamp("last_changed").toLocalDateTime(), caseId)
            .stream().findFirst();
    }
}
