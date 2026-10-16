package uk.gov.hmcts.reform.deedpoll.config;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.Part;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.slf4j.LoggerFactory;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import uk.gov.hmcts.reform.deedpoll.repository.RequestBudgetRepository;
import uk.gov.hmcts.reform.deedpoll.repository.RequestBudgetRepository.Spend;

import java.io.BufferedReader;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.EnumMap;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Which requests {@link RateLimitFilter} counts, under which key, and what each mode does with an over-budget one.
 *
 * <p>No context: the counter is a mock that counts in memory, so a mode, a bucket and a subject can be set up in a
 * line. {@code RateLimitSecurityTest} asserts the same filter in its chain, against the real counter.
 */
class RateLimitFilterTest {

    private static final Instant NOW = Instant.parse("2026-10-16T12:00:30Z");

    private static final String SUBJECT = "a-caseworker";

    private final RequestBudgetRepository budget = mock(RequestBudgetRepository.class);

    private final Map<String, Integer> counts = new HashMap<>();

    private ListAppender<ILoggingEvent> logs;
    private Logger logger;

    @BeforeEach
    void setUp() {
        when(budget.spend(anyString(), anyString(), any(), any())).thenAnswer(call -> {
            String key = call.getArgument(0) + "/" + call.getArgument(1);
            Duration window = call.getArgument(3);
            return new Spend(counts.merge(key, 1, Integer::sum), Instant.parse("2026-10-16T12:00:00Z").plus(window));
        });
        signIn(SUBJECT);
        logs = new ListAppender<>();
        logs.start();
        logger = (Logger) LoggerFactory.getLogger(RateLimitFilter.class);
        logger.addAppender(logs);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        logger.detachAppender(logs);
    }

    @ParameterizedTest(name = "{0} {1} counts in {2}")
    @CsvSource({
        "GET,  /caseworker/cases,                                         search,        q=Smith",
        "GET,  /caseworker/cases,                                         search,        where=type:is:Adult",
        "POST, /caseworker/cases/abc/emails/chaseMissingDocuments/preview, email-preview, ''",
        "POST, /caseworker/gazette-notice-previews,                        email-preview, ''",
        "POST, /caseworker/cases/abc/emails/chaseMissingDocuments,         email-send,    ''",
        "POST, /caseworker/gazette-notices,                                email-send,    ''",
        "POST, /caseworker/cases/abc/documents,                            upload,        ''"
    })
    @DisplayName("each of the six routes counts in its bucket, keyed on the subject")
    void eachRouteCountsInItsBucket(String method, String path, String bucket, String query) throws Exception {
        MockHttpServletRequest request = request(method, path, query);

        run(filter(RateLimitProperties.Mode.ENFORCE), request);

        verify(budget).spend(eq(SUBJECT), eq(bucket), eq(NOW), any());
    }

