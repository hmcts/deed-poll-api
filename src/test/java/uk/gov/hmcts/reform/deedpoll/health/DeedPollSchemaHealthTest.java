package uk.gov.hmcts.reform.deedpoll.health;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.ActiveProfiles;
import uk.gov.hmcts.reform.deedpoll.config.AbstractPostgresContainerTest;
import uk.gov.hmcts.reform.deedpoll.reference.PrismaSchema;

import java.util.List;
import java.util.Properties;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The readiness check, ported from {@code reference/ts/health.test.ts}.
 *
 * <p><strong>Against real schemas, not a mocked query.</strong> The reference mocks Prisma's {@code $queryRaw}
 * and, having done so, can only assert that the SQL string it built contains {@code current_schema()} -- a
 * transcription check, which agrees with the query on the day it is written. Here each case is a real Postgres
 * schema containing exactly the tables the case is about, so "looks in the schema the connection resolves to"
 * is asserted by getting a different answer from a different schema, and a query hardcoding {@code 'public'}
 * fails rather than passing with the right substring in it.
 *
 * <p>The throwaway container is shared across the suite, so no test touches {@code public}: each builds its own
 * schema and drops it afterwards, and reaches it through a separate unpooled connection with
 * {@code currentSchema} set. Setting {@code search_path} on the application's own pooled connection would leave
 * the session state behind for whichever test borrowed it next.
 *
 * <p>Two reference blocks have no equivalent and are noted where they would have been.
 */
@SpringBootTest
@ActiveProfiles("test")
class DeedPollSchemaHealthTest extends AbstractPostgresContainerTest {

    private static final String PROBE_SCHEMA = "readiness_probe";

    /** A port nothing listens on: the closest thing to "the database is gone" a test can arrange. */
    private static final String UNREACHABLE_URL = "jdbc:postgresql://localhost:1/nothing";

    @Autowired
    private DeedPollSchema schema;

    @Autowired
    private DeedPollSchemaHealthIndicator indicator;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Value("${spring.datasource.url}")
    private String url;

    @Value("${spring.datasource.username}")
    private String username;

    @Value("${spring.datasource.password}")
    private String password;

    @AfterEach
    void dropProbeSchema() {
        jdbcTemplate.execute("drop schema if exists " + PROBE_SCHEMA + " cascade");
    }

    /** A schema containing exactly the named tables, and nothing else. */
    private void probeSchemaContaining(String... tables) {
        jdbcTemplate.execute("drop schema if exists " + PROBE_SCHEMA + " cascade");
        jdbcTemplate.execute("create schema " + PROBE_SCHEMA);
        for (String table : tables) {
            jdbcTemplate.execute("create table " + PROBE_SCHEMA + "." + table + " (id uuid primary key)");
        }
    }

    private DeedPollSchema resolvingTo(String schemaName) {
        return new DeedPollSchema(new JdbcTemplate(unpooledDataSource(url, connectionProperty("currentSchema",
                                                                                              schemaName))));
    }

    private static Properties connectionProperty(String name, String value) {
        Properties properties = new Properties();
        properties.setProperty(name, value);
        return properties;
    }

    private DriverManagerDataSource unpooledDataSource(String jdbcUrl, Properties connectionProperties) {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(jdbcUrl, username, password);
        dataSource.setDriverClassName("org.postgresql.Driver");
        dataSource.setConnectionProperties(connectionProperties);
        return dataSource;
    }

    @Nested
    @DisplayName("DEED_POLL_TABLES")
    class DeedPollTables {

        /**
         * Tables this service has that the reference schema does not, because they are new behaviour rather
         * than a port. Listed here so the assertion below stays exact -- "the reference's tables plus these"
         * -- instead of degrading to "contains at least the reference's", which would stop catching the
         * omission it exists to catch the moment the first unported table was added.
         */
        private static final List<String> NOT_IN_THE_REFERENCE = List.of(
            "deedpoll_savedquery",
            "deedpoll_savedqueryselection",
            "deedpoll_referral",
            "deedpoll_notification",
            "deedpoll_ccdcreationattempt",
            "deedpoll_deedpreparation",
            "deedpoll_stagedpayload",
            "deedpoll_ownedstage",
            "deedpoll_gazette_notice_batch",
            "deedpoll_gazette_notice_batch_case",
            "deedpoll_gazette_settings",
            "deedpoll_gazette_settings_change",
            "deedpoll_process_settings",
            "deedpoll_process_settings_change",
            "deedpoll_master",
            "deedpoll_master_email",
            "deedpoll_window_digest_run",
            "deedpoll_bank_holiday",
            "deedpoll_gazette_request",
            "deedpoll_gazette_request_case",
            "deedpoll_gazette_reservation",
            "deedpoll_gazette_request_file",
            "deedpoll_audit_event",
            "deedpoll_audit_event_value",
            "deedpoll_request_budget"
        );

