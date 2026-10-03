package uk.gov.hmcts.reform.deedpoll.db;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import uk.gov.hmcts.reform.deedpoll.config.AbstractPostgresContainerTest;
import uk.gov.hmcts.reform.deedpoll.storage.StagedPayloads;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Executes V26 against populated V25 table shapes; legacy duplicate rows remain evidence. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional
class DeedPreparationMigrationTest extends AbstractPostgresContainerTest {
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void migratePopulatedV25() throws IOException {
        jdbc.execute("CREATE SCHEMA deed_preparation_migration");
        for (String table : new String[]{"deedpoll_deedpoll", "deedpoll_supportingdocument"}) {
            jdbc.execute("CREATE TABLE deed_preparation_migration." + table
                + " (LIKE public." + table + " INCLUDING ALL)");
        }
        jdbc.execute("SET LOCAL search_path TO deed_preparation_migration, public");
        jdbc.update("INSERT INTO deedpoll_deedpoll (id, \"modifiedOn\") VALUES ('case', now())");
        jdbc.update("""
            INSERT INTO deedpoll_supportingdocument
                (id, "deedPollId", direction, name, "documentTypeId", "modifiedOn")
            VALUES ('legacy1', 'case', 'Outgoing', 'LOC020', 'type', now()),
                   ('legacy2', 'case', 'Incoming', 'LOC020', 'type', now())
            """);
        try (var input = new ClassPathResource("db/migration/V26__deed_preparation.sql").getInputStream()) {
            jdbc.execute(new String(input.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    @Test
    void preservesLegacyDuplicatesWithoutInventingProvenance() {
        assertThat(jdbc.queryForList("SELECT id FROM deedpoll_supportingdocument ORDER BY id", String.class))
            .containsExactly("legacy1", "legacy2");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM deedpoll_deedpreparation", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM deedpoll_stagedpayload", Long.class)).isZero();
        assertThat(jdbc.queryForList("SELECT \"storageState\" FROM deedpoll_supportingdocument", String.class))
            .containsOnly("Local");
    }

    @Test
    void uniqueCaseKeySpansBothTemplates() {
        reservation("doc1", "ADULT");
        assertThatThrownBy(() -> reservation("doc2", "MINOR")).isInstanceOf(DataAccessException.class);
    }

    @Test
    void refusesMetadataRewrite() {
        reservation("doc1", "ADULT");
        assertThatThrownBy(() -> jdbc.update("UPDATE deedpoll_deedpreparation SET \"originalName\" = 'changed'"))
            .isInstanceOf(DataAccessException.class);
    }

    @Test
    void refusesPayloadRewrite() {
        payload(StagedPayloads.checksum(new byte[]{1}));
        assertThatThrownBy(() -> jdbc.update("UPDATE deedpoll_stagedpayload SET content = ?", new byte[]{2}))
            .isInstanceOf(DataAccessException.class);
    }

    @Test
    void refusesWrongChecksumEvenWithCorrectSize() {
        assertThatThrownBy(() -> payload("0".repeat(64))).isInstanceOf(DataAccessException.class);
    }

    private void reservation(String documentId, String template) {
        jdbc.update("""
            INSERT INTO deedpoll_deedpreparation
                ("deedPollId", "documentId", template, "originalName", "mimeType", "sizeBytes", checksum)
            VALUES ('case', ?, ?, 'deed.docx', 'application/test', 1, ?)
            """, documentId, template, StagedPayloads.checksum(new byte[]{1}));
    }

    private void payload(String checksum) {
        jdbc.update("INSERT INTO deedpoll_stagedpayload (id, content, \"sizeBytes\", checksum) VALUES ('doc', ?, 1, ?)",
            new byte[]{1}, checksum);
    }
}