    @ParameterizedTest(name = "{0} {1}?{2} is not counted")
    @CsvSource({
        "GET,    /caseworker/cases,                               ''",
        "GET,    /caseworker/cases,                               filter=open&sort=dateReceived:asc&page=2",
        "GET,    /caseworker/cases,                               q=",
        "GET,    /caseworker/cases,                               q=%20%20",
        "POST,   /caseworker/cases,                               q=Smith",
        "GET,    /caseworker/cases/abc,                           q=Smith",
        "GET,    /caseworker/cases/abc/emails,                    ''",
        "GET,    /caseworker/cases/abc/documents,                 ''",
        "GET,    /caseworker/gazette-notices,                     ''",
        "DELETE, /caseworker/cases/abc/documents,                 ''",
        "POST,   /caseworker/cases/abc/emails/chase/preview/more, ''",
        "POST,   /caseworker/cases/abc/notes,                     ''"
    })
    @DisplayName("an unfiltered list read and every other route are not counted")
    void otherRequestsAreNotCounted(String method, String path, String query) throws Exception {
        MockFilterChain chain = run(filter(RateLimitProperties.Mode.ENFORCE), request(method, path, query));

        verifyNoInteractions(budget);
        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    @DisplayName("a repeated or encoded parameter name still makes a list read a search")
    void encodedParameterNames() throws Exception {
        run(filter(RateLimitProperties.Mode.ENFORCE), request("GET", "/caseworker/cases", "page=1&%77here=x"));

        verify(budget).spend(eq(SUBJECT), eq("search"), any(), any());
    }

    @Test
    @DisplayName("enforce: request N+1 is a 429 with Retry-After to the window's end and the fixed wording")
    void enforceRefusesOverTheLimit() throws Exception {
        RateLimitFilter filter = filter(RateLimitProperties.Mode.ENFORCE, RateLimitBucket.EMAIL_SEND, 2,
                                        Duration.ofHours(1));
        for (int i = 0; i < 2; i++) {
            assertThat(run(filter, send()).getRequest()).as("request %d is admitted", i + 1).isNotNull();
        }

        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(send(), response, chain);

        assertThat(chain.getRequest()).as("the chain stops at the refusal").isNull();
        assertThat(response.getStatus()).isEqualTo(429);
        // The window opened at 12:00:00 and lasts an hour; it is 12:00:30.
        assertThat(response.getHeader("Retry-After")).isEqualTo("3570");
        assertThat(response.getContentType()).isEqualTo("application/json");
        assertThat(new ObjectMapper().readTree(response.getContentAsString()).path("error").asText())
            .isEqualTo(RateLimitFilter.REFUSAL);
        assertThat(onlyLine()).isEqualTo(
            "rate_limit bucket=email-send mode=enforce subject=a-caseworker count=3 limit=2");
    }

    @Test
    @DisplayName("an over-budget upload is refused without its body, its parts or its parameters being read")
    void anUploadIsRefusedUnread() throws Exception {
        RateLimitFilter filter = filter(RateLimitProperties.Mode.ENFORCE, RateLimitBucket.UPLOAD, 1,
                                        Duration.ofMinutes(10));
        run(filter, upload());

        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(new UnreadableRequest("POST", "/caseworker/cases/abc/documents"), response, chain);

        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(chain.getRequest()).as("the controller is never reached").isNull();
    }

    @Test
    @DisplayName("a list read is classified from the raw query string, never through getParameter")
    void searchIsClassifiedUnread() throws Exception {
        UnreadableRequest request = new UnreadableRequest("GET", "/caseworker/cases");
        request.setQueryString("q=Smith");

        run(filter(RateLimitProperties.Mode.ENFORCE), request);

        verify(budget).spend(eq(SUBJECT), eq("search"), any(), any());
    }

    @Test
    @DisplayName("Retry-After rounds up, so a caller never retries into the window that refused them")
    void retryAfterRoundsUp() throws Exception {
        doReturn(new Spend(99, NOW.plusMillis(1))).when(budget).spend(anyString(), anyString(), any(), any());

        MockHttpServletResponse response = new MockHttpServletResponse();
        filter(RateLimitProperties.Mode.ENFORCE).doFilter(send(), response, new MockFilterChain());

        assertThat(response.getHeader("Retry-After")).isEqualTo("1");
    }

    @Test
    @DisplayName("the refusal never quotes request input")
    void theRefusalQuotesNothing() throws Exception {
        RateLimitFilter filter = filter(RateLimitProperties.Mode.ENFORCE, RateLimitBucket.SEARCH, 1,
                                        Duration.ofMinutes(1));
        run(filter, request("GET", "/caseworker/cases", "q=Secret-Name"));

        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request("GET", "/caseworker/cases", "q=Secret-Name"), response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getContentAsString()).doesNotContain("Secret").doesNotContain("caseworker/cases");
        assertThat(onlyLine()).doesNotContain("Secret").doesNotContain("/caseworker");
    }

    @Test
    @DisplayName("observe: an over-budget request is admitted, and logged exactly as enforce would refuse it")
    void observeLogsAndAdmits() throws Exception {
        RateLimitFilter filter = filter(RateLimitProperties.Mode.OBSERVE, RateLimitBucket.UPLOAD, 1,
                                        Duration.ofMinutes(10));

        assertThat(run(filter, upload()).getRequest()).isNotNull();
        assertThat(logs.list).as("within budget: nothing logged").isEmpty();
        assertThat(run(filter, upload()).getRequest()).as("over budget: still admitted").isNotNull();

        assertThat(onlyLine()).isEqualTo(
            "rate_limit bucket=upload mode=observe subject=a-caseworker count=2 limit=1");
    }

    @Test
    @DisplayName("off: nothing is counted and nothing is refused")
    void offCountsNothing() throws Exception {
        RateLimitFilter filter = filter(RateLimitProperties.Mode.OFF, RateLimitBucket.EMAIL_SEND, 1,
                                        Duration.ofHours(1));

        for (int i = 0; i < 3; i++) {
            assertThat(run(filter, send()).getRequest()).isNotNull();
        }
        verifyNoInteractions(budget);
        assertThat(logs.list).isEmpty();
    }