        @Test
        @DisplayName("lists exactly the tables the reference schema maps, plus the ones that are not a port")
        void listsExactlyTheTablesTheReferenceSchemaMaps() {
            // The list cannot be derived -- see DeedPollSchema for why deriving it from the entities would be
            // circular -- so this is what stops it silently falling behind. A model added without updating it
            // would leave readiness passing on a database that lacks the new table, which is the failure this
            // whole check exists to prevent.
            List<String> expected = Stream.concat(
                    PrismaSchema.loadReference().models().values().stream().map(PrismaSchema.Model::table),
                    NOT_IN_THE_REFERENCE.stream())
                .toList();

            assertThat(DeedPollSchema.DEED_POLL_TABLES).containsExactlyInAnyOrderElementsOf(expected);
        }

        @Test
        @DisplayName("the tables that are not a port are still tables the migrations create")
        void theTablesThatAreNotAPortAreStillCreatedByTheMigrations() {
            // The half of the assertion above that the reference cannot supply. Nothing checks an unported
            // name against anything, so a typo in one -- in the list, or in V5 -- would leave readiness
            // reporting a table missing on every environment forever. Asked of the migrated schema, so it
            // fails here if V5 or V6 stopped creating them too.
            assertThat(schema.missingDeedPollTables()).doesNotContainAnyElementsOf(NOT_IN_THE_REFERENCE);
        }

        @Test
        @DisplayName("lists every domain table created by the migrations")
        void listsEveryMigratedDomainTable() {
            // Read the migrated schema independently, so omitting a table from both hand-written lists fails.
            List<String> migrated = jdbcTemplate.queryForList("""
                select table_name from information_schema.tables
                where table_schema = current_schema() and table_type = 'BASE TABLE'
                    and starts_with(table_name, 'deedpoll_')
                """, String.class);

            assertThat(DeedPollSchema.DEED_POLL_TABLES).containsExactlyInAnyOrderElementsOf(migrated);
        }
    }

    @Nested
    @DisplayName("missingDeedPollTables")
    class MissingTables {

        @Test
        @DisplayName("reports nothing missing when every table is present")
        void reportsNothingMissingWhenEveryTableIsPresent() {
            // The migrated schema, as Flyway left it. Nothing is stubbed: if V1 to V3 stopped creating one of
            // these, this fails here as well as in SchemaConformanceTest.
            assertThat(schema.missingDeedPollTables()).isEmpty();
        }

        @Test
        @DisplayName("names the tables that are absent")
        void namesTheTablesThatAreAbsent() {
            // A half-migrated database, which `select 1` would pass. The names are the whole point: "readiness
            // DOWN" with no detail is a bad thing to be woken up for.
            probeSchemaContaining("deedpoll_deedpoll");

            List<String> missing = resolvingTo(PROBE_SCHEMA).missingDeedPollTables();

            assertThat(missing).doesNotContain("deedpoll_deedpoll");
            assertThat(missing).contains("deedpoll_notes");
            assertThat(missing).hasSize(DeedPollSchema.DEED_POLL_TABLES.size() - 1);
        }

        @Test
        @DisplayName("reports all of them missing against an empty schema, which is where a hardcoded "
            + "'public' would answer nothing missing")
        void reportsAllOfThemMissingAgainstAnEmptySchema() {
            // This is the reference's "looks in the schema the connection resolves to" made an assertion about
            // behaviour instead of about the SQL string. `public` next door has them all, so a query that
            // looked there rather than at current_schema() would answer nothing missing and pass every other test
            // here. Plan 02 puts a `ccd` schema in this database, at which point looking in the wrong one would
            // be worse than not looking.
            probeSchemaContaining();

            assertThat(resolvingTo(PROBE_SCHEMA).missingDeedPollTables())
                .isEqualTo(DeedPollSchema.DEED_POLL_TABLES);
        }

        @Test
        @DisplayName("ignores tables that are not ours")
        void ignoresTablesThatAreNotOurs() {
            // Defensive, as it is in the reference: the bound `in` list should make a stray row impossible, but
            // one must never be read as satisfying one of ours. The count is what says so -- every table of
            // ours plus one of somebody else's is still nothing missing, and only somebody else's would be all
            // of ours missing.
            //
            // Built from DEED_POLL_TABLES rather than named, because a hand-written copy of the list is a copy
            // that a new table has to be added to in two places and will not be.
            probeSchemaContaining(Stream.concat(DeedPollSchema.DEED_POLL_TABLES.stream(),
                                                Stream.of("ccd_case_data"))
                                      .toArray(String[]::new));

            assertThat(resolvingTo(PROBE_SCHEMA).missingDeedPollTables()).isEmpty();
        }

