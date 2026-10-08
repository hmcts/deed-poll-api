package uk.gov.hmcts.reform.deedpoll.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import uk.gov.hmcts.reform.deedpoll.domain.gazette.GazetteSettings;

import java.time.LocalDateTime;

/** The singleton is seeded by Flyway so first-time editors have the same lock and revision. */
@Repository
public class GazetteSettingsRepository {

    private final JdbcTemplate jdbc;

    public GazetteSettingsRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public GazetteSettings read(boolean lock) {
        return jdbc.queryForObject("SELECT recipient_email, revision, updated_at, updated_by_name "
            + "FROM deedpoll_gazette_settings WHERE id = 1" + (lock ? " FOR UPDATE" : ""),
            (row, index) -> new GazetteSettings(row.getString("recipient_email"), row.getInt("revision"),
                row.getObject("updated_at", LocalDateTime.class), row.getString("updated_by_name")));
    }

    /** Called inside the service transaction while it holds the singleton lock. */
    public void change(GazetteSettings previous, GazetteSettings next, String subject) {
        jdbc.update("""
            INSERT INTO deedpoll_gazette_settings_change
                (revision, previous_email, recipient_email, changed_at, changed_by_id, changed_by_name)
            VALUES (?, ?, ?, ?, ?, ?)
            """, next.revision(), previous.recipientEmail(), next.recipientEmail(), next.updatedAt(),
            subject, next.updatedBy());
        jdbc.update("""
            UPDATE deedpoll_gazette_settings SET recipient_email = ?, revision = ?, updated_at = ?,
                updated_by_id = ?, updated_by_name = ? WHERE id = 1
            """, next.recipientEmail(), next.revision(), next.updatedAt(), subject, next.updatedBy());
    }
}
