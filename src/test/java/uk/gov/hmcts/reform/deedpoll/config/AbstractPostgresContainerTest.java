package uk.gov.hmcts.reform.deedpoll.config;

import org.junit.jupiter.api.Tag;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import uk.gov.hmcts.reform.deedpoll.db.ContainerDatabase;

/**
 * Base class for tests that boot the Spring context. The application has a datasource and runs
 * Flyway on start-up, so booting it needs a database -- a throwaway one per build, never a
 * developer's local instance and never a deployed one.
 *
 * <p>The container is started once for the JVM and shared, rather than per class: starting
 * Postgres costs seconds and the migrations produce the same schema for every context.
 *
 * <p>It lives in the {@code test} source set rather than {@code integrationTest} because both need
 * it -- {@code integrationTest} has {@code test}'s output on its classpath, not the reverse -- and
 * a second copy would be a second thing to keep in step with {@link ContainerDatabase}.
 *
 * <p>The {@code requires-docker} tag supports explicitly Docker-free local runs. Jenkins includes
 * these tests. JUnit 5 inherits the tag, so a new subclass needs no annotation.
 */
@Tag("requires-docker")
public abstract class AbstractPostgresContainerTest {

    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final String JDBC_URL;

    static {
        POSTGRES.start();
        // Resolved once, and only after something answers on it -- see ContainerDatabase for
        // why the container's own URL is not used directly.
        JDBC_URL = ContainerDatabase.awaitJdbcUrl(POSTGRES);
    }

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        // Recovery is exercised explicitly in tests, without cached contexts racing another test's fixtures.
        registry.add("deedpoll.gazette.recovery-initial-delay-ms", () -> 3_600_000);
        // The processing-window sweep likewise: tests call ReferralService.unassignOverdue themselves.
        registry.add("deedpoll.referrals.unassign-enabled", () -> false);
        // And the Master email timers (plan D3): tests run the digest, reminder and drain themselves.
        registry.add("deedpoll.masters.emails-enabled", () -> false);
        registry.add("spring.datasource.url", () -> JDBC_URL);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", POSTGRES::getDriverClassName);
        registry.add("spring.flyway.locations", () -> "classpath:db/migration");
    }
}
