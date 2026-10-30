package uk.gov.hmcts.reform.deedpoll.db;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import uk.gov.hmcts.reform.deedpoll.config.AbstractPostgresContainerTest;
import uk.gov.hmcts.reform.deedpoll.domain.auth.CurrentUser;
import uk.gov.hmcts.reform.deedpoll.repository.MasterRepository;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.sql.Date;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What {@code V42__masters_and_referral_process_settings.sql} does to a process settings row that already exists.
 *
 * <p>Every context boot runs V42 against V40's untouched seed, so the two branches for a value a Delivery Manager
 * chose would migrate cleanly on every build and be found in the first environment where somebody had saved one.
 * Hence {@code ReferralChangesToStandardPublicationMigrationTest}'s method: the committed files, V40 then V42, run in
 * a schema {@code search_path} puts first, with the row set between them as an earlier instance left it, inside the
 * test's transaction so the rollback takes all of it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional
class MastersAndReferralProcessSettingsMigrationTest extends AbstractPostgresContainerTest {

    private static final String V40 = "db/migration/V40__process_settings.sql";

    private static final String V42 = "db/migration/V42__masters_and_referral_process_settings.sql";

    private static final String SCRATCH = "migration_proof";

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** The settings tables as V40 left them: seeded at 120 minutes, revision 0. */
    @BeforeEach
    void rebuildTheTablesAsTheyWereBeforeV42() {
        jdbcTemplate.execute("CREATE SCHEMA \"" + SCRATCH + "\"");
        jdbcTemplate.execute("SET LOCAL search_path TO \"" + SCRATCH + "\", \"public\"");
        jdbcTemplate.execute(sql(V40));
    }

    @Test
    @DisplayName("a window never changed (revision 0) becomes seven days, with no history and no attribution")
    void untouchedSeedBecomesSevenDays() {
        migrate();

        assertThat(row()).containsEntry("referral_claim_expiry_minutes", 10080).containsEntry("revision", 0)
            .containsEntry("updated_at", null).containsEntry("updated_by_id", null);
        assertThat(history()).isEmpty();
    }

    @Test
    @DisplayName("a chosen window below a day becomes a day, as the service account's change with one history row")
    void chosenShortWindowRaisedToADay() {
        choose(120, 3);

        migrate();

        assertThat(row()).containsEntry("referral_claim_expiry_minutes", 1440).containsEntry("revision", 4)
            .containsEntry("updated_by_id", CurrentUser.SERVICE_ACCOUNT_NAME)
            .containsEntry("updated_by_name", CurrentUser.SERVICE_ACCOUNT_NAME);
        assertThat(row().get("updated_at")).isNotNull();
        assertThat(history()).singleElement().satisfies(change -> assertThat(change)
            .containsEntry("revision", 4)
            .containsEntry("field", "referralClaimExpiryMinutes")
            .containsEntry("previous_value", "120")
            .containsEntry("new_value", "1440")
            .containsEntry("changed_by_id", CurrentUser.SERVICE_ACCOUNT_NAME)
            .containsEntry("changed_by_name", CurrentUser.SERVICE_ACCOUNT_NAME));
    }

    @Test
    @DisplayName("a chosen window already a day or more is left exactly as the Delivery Manager saved it")
    void chosenWindowInRangeKept() {
        // V40 allowed at most 1440, so a chosen value in the new range can only be 1440; 5000 is what the plan
        // names, written past V40's check as an instance on a later release could have stored it.
        jdbcTemplate.execute("ALTER TABLE deedpoll_process_settings "
            + "DROP CONSTRAINT deedpoll_process_settings_referral_claim_expiry_minutes_check");
        jdbcTemplate.execute("ALTER TABLE deedpoll_process_settings ADD CONSTRAINT "
            + "deedpoll_process_settings_referral_claim_expiry_minutes_check "
            + "CHECK (referral_claim_expiry_minutes > 0)");
        choose(5000, 3);

        migrate();

        assertThat(row()).containsEntry("referral_claim_expiry_minutes", 5000).containsEntry("revision", 3)
            .containsEntry("updated_by_id", "dm-subject").containsEntry("updated_by_name", "A Delivery Manager");
        assertThat(history()).isEmpty();
    }

    @Test
    @DisplayName("a chosen window of exactly a day is kept, with no history")
    void chosenDayKept() {
        choose(1440, 2);

        migrate();

        assertThat(row()).containsEntry("referral_claim_expiry_minutes", 1440).containsEntry("revision", 2);
        assertThat(history()).isEmpty();
    }

