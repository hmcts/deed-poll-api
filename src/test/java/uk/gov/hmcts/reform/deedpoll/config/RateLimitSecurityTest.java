package uk.gov.hmcts.reform.deedpoll.config;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import uk.gov.hmcts.reform.deedpoll.auth.StubIdam;
import uk.gov.hmcts.reform.deedpoll.domain.auth.Role;
import uk.gov.hmcts.reform.deedpoll.domain.documents.DocumentService;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * {@link RateLimitFilter} in the real chain, against the real counter, on a real Tomcat.
 *
 * <p>A real server rather than MockMvc because MockMvc builds its multipart request already parsed, so it could not
 * show that an over-budget upload is refused before Tomcat reads it. A malformed multipart body is the probe: parsing
 * it fails, so a 429 for one proves nothing read it, and the first request, admitted, shows that it would have failed.
 *
 * <p>Enforced, with an upload limit of one, set through the bucket's property key so that binding is under test too.
 * The counter table is shared by every context on the container, so each test starts it empty.
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class RateLimitSecurityTest extends AbstractPostgresContainerTest {

    private static final StubIdam IDAM = new StubIdam();

    private static final String MALFORMED = "this is not a multipart body";

    static {
        IDAM.start();
    }

    @AfterAll
    static void stopIdam() {
        IDAM.stop();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("idam.api.issuer-url", IDAM::issuerUrl);
        registry.add("idam.api.audiences", () -> StubIdam.AUDIENCE);
        registry.add("deedpoll.rate-limit.mode", () -> "enforce");
        registry.add("deedpoll.rate-limit.buckets.upload.limit", () -> "1");
    }

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    @Qualifier("securityFilterChain")
    private SecurityFilterChain staffChain;

    @Autowired
    @Qualifier("ccdPersistenceFilterChain")
    private SecurityFilterChain ccdPersistenceChain;

    @MockitoSpyBean
    private DocumentService documents;

    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void setUp() {
        IDAM.stubDefaults();
        IDAM.stubUserInfo(StubIdam.SUBJECT, "A Caseworker", List.of(Role.CASEWORKER.claimValue()));
        jdbc.update("DELETE FROM \"deedpoll_request_budget\"");
    }

    @Test
    @DisplayName("a filtered list read spends from the caller's search budget; an unfiltered one spends nothing")
    void searchSpendsAndListReadDoesNot() throws Exception {
        assertThat(send(get("/caseworker/cases")).statusCode()).isEqualTo(200);
        assertThat(count("search")).as("an unfiltered list read").isNull();

        assertThat(send(get("/caseworker/cases?q=Smith")).statusCode()).isEqualTo(200);
        assertThat(count("search")).isEqualTo(1);
    }

    @Test
    @DisplayName("an over-budget upload is a 429 before Tomcat parses the multipart or the controller is reached")
    void anOverBudgetUploadIsRefusedUnparsed() throws Exception {
        HttpResponse<String> admitted = send(upload(MALFORMED));
        assertThat(admitted.statusCode()).as("within budget, the malformed body is parsed and refused")
            .isNotEqualTo(429).isGreaterThanOrEqualTo(400);

        HttpResponse<String> wellFormed = send(upload(multipart()));
        HttpResponse<String> malformed = send(upload(MALFORMED));

        for (HttpResponse<String> refused : List.of(wellFormed, malformed)) {
            assertThat(refused.statusCode()).isEqualTo(429);
            assertThat(Long.parseLong(refused.headers().firstValue("Retry-After").orElseThrow()))
                .as("whole seconds to the end of the ten-minute window").isBetween(1L, 600L);
            assertThat(refused.headers().firstValue("Content-Type")).hasValueSatisfying(
                type -> assertThat(type).startsWith("application/json"));
            assertThat(refused.body()).isEqualTo("{\"error\":\"" + RateLimitFilter.REFUSAL + "\"}");
        }
        verifyNoInteractions(documents);
        assertThat(count("upload")).isEqualTo(3);
    }

    @Test
    @DisplayName("an unauthenticated upload is the chain's 401, and spends nothing")
    void anUnauthenticatedRequestIsUnchanged() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri("/caseworker/cases/abc/documents"))
            .header("Content-Type", "multipart/form-data; boundary=x")
            .POST(HttpRequest.BodyPublishers.ofString(MALFORMED))
            .build();

        assertThat(send(request).statusCode()).isEqualTo(401);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM \"deedpoll_request_budget\"", Integer.class)).isZero();
    }

    @Test
    @DisplayName("the filter is on the staff chain, after bearer authentication, and not on the CCD persistence chain")
    void onlyTheStaffChainLimits() {
        List<String> staff = staffChain.getFilters().stream().map(filter -> filter.getClass().getSimpleName())
            .toList();
        assertThat(staff).contains("RateLimitFilter");
        assertThat(staff.indexOf("RateLimitFilter")).isEqualTo(staff.indexOf("BearerTokenAuthenticationFilter") + 1);

        assertThat(ccdPersistenceChain.getFilters()).noneMatch(RateLimitFilter.class::isInstance);
    }

    private Integer count(String bucket) {
        List<Integer> counts = jdbc.queryForList(
            "SELECT \"count\" FROM \"deedpoll_request_budget\" WHERE \"subject\" = ? AND \"bucket\" = ?",
            Integer.class, StubIdam.SUBJECT, bucket);
        return counts.isEmpty() ? null : counts.get(0);
    }

    private HttpRequest get(String path) {
        return authorised(HttpRequest.newBuilder(uri(path)).GET());
    }

    private HttpRequest upload(String body) {
        return authorised(HttpRequest.newBuilder(uri("/caseworker/cases/abc/documents"))
            .header("Content-Type", "multipart/form-data; boundary=x")
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)));
    }

    private static String multipart() {
        return "--x\r\n"
            + "Content-Disposition: form-data; name=\"file\"; filename=\"a.pdf\"\r\n"
            + "Content-Type: application/pdf\r\n\r\n"
            + "%PDF-1.4\r\n"
            + "--x--\r\n";
    }

    private HttpRequest authorised(HttpRequest.Builder request) {
        return request.header("Authorization", "Bearer " + IDAM.accessToken()).build();
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private HttpResponse<String> send(HttpRequest request) throws IOException, InterruptedException {
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
