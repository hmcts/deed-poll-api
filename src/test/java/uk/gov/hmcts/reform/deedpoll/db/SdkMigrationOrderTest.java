package uk.gov.hmcts.reform.deedpoll.db;

import jakarta.persistence.EntityManagerFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import uk.gov.hmcts.reform.deedpoll.config.AbstractPostgresContainerTest;

import java.sql.Timestamp;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The CCD SDK's migrations and ours, run by one application against one real Postgres.
 *
 * <p>{@code decentralised-runtime} brings its own {@code ccd}-schema migrations and a
 * {@link FlywayMigrationStrategy} that runs them, with their own history table, before handing over to the
 * application's Flyway. That strategy is conditional on nobody else defining one, which is why plan 02
 * decision 6 forbids this application from doing so: ours would replace it and the {@code ccd} schema would
 * silently never be created. {@code ConfigGenProfileTest} states that rule without a database; this class shows
 * what it protects.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class SdkMigrationOrderTest extends AbstractPostgresContainerTest {

    /**
     * Set here as well as in {@code application.yaml}, so the last assertion cannot pass vacuously.
     */
    @DynamicPropertySource
    static void validateAgainstFlyway(DynamicPropertyRegistry registry) {
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    }

    @Autowired
    private ApplicationContext context;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private Flyway flyway;

    @Test
    @DisplayName("the only migration strategy is the SDK's own")
    void theOnlyMigrationStrategyIsTheSdks() {
        assertThat(context.getBeansOfType(FlywayMigrationStrategy.class))
            .as("a strategy defined here would replace the SDK's and stop the ccd migrations")
            .containsOnlyKeys("orderedFlywayMigrationStrategy");
    }

    @Test
    @DisplayName("the ccd schema exists with its own history, every entry successful")
    void theCcdSchemaHasItsOwnHistory() {
        Integer failed = jdbc.queryForObject(
            "select count(*) from ccd.flyway_schema_history where not success", Integer.class);
        Integer applied = jdbc.queryForObject(
            "select count(*) from ccd.flyway_schema_history where version is not null", Integer.class);

        assertThat(failed).isZero();
        assertThat(applied).isPositive();
        assertThat(jdbc.queryForList(
            "select table_name from information_schema.tables where table_schema = 'ccd'", String.class))
            .contains("case_data", "case_event");
    }

    @Test
    @DisplayName("public's history is ours alone -- V1 to V26, each successful, checksums matching the files")
    void publicHistoryIsUnchanged() {
        List<String> versions = jdbc.queryForList(
            "select version from public.flyway_schema_history where version is not null order by installed_rank",
            String.class);

        assertThat(versions).containsExactlyElementsOf(
            IntStream.rangeClosed(1, 26).mapToObj(String::valueOf).toList());
        assertThat(jdbc.queryForObject(
            "select count(*) from public.flyway_schema_history where not success", Integer.class)).isZero();
        // Fails on any checksum that differs from the migration file on the classpath.
        assertThat(flyway.validateWithResult().validationSuccessful).isTrue();
    }

    @Test
    @DisplayName("the SDK's migrations ran before ours")
    void theSdkMigratedFirst() {
        Timestamp lastCcd = jdbc.queryForObject(
            "select max(installed_on) from ccd.flyway_schema_history", Timestamp.class);
        Timestamp firstOurs = jdbc.queryForObject(
            "select min(installed_on) from public.flyway_schema_history", Timestamp.class);

        assertThat(lastCcd).isBeforeOrEqualTo(firstOurs);
    }

    @Test
    @DisplayName("Hibernate still validates the entities against the migrated schema")
    void hibernateStillValidates() {
        // The context only exists if `validate` passed; this names the bean so that is not left implicit.
        assertThat(context.getBeansOfType(EntityManagerFactory.class)).isNotEmpty();
    }
}
