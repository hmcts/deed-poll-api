package uk.gov.hmcts.reform.deedpoll.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.PathContainer;
import org.springframework.http.server.RequestPath;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.pattern.PathPattern;
import uk.gov.hmcts.reform.deedpoll.api.ApiError;
import uk.gov.hmcts.reform.deedpoll.repository.RequestBudgetRepository;
import uk.gov.hmcts.reform.deedpoll.repository.RequestBudgetRepository.Spend;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * Per-identity budgets on search, email preview, email send and upload (OWASP M6, abuse-limits plan).
 *
 * <p><strong>A filter in {@code SecurityConfiguration.securityFilterChain}, after
 * {@code BearerTokenAuthenticationFilter}, and nowhere else.</strong> After, because the key is the verified token's
 * {@code sub}, never an address: staff share NAT egress. A filter rather than a {@code HandlerInterceptor}, because
 * {@code DispatcherServlet.checkMultipart} parses a 25 MB upload before any interceptor runs, and the point of the
 * upload budget is to refuse before that. For the same reason this class never calls {@code getParameter}: on a
 * multipart request Tomcat answers it by parsing the body, so the search test reads the raw query string. It is not a
 * bean, because Boot would register a {@code Filter} bean on every path, the CCD persistence chain's included, and
 * CCD callbacks are service traffic.
 *
 * <p>A request with no verified token is left alone, so the chain's 401 is unchanged and an anonymous caller cannot
 * spend anybody's budget. A token with no {@code sub} spends from the one shared {@link #NO_SUBJECT} bucket.
 *
 * <p>The spend commits on its own before the request runs, so a request that then fails still counts. A counter that
 * cannot answer admits the request and logs the exception's class: a database blip must not become a 429 storm, and
 * the request is about to need the same database anyway.
 */
public class RateLimitFilter extends OncePerRequestFilter {

    /** The 429 body, in fixed wording that quotes nothing from the request. */
    public static final String REFUSAL = "Too many requests. Wait a few minutes and try again.";

    /**
     * The subject for a verified token that carries no {@code sub}.
     */
    public static final String NO_SUBJECT = "no-subject";

    private static final Logger LOG = LoggerFactory.getLogger(RateLimitFilter.class);

    private static final List<Matcher> MATCHERS = matchers();

    private final RateLimitProperties properties;
    private final RequestBudgetRepository budget;
    private final ObjectMapper json;
    private final Clock clock;

    public RateLimitFilter(RateLimitProperties properties, RequestBudgetRepository budget, ObjectMapper json,
                           Clock clock) {
        this.properties = properties;
        this.budget = budget;
        this.json = json;
        this.clock = clock;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException {

        if (properties.mode() == RateLimitProperties.Mode.OFF) {
            chain.doFilter(request, response);
            return;
        }
        Optional<RateLimitBucket> bucket = bucketOf(request);
        Optional<String> subject = bucket.isPresent() ? subjectOf() : Optional.empty();
        if (bucket.isEmpty() || subject.isEmpty()) {
            chain.doFilter(request, response);
            return;
        }

        Optional<Duration> refusal = spend(subject.get(), bucket.get());
        if (refusal.isPresent() && properties.mode() == RateLimitProperties.Mode.ENFORCE) {
            refuse(response, refusal.get());
            return;
        }
        chain.doFilter(request, response);
    }

    /**
     * Spends one request, and answers how long until the window ends if it was over the limit.
     */
    private Optional<Duration> spend(String subject, RateLimitBucket bucket) {
        RateLimitProperties.Budget allowance = properties.budget(bucket);
        Instant now = clock.instant();
        Spend spent;
        try {
            spent = budget.spend(subject, bucket.key(), now, allowance.window());
        } catch (RuntimeException unavailable) {
            LOG.warn("rate_limit unavailable bucket={} reason={}", bucket.key(),
                     unavailable.getClass().getSimpleName());
            return Optional.empty();
        }
        if (spent.count() <= allowance.limit()) {
            return Optional.empty();
        }
        LOG.warn("rate_limit bucket={} mode={} subject={} count={} limit={}",
                 bucket.key(), properties.mode().key(), subject, spent.count(), allowance.limit());
        return Optional.of(Duration.between(now, spent.windowEnd()));
    }

    private void refuse(HttpServletResponse response, Duration untilWindowEnds) throws IOException {
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(retryAfterSeconds(untilWindowEnds)));
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        json.writeValue(response.getOutputStream(), ApiError.of(REFUSAL));
    }

    /** Whole seconds, rounded up and at least one, so a caller never retries into the window that refused them. */
    static long retryAfterSeconds(Duration untilWindowEnds) {
        long millis = Math.max(untilWindowEnds.toMillis(), 1);
        return (millis + 999) / 1000;
    }

    private static Optional<String> subjectOf() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
            || !(authentication.getCredentials() instanceof Jwt token)) {
            return Optional.empty();
        }
        String subject = token.getSubject();
        return Optional.of(subject == null || subject.isBlank() ? NO_SUBJECT : subject);
    }

    static Optional<RateLimitBucket> bucketOf(HttpServletRequest request) {
        PathContainer path = RequestPath.parse(request.getRequestURI(), request.getContextPath())
            .pathWithinApplication();
        for (Matcher matcher : MATCHERS) {
            if (matcher.route.method().matches(request.getMethod()) && matcher.pattern.matches(path)) {
                return matcher.bucket != RateLimitBucket.SEARCH || isSearch(request.getQueryString())
                    ? Optional.of(matcher.bucket)
                    : Optional.empty();
            }
        }
        return Optional.empty();
    }

    /**
     * Whether a list read filters: a non-blank {@code q}, or any {@code where} at all.
     *
     * <p>Read from the raw query string and decoded here, never through {@code getParameter} (see the class comment),
     * so an encoded name such as {@code %77here} is still a {@code where}. A malformed escape is compared undecoded;
     * the controller refuses it either way.
     */
    static boolean isSearch(String queryString) {
        if (queryString == null || queryString.isEmpty()) {
            return false;
        }
        for (String pair : queryString.split("&")) {
            int equals = pair.indexOf('=');
            String name = decode(equals < 0 ? pair : pair.substring(0, equals));
            String value = equals < 0 ? "" : decode(pair.substring(equals + 1));
            if ("where".equals(name) || "q".equals(name) && !value.isBlank()) {
                return true;
            }
        }
        return false;
    }

    private static String decode(String raw) {
        try {
            return URLDecoder.decode(raw, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException malformed) {
            return raw;
        }
    }

    private static List<Matcher> matchers() {
        return Arrays.stream(RateLimitBucket.values())
            .flatMap(bucket -> bucket.routes().stream().map(route -> new Matcher(bucket, route, route.parsed())))
            .toList();
    }

    private record Matcher(RateLimitBucket bucket, RateLimitBucket.Route route, PathPattern pattern) {
    }
}
