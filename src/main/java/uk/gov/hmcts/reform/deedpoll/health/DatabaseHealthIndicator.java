package uk.gov.hmcts.reform.deedpoll.health;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Readiness: does the database answer at all?
 *
 * <p>Contributed as {@code db} -- Spring Boot takes the contributor's name from the bean name with the
 * {@code HealthIndicator} suffix removed, so the bean is named {@code dbHealthIndicator} -- and it
 * <strong>replaces</strong> Boot's own {@code db} contributor, which {@code application.yaml} disables with
 * {@code management.health.db.enabled: false}. It asks the same question in the same way, one validation
 * query through the pool, and differs only in what it says when the answer is no.
 *
 * <p><strong>Why replace it rather than use it.</strong> {@code /health} and {@code /health/readiness} are on
 * the unauthenticated allowlist in {@code SecurityConfiguration}, and the readiness group runs with
 * {@code show-details: always} because the missing table names from {@link DeedPollSchemaHealthIndicator} are
 * the whole point of the probe. Boot's {@code DataSourceHealthIndicator} inherits
 * {@code AbstractHealthIndicator}, which reports a failure as {@code Health.down(exception)} and so writes the
 * driver's own message into the details -- and a JDBC message names the host, the port, the database and the
 * user it failed to authenticate. Published on an endpoint that takes no credential, that is a free
 * description of the database's location to anyone who asks.
 *
 * <p>So the exception is logged and not published, which is exactly the choice
 * {@link DeedPollSchemaHealthIndicator} already makes for its own contributor and the same reasoning. The
 * probe still answers DOWN, and whoever is paged still gets the driver's message -- from the pod's logs,
 * which do take a credential to read.
 */
@Component("dbHealthIndicator")
public class DatabaseHealthIndicator implements HealthIndicator {

    private static final Logger LOG = LoggerFactory.getLogger(DatabaseHealthIndicator.class);

    /**
     * Postgres's validation query, which is what Boot's own indicator resolves to for this driver.
     */
    private static final String VALIDATION_QUERY = "select 1";

    private final JdbcTemplate jdbcTemplate;

    public DatabaseHealthIndicator(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Health health() {
        try {
            jdbcTemplate.queryForObject(VALIDATION_QUERY, Integer.class);
            return Health.up().build();
        } catch (DataAccessException e) {
            LOG.error("Readiness: the database did not answer", e);
            return Health.down().withDetail("error", "the database did not answer").build();
        }
    }
}
