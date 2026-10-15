package uk.gov.hmcts.reform.deedpoll.config;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import uk.gov.hmcts.reform.authorisation.filters.ServiceAuthFilter;
import uk.gov.hmcts.reform.deedpoll.auth.StubIdam;
import uk.gov.hmcts.reform.deedpoll.auth.StubS2s;
import uk.gov.hmcts.reform.deedpoll.domain.ForbiddenError;
import uk.gov.hmcts.reform.deedpoll.domain.auth.Role;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Every authentication and authorisation failure is logged once, with identifiers only, and answers exactly as it
 * did before (audit plan, decision 12).
 *
 * <p>Boots the whole context, for the reason {@link SecurityConfiguration}'s Javadoc gives. The profiles are
 * {@code config-gen} and {@code test}, so no database is needed. The logger is named by string, so this class
 * compiled, and failed, before {@code SecurityEvents} existed.
 *
 * <p>The appender copies the MDC when the event is logged. Logback reads it lazily otherwise, and the keys are gone
 * by the time an assertion would look.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"config-gen", "test"})
@Import(SecurityEventLoggingTest.ForbiddenStandIn.class)
class SecurityEventLoggingTest {

    private static final String SECURITY_EVENTS = "uk.gov.hmcts.reform.deedpoll.auth.SecurityEvents";

    private static final String DOMAIN_REFUSAL = "/caseworker/security-event-stand-in";

    private static final String CCD_DATA = "ccd-data-s2s-token";

    private static final StubIdam IDAM = new StubIdam();
    private static final StubS2s S2S = new StubS2s();

    static {
        IDAM.start();
        S2S.start();
    }

    @AfterAll
    static void stopStubs() {
        IDAM.stop();
        S2S.stop();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("idam.api.issuer-url", IDAM::issuerUrl);
        registry.add("idam.api.audiences", () -> StubIdam.AUDIENCE);
        registry.add("idam.s2s-auth.url", S2S::baseUrl);
    }

    @Autowired
    private MockMvc mockMvc;

    private ListAppender<ILoggingEvent> logs;
    private Logger logger;

    @BeforeEach
    void setUp() {
        IDAM.stubDefaults();
        S2S.reset();
        S2S.token(CCD_DATA, "ccd_data");
        MDC.clear();
        logs = new ListAppender<>() {
            @Override
            protected void append(ILoggingEvent event) {
                event.prepareForDeferredProcessing();
                super.append(event);
            }
        };
        logs.start();
        logger = (Logger) LoggerFactory.getLogger(SECURITY_EVENTS);
        logger.addAppender(logs);
    }

    @AfterEach
    void releaseLogs() {
        logger.detachAppender(logs);
    }

    @Test
    @DisplayName("no token: 401 with WWW-Authenticate Bearer, logged with a reason, no subject, and the MDC cleared")
    void missingToken() throws Exception {
        mockMvc.perform(get("/caseworker/cases"))
            .andExpect(status().isUnauthorized())
            .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"));

        assertThat(onlyEvent().getMDCPropertyMap()).containsExactlyInAnyOrderEntriesOf(Map.of(
            "security.event", "auth.unauthenticated",
            "path", "/caseworker/cases",
            "reason", "missing_token"));
        assertThat(onlyEvent().getFormattedMessage())
            .isEqualTo("auth.unauthenticated path=/caseworker/cases reason=missing_token");
        assertMdcCleared();
    }

    @Test
    @DisplayName("a token that fails verification: the OAuth2 error code is the reason, and no token text is logged")
    void invalidToken() throws Exception {
        String token = IDAM.tokenSignedByAnotherKey();

        MvcResult result = mockMvc.perform(get("/caseworker/cases").headers(bearer(token)))
            .andExpect(status().isUnauthorized())
            .andReturn();

        assertThat(result.getResponse().getHeader(HttpHeaders.WWW_AUTHENTICATE))
            .startsWith("Bearer error=\"invalid_token\"");
        ILoggingEvent event = onlyEvent();
        assertThat(event.getMDCPropertyMap())
            .containsEntry("security.event", "auth.unauthenticated")
            .containsEntry("reason", "invalid_token")
            .doesNotContainKey("subject");
        assertNoTokenText(event, token);
        assertMdcCleared();
    }

