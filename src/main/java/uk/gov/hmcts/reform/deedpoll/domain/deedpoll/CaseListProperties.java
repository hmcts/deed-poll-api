package uk.gov.hmcts.reform.deedpoll.domain.deedpoll;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * How long the case list read may run: {@code deedpoll.list}.
 *
 * <p>The abuse-limits plan's decision 9. A name search is a {@code like '%…%'} over unindexed columns, so one request
 * can hold a connection and a backend for as long as the table takes to scan; the rate limit bounds how often, and
 * this bounds how long.
 *
 * @param statementTimeout the longest any one statement of the list read may run; unset is
 *     {@link #STATEMENT_TIMEOUT}
 */
@ConfigurationProperties(prefix = "deedpoll.list")
public record CaseListProperties(Duration statementTimeout) {

    /** Decision 9's default. */
    public static final Duration STATEMENT_TIMEOUT = Duration.ofSeconds(5);

    public CaseListProperties {
        statementTimeout = statementTimeout == null ? STATEMENT_TIMEOUT : statementTimeout;
        if (statementTimeout.toMillis() < 1) {
            // Postgres reads a statement_timeout of 0 as no timeout at all, which is switching this off by accident.
            throw new IllegalArgumentException("the case list statement timeout must be at least 1ms");
        }
    }
}
