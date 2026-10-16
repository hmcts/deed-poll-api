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
import java.util.concurrent.Semaphore;

/**
 * Per-identity budgets on search, email preview, email send and upload (OWASP M6, abuse-limits plan).
 *
 * <p><strong>A filter in {@code SecurityConfiguration.securityFilterChain}, after {@code AuthorizationFilter}, and
 * nowhere else.</strong> After, because the key is the verified token's {@code sub}, never an address (staff share NAT
 * egress), and because only an admitted request should spend: a caller the staff gate refuses adds no budget row.
 * A filter rather than a {@code HandlerInterceptor}, because
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
 *
 * <p><strong>The in-flight upload cap enforces in every mode, {@code off} included</strong>, because it protects this
 * pod's heap rather than anybody's throughput: each upload can hold 25 MB in memory. It is per pod, one
 * {@link Semaphore} per filter, and there is one filter per pod. An upload within its budget takes a permit without
 * waiting, or is refused with 429 and {@code Retry-After: 5}; the permit is released in {@code finally}, so a
 * controller that throws gives it back. It is taken after the budget, so an upload the budget refuses holds none,
 * and only by a verified caller, so an anonymous upload still gets the chain's 401. Its log line has decision 6's
 * shape under the bucket {@value #UPLOAD_IN_FLIGHT} and always says {@code mode=enforce}, because it always refuses;
 * its count is the upload that would have been one too many.
 */
public class RateLimitFilter extends OncePerRequestFilter {

    /** The 429 body, in fixed wording that quotes nothing from the request. */
    public static final String REFUSAL = "Too many requests. Wait a few minutes and try again.";

    /**
     * The subject for a verified token that carries no {@code sub}.
     */
    public static final String NO_SUBJECT = "no-subject";

    /**
     * The bucket the in-flight cap logs under; not a {@link RateLimitBucket}, because nothing stores or spends it.
     */
    static final String UPLOAD_IN_FLIGHT = "upload-in-flight";

    /**
     * Decision 7's fixed {@code Retry-After} for the in-flight cap: an upload in progress is over in seconds.
     */
    private static final Duration UPLOAD_RETRY_AFTER = Duration.ofSeconds(5);

    private static final Logger LOG = LoggerFactory.getLogger(RateLimitFilter.class);

    private static final List<Matcher> MATCHERS = matchers();

    private final RateLimitProperties properties;
    private final RequestBudgetRepository budget;
    private final ObjectMapper json;
    private final Clock clock;
    private final Semaphore uploadsInFlight;

    public RateLimitFilter(RateLimitProperties properties, RequestBudgetRepository budget, ObjectMapper json,
                           Clock clock) {
        this.properties = properties;
        this.budget = budget;
        this.json = json;
        this.clock = clock;
        this.uploadsInFlight = new Semaphore(properties.uploadInFlight());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException {

        Optional<RateLimitBucket> bucket = bucketOf(request);
        Optional<String> subject = bucket.isPresent() ? subjectOf() : Optional.empty();
        if (bucket.isEmpty() || subject.isEmpty()) {
            chain.doFilter(request, response);
            return;
        }

        if (properties.mode() != RateLimitProperties.Mode.OFF) {
            Optional<Duration> refusal = spend(subject.get(), bucket.get());
            if (refusal.isPresent() && properties.mode() == RateLimitProperties.Mode.ENFORCE) {
                refuse(response, refusal.get());
                return;
            }
        }
        if (bucket.get() != RateLimitBucket.UPLOAD) {
            chain.doFilter(request, response);
            return;
        }
        if (!uploadsInFlight.tryAcquire()) {
            LOG.warn("rate_limit bucket={} mode={} subject={} count={} limit={}", UPLOAD_IN_FLIGHT,
                     RateLimitProperties.Mode.ENFORCE.key(), subject.get(), properties.uploadInFlight() + 1,
                     properties.uploadInFlight());
            refuse(response, UPLOAD_RETRY_AFTER);
            return;
        }
        try {
            chain.doFilter(request, response);
        } finally {
            uploadsInFlight.release();
        }
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
        // Spring MVC answers HEAD from the GET handler, so a HEAD list read costs what a GET does.
        String method = "HEAD".equalsIgnoreCase(request.getMethod()) ? "GET" : request.getMethod();
        for (Matcher matcher : MATCHERS) {
            if (matcher.route.method().matches(method) && matcher.pattern.matches(path)) {
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