        @Test
        @DisplayName("propagates a connection failure rather than reporting an unmigrated database")
        void propagatesAConnectionFailureRatherThanReportingAnUnmigratedDatabase() {
            // Two different faults. "Cannot connect" and "connected but unmigrated" want different responses --
            // one is somebody else's database, the other is a migration job that has not run -- and flattening
            // them here would lose the distinction the caller reports.
            DeedPollSchema unreachable =
                new DeedPollSchema(new JdbcTemplate(unpooledDataSource(UNREACHABLE_URL, new Properties())));

            assertThatThrownBy(unreachable::missingDeedPollTables).isInstanceOf(DataAccessException.class);
        }
    }

    /**
     * What the probe is told. The reference has no equivalent: its framework collapses a check to UP or DOWN,
     * so the names could only be logged, and it asserts the check's return value instead of a response.
     */
    @Nested
    @DisplayName("the health indicator")
    class Reporting {

        @Test
        @DisplayName("is UP against the migrated schema")
        void isUpAgainstTheMigratedSchema() {
            assertThat(indicator.health().getStatus()).isEqualTo(Status.UP);
        }

        @Test
        @DisplayName("is DOWN and names the missing tables")
        void isDownAndNamesTheMissingTables() {
            probeSchemaContaining("deedpoll_deedpoll");

            Health health = new DeedPollSchemaHealthIndicator(resolvingTo(PROBE_SCHEMA)).health();

            assertThat(health.getStatus()).isEqualTo(Status.DOWN);
            assertThat(health.getDetails().get("missingTables"))
                .isEqualTo(DeedPollSchema.DEED_POLL_TABLES.stream()
                               .filter(table -> !"deedpoll_deedpoll".equals(table))
                               .toList());
            assertThat((String) health.getDetails().get("error")).contains("migration job");
        }

        @Test
        @DisplayName("is DOWN when only the owned staging table is missing")
        void isDownWhenOnlyOwnedStagingIsMissing() {
            probeSchemaContaining(DeedPollSchema.DEED_POLL_TABLES.stream()
                .filter(table -> !"deedpoll_ownedstage".equals(table))
                .toArray(String[]::new));

            Health health = new DeedPollSchemaHealthIndicator(resolvingTo(PROBE_SCHEMA)).health();

            assertThat(health.getStatus()).isEqualTo(Status.DOWN);
            assertThat(health.getDetails().get("missingTables")).isEqualTo(List.of("deedpoll_ownedstage"));
        }

        @Test
        @DisplayName("is DOWN for the other reason when the database does not answer, and says which")
        void isDownForTheOtherReasonWhenTheDatabaseDoesNotAnswer() {
            DeedPollSchema unreachable =
                new DeedPollSchema(new JdbcTemplate(unpooledDataSource(UNREACHABLE_URL, new Properties())));

            Health health = new DeedPollSchemaHealthIndicator(unreachable).health();

            assertThat(health.getStatus()).isEqualTo(Status.DOWN);
            // Not reported as an incomplete schema, because it is not one: the fix is somebody else's.
            assertThat(health.getDetails()).doesNotContainKey("missingTables");
            assertThat((String) health.getDetails().get("error")).contains("did not answer");
        }

        @Test
        @DisplayName("logs the driver's failure rather than publishing it")
        void logsTheDriversFailureRatherThanPublishingIt() {
            // The published detail is deliberately generic -- this endpoint takes no credential and a driver's
            // message names hosts and databases. That leaves the log as the only place the actual cause exists,
            // so a swallowed exception with no log would pass every other assertion here.
            Logger logger = (Logger) LoggerFactory.getLogger(DeedPollSchemaHealthIndicator.class);
            ListAppender<ILoggingEvent> appender = new ListAppender<>();
            appender.start();
            logger.addAppender(appender);

            try {
                DeedPollSchema unreachable =
                    new DeedPollSchema(new JdbcTemplate(unpooledDataSource(UNREACHABLE_URL, new Properties())));
                Health health = new DeedPollSchemaHealthIndicator(unreachable).health();

                assertThat(health.getDetails().get("error").toString()).doesNotContain(UNREACHABLE_URL);
                assertThat(appender.list)
                    .anySatisfy(event -> {
                        assertThat(event.getLevel()).isEqualTo(Level.ERROR);
                        assertThat(event.getThrowableProxy()).isNotNull();
                    });
            } finally {
                logger.detachAppender(appender);
            }
        }
    }
}
