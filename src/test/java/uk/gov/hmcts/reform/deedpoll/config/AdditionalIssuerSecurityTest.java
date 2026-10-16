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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import uk.gov.hmcts.reform.authorisation.filters.ServiceAuthFilter;
import uk.gov.hmcts.reform.deedpoll.auth.StubIdam;
import uk.gov.hmcts.reform.deedpoll.auth.StubS2s;
import uk.gov.hmcts.reform.deedpoll.domain.auth.Role;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * IDAM's ForgeRock issuer, configured as an additional issuer, through both filter chains (additional-issuers plan,
 * Task 2).
 *
 * <p>The AAT smoke found every password-grant token naming ForgeRock's internal address as {@code iss}, signed by the
 * key set discovered from the main issuer. Configuring the name admits those tokens; it admits nothing signed by any
 * other key, addressed to any other audience, or naming any other issuer. The unconfigured refusal is pinned in
 * {@link SecurityEventLoggingTest}, which needs no database.
 *
 * <p>Boots the whole context, for the reason {@link SecurityConfiguration}'s Javadoc gives, against Postgres so that
 * {@code GET /caseworker/document-types} answers with a real body to compare.
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AdditionalIssuerSecurityTest extends AbstractPostgresContainerTest {

    /**
     * The issuer the AAT smoke measured on 2026-10-16, from {@code idam-api} and {@code hmcts-access} alike.
     */
    static final String FORGEROCK =
        "https://forgerock-am.service.core-compute-idam-aat2.internal:8443/openam/oauth2/realms/root/realms/hmcts";

    private static final String STAFF_ONLY = "/caseworker/document-types";

    private static final String SECURITY_EVENTS = "uk.gov.hmcts.reform.deedpoll.auth.SecurityEvents";

    private static final String SYSTEM_ROLE = "deed-poll-system-under-test";

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
        registry.add("idam.api.additional-issuers", () -> FORGEROCK);
        registry.add("idam.s2s-auth.url", S2S::baseUrl);
        registry.add("deedpoll.ccd.system-role", () -> SYSTEM_ROLE);
    }

    @Autowired
    private MockMvc mockMvc;

    private ListAppender<ILoggingEvent> logs;
    private Logger logger;

    @BeforeEach
    void setUp() {
        IDAM.stubDefaults();
        IDAM.stubUserInfo(StubIdam.SUBJECT, "A Caseworker", List.of(Role.CASEWORKER.claimValue()));
        S2S.reset();
        S2S.token(CCD_DATA, "ccd_data");
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
    @DisplayName("a ForgeRock-issuer token is admitted, and answered exactly as a main-issuer token is")
    void admitsTheForgeRockIssuer() throws Exception {
        String main = mockMvc.perform(get(STAFF_ONLY).headers(bearer(IDAM.accessToken())))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();

        String forgeRock = mockMvc.perform(get(STAFF_ONLY).headers(bearer(issuedBy(FORGEROCK))))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();

        assertThat(forgeRock).isNotBlank().isEqualTo(main);
        assertThat(logs.list).isEmpty();
    }

    @Test
    @DisplayName("another unlisted issuer is still refused with 401 invalid_token, and the event is emitted")
    void refusesAnotherIssuer() throws Exception {
        MvcResult result = mockMvc.perform(get(STAFF_ONLY).headers(bearer(issuedBy("https://idam.example.net/o"))))
            .andExpect(status().isUnauthorized())
            .andReturn();

        assertThat(result.getResponse().getHeader(HttpHeaders.WWW_AUTHENTICATE))
            .startsWith("Bearer error=\"invalid_token\"")
            .contains("The iss claim is not valid");
        assertUnauthenticatedEvent(STAFF_ONLY);
    }

    @Test
    @DisplayName("a ForgeRock-issuer token signed by a key outside the discovered set is refused")
    void refusesAnotherKey() throws Exception {
        String token = IDAM.tokenSignedByAnotherKey(claims -> claims.issuer(FORGEROCK));

        mockMvc.perform(get(STAFF_ONLY).headers(bearer(token))).andExpect(status().isUnauthorized());
        assertUnauthenticatedEvent(STAFF_ONLY);
    }

    @Test
    @DisplayName("a ForgeRock-issuer token for another audience is refused")
    void refusesAnotherAudience() throws Exception {
        String token = IDAM.accessToken(claims -> claims.issuer(FORGEROCK).audience("another_service"));

        mockMvc.perform(get(STAFF_ONLY).headers(bearer(token))).andExpect(status().isUnauthorized());
        assertUnauthenticatedEvent(STAFF_ONLY);
    }

    @Test
    @DisplayName("/ccd-persistence: data-store carrying a ForgeRock-issuer system user gets past authentication")
    void admitsTheForgeRockIssuerOnThePersistenceChain() throws Exception {
        IDAM.stubUserInfo(StubIdam.SUBJECT, "Deed Poll System", List.of("caseworker", SYSTEM_ROLE));

        // A 400 for the missing Idempotency-Key is the SDK's controller answering: no filter in the chain produces it.
        mockMvc.perform(persistenceSubmission(issuedBy(FORGEROCK)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.errors[0].field").value("Idempotency-Key"));

        // The same request with an unlisted issuer is the 401 the decoder gives, so the pass above was the issuer.
        mockMvc.perform(persistenceSubmission(issuedBy("https://idam.example.net/o")))
            .andExpect(status().isUnauthorized());
        assertUnauthenticatedEvent("/ccd-persistence/cases");
    }

    private void assertUnauthenticatedEvent(String path) {
        assertThat(logs.list).hasSize(1);
        assertThat(logs.list.get(0).getMDCPropertyMap())
            .containsEntry("security.event", "auth.unauthenticated")
            .containsEntry("path", path)
            .containsEntry("reason", "invalid_token")
            .doesNotContainKey("subject");
    }

    private static MockHttpServletRequestBuilder persistenceSubmission(String userToken) {
        HttpHeaders headers = bearer(userToken);
        headers.set(ServiceAuthFilter.AUTHORISATION, "Bearer " + CCD_DATA);
        return post("/ccd-persistence/cases").headers(headers).contentType(MediaType.APPLICATION_JSON).content("{}");
    }

    private static String issuedBy(String issuer) {
        return IDAM.accessToken(claims -> claims.issuer(issuer));
    }

    private static HttpHeaders bearer(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return headers;
    }
}
