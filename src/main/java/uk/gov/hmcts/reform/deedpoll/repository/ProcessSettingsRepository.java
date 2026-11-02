package uk.gov.hmcts.reform.deedpoll.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import uk.gov.hmcts.reform.deedpoll.domain.settings.ProcessSettings;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

/**
 * The singleton is seeded by Flyway (V40, extended by V42), so the first editor has the same lock and revision as
 * every later one, and a missing row is an error rather than a default.
 */
@Repository
public class ProcessSettingsRepository {

    // Compile-time constants, one per variant, so no statement is assembled at runtime.
    private static final String READ = "SELECT referral_claim_expiry_minutes, reminder_interval_hours, "
        + "window_cap, morning_window_start, morning_window_end, afternoon_window_start, afternoon_window_end, "
        + "revision, updated_at, updated_by_name FROM deedpoll_process_settings WHERE id = 1";
    private static final String READ_FOR_UPDATE = READ + " FOR UPDATE";

    private final JdbcTemplate jdbc;

    public ProcessSettingsRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** The row, joining the caller's transaction if there is one; empty only if somebody deleted it. */
    public Optional<ProcessSettings> read(boolean lock) {
        List<ProcessSettings> rows = jdbc.query(lock ? READ_FOR_UPDATE : READ,
            (row, index) -> new ProcessSettings(Duration.ofMinutes(row.getInt("referral_claim_expiry_minutes")),
                Duration.ofHours(row.getInt("reminder_interval_hours")), row.getInt("window_cap"),
                row.getObject("morning_window_start", LocalTime.class),
                row.getObject("morning_window_end", LocalTime.class),
                row.getObject("afternoon_window_start", LocalTime.class),
                row.getObject("afternoon_window_end", LocalTime.class),
                row.getInt("revision"), row.getObject("updated_at", LocalDateTime.class),
                row.getString("updated_by_name")));
        return rows.stream().findFirst();
    }

    /**
     * One history row per changed field, then the singleton. Called inside the service transaction while it holds
     * the singleton lock, so a refused history row leaves the singleton as it was.
     */
    public void change(ProcessSettings previous, ProcessSettings next, String subject, List<String> changedFields) {
        for (String field : changedFields) {
            jdbc.update("""
                INSERT INTO deedpoll_process_settings_change
                    (revision, field, previous_value, new_value, changed_at, changed_by_id, changed_by_name)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, next.revision(), field, previous.valueOf(field), next.valueOf(field), next.updatedAt(),
                subject, next.updatedBy());
        }
        jdbc.update("""
            UPDATE deedpoll_process_settings SET referral_claim_expiry_minutes = ?, reminder_interval_hours = ?,
                window_cap = ?, morning_window_start = ?, morning_window_end = ?, afternoon_window_start = ?,
                afternoon_window_end = ?, revision = ?, updated_at = ?, updated_by_id = ?, updated_by_name = ?
            WHERE id = 1
            """, next.referralClaimExpiryMinutes(), next.reminderIntervalHours(), next.windowCap(),
            next.morningWindowStart(), next.morningWindowEnd(), next.afternoonWindowStart(),
            next.afternoonWindowEnd(), next.revision(), next.updatedAt(), subject, next.updatedBy());
    }
}
