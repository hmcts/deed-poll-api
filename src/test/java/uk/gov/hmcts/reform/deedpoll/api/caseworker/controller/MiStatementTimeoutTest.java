package uk.gov.hmcts.reform.deedpoll.api.caseworker.controller;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import uk.gov.hmcts.reform.deedpoll.auth.StubIdam;
import uk.gov.hmcts.reform.deedpoll.config.AbstractPostgresContainerTest;
import uk.gov.hmcts.reform.deedpoll.domain.auth.Role;

import javax.sql.DataSource;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Management information's statement timeout ({@code MiStatementTimeout}), against real Postgres on a real Tomcat, as
 * {@code CaseListStatementTimeoutTest} proves the case list's.
 *
 * <p>The slow query is forced: a second connection holds an {@code ACCESS EXCLUSIVE} lock on {@code deedpoll_deedpoll},
 * so every report waits, and Postgres counts a lock wait against {@code statement_timeout}. The pool has two
 * connections, and afterwards the test holds both and asks each for its {@code statement_timeout}: both answering the
 * server default proves the setting was transaction-local.
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class MiStatementTimeoutTest extends AbstractPostgresContainerTest {

    private static final StubIdam IDAM = new StubIdam();

    private static final String QUERY = "?from=2026-01-01&to=2026-03-31&granularity=MONTH";

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
        registry.add("deedpoll.mi.statement-timeout", () -> "300ms");
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "2");
    }

    @LocalServerPort
    private int port;

    @Autowired
    private DataSource dataSource;

    @Value("${spring.datasource.url}")
    private String url;

    @Value("${spring.datasource.username}")
    private String username;

    @Value("${spring.datasource.password}")
    private String password;

    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void setUp() {
        IDAM.stubDefaults();
        IDAM.stubUserInfo(StubIdam.SUBJECT, "A Team Leader", List.of(Role.TEAM_LEADER.claimValue()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"volume", "stage-durations", "referral-outcomes", "completion"})
    @DisplayName("every report over the timeout fails as any failed read does, and leaves the connection as it was")
    void slowReportTimesOutAndTheConnectionIsUnaffected(String report) throws Exception {
        String path = "/caseworker/management-information/" + report + QUERY;
        try (Connection holder = DriverManager.getConnection(url, username, password)) {
            holder.setAutoCommit(false);
            try (Statement lock = holder.createStatement()) {
                lock.execute("LOCK TABLE \"deedpoll_deedpoll\" IN ACCESS EXCLUSIVE MODE");
            }

            HttpResponse<String> timedOut = send(get(path));

            assertThat(timedOut.statusCode()).as("Boot's own answer to a failed read, not a new status")
                .isEqualTo(500);
            assertThat(timedOut.body()).as("nothing of the query or the database reaches the caller")
                .doesNotContainIgnoringCase("statement").doesNotContainIgnoringCase("timeout")
                .doesNotContain("deedpoll_deedpoll");
            holder.rollback();
        }

        assertThat(send(get(path)).statusCode()).isEqualTo(200);
        try (Connection first = dataSource.getConnection(); Connection second = dataSource.getConnection()) {
            assertThat(List.of(timeoutOf(first), timeoutOf(second))).as("every pooled connection, after both requests")
                .containsOnly("0");
        }
    }

    private static String timeoutOf(Connection connection) throws Exception {
        try (Statement show = connection.createStatement();
             ResultSet result = show.executeQuery("SHOW statement_timeout")) {
            result.next();
            return result.getString(1);
        }
    }

    private HttpRequest get(String path) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
            .timeout(Duration.ofSeconds(10))
            .header("Authorization", "Bearer " + IDAM.accessToken())
            .GET().build();
    }

    private HttpResponse<String> send(HttpRequest request) throws Exception {
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
