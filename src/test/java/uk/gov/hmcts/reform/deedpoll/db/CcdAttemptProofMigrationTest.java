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
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What {@code V24__ccd_attempt_proven_nothing_created.sql} actually does to a table that already has rows in it.
 *
 * <p>{@link CcdAttemptRepairMigrationTest}'s reason and technique: every context boot migrates an empty table, so a
 * default of {@code true} -- the one value that would open a retry from scratch to every attempt that may already
 * have created a case -- would migrate cleanly on every build. The committed file is executed against a copy of the
 * real table stripped of the column V24 adds, inside the test's transaction.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional
class CcdAttemptProofMigrationTest extends AbstractPostgresContainerTest {

    private static final String MIGRATION = "db/migration/V24__ccd_attempt_proven_nothing_created.sql";

    private static final String SCRATCH = "migration_proof";

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 1, 12, 0);

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private static String migrationSql() {
        try (var stream = new ClassPathResource(MIGRATION).getInputStream()) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + MIGRATION, e);
        }
    }

    /** The attempt table as it stood before V24. */
    @BeforeEach
    void rebuildTheTableAsItWasBeforeV24() {
        jdbcTemplate.execute("CREATE SCHEMA \"" + SCRATCH + "\"");
        jdbcTemplate.execute("""
            CREATE TABLE "migration_proof"."deedpoll_ccdcreationattempt" (
                 LIKE "public"."deedpoll_ccdcreationattempt" INCLUDING ALL)
            """);
        jdbcTemplate.execute("""
            ALTER TABLE "migration_proof"."deedpoll_ccdcreationattempt"
                DROP COLUMN "provenNothingCreated"
            """);
    }

    @Test
    @DisplayName("every existing row, a submitted NeedsRepair one above all, is left unproven")
    void backfillsEveryExistingRowFalse() {
        // A NeedsRepair row that may have been submitted is the one that matters: true would open it to a retry.
        UUID needsRepair = existingRow("NeedsRepair", true);
        UUID pending = existingRow("Pending", false);

        migrate();

        assertThat(provenOf(needsRepair)).isFalse();
        assertThat(provenOf(pending)).isFalse();
    }

    @Test
    @DisplayName("the column ends up NOT NULL with a default of false, so an opened attempt proves nothing")
    void leavesTheColumnShapedAsThePlanSays() {
        migrate();

        Map<String, Object> column = jdbcTemplate.queryForMap("""
            select "is_nullable", "data_type", "column_default"
              from "information_schema"."columns"
             where "table_schema" = ?
               and "table_name" = 'deedpoll_ccdcreationattempt'
               and "column_name" = 'provenNothingCreated'
            """, SCRATCH);
        assertThat(column).containsEntry("is_nullable", "NO").containsEntry("data_type", "boolean");
        assertThat(column.get("column_default")).asString().isEqualTo("false");
        assertThat(provenOf(existingRow("Pending", false))).isFalse();
    }

    private void migrate() {
        jdbcTemplate.execute("SET LOCAL search_path TO \"" + SCRATCH + "\", \"public\"");
        jdbcTemplate.execute(migrationSql());
    }

    private UUID existingRow(String status, boolean submissionMayHaveReached) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
            INSERT INTO "migration_proof"."deedpoll_ccdcreationattempt" (
                        "id", "deedPollId", "operationId", "status", "attempts", "submissionMayHaveReached",
                        "createdOn", "modifiedOn")
                 VALUES (?, ?, ?, CAST(? AS "public"."CcdCreationAttemptStatus"), 3, ?, ?, ?)
            """, id, UUID.randomUUID().toString(), UUID.randomUUID(), status, submissionMayHaveReached, NOW, NOW);
        return id;
    }

    private boolean provenOf(UUID id) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject("""
            select "provenNothingCreated"
              from "migration_proof"."deedpoll_ccdcreationattempt"
             where "id" = ?
            """, Boolean.class, id));
    }
}
