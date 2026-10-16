package uk.gov.hmcts.reform.deedpoll.config;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
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
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * {@link RateLimitFilter}'s per-pod in-flight upload cap in the real chain, on a real Tomcat, with rate limiting
 * switched {@code off}: the cap protects the heap, so it refuses whatever the mode.
 *
 * <p>One permit. The document service is a spy whose upload waits for the test, so the first upload is past the
 * filter, parsed and holding the permit before the second is sent: timing alone cannot promise that, because a
 * probe sent too early holds the permit itself. The held upload then fails, and the third request shows the permit
 * came back after a thrown error.
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class UploadInFlightSecurityTest extends AbstractPostgresContainerTest {

    private static final StubIdam IDAM = new StubIdam();

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
        registry.add("deedpoll.rate-limit.mode", () -> "off");
        registry.add("deedpoll.rate-limit.upload-in-flight", () -> "1");
    }

    @LocalServerPort
    private int port;

    @MockitoSpyBean
    private DocumentService documents;

    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void setUp() {
        IDAM.stubDefaults();
        IDAM.stubUserInfo(StubIdam.SUBJECT, "A Caseworker", List.of(Role.CASEWORKER.claimValue()));
    }

    @Test
    @DisplayName("off: while one upload is mid-flight a second is a 429 with Retry-After: 5, and after it, admitted")
    void secondUploadIsRefusedWhileOneIsInFlight() throws Exception {
        CountDownLatch inFlight = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        doAnswer(call -> {
            inFlight.countDown();
            assertThat(finish.await(30, TimeUnit.SECONDS)).isTrue();
            throw new IllegalStateException("the held upload fails");
        }).when(documents).uploadDocument(any(), any(), any(), any());

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            final Future<HttpResponse<String>> first = pool.submit(() -> send(upload()));
            assertThat(inFlight.await(30, TimeUnit.SECONDS)).as("the first upload is in flight").isTrue();

            HttpResponse<String> refused = send(upload());
            assertThat(refused.statusCode()).isEqualTo(429);
            assertThat(refused.headers().firstValue("Retry-After")).hasValue("5");
            assertThat(refused.body()).isEqualTo("{\"error\":\"" + RateLimitFilter.REFUSAL + "\"}");

            finish.countDown();
            assertThat(first.get(30, TimeUnit.SECONDS).statusCode()).as("the held upload ends in an error")
                .isGreaterThanOrEqualTo(500);
        } finally {
            finish.countDown();
            pool.shutdownNow();
        }
        verify(documents, times(1)).uploadDocument(any(), any(), any(), any());

        doThrow(new IllegalStateException("admitted")).when(documents).uploadDocument(any(), any(), any(), any());
        assertThat(send(upload()).statusCode()).as("the permit was released after the error").isNotEqualTo(429);
        verify(documents, times(2)).uploadDocument(any(), any(), any(), any());
    }

    private HttpRequest upload() {
        String body = "--x\r\n"
            + "Content-Disposition: form-data; name=\"documentTypeId\"\r\n\r\n"
            + UUID.randomUUID() + "\r\n"
            + "--x\r\n"
            + "Content-Disposition: form-data; name=\"file\"; filename=\"a.pdf\"\r\n"
            + "Content-Type: application/pdf\r\n\r\n"
            + "%PDF-1.4\r\n"
            + "--x--\r\n";
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/caseworker/cases/abc/documents"))
            .header("Authorization", "Bearer " + IDAM.accessToken())
            .header("Content-Type", "multipart/form-data; boundary=x")
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
            .build();
    }

    private HttpResponse<String> send(HttpRequest request) throws IOException, InterruptedException {
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
