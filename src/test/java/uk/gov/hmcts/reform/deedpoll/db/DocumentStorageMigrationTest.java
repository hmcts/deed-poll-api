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
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What {@code V25__document_storage_state.sql} actually does to a table that already has rows in it.
 *
 * <p>{@link CcdAttemptProofMigrationTest}'s reason and technique: every context boot migrates an empty table, so a
 * backfill that left existing documents in any state but {@code Local} -- which would stop them being served --
 * would migrate cleanly on every build. The committed file is executed against a copy of the real table stripped of
 * the columns V25 adds, inside the test's transaction.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional
class DocumentStorageMigrationTest extends AbstractPostgresContainerTest {

    private static final String MIGRATION = "db/migration/V25__document_storage_state.sql";

    private static final String SCRATCH = "migration_proof";

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 1, 12, 0);

    private static final String TOKEN = "a".repeat(64);

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private static String migrationSql() {
        try (var stream = new ClassPathResource(MIGRATION).getInputStream()) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + MIGRATION, e);
        }
    }

    /** The document table as it stood before V25. */
    @BeforeEach
    void rebuildTheTableAsItWasBeforeV25() {
        jdbcTemplate.execute("CREATE SCHEMA \"" + SCRATCH + "\"");
        jdbcTemplate.execute("""
            CREATE TABLE "migration_proof"."deedpoll_supportingdocument" (
                 LIKE "public"."deedpoll_supportingdocument" INCLUDING ALL)
            """);
        jdbcTemplate.execute("""
            ALTER TABLE "migration_proof"."deedpoll_supportingdocument"
                DROP COLUMN "storageState",
                DROP COLUMN "cdamDocumentId",
                DROP COLUMN "cdamHashToken",
                DROP COLUMN "storageAttemptedOn",
                DROP COLUMN "storageUploadAttempts"
            """);
    }

    @Test
    @DisplayName("every existing row, with a file or without one, is backfilled Local with no uploads and no CDAM id")
    void backfillsEveryExistingRowLocal() {
        String withFile = existingRow("local/2026/birth-certificate.pdf");
        String withoutFile = existingRow(null);

        migrate();

        for (String id : new String[]{withFile, withoutFile}) {
            Map<String, Object> row = jdbcTemplate.queryForMap("""
                select "storageState", "cdamDocumentId", "cdamHashToken", "storageAttemptedOn",
                       "storageUploadAttempts"
                  from "migration_proof"."deedpoll_supportingdocument"
                 where "id" = ?
                """, id);
            assertThat(row).containsEntry("storageState", "Local")
                .containsEntry("storageUploadAttempts", 0)
                .containsEntry("cdamDocumentId", null)
                .containsEntry("cdamHashToken", null)
                .containsEntry("storageAttemptedOn", null);
        }
    }

    @Test
    @DisplayName("the columns end up shaped as the plan says, and a row written without naming a state is Local")
    void leavesTheColumnsShapedAsThePlanSays() {
        migrate();

        assertThat(column("storageState")).containsEntry("is_nullable", "NO")
            .containsEntry("data_type", "character varying");
        assertThat(column("storageState").get("column_default")).asString().startsWith("'Local'");
        assertThat(column("cdamDocumentId")).containsEntry("is_nullable", "YES").containsEntry("data_type", "uuid");
        assertThat(column("cdamHashToken")).containsEntry("is_nullable", "YES")
            .containsEntry("character_maximum_length", 64);
        assertThat(column("storageAttemptedOn")).containsEntry("is_nullable", "YES")
            .containsEntry("data_type", "timestamp without time zone");
        assertThat(column("storageUploadAttempts")).containsEntry("is_nullable", "NO")
            .containsEntry("data_type", "smallint");
        assertThat(column("storageUploadAttempts").get("column_default")).asString().isEqualTo("0");

        String written = existingRow(null);
        assertThat(jdbcTemplate.queryForObject("""
            select "storageState" from "migration_proof"."deedpoll_supportingdocument" where "id" = ?
            """, String.class, written)).isEqualTo("Local");
    }

    @Test
    @DisplayName("refuses a state the enum does not name")
    void refusesAnUnknownState() {
        migrate();
        String id = existingRow(null);

        assertThatThrownBy(() -> setState(id, "Stored", null, null, 0))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("refuses a third upload")
    void refusesAThirdUpload() {
        migrate();
        String id = existingRow(null);

        setState(id, "UploadUncertain", null, null, 2);
        assertThatThrownBy(() -> setState(id, "UploadUncertain", null, null, 3))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("holds the hash token exactly while Uploaded or AttachUncertain")
    void holdsTheHashTokenOnlyWhileAttaching() {
        migrate();
        String id = existingRow(null);
        UUID cdamId = UUID.randomUUID();

        setState(id, "Uploaded", cdamId, TOKEN, 1);
        setState(id, "AttachUncertain", cdamId, TOKEN, 1);
        setState(id, "Attached", cdamId, null, 1);
        // A refused statement aborts the test's transaction, so each refusal is the last statement of its own test.
        assertThatThrownBy(() -> setState(id, "Attached", cdamId, TOKEN, 1))
            .as("a token kept past the attach").isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("refuses an Uploaded row with no hash token, which could never be attached")
    void refusesAnUploadWithNoToken() {
        migrate();
        String id = existingRow(null);

        assertThatThrownBy(() -> setState(id, "Uploaded", UUID.randomUUID(), null, 1))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("refuses a state that says CDAM holds the document without naming it")
    void refusesACdamStateWithNoId() {
        migrate();
        String id = existingRow(null);

        assertThatThrownBy(() -> setState(id, "Attached", null, null, 1))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    private void migrate() {
        jdbcTemplate.execute("SET LOCAL search_path TO \"" + SCRATCH + "\", \"public\"");
        jdbcTemplate.execute(migrationSql());
    }

    private Map<String, Object> column(String name) {
        return jdbcTemplate.queryForMap("""
            select "is_nullable", "data_type", "column_default", "character_maximum_length"
              from "information_schema"."columns"
             where "table_schema" = ?
               and "table_name" = 'deedpoll_supportingdocument'
               and "column_name" = ?
            """, SCRATCH, name);
    }

    private String existingRow(String storageKey) {
        String id = UUID.randomUUID().toString();
        jdbcTemplate.update("""
            INSERT INTO "migration_proof"."deedpoll_supportingdocument" (
                        "id", "deedPollId", "direction", "name", "documentTypeId", "storageKey",
                        "createdOn", "modifiedOn")
                 VALUES (?, ?, CAST('Incoming' AS "public"."DocumentDirection"), 'Birth certificate', ?, ?, ?, ?)
            """, id, UUID.randomUUID().toString(), UUID.randomUUID().toString(), storageKey, NOW, NOW);
        return id;
    }

    private void setState(String id, String state, UUID cdamDocumentId, String hashToken, int uploadAttempts) {
        jdbcTemplate.update("""
            UPDATE "migration_proof"."deedpoll_supportingdocument"
               SET "storageState" = ?, "cdamDocumentId" = ?, "cdamHashToken" = ?, "storageUploadAttempts" = ?
             WHERE "id" = ?
            """, state, cdamDocumentId, hashToken, uploadAttempts, id);
    }
}