    @Test
    @DisplayName("the new columns take their defaults on the existing row, and the widened check holds")
    void newColumnsAndWidenedCheck() {
        migrate();

        assertThat(row()).containsEntry("reminder_interval_hours", 48).containsEntry("master_window_cap", 10);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT morning_window_start FROM deedpoll_process_settings", LocalTime.class))
            .isEqualTo(LocalTime.of(7, 0));
        assertThatThrownBy(() -> jdbcTemplate.update(
            "UPDATE deedpoll_process_settings SET referral_claim_expiry_minutes = 43201"))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("a fresh database is seeded with the defaults and the 2026 to 2028 bank holidays")
    void freshDatabaseSeed() {
        // V46 has renamed the cap's column by now.
        jdbcTemplate.execute("SET LOCAL search_path TO \"public\"");

        assertThat(jdbcTemplate.queryForMap("""
            SELECT reminder_interval_hours, window_cap, morning_window_start::text AS ms,
                   morning_window_end::text AS me, afternoon_window_start::text AS afs,
                   afternoon_window_end::text AS afe
            FROM deedpoll_process_settings WHERE id = 1
            """)).containsEntry("reminder_interval_hours", 48).containsEntry("window_cap", 10)
            .containsEntry("ms", "07:00:00").containsEntry("me", "10:30:00")
            .containsEntry("afs", "14:00:00").containsEntry("afe", "14:30:00");
        List<LocalDate> dates = jdbcTemplate.queryForList(
            "SELECT holiday_date FROM deedpoll_bank_holiday ORDER BY holiday_date", Date.class)
            .stream().map(Date::toLocalDate).toList();
        // GOV.UK's England and Wales list, substitute days included, copied independently of the migration.
        assertThat(dates).map(LocalDate::toString).containsExactly(
            "2026-01-01", "2026-04-03", "2026-04-06", "2026-05-04", "2026-05-25", "2026-08-31", "2026-12-25",
            "2026-12-28", "2027-01-01", "2027-03-26", "2027-03-29", "2027-05-03", "2027-05-31", "2027-08-30",
            "2027-12-27", "2027-12-28", "2028-01-03", "2028-04-14", "2028-04-17", "2028-05-01", "2028-05-29",
            "2028-08-28", "2028-12-25", "2028-12-26");
        assertThat(dates).allSatisfy(date -> assertThat(date.getDayOfWeek().getValue()).isLessThan(6));
        assertThat(jdbcTemplate.queryForObject(
            "SELECT count(DISTINCT created_by_name) FROM deedpoll_bank_holiday WHERE created_by_name = ?",
            Integer.class, CurrentUser.SERVICE_ACCOUNT_NAME)).isOne();
    }

    @Test
    @DisplayName("the Masters list refuses a second entry for the same email in any case, and a subject twice")
    void mastersUniqueness() {
        migrate();
        insertMaster("a@example.com", "subject-1");

        refused(() -> insertMaster("A@Example.com", null));
        refused(() -> insertMaster("b@example.com", "subject-1"));
        insertMaster("c@example.com", null);
        insertMaster("d@example.com", null);
    }

    @Test
    @DisplayName("linking re-checks the email, so an entry whose address changed since it was read stays unlinked")
    void linkRechecksTheEmail() {
        migrate();
        insertMaster("old@example.com", null);
        MasterRepository masters = new MasterRepository(jdbcTemplate);
        UUID id = masters.linkingByEmail("old@example.com").orElseThrow().id();
        jdbcTemplate.update("UPDATE deedpoll_master SET email = 'new@example.com' WHERE id = ?", id);

        assertThat(masters.link(id, "old@example.com", "subject-old", LocalDateTime.now())).isFalse();
        assertThat(masters.linkingByEmail("new@example.com").orElseThrow().subject()).isNull();
        assertThat(masters.link(id, "NEW@example.com", "subject-new", LocalDateTime.now())).isTrue();
        assertThat(masters.linkingByEmail("new@example.com").orElseThrow().subject()).isEqualTo("subject-new");
    }

    /** Refused, inside a savepoint, so the test's transaction survives the error. */
    private void refused(Runnable statement) {
        jdbcTemplate.execute("SAVEPOINT refused");
        assertThatThrownBy(statement::run).isInstanceOf(DataIntegrityViolationException.class);
        jdbcTemplate.execute("ROLLBACK TO SAVEPOINT refused");
    }

    private void insertMaster(String email, String subject) {
        jdbcTemplate.update("""
            INSERT INTO deedpoll_master (id, name, email, idam_subject, linked_at, created_at, created_by_id,
                created_by_name)
            VALUES (gen_random_uuid(), 'Master', ?, ?, CASE WHEN ?::text IS NULL THEN NULL ELSE now() END, now(),
                'x', 'X')
            """, email, subject, subject);
    }

    /** The row as a Delivery Manager's save left it. */
    private void choose(int minutes, int revision) {
        jdbcTemplate.update("""
            UPDATE deedpoll_process_settings SET referral_claim_expiry_minutes = ?, revision = ?, updated_at = now(),
                updated_by_id = 'dm-subject', updated_by_name = 'A Delivery Manager' WHERE id = 1
            """, minutes, revision);
    }

    private void migrate() {
        jdbcTemplate.execute(sql(V42));
    }

    private Map<String, Object> row() {
        return jdbcTemplate.queryForMap("SELECT * FROM \"migration_proof\".\"deedpoll_process_settings\"");
    }

    private List<Map<String, Object>> history() {
        return jdbcTemplate.queryForList("SELECT * FROM \"migration_proof\".\"deedpoll_process_settings_change\"");
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
