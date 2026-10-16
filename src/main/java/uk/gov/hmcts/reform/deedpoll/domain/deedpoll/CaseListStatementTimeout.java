package uk.gov.hmcts.reform.deedpoll.domain.deedpoll;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Bounds the statements of the case list read, and nothing else (abuse-limits plan, decision 9).
 *
 * <p>{@code set_config('statement_timeout', ?, true)}, as {@code OwnedStaging} does: the third argument makes it
 * transaction-local, so it ends with the list read's transaction and the pooled connection goes back to the next
 * borrower with the server default. A session-level {@code SET} would follow the connection into whichever request
 * borrowed it next. Hence {@code MANDATORY}: outside a transaction the setting would end with its own statement and
 * bound nothing, silently.
 *
 * <p>A statement that runs over is cancelled by Postgres and reaches the caller as any other failed database read
 * does, through Boot's default error answer. There is deliberately no new status and no handler: the caller can do
 * nothing about it but try again, and the default body carries neither the query nor the database's message.
 */
@Component
@EnableConfigurationProperties(CaseListProperties.class)
public class CaseListStatementTimeout {

    private final JdbcTemplate jdbc;
    private final CaseListProperties properties;

    public CaseListStatementTimeout(JdbcTemplate jdbc, CaseListProperties properties) {
        this.jdbc = jdbc;
        this.properties = properties;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void apply() {
        jdbc.queryForObject("SELECT set_config('statement_timeout', ?, true)",
            String.class, properties.statementTimeout().toMillis() + "ms");
    }
}
