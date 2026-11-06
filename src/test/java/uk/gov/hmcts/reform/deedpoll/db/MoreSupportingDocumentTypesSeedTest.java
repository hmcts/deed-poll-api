package uk.gov.hmcts.reform.deedpoll.db;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V47 adds the four types the Power App migration needs, at their fixed ids, without touching V39's 32 or
 * duplicating a type an environment already has under the same name.
 */
@Tag("requires-docker")
@Testcontainers
class MoreSupportingDocumentTypesSeedTest {
    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final Map<String, String> ADDED = Map.of(
        "a5ad0d5d-626e-5e3d-b511-3711588b4246", "Letter of consent",
        "84a9c547-5ea7-5413-b032-fca38c2113ba", "Minors Consent Letter (16-17 years)",
        "41de8383-4817-5aad-915d-2b03ad30d114", "Notice for the Gazette (LOC025)",
        "0f025165-5de0-51e6-97ec-8aec08c53ca8", "Notice for the Gazette for a minor (LOC026)");

    @Test
    void addsTheFourAtTheirIdsAndLeavesV39sAlone() throws IOException {
        String url = ContainerDatabase.awaitJdbcUrl(POSTGRES);
        var source = new DriverManagerDataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(source).locations("classpath:db/migration").target("46").load().migrate();
        var jdbc = new JdbcTemplate(source);
        List<String> before = jdbc.queryForList("SELECT id FROM deedpoll_supportingdocumenttype ORDER BY id",
                                                String.class);
        assertThat(before).hasSize(32);
        // An environment that already holds one of the names under another id keeps it, and gets no second row.
        jdbc.update("""
            INSERT INTO deedpoll_supportingdocumenttype (id, name) VALUES ('already-here', 'Letter of consent')
            """);

        var latest = Flyway.configure().dataSource(source).locations("classpath:db/migration").target("47").load();
        assertThat(latest.migrate().migrationsExecuted).isOne();
        latest.validate();

        List<String> names = jdbc.queryForList("SELECT name FROM deedpoll_supportingdocumenttype", String.class);
        assertThat(names).hasSize(36).doesNotHaveDuplicates().containsAll(ADDED.values());
        assertThat(jdbc.queryForObject(
            "SELECT id FROM deedpoll_supportingdocumenttype WHERE name = 'Letter of consent'", String.class))
            .isEqualTo("already-here");
        ADDED.forEach((id, name) -> {
            if (!"Letter of consent".equals(name)) {
                assertThat(jdbc.queryForObject("SELECT name FROM deedpoll_supportingdocumenttype WHERE id = ?",
                                               String.class, id)).isEqualTo(name);
            }
        });
        assertThat(jdbc.queryForList("SELECT id FROM deedpoll_supportingdocumenttype WHERE id = ANY (?::text[])",
                                     String.class, (Object) before.toArray(String[]::new))).hasSize(32);
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM deedpoll_supportingdocumenttype
             WHERE id IN ('84a9c547-5ea7-5413-b032-fca38c2113ba', '41de8383-4817-5aad-915d-2b03ad30d114',
                          '0f025165-5de0-51e6-97ec-8aec08c53ca8')
               AND cardinality("applicableTypes") = 0 AND cardinality("applicableMaritalStatuses") = 0
            """, Long.class)).isEqualTo(3);

        // Run again by hand, as a repair would: it changes nothing.
        String sql = new String(MoreSupportingDocumentTypesSeedTest.class.getResourceAsStream(
            "/db/migration/V47__more_supporting_document_types.sql").readAllBytes(), StandardCharsets.UTF_8);
        jdbc.execute(sql);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM deedpoll_supportingdocumenttype", Long.class))
            .isEqualTo(36);
    }
}