    @Test
    @DisplayName("a chain refusal: the 403 and its body are unchanged, and the subject and path are logged")
    void chainRefusal() throws Exception {
        IDAM.stubUserInfo(StubIdam.SUBJECT, "A Caseworker", List.of(Role.CASEWORKER.claimValue()));
        String token = IDAM.accessToken();

        mockMvc.perform(get("/judicial/referrals/mine").headers(bearer(token)))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.error").value(StaffArea.JUDICIAL.refusal()));

        ILoggingEvent event = onlyEvent();
        assertThat(event.getMDCPropertyMap()).containsExactlyInAnyOrderEntriesOf(Map.of(
            "security.event", "authz.denied",
            "subject", StubIdam.SUBJECT,
            "path", "/judicial/referrals/mine",
            "reason", "access_rule"));
        assertNoTokenText(event, token);
        assertMdcCleared();
    }

    @Test
    @DisplayName("a domain refusal: DomainErrorHandler.forbidden logs the subject and path, and the 403 is unchanged")
    void domainRefusal() throws Exception {
        IDAM.stubUserInfo(StubIdam.SUBJECT, "A Caseworker", List.of(Role.CASEWORKER.claimValue()));

        mockMvc.perform(get(DOMAIN_REFUSAL).headers(bearer(IDAM.accessToken())))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.error").value("Requires the stand-in role"));

        assertThat(onlyEvent().getMDCPropertyMap()).containsExactlyInAnyOrderEntriesOf(Map.of(
            "security.event", "authz.denied",
            "subject", StubIdam.SUBJECT,
            "path", DOMAIN_REFUSAL,
            "reason", "domain_rule"));
        assertMdcCleared();
    }

    @Test
    @DisplayName("the persistence chain's 403 keeps its Bearer challenge, and is logged")
    void persistenceChainRefusal() throws Exception {
        IDAM.stubUserInfo(StubIdam.SUBJECT, "A Caseworker", List.of("caseworker"));
        HttpHeaders headers = bearer(IDAM.accessToken());
        headers.set(ServiceAuthFilter.AUTHORISATION, "Bearer " + CCD_DATA);

        mockMvc.perform(get("/ccd-persistence/cases").headers(headers))
            .andExpect(status().isForbidden())
            .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"));

        assertThat(onlyEvent().getMDCPropertyMap())
            .containsEntry("security.event", "authz.denied")
            .containsEntry("subject", StubIdam.SUBJECT)
            .containsEntry("path", "/ccd-persistence/cases");
        assertMdcCleared();
    }

    @Test
    @DisplayName("the persistence chain's 401 keeps its Bearer challenge, and is logged")
    void persistenceChainUnauthenticated() throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.set(ServiceAuthFilter.AUTHORISATION, "Bearer " + CCD_DATA);

        mockMvc.perform(get("/ccd-persistence/cases").headers(headers))
            .andExpect(status().isUnauthorized())
            .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"));

        assertThat(onlyEvent().getMDCPropertyMap())
            .containsEntry("security.event", "auth.unauthenticated")
            .containsEntry("reason", "missing_token");
        assertMdcCleared();
    }

    private ILoggingEvent onlyEvent() {
        assertThat(logs.list).hasSize(1);
        return logs.list.get(0);
    }

    private static void assertNoTokenText(ILoggingEvent event, String token) {
        assertThat(event.getFormattedMessage()).doesNotContain(token);
        event.getMDCPropertyMap().values().forEach(value -> assertThat(value).doesNotContain(token));
        // A JWT's signature alone is enough to replay nothing, but its header and claims are still the token.
        assertThat(event.getFormattedMessage()).doesNotContain(token.substring(0, token.indexOf('.')));
    }

    private static void assertMdcCleared() {
        Map<String, String> remaining = MDC.getCopyOfContextMap();
        assertThat(remaining == null ? Map.of() : remaining)
            .doesNotContainKeys("security.event", "subject", "path", "reason");
    }

    private static HttpHeaders bearer(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return headers;
    }

    /**
     * A caseworker route the domain refuses, so {@code DomainErrorHandler.forbidden} is reached over real HTTP.
     * Nested in a test class, so component scanning skips it and only this test's {@code @Import} maps it.
     */
    @RestController
    static class ForbiddenStandIn {

        @GetMapping(DOMAIN_REFUSAL)
        String refuse() {
            throw new ForbiddenError("Requires the stand-in role");
        }
    }
}
