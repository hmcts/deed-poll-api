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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What {@code V19__notification_request_id.sql} actually does to a table that already has rows in it.
 *
 * <p><strong>Nothing else can assert this, for the reason {@code ReferralBackfillTest} exists.</strong> The
 * migration runs during every context boot, but against an empty outbox -- so a backfill that wrote the wrong
 * value, or a sequence that made the column {@code NOT NULL} before filling it, would migrate cleanly on every
 * build and be discovered once, in the first environment that has sent an email. Every environment that ever
 * applies this file has rows; this build's does not.
 *
 * <p><strong>The file is read and executed rather than restated.</strong> A copy of the SQL here would be a copy
 * that stops being the migration the moment either changes, which is the failure this test exists to prevent.
 *
 * <p><strong>It runs against a copy of the table in a schema of its own, rebuilt to the shape the column did not
 * exist in.</strong> That is the one way to execute an {@code ADD COLUMN} whose column Flyway has already added:
 * {@code search_path} puts the copy first, so every unqualified {@code "deedpoll_notification"} in the migration
 * resolves to it and the real table is untouched. The copy is taken from the real table -- {@code LIKE ...
 * INCLUDING ALL} -- rather than written out, so it is the committed schema minus the one column, constraints and
 * keys included, and not a guess at it.
 *
 * <p>Everything here is inside the test's transaction, including {@code CREATE SCHEMA}: Postgres makes DDL
 * transactional, so the rollback takes the schema, the copy and its index with it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional
class NotificationRequestIdMigrationTest extends AbstractPostgresContainerTest {

    private static final String MIGRATION = "db/migration/V19__notification_request_id.sql";

