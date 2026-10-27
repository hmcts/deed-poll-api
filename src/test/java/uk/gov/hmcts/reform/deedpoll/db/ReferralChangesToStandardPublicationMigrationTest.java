package uk.gov.hmcts.reform.deedpoll.db;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import uk.gov.hmcts.reform.deedpoll.config.AbstractPostgresContainerTest;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What {@code V41__referral_changes_to_standard_publication.sql} does to referrals that already carry a Master's
 * Gazette answer.
 *
 * <p>The migration runs at every context boot against an empty referral table, so an inversion that went the wrong
 * way, or one that turned a referral with no direction into a "No", would migrate cleanly on every build and be
 * found in the first environment with directions in it -- as a caseworker publishing a change the Master said to
 * hold back. Hence {@code NotificationRequestIdMigrationTest}'s method: the committed file, executed against a copy
 * of the table in the shape it had before, in a schema {@code search_path} puts first, inside the test's
 * transaction so the rollback takes all of it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional
class ReferralChangesToStandardPublicationMigrationTest extends AbstractPostgresContainerTest {

    private static final String MIGRATION = "db/migration/V41__referral_changes_to_standard_publication.sql";

    private static final String SCRATCH = "migration_proof";

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 1, 9, 0);

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * The referral table as it stood before V41: the committed table with the column under its old name.
     */
    @BeforeEach
    void rebuildTheTableAsItWasBeforeV41() {
        jdbcTemplate.execute("CREATE SCHEMA \"" + SCRATCH + "\"");
        jdbcTemplate.execute("""
            CREATE TABLE "migration_proof"."deedpoll_referral" (
                 LIKE "public"."deedpoll_referral" INCLUDING ALL)
            """);
        jdbcTemplate.execute("ALTER TABLE \"" + SCRATCH + "\".\"deedpoll_referral\" "
            + "RENAME COLUMN \"changesToStandardPublication\" TO \"publishToGazette\"");
    }

    @Test
    @DisplayName("inverts every answer: not publishing was a change to the standard publication, publishing was not")
    void invertsEveryAnswer() {
        UUID withheld = legacyReferral(false);
        UUID published = legacyReferral(true);

        migrate();

        assertThat(changes(withheld)).isTrue();
        assertThat(changes(published)).isFalse();
    }

    @Test
    @DisplayName("leaves a referral with no direction unanswered, rather than inventing a No")
    void leavesNoAnswerAsNoAnswer() {
        UUID undirected = legacyReferral(null);

        migrate();

        assertThat(changes(undirected)).isNull();
    }

    private void migrate() {
        jdbcTemplate.execute("SET LOCAL search_path TO \"" + SCRATCH + "\", \"public\"");
        jdbcTemplate.execute(migrationSql());
    }

    /** A referral as an instance before V41 wrote one, with the Master's answer under the old name. */
    private UUID legacyReferral(Boolean publishToGazette) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
            INSERT INTO "migration_proof"."deedpoll_referral" ("id", "deedPollId", "modifiedOn", "publishToGazette")
            VALUES (?, ?, ?, ?)
            """, id, "DP2026-000123", NOW, publishToGazette);
        return id;
    }

    private Boolean changes(UUID id) {
        return jdbcTemplate.queryForObject(
            "SELECT \"changesToStandardPublication\" FROM \"migration_proof\".\"deedpoll_referral\" WHERE \"id\" = ?",
            Boolean.class, id);
    }

    /** The committed migration, as committed. */
    private static String migrationSql() {
        try (var stream = new ClassPathResource(MIGRATION).getInputStream()) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + MIGRATION, e);
        }
    }
}
