package uk.gov.hmcts.reform.deedpoll.db;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V39 against a database that already holds the api's own LOC022 type with a document under it, as AAT and ITHC
 * do: the row is renamed in place, keeping its id and its document, and nothing is duplicated.
 */
@Tag("requires-docker")
@Testcontainers
class SupportingDocumentTypeSeedTest {
    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Test
    void renamesTheApisDeedTypeInPlaceAndSeedsTheRestOnce() throws java.io.IOException {
        String url = ContainerDatabase.awaitJdbcUrl(POSTGRES);
        var source = new DriverManagerDataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(source).locations("classpath:db/migration").target("38").load().migrate();
        var jdbc = new JdbcTemplate(source);
        jdbc.update("INSERT INTO deedpoll_deedpoll (id, \"modifiedOn\") VALUES ('case', now())");
        jdbc.update("""
            INSERT INTO deedpoll_supportingdocumenttype (id, name, "applicableTypes")
            VALUES ('existing-loc022', 'LOC022 Change of name deed for a child', '{3,4}')
            """);
        jdbc.update("""
            INSERT INTO deedpoll_supportingdocument
                (id, "deedPollId", direction, name, "documentTypeId", "modifiedOn")
            VALUES ('upload', 'case', 'Incoming', 'LOC021_0226.pdf', 'existing-loc022', now())
            """);

        var latest = Flyway.configure().dataSource(source).locations("classpath:db/migration").target("39").load();
        assertThat(latest.migrate().migrationsExecuted).isOne();
        latest.validate();

        assertThat(jdbc.queryForObject("SELECT name FROM deedpoll_supportingdocumenttype WHERE id = 'existing-loc022'",
                                       String.class)).isEqualTo("Change of Name Deed for a minor (LOC022)");
        assertThat(jdbc.queryForObject("SELECT \"documentTypeId\" FROM deedpoll_supportingdocument WHERE id = 'upload'",
                                       String.class)).isEqualTo("existing-loc022");
        List<String> names = jdbc.queryForList("SELECT name FROM deedpoll_supportingdocumenttype", String.class);
        assertThat(names).hasSize(32).doesNotHaveDuplicates()
            .contains("Master's Directions", "Change of Name Deed (LOC020)", "Court Order", "Return Letter")
            .doesNotContain("LOC022 Change of name deed for a child", "LOC020 Change of name deed");
        assertThat(jdbc.queryForObject("""
            SELECT "applicableTypes"::text FROM deedpoll_supportingdocumenttype
             WHERE name = 'Change of Name Deed (LOC020)'
            """, String.class)).isEqualTo("{1,2}");
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM deedpoll_supportingdocumenttype
             WHERE cardinality("applicableMaritalStatuses") > 0
            """, Long.class)).isZero();

        // Run again by hand, as a repair would: it changes nothing.
        String sql = new String(SupportingDocumentTypeSeedTest.class.getResourceAsStream(
            "/db/migration/V39__seed_supporting_document_types.sql").readAllBytes(),
            java.nio.charset.StandardCharsets.UTF_8);
        jdbc.execute(sql);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM deedpoll_supportingdocumenttype", Long.class))
            .isEqualTo(32);
    }
}