    /**
     * The schema the pre-migration copy lives in, and the one {@code search_path} puts first.
     */
    private static final String SCRATCH = "migration_proof";

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 6, 1, 12, 0);

    private static final String CASE_ID = "DP2026-000123";

    private static final String ANOTHER_CASE_ID = "DP2026-000124";

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
     * The outbox as it stood before V19: the committed table, minus the column this migration adds.
     *
     * <p><strong>{@code LIKE ... INCLUDING ALL} and not {@code CREATE TABLE ... AS SELECT}.</strong> The latter
     * copies column names and types and <em>nothing else</em> -- no {@code NOT NULL}, no defaults, no primary key,
     * no indexes -- so the migration would have been run against a table far looser than the one it will meet,
     * and neither the backfill nor step 5's {@code CREATE UNIQUE INDEX} would have been executed alongside V12's
     * and V15's partial keys. {@code INCLUDING ALL} brings all of that, which is what makes this "the committed
     * schema minus the one column" rather than a sketch of it: dropping {@code "requestId"} takes the copied
     * V19 index with it, leaving exactly the pre-V19 shape.
     *
     * <p>No foreign keys, because {@code LIKE} copies none -- which suits this test, since what is under
     * examination is one column and its index rather than the case a row belongs to.
     */
    @BeforeEach
    void rebuildTheTableAsItWasBeforeV19() {
        jdbcTemplate.execute("CREATE SCHEMA \"" + SCRATCH + "\"");
        jdbcTemplate.execute("""
            CREATE TABLE "migration_proof"."deedpoll_notification" (
                 LIKE "public"."deedpoll_notification" INCLUDING ALL)
            """);
        jdbcTemplate.execute(
            "ALTER TABLE \"" + SCRATCH + "\".\"deedpoll_notification\" DROP COLUMN \"requestId\"");
    }

    @Test
    @DisplayName("every existing row is backfilled from its own id, so no two of a case's rows collide")
    void backfillsEveryRowFromItsOwnId() {
        // Two rows on one case, which is what the backfill has to survive: the key is per case, so a backfill from
        // anything but a per-row value -- a constant, or the case id -- would make the unique index below
        // impossible to build and fail the migration in the first environment that had chased anybody twice.
        List<UUID> onOneCase = List.of(legacyRow(CASE_ID, NOW), legacyRow(CASE_ID, NOW.plusDays(7)));
        UUID elsewhere = legacyRow(ANOTHER_CASE_ID, NOW);

        migrate();

        // From "id" rather than from gen_random_uuid(), and that is what is asserted rather than merely
        // "something non-null": the value is deterministic, so a repair run writes the same values again, and a
        // row whose request id is its own id reads as "nobody supplied one". It is also already the Notify
        // `reference` the send carried, so a historical row's two identifiers agree.
        for (UUID id : onOneCase) {
            assertThat(requestIdOf(id)).isEqualTo(id);
        }
        assertThat(requestIdOf(elsewhere)).isEqualTo(elsewhere);
    }

    @Test
    @DisplayName("the column ends up NOT NULL with a generated default, which is the rolling-deployment sequence")
    void leavesTheColumnNotNullWithAGeneratedDefault() {
        legacyRow(CASE_ID, NOW);

        migrate();

        Map<String, Object> column = jdbcTemplate.queryForMap("""
            select "is_nullable", "column_default"
              from "information_schema"."columns"
             where "table_schema" = ?
               and "table_name" = 'deedpoll_notification'
               and "column_name" = 'requestId'
            """, SCRATCH);
        // NOT NULL, and only after the backfill: reversing those two steps fails the migration outright on any
        // table with a row in it.
        assertThat(column).containsEntry("is_nullable", "NO");
        // And the default, which is what carries an application instance that predates this migration: such an
        // instance issues an INSERT that does not name the column, and without a default every send from an old
        // pod would violate the NOT NULL for as long as old and new pods overlap.
        assertThat(column.get("column_default")).asString().contains("gen_random_uuid()");
    }

    @Test
    @DisplayName("an insert that does not name the column still works afterwards, and gets a value of its own")
    void carriesAnInstanceThatPredatesTheMigration() {
        migrate();

        // Exactly what an old pod issues, twice: the default has to be unique per row as well as present, because
        // a constant would make the second send on any one case collide with the first and be answered as an
        // idempotent repeat of an email it has nothing to do with.
        UUID first = legacyRow(CASE_ID, NOW);
        UUID second = legacyRow(CASE_ID, NOW.plusMinutes(1));

        assertThat(List.of(requestIdOf(first), requestIdOf(second)))
            .doesNotContainNull()
            .doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("the index it builds is unique on the case and the request, and refuses a second of the pair")
    void buildsTheUniqueIndexOnTheCaseAndTheRequest() {
        legacyRow(CASE_ID, NOW);

        migrate();

        assertThat(jdbcTemplate.queryForObject("""
            select "indexdef"
              from "pg_indexes"
             where "schemaname" = ?
               and "indexname" = 'deedpoll_notification_case_request_key'
            """, String.class, SCRATCH))
            .as("unique, and on the pair -- scoped to the case, so a caller's chosen UUID cannot reach another")
            .contains("UNIQUE")
            .contains("\"deedPollId\"")
            .contains("\"requestId\"")
            // Deliberately total, unlike V12's and V15's predicate: a permanently refused row keeps its request
            // id, so a retry of the same request answers with that failed row rather than writing a second.
            .doesNotContain("WHERE");

        // And it bites, which is the only thing that makes any of the above worth having. Last, because a
        // constraint violation aborts the transaction and nothing after it could run.
        UUID requestId = UUID.randomUUID();
        insertWithRequestId(CASE_ID, requestId);
        assertThatThrownBy(() -> insertWithRequestId(CASE_ID, requestId))
            .isInstanceOf(DataIntegrityViolationException.class)
            .hasMessageContaining("deedpoll_notification_case_request_key");
    }

    /**
     * Runs the committed migration against the copy, by pointing {@code search_path} at it.
     *
     * <p>{@code SET LOCAL}, so the setting belongs to this transaction and goes when it rolls back. The whole file
     * in one {@code execute}, because the order of its statements is the thing being asserted -- splitting it here
     * would be this test choosing an order rather than reading the one that ships.
     */
    private void migrate() {
        jdbcTemplate.execute("SET LOCAL search_path TO \"" + SCRATCH + "\", \"public\"");
        jdbcTemplate.execute(migrationSql());
    }

    /**
     * A row as an instance that has never heard of {@code requestId} writes one: the column is not named.
     */
    private UUID legacyRow(String deedPollId, LocalDateTime createdOn) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
            INSERT INTO "migration_proof"."deedpoll_notification" (
                        "id", "deedPollId", "template", "exclusionGroup", "recipient", "personalisation",
                        "state", "attempts", "nextAttemptAt", "createdBy", "createdOn", "modifiedOn")
                 VALUES (?, ?, 'CHASE_MISSING_DOCUMENTS', 'DOCUMENT_CHASE', 'applicant@example.com',
                        CAST('{"reference_number":"DP2026-000123"}' AS jsonb),
                        'Pending', 0, ?, 'A Caseworker', ?, ?)
            """, id, deedPollId, createdOn, createdOn, createdOn);
        return id;
    }

    /** A row written the way this release writes one: the caller's own request id, bound explicitly. */
    private void insertWithRequestId(String deedPollId, UUID requestId) {
        jdbcTemplate.update("""
            INSERT INTO "migration_proof"."deedpoll_notification" (
                        "id", "deedPollId", "requestId", "template", "exclusionGroup", "recipient",
                        "personalisation", "state", "attempts", "nextAttemptAt", "createdBy", "createdOn",
                        "modifiedOn")
                 VALUES (?, ?, ?, 'CHASE_MISSING_DOCUMENTS', 'DOCUMENT_CHASE', 'applicant@example.com',
                        CAST('{}' AS jsonb), 'Pending', 0, ?, 'A Caseworker', ?, ?)
            """, UUID.randomUUID(), deedPollId, requestId, NOW, NOW, NOW);
    }

    private UUID requestIdOf(UUID id) {
        return jdbcTemplate.queryForObject("""
            select "requestId"
              from "migration_proof"."deedpoll_notification"
             where "id" = ?
            """, UUID.class, id);
    }
}
