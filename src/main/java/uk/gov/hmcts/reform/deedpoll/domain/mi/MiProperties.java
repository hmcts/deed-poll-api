package uk.gov.hmcts.reform.deedpoll.domain.mi;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Management information's bounds (plan 20261103-mi-dashboard). {@code statementTimeout} is the longest one statement
 * of a report may run; see {@link MiStatementTimeout}.
 */
@ConfigurationProperties(prefix = "deedpoll.mi")
public record MiProperties(Duration statementTimeout) {

    /**
     * Longer than the case list's 5 s, because a report may scan two years of cases, and five times the plan's 2 s
     * target, so an ordinary report never meets it.
     */
    public static final Duration STATEMENT_TIMEOUT = Duration.ofSeconds(10);

    public MiProperties {
        statementTimeout = statementTimeout == null ? STATEMENT_TIMEOUT : statementTimeout;
        if (statementTimeout.toMillis() < 1) {
            // Postgres reads a statement_timeout of 0 as no timeout at all, which is switching this off by accident.
            throw new IllegalArgumentException("the management information statement timeout must be at least 1ms");
        }
    }
}
