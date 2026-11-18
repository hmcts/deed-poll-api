package uk.gov.hmcts.reform.deedpoll.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import uk.gov.hmcts.reform.deedpoll.domain.audit.AuditAction;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * When anything about a case last changed: the later of its own {@code modifiedOn} and its newest audited write.
 *
 * <p>{@code modifiedOn} moves only when the case row is written. A note, a witness, a document, a referral, an email
 * or a Master's direction leaves it alone, but every such write records an {@code AuditTrail} event on the case in its
 * own transaction, so the newest non-read event is when one of them last happened. Reads ({@link AuditAction#isRead})
 * are left out: opening the case to review it must not make the review stale.
 *
 * <p>Published as the case detail's {@code lastChangedOn} and compared by application deletion under the case lock
 * ({@code DeletionAdmissionRepository.lock} reads {@link #sqlFor} in its own statement), so a change made after the
 * Delivery Manager opened the case refuses the deletion. Both sides use this one expression, so they cannot disagree.
 */
@Repository
public class CaseLastChanged {

    /** The read actions, as a constant SQL list built from the enum: names only, never caller input. */
    private static final String READ_ACTIONS = Arrays.stream(AuditAction.values())
        .filter(AuditAction::isRead)
        .map(action -> "'" + action.name() + "'")
        .collect(Collectors.joining(", "));

    private final JdbcTemplate jdbc;

    public CaseLastChanged(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * The expression over a {@code deedpoll_deedpoll} row aliased {@code alias}; {@code greatest} ignores a case with
     * no audited write yet.
     */
    static String sqlFor(String alias) {
        return "greatest(" + alias + ".\"modifiedOn\", (SELECT max(e.occurred_at) FROM deedpoll_audit_event e"
            + " WHERE e.case_id = " + alias + ".id AND e.action NOT IN (" + READ_ACTIONS + ")))";
    }

    /**
     * The case's last change, or empty when there is no such case. Read it <em>before</em> the case it describes, so a
     * write landing between the two reads makes the value older than what was shown, which refuses a deletion rather
     * than admitting one over a change nobody saw.
     */
    public Optional<LocalDateTime> of(String caseId) {
        return jdbc.query("SELECT " + sqlFor("d") + " AS last_changed FROM deedpoll_deedpoll d WHERE d.id = ?",
                          (row, index) -> row.getTimestamp("last_changed").toLocalDateTime(), caseId)
            .stream().findFirst();
    }
}
