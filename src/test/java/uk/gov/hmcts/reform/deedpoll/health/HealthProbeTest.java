package uk.gov.hmcts.reform.deedpoll.health;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import uk.gov.hmcts.reform.deedpoll.config.AbstractPostgresContainerTest;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The two probes over HTTP, which is the only place the difference between them is visible.
 *
 * <p>Every assertion here is about what Kubernetes would do, not about a bean. Readiness answering 200 while
 * the database is unreachable means traffic keeps arriving at a pod that cannot serve it; liveness answering
 * 503 for the same reason means every replica is restarted over a fault none of them caused and no restart can
 * fix. Both are configuration mistakes rather than code mistakes -- one line in the wrong health group -- which
 * is exactly the kind that a test of the indicator alone cannot see.
 *
 * <p><strong>The database is made unreachable by taking it away, not by never having it.</strong> The
 * application boots normally, runs Flyway and validates its entities, and only then does
 * {@link InterruptibleDataSource} start refusing connections -- which is what a blip actually looks like. The
 * alternative, a context pointed at a dead port from the start, would have to disable Flyway and Hibernate's
 * validation to boot at all, and so would be asserting the probes of a differently-configured service.
 *
 * <p>{@link DeedPollSchema} is mocked, because "the schema is incomplete" cannot be arranged by editing the
 * shared container's schema without breaking every other test in the run. What the real query does against a
 * real half-migrated schema is {@code DeedPollSchemaHealthTest}, which is why it is not repeated here.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class HealthProbeTest extends AbstractPostgresContainerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private DataSource dataSource;

    @MockitoBean
    private DeedPollSchema schema;

    @BeforeEach
    void healthyByDefault() {
        given(schema.missingDeedPollTables()).willReturn(List.of());
    }

    @AfterEach
    void restoreTheDatabase() {
        database().reachable();
    }

    private InterruptibleDataSource database() {
        return (InterruptibleDataSource) dataSource;
    }

    @Test
    @DisplayName("readiness is UP, and reports both halves of the check")
    void readinessIsUpAndReportsBothHalvesOfTheCheck() throws Exception {
        mockMvc.perform(get("/health/readiness"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("UP"))
            // Named individually rather than counted: a group whose include list lost `db` or `deedPollSchema`
            // still answers UP, which is the failure this asserts against.
            .andExpect(jsonPath("$.components.db.status").value("UP"))
            .andExpect(jsonPath("$.components.deedPollSchema.status").value("UP"))
            .andExpect(jsonPath("$.components.readinessState.status").value("UP"));
    }

    @Test
    @DisplayName("readiness answers 503, not 200, when the database is unreachable")
    void readinessAnswers503WhenTheDatabaseIsUnreachable() throws Exception {
        // Returning 200 on a failed probe is a defect this codebase has already had once, which is why the
        // status code is asserted rather than the body's status field: a body saying DOWN under a 200 is
        // invisible to Kubernetes.
        database().unreachable();

        mockMvc.perform(get("/health/readiness"))
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.status").value("DOWN"))
            .andExpect(jsonPath("$.components.db.status").value("DOWN"));
    }

    @Test
    @DisplayName("a database failure does not publish the driver's message to an unauthenticated caller")
    void readinessDoesNotLeakTheDriversMessage() throws Exception {
        // This group runs with show-details always, because naming the missing tables is the point of the probe,
        // and `/health/**` takes no credential. Boot's own `db` contributor reports a failure as
        // Health.down(exception), which writes the driver's text -- naming the host, the database and the user --
        // straight into a body anyone can fetch. DatabaseHealthIndicator replaces it for that reason, and this
        // pins the replacement: the same fixed sentence DeedPollSchemaHealthIndicator already gives, and nothing
        // of the exception.
        database().unreachable();

        String body = mockMvc.perform(get("/health/readiness"))
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.components.db.status").value("DOWN"))
            .andExpect(jsonPath("$.components.db.details.error").value("the database did not answer"))
            .andReturn()
            .getResponse()
            .getContentAsString();

        // Asserted over the whole body rather than one field, because the leak's shape is "somewhere in the
        // details", and a future contributor added to this group would reintroduce it somewhere else.
        assertThat(body)
            .doesNotContain("Connection to the database refused")
            .doesNotContain("SQLException")
            .doesNotContain("CannotGetJdbcConnection");
    }

    @Test
    @DisplayName("readiness answers 503 and names the missing tables when the schema is incomplete")
    void readinessNamesTheMissingTablesWhenTheSchemaIsIncomplete() throws Exception {
        // The case a connection check cannot see: Postgres answers, so `db` is UP, and every request would 500.
        // The names are in the response as well as the log because show-details is on for this group -- which is
        // the half of "name the missing tables" that lives in configuration rather than in code.
        given(schema.missingDeedPollTables()).willReturn(List.of("deedpoll_notes", "deedpoll_witness"));

        mockMvc.perform(get("/health/readiness"))
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.components.db.status").value("UP"))
            .andExpect(jsonPath("$.components.deedPollSchema.status").value("DOWN"))
            .andExpect(jsonPath("$.components.deedPollSchema.details.missingTables[0]").value("deedpoll_notes"))
            .andExpect(jsonPath("$.components.deedPollSchema.details.missingTables[1]").value("deedpoll_witness"));
    }

    @Test
    @DisplayName("readiness answers 503 when the schema cannot be read at all")
    void readinessAnswers503WhenTheSchemaCannotBeReadAtAll() throws Exception {
        willThrow(new CannotGetJdbcConnectionException("no route")).given(schema).missingDeedPollTables();

        mockMvc.perform(get("/health/readiness"))
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.components.deedPollSchema.status").value("DOWN"))
            // The driver's message is not on an endpoint that takes no credential.
            .andExpect(jsonPath("$.components.deedPollSchema.details.error").value(
                "the deed-poll schema could not be read; the database did not answer"));
    }

    @Test
    @DisplayName("liveness stays 200 while the database is unreachable")
    void livenessStays200WhileTheDatabaseIsUnreachable() throws Exception {
        // The load-bearing assertion of "liveness stays empty on purpose". Restarting this process cannot fix
        // someone else's database, and killing every replica removes the ones that would have recovered when it
        // came back. Both faults at once, so a check added to the wrong group fails here whichever it is.
        database().unreachable();
        given(schema.missingDeedPollTables()).willReturn(DeedPollSchema.DEED_POLL_TABLES);

        mockMvc.perform(get("/health/liveness"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    @DisplayName("liveness has nothing in it but the process")
    void livenessHasNothingInItButTheProcess() throws Exception {
        mockMvc.perform(get("/health/liveness"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.components.livenessState.status").value("UP"))
            .andExpect(jsonPath("$.components.db").doesNotExist())
            .andExpect(jsonPath("$.components.deedPollSchema").doesNotExist());
    }

    @Test
    @DisplayName("both probes answer without a credential")
    void bothProbesAnswerWithoutACredential() throws Exception {
        // Kubernetes has no token to present. Asserted here as well as in SecurityConfigurationTest because the
        // group paths are not the ones that test names, and `/health/**` covering them is an assumption until
        // something checks it.
        mockMvc.perform(get("/health/readiness")).andExpect(status().isOk());
        mockMvc.perform(get("/health/liveness")).andExpect(status().isOk());
    }

    @Test
    @DisplayName("the ungrouped /health publishes a status and nothing else")
    void theUngroupedHealthPublishesAStatusAndNothingElse() throws Exception {
        // The two groups have closed include lists, so what they can leak is reviewable. Bare /health does not:
        // it aggregates every contributor Boot or a dependency happens to register, and it is on the same
        // unauthenticated allowlist. Inheriting `show-details: always` there would publish, for one, the
        // auto-configured diskSpace contributor's absolute filesystem path and free bytes -- which is the class of
        // detail DatabaseHealthIndicator was written to keep off this endpoint, arriving through a contributor
        // nobody chose. `never` at the endpoint level is what stops that, and this is what notices if it comes back.
        String body = mockMvc.perform(get("/health"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("UP"))
            .andExpect(jsonPath("$.components").doesNotExist())
            .andReturn()
            .getResponse()
            .getContentAsString();

        assertThat(body).doesNotContain("diskSpace").doesNotContain("details");
    }

    /**
     * Wraps whatever {@code DataSource} the application built, so a test can take the database away mid-run.
     *
     * <p>A {@link BeanPostProcessor} rather than a replacement bean, so the real Hikari pool, Flyway and
     * Hibernate's validation are all still exercised on start-up: the wrapper only intercepts the request for a
     * connection, and only once a test asks it to.
     */
    @TestConfiguration
    static class DatabaseInterruption {

        @Bean
        static BeanPostProcessor interruptibleDataSource() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String beanName) {
                    return bean instanceof DataSource target && !(bean instanceof InterruptibleDataSource)
                        ? new InterruptibleDataSource(target)
                        : bean;
                }
            };
        }
    }

    static final class InterruptibleDataSource extends DelegatingDataSource {

        private volatile boolean unreachable;

        InterruptibleDataSource(DataSource target) {
            super(target);
        }

        void unreachable() {
            unreachable = true;
        }

        void reachable() {
            unreachable = false;
        }

        @Override
        public Connection getConnection() throws SQLException {
            refuseIfUnreachable();
            return super.getConnection();
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            refuseIfUnreachable();
            return super.getConnection(username, password);
        }

        private void refuseIfUnreachable() throws SQLException {
            if (unreachable) {
                throw new SQLException("Connection to the database refused");
            }
        }
    }
}