    @Test
    @DisplayName("a token with no sub spends from the one shared no-subject bucket")
    void noSubjectSharesOneBucket() throws Exception {
        signIn(null);

        run(filter(RateLimitProperties.Mode.ENFORCE), send());

        verify(budget).spend(eq(RateLimitFilter.NO_SUBJECT), eq("email-send"), any(), any());
    }

    @Test
    @DisplayName("an unauthenticated request is left to the chain's 401, and spends nothing")
    void unauthenticatedIsNotCounted() throws Exception {
        SecurityContextHolder.clearContext();
        run(filter(RateLimitProperties.Mode.ENFORCE), send());

        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken("someone", null));
        run(filter(RateLimitProperties.Mode.ENFORCE), send());

        verifyNoInteractions(budget);
    }

    @Test
    @DisplayName("a counter that fails admits the request and logs the exception class only")
    void failingCounterAdmits() throws Exception {
        doThrow(new QueryTimeoutException("statement text with " + SUBJECT))
            .when(budget).spend(anyString(), anyString(), any(), any());

        assertThat(run(filter(RateLimitProperties.Mode.ENFORCE), send()).getRequest()).isNotNull();

        assertThat(onlyLine()).isEqualTo("rate_limit unavailable bucket=email-send reason=QueryTimeoutException");
    }

    @Test
    @DisplayName("each bucket is spent with its own window")
    void theBucketsWindowIsUsed() throws Exception {
        run(filter(RateLimitProperties.Mode.ENFORCE, RateLimitBucket.SEARCH, 5, Duration.ofSeconds(90)),
            request("GET", "/caseworker/cases", "q=x"));

        verify(budget).spend(SUBJECT, "search", NOW, Duration.ofSeconds(90));
    }

    private RateLimitFilter filter(RateLimitProperties.Mode mode) {
        return new RateLimitFilter(new RateLimitProperties(mode, Map.of()), budget, new ObjectMapper(),
                                   Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private RateLimitFilter filter(RateLimitProperties.Mode mode, RateLimitBucket bucket, int limit,
                                   Duration window) {
        Map<RateLimitBucket, RateLimitProperties.Budget> buckets = new EnumMap<>(RateLimitBucket.class);
        buckets.put(bucket, new RateLimitProperties.Budget(limit, window));
        return new RateLimitFilter(new RateLimitProperties(mode, buckets), budget, new ObjectMapper(),
                                   Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static MockFilterChain run(RateLimitFilter filter, MockHttpServletRequest request) throws Exception {
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request, new MockHttpServletResponse(), chain);
        return chain;
    }

    private static MockHttpServletRequest send() {
        return request("POST", "/caseworker/cases/abc/emails/chaseMissingDocuments", "");
    }

    private static MockHttpServletRequest upload() {
        return request("POST", "/caseworker/cases/abc/documents", "");
    }

    private static MockHttpServletRequest request(String method, String path, String query) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        if (!query.isEmpty()) {
            request.setQueryString(query);
        }
        return request;
    }

    /**
     * A request that fails the test the moment anything reads its body or asks Tomcat to parse it.
     */
    private static final class UnreadableRequest extends MockHttpServletRequest {

        UnreadableRequest(String method, String path) {
            super(method, path);
            setContentType("multipart/form-data; boundary=x");
        }

        @Override
        public String getParameter(String name) {
            throw new AssertionError("getParameter parses a multipart body");
        }

        @Override
        public Map<String, String[]> getParameterMap() {
            throw new AssertionError("getParameterMap parses a multipart body");
        }

        @Override
        public Enumeration<String> getParameterNames() {
            throw new AssertionError("getParameterNames parses a multipart body");
        }

        @Override
        public String[] getParameterValues(String name) {
            throw new AssertionError("getParameterValues parses a multipart body");
        }

        @Override
        public Collection<Part> getParts() {
            throw new AssertionError("getParts parses a multipart body");
        }

        @Override
        public Part getPart(String name) {
            throw new AssertionError("getPart parses a multipart body");
        }

        @Override
        public ServletInputStream getInputStream() {
            throw new AssertionError("the body was read");
        }

        @Override
        public BufferedReader getReader() {
            throw new AssertionError("the body was read");
        }
    }

    private static void signIn(String subject) {
        Jwt.Builder token = Jwt.withTokenValue("token").header("alg", "RS256").claim("scope", "openid");
        if (subject != null) {
            token.subject(subject);
        }
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(token.build(), List.of()));
    }

    private String onlyLine() {
        assertThat(logs.list).hasSize(1);
        return logs.list.get(0).getFormattedMessage();
    }
}
