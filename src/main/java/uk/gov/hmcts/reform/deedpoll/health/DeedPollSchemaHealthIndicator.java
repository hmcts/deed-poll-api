package uk.gov.hmcts.reform.deedpoll.health;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Readiness: is the schema this service needs actually there?
 *
 * <p>Contributed as {@code deedPollSchema} -- Spring Boot takes the contributor's name from the bean name with
 * the {@code HealthIndicator} suffix removed -- and included in the readiness group by
 * {@code application.yaml}, alongside {@link DatabaseHealthIndicator}'s {@code db}. The two together are the
 * reference's two checks:
 * {@code db} opens a connection, this one asks whether the migrations have run. Neither is enough alone; a
 * connection check passes against an unmigrated database, and this check cannot distinguish "no tables" from
 * "no database" without the connection check beside it to say which.
 *
 * <p><strong>Readiness, not liveness.</strong> The reference is explicit that this is the deploying app's call
 * and not the domain's, so it is made here: a service whose schema is incomplete cannot serve traffic, which is
 * what readiness means, and restarting the process cannot create the tables, which is what liveness would ask
 * Kubernetes to try. The liveness group is deliberately left with nothing in it but the process itself.
 *
 * <p><strong>The missing names go in the details and in the log.</strong> The reference could only log them,
 * because the framework it plugs into collapses a check to UP or DOWN; Spring Boot's {@code Health} carries
 * detail, so the names reach whoever reads the probe response as well as whoever reads the pod's logs. Being
 * paged for "readiness DOWN" with no further detail is most of the diagnosis wasted, and which of the two
 * halves is missing decides whether the answer is "re-run the migration job" or "the database is gone".
 */
@Component
public class DeedPollSchemaHealthIndicator implements HealthIndicator {

    private static final Logger LOG = LoggerFactory.getLogger(DeedPollSchemaHealthIndicator.class);

    private final DeedPollSchema schema;

    public DeedPollSchemaHealthIndicator(DeedPollSchema schema) {
        this.schema = schema;
    }

    @Override
    public Health health() {
        List<String> missing;
        try {
            missing = schema.missingDeedPollTables();
        } catch (DataAccessException e) {
            // Down, but for the other reason, and said so rather than reported as an incomplete schema: the
            // database being unreachable is somebody else's fault and a different fix. The exception is logged
            // rather than published, because a driver's message names hosts and databases and this endpoint
            // takes no credential.
            LOG.error("Readiness: the deed-poll schema could not be read", e);
            return Health.down()
                .withDetail("error", "the deed-poll schema could not be read; the database did not answer")
                .build();
        }

        if (missing.isEmpty()) {
            return Health.up().withDetail("tables", DeedPollSchema.DEED_POLL_TABLES.size()).build();
        }

        LOG.error("Readiness: the deed-poll schema is incomplete, missing: {}. Has the migration job run?",
                  String.join(", ", missing));
        return Health.down()
            .withDetail("error", "the deed-poll schema is incomplete. Has the migration job run?")
            .withDetail("missingTables", missing)
            .build();
    }
}
