package uk.gov.hmcts.reform.deedpoll.db;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

/** A real Flyway upgrade, including history validation, from populated V25 through V27. */
@Tag("requires-docker")
@Testcontainers
class DeedPreparationUpgradeTest {
    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Test
    void upgradesExistingDatabaseWithoutChangingLegacyDocuments() {
        String url = ContainerDatabase.awaitJdbcUrl(POSTGRES);
        var source = new DriverManagerDataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(source).locations("classpath:db/migration").target("25").load().migrate();
        var jdbc = new JdbcTemplate(source);
        jdbc.update("INSERT INTO deedpoll_deedpoll (id, \"modifiedOn\") VALUES ('case', now())");
        jdbc.update("""
            INSERT INTO deedpoll_supportingdocumenttype (id, name)
            VALUES ('type', 'LOC020 Change of name deed')
            """);
        jdbc.update("""
            INSERT INTO deedpoll_supportingdocument
                (id, "deedPollId", direction, name, "documentTypeId", "modifiedOn")
            VALUES ('legacy1', 'case', 'Outgoing', 'LOC020', 'type', now()),
                   ('legacy2', 'case', 'Incoming', 'LOC020', 'type', now())
            """);
        var before = jdbc.queryForList("SELECT * FROM deedpoll_supportingdocument ORDER BY id");
        var upgrade = Flyway.configure().dataSource(source).locations("classpath:db/migration")
            .target("26").load();
        assertThat(upgrade.migrate().migrationsExecuted).isOne();
        upgrade.validate();
        assertThat(jdbc.queryForList("SELECT * FROM deedpoll_supportingdocument ORDER BY id")).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM deedpoll_deedpreparation", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM deedpoll_stagedpayload", Long.class)).isZero();
        assertThat(upgrade.migrate().migrationsExecuted).isZero();
        jdbc.update("""
            INSERT INTO deedpoll_deedpreparation
                ("deedPollId", "documentId", template, "originalName", "mimeType", "sizeBytes", checksum)
            VALUES ('case', 'legacy1', 'ADULT', 'original.docx', 'application/docx', 1, repeat('0', 64))
            """);
        var latest = Flyway.configure().dataSource(source).locations("classpath:db/migration").target("27").load();
        assertThat(latest.migrate().migrationsExecuted).isOne();
        latest.validate();
        assertThat(jdbc.queryForObject("""
            SELECT "inputFingerprint" FROM deedpoll_deedpreparation WHERE "deedPollId" = 'case'
            """, String.class)).isNull();
        assertThat(jdbc.queryForObject("""
            SELECT "originalName" FROM deedpoll_deedpreparation WHERE "deedPollId" = 'case'
            """, String.class)).isEqualTo("original.docx");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.update("""
            UPDATE deedpoll_deedpreparation SET "inputFingerprint" = repeat('0', 64) WHERE "deedPollId" = 'case'
            """)).isInstanceOf(org.springframework.dao.DataAccessException.class);

    }
}
