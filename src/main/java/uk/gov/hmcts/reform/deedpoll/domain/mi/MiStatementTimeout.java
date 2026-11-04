package uk.gov.hmcts.reform.deedpoll.domain.mi;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Bounds the statements of a management information report, as {@code CaseListStatementTimeout} bounds the case list.
 * The reports have no rate limit and aggregate over up to two years of cases, so without this one slow report could
 * hold a pooled connection for as long as Postgres takes.
 *
 * <p>{@code set_config('statement_timeout', ?, true)} is transaction-local: it ends with the report's transaction, and
 * the connection goes back to the pool with the server default. Hence {@code MANDATORY}: outside a transaction the
 * setting would end with its own statement and bound nothing, silently.
 *
 * <p>A statement that runs over is cancelled by Postgres and reaches the caller as any other failed database read does,
 * through Boot's default error answer, which carries neither the query nor the database's message.
 */
@Component
@EnableConfigurationProperties(MiProperties.class)
public class MiStatementTimeout {

    private final JdbcTemplate jdbc;
    private final MiProperties properties;

    public MiStatementTimeout(JdbcTemplate jdbc, MiProperties properties) {
        this.jdbc = jdbc;
        this.properties = properties;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void apply() {
        jdbc.queryForObject("SELECT set_config('statement_timeout', ?, true)",
            String.class, properties.statementTimeout().toMillis() + "ms");
    }
}
