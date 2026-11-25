package uk.gov.hmcts.reform.deedpoll.domain.deletion;

import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import uk.gov.hmcts.reform.deedpoll.entity.AuditClock;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * For tests of what happens after a deletion request's grace period: makes an admitted request look as though it was
 * admitted a grace period ago, so the worker may claim it now.
 *
 * <p>{@code restorable_until} and {@code requested_at} are admission's, and V48/V50's trigger refuses to move them,
 * which is the point of freezing them. This is the one place that moves them anyway, with the triggers off for its own
 * statement ({@code session_replication_role = replica}, which the test database's superuser may set). Both move back
 * by the same amount, so the period itself and V50's {@code restorable_until > requested_at} check still hold. No
 * application code has, or should have, a way to do this.
 */
public final class DeletionGracePeriods {

    private DeletionGracePeriods() {
    }

    /**
     * Ends {@code request}'s grace period one millisecond ago, keeping its length, and makes it due if it was not.
     */
    public static void elapse(JdbcTemplate jdbc, UUID request) {
        LocalDateTime ended = AuditClock.now().truncatedTo(ChronoUnit.MILLIS).minusNanos(1_000_000);
        int moved = jdbc.execute((ConnectionCallback<Integer>) connection -> {
            try (Statement session = connection.createStatement()) {
                session.execute("SET session_replication_role = replica");
                try (PreparedStatement update = connection.prepareStatement("""
                    UPDATE deedpoll_deletion_request
                       SET requested_at = requested_at - (restorable_until - ?),
                           restorable_until = ?,
                           next_attempt_at = least(next_attempt_at, ?)
                     WHERE id = ? AND restorable_until > ?
                    """)) {
                    Timestamp at = Timestamp.valueOf(ended);
                    update.setTimestamp(1, at);
                    update.setTimestamp(2, at);
                    update.setTimestamp(3, at);
                    update.setObject(4, request);
                    update.setTimestamp(5, at);
                    return update.executeUpdate();
                } finally {
                    session.execute("SET session_replication_role = origin");
                }
            }
        });
        if (moved != 1) {
            throw new IllegalStateException("No deletion request in its grace period: " + request);
        }
    }
}
