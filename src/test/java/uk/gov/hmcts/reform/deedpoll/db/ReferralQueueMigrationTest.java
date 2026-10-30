package uk.gov.hmcts.reform.deedpoll.db;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import uk.gov.hmcts.reform.deedpoll.config.AbstractPostgresContainerTest;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code V46__referral_queue.sql} (plan F1): the cap's column renamed with its value, default and check, and the
 * Master email ledger admitting {@code SENT} beside the kinds already written. Each refusal is the last statement of
 * its test, because a refused statement aborts the test's transaction.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional
class ReferralQueueMigrationTest extends AbstractPostgresContainerTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 30, 10, 0);

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("a cap a Delivery Manager chose survives the rename, and the history keeps the old field name")
    void chosenCapKept() {
        // V40 and V42 as committed, in a schema search_path puts first, with V44's ledger reduced to its kind check.
        jdbcTemplate.execute("CREATE SCHEMA \"migration_proof\"");
        jdbcTemplate.execute("SET LOCAL search_path TO \"migration_proof\", \"public\"");
        jdbcTemplate.execute(sql("db/migration/V40__process_settings.sql"));
        jdbcTemplate.execute(sql("db/migration/V42__masters_and_referral_process_settings.sql"));
        jdbcTemplate.execute(
            "CREATE TABLE deedpoll_master_email (kind varchar(16) NOT NULL CHECK (kind IN ('DIGEST', 'REMINDER')))");
        jdbcTemplate.update("""
            UPDATE deedpoll_process_settings SET master_window_cap = 7, revision = 2, updated_at = now(),
                updated_by_id = 'dm-subject', updated_by_name = 'A Delivery Manager' WHERE id = 1
            """);
        jdbcTemplate.update("""
            INSERT INTO deedpoll_process_settings_change
                (revision, field, previous_value, new_value, changed_at, changed_by_id, changed_by_name)
            VALUES (2, 'masterWindowCap', '10', '7', now(), 'dm-subject', 'A Delivery Manager')
            """);

        jdbcTemplate.execute(sql("db/migration/V46__referral_queue.sql"));

        assertThat(jdbcTemplate.queryForMap(
            "SELECT window_cap, revision FROM \"migration_proof\".deedpoll_process_settings"))
            .containsEntry("window_cap", 7).containsEntry("revision", 2);
        assertThat(jdbcTemplate.queryForList(
            "SELECT field FROM \"migration_proof\".deedpoll_process_settings_change", String.class))
            .containsExactly("masterWindowCap");
        assertThat(jdbcTemplate.update("INSERT INTO \"migration_proof\".deedpoll_master_email VALUES ('SENT')"))
            .isOne();
    }

    @Test
    @DisplayName("a fresh database seeds the window cap at 10")
    void freshSeed() {
        assertThat(jdbcTemplate.queryForObject(
            "SELECT window_cap FROM deedpoll_process_settings WHERE id = 1", Integer.class)).isEqualTo(10);
        assertThat(jdbcTemplate.queryForObject("""
            SELECT count(*) FROM information_schema.columns
             WHERE table_name = 'deedpoll_process_settings' AND column_name = 'master_window_cap'
            """, Integer.class)).isZero();
    }

    @Test
    @DisplayName("the renamed check still refuses a cap above 100")
    void refusesACapAboveAHundred() {
        assertThatThrownBy(() -> jdbcTemplate.update("UPDATE deedpoll_process_settings SET window_cap = 101"))
            .isInstanceOf(DataIntegrityViolationException.class)
            .hasMessageContaining("deedpoll_process_settings_window_cap_check");
    }

    @Test
    @DisplayName("the renamed check still refuses a cap below 1")
    void refusesACapBelowOne() {
        assertThatThrownBy(() -> jdbcTemplate.update("UPDATE deedpoll_process_settings SET window_cap = 0"))
            .isInstanceOf(DataIntegrityViolationException.class)
            .hasMessageContaining("deedpoll_process_settings_window_cap_check");
    }

    @Test
    @DisplayName("the ledger admits SENT, and still DIGEST and REMINDER")
    void admitsSent() {
        UUID masterId = UUID.randomUUID();
        jdbcTemplate.update("""
            INSERT INTO deedpoll_master (id, name, email, created_at, created_by_id, created_by_name)
            VALUES (?, 'Master Sullivan', ?, ?, 'subject-dm', 'Dee Manager')
            """, masterId, masterId + "@example.test", NOW);

        for (String kind : new String[] {"SENT", "DIGEST", "REMINDER"}) {
            assertThat(jdbcTemplate.update("""
                INSERT INTO deedpoll_master_email (id, master_id, kind, period_key, referral_ids, state, created_at)
                VALUES (?, ?, ?, 'key', array[]::uuid[], 'Pending', ?)
                """, UUID.randomUUID(), masterId, kind, NOW)).isOne();
        }
    }

    /** A committed migration, as committed. */
    private static String sql(String migration) {
        try (var stream = new ClassPathResource(migration).getInputStream()) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + migration, e);
        }
    }
}
