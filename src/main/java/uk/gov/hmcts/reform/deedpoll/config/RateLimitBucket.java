package uk.gov.hmcts.reform.deedpoll.config;

import org.springframework.http.HttpMethod;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

import java.time.Duration;
import java.util.List;

/**
 * The four budgets {@link RateLimitFilter} spends from, and the six routes that spend them (abuse-limits plan,
 * decision 1).
 *
 * <p>Each route is a method and the controller's own path pattern, written as the controller writes it, so
 * {@code RateLimitRoutesTest} can hold every one to a real handler mapping: a route renamed in a controller and
 * not here would otherwise stop being limited, and nothing would say so.
 *
 * <p>{@link #SEARCH}'s one route counts only when it carries {@code q} or {@code where}; that test is the filter's,
 * because it reads the query string. An unfiltered list read is never counted: admin's dashboard makes up to four
 * per view.
 */
public enum RateLimitBucket {

    SEARCH("search", 120, Duration.ofMinutes(1),
           List.of(new Route(HttpMethod.GET, "/caseworker/cases"))),
    EMAIL_PREVIEW("email-preview", 30, Duration.ofMinutes(10),
                  List.of(new Route(HttpMethod.POST, "/caseworker/cases/{id}/emails/{template}/preview"),
                          new Route(HttpMethod.POST, "/caseworker/gazette-notice-previews"))),
    EMAIL_SEND("email-send", 20, Duration.ofHours(1),
               List.of(new Route(HttpMethod.POST, "/caseworker/cases/{id}/emails/{template}"),
                       new Route(HttpMethod.POST, "/caseworker/gazette-notices"))),
    UPLOAD("upload", 30, Duration.ofMinutes(10),
           List.of(new Route(HttpMethod.POST, "/caseworker/cases/{id}/documents")));

    private final String key;
    private final RateLimitProperties.Budget defaultBudget;
    private final List<Route> routes;

    RateLimitBucket(String key, int limit, Duration window, List<Route> routes) {
        this.key = key;
        this.defaultBudget = new RateLimitProperties.Budget(limit, window);
        this.routes = routes;
    }

    /**
     * The name stored in {@code deedpoll_request_budget."bucket"}, logged, and used in the property's key.
     */
    public String key() {
        return key;
    }

    /**
     * Decision 1's numbers, used when {@code deedpoll.rate-limit.buckets} does not name this bucket.
     */
    public RateLimitProperties.Budget defaultBudget() {
        return defaultBudget;
    }

    public List<Route> routes() {
        return routes;
    }

    /**
     * One limited operation.
     *
     * @param method  the HTTP method the controller maps
     * @param pattern the path pattern exactly as the controller's mapping composes it
     */
    public record Route(HttpMethod method, String pattern) {

        PathPattern parsed() {
            return PathPatternParser.defaultInstance.parse(pattern);
        }
    }
}
