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
 * What {@code V23__ccd_attempt_repair.sql} actually does to a table that already has rows in it.
 *
 * <p><strong>Nothing else can assert this</strong>, for the reason {@code NotificationRequestIdMigrationTest}
 * gives: the migration runs during every context boot against an empty table, so a backfill that wrote
 * {@code false} into existing rows -- the one value that would let an admin retry from scratch an attempt that may
 * already have created a CCD case -- would migrate cleanly on every build.
 *
 * <p>The same technique as that test: the committed file is executed, not restated, against a copy of the real
 * table in a schema of its own, taken with {@code LIKE ... INCLUDING ALL} and stripped of the four columns V23
 * adds, with {@code search_path} putting the copy first. Everything is inside the test's transaction, so the
 * rollback takes the schema with it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional
class CcdAttemptRepairMigrationTest extends AbstractPostgresContainerTest {

    private static final String MIGRATION = "db/migration/V23__ccd_attempt_repair.sql";

    /**
     * The schema the pre-migration copy lives in, and the one {@code search_path} puts first.
     */
    private static final String SCRATCH = "migration_proof";

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 1, 12, 0);

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** The committed migration, as committed. */
    private static String migrationSql() {
        try (var stream = new ClassPathResource(MIGRATION).getInputStream()) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + MIGRATION, e);
        }
    }

    /**
     * The attempt table as it stood before V23. No foreign key, because {@code LIKE} copies none, which is why the
     * rows below need no deed-poll record to belong to.
     */
    @BeforeEach
    void rebuildTheTableAsItWasBeforeV23() {
        jdbcTemplate.execute("CREATE SCHEMA \"" + SCRATCH + "\"");
        jdbcTemplate.execute("""
            CREATE TABLE "migration_proof"."deedpoll_ccdcreationattempt" (
                 LIKE "public"."deedpoll_ccdcreationattempt" INCLUDING ALL)
            """);
        jdbcTemplate.execute("""
            ALTER TABLE "migration_proof"."deedpoll_ccdcreationattempt"
                DROP COLUMN "submissionMayHaveReached",
                DROP COLUMN "notBefore",
                DROP COLUMN "repairedById",
                DROP COLUMN "repairedOn"
            """);
    }

    @Test
    @DisplayName("every existing row is backfilled as possibly submitted, whatever its status")
    void backfillsEveryExistingRowTrue() {
        // Pending and NeedsRepair are the two that matter: a Pending row really has submitted nothing, but plan 02
        // recorded no such fact, so nobody can vouch for it -- and NeedsRepair is where a false would let an admin
        // retry from scratch an attempt that may already have created a case.
        UUID pending = legacyRow("Pending");
        UUID needsRepair = legacyRow("NeedsRepair");
        UUID linked = legacyRow("Linked");

        migrate();

        for (UUID id : new UUID[] {pending, needsRepair, linked}) {
            assertThat(columnsOf(id))
                .containsEntry("submissionMayHaveReached", true)
                .containsEntry("notBefore", null)
                .containsEntry("repairedById", null)
                .containsEntry("repairedOn", null);
        }
    }

    @Test
    @DisplayName("a row opened afterwards, by the insert the application issues, has submitted nothing")
    void newRowDefaultsToFalse() {
        migrate();

        // Exactly the columns CcdCreationAttemptRepository.open names: it does not name the new one, so the
        // default is what a freshly opened attempt gets.
        UUID opened = legacyRow("Pending");

        assertThat(columnsOf(opened))
            .containsEntry("submissionMayHaveReached", false)
            .containsEntry("notBefore", null);
    }

    @Test
    @DisplayName("the flag ends up NOT NULL with a default of false, and the other three nullable")
    void leavesTheColumnsShapedAsThePlanSays() {
        migrate();

        Map<String, Object> flag = column("submissionMayHaveReached");
        assertThat(flag).containsEntry("is_nullable", "NO").containsEntry("data_type", "boolean");
        assertThat(flag.get("column_default")).asString().isEqualTo("false");
        assertThat(column("notBefore")).containsEntry("is_nullable", "YES");
        assertThat(column("repairedById")).containsEntry("is_nullable", "YES").containsEntry("data_type", "text");
        assertThat(column("repairedOn")).containsEntry("is_nullable", "YES");
    }

    /**
     * Runs the committed migration against the copy; {@code SET LOCAL}, so the setting goes with the rollback.
     */
    private void migrate() {
        jdbcTemplate.execute("SET LOCAL search_path TO \"" + SCRATCH + "\", \"public\"");
        jdbcTemplate.execute(migrationSql());
    }

    /**
     * A row as plan 02's application writes one: the columns {@code open} names, and no others.
     */
    private UUID legacyRow(String status) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
            INSERT INTO "migration_proof"."deedpoll_ccdcreationattempt" (
                        "id", "deedPollId", "operationId", "status", "attempts", "createdOn", "modifiedOn")
                 VALUES (?, ?, ?, CAST(? AS "public"."CcdCreationAttemptStatus"), 1, ?, ?)
            """, id, UUID.randomUUID().toString(), UUID.randomUUID(), status, NOW, NOW);
        return id;
    }

    private Map<String, Object> columnsOf(UUID id) {
        return jdbcTemplate.queryForMap("""
            select "submissionMayHaveReached", "notBefore", "repairedById", "repairedOn"
              from "migration_proof"."deedpoll_ccdcreationattempt"
             where "id" = ?
            """, id);
    }

    private Map<String, Object> column(String name) {
        return jdbcTemplate.queryForMap("""
            select "is_nullable", "data_type", "column_default"
              from "information_schema"."columns"
             where "table_schema" = ?
               and "table_name" = 'deedpoll_ccdcreationattempt'
               and "column_name" = ?
            """, SCRATCH, name);
    }
}
