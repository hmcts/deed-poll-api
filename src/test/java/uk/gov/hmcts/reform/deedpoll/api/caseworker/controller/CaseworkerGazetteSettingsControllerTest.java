package uk.gov.hmcts.reform.deedpoll.api.caseworker.controller;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import uk.gov.hmcts.reform.deedpoll.auth.StubIdam;
import uk.gov.hmcts.reform.deedpoll.config.AbstractPostgresContainerTest;
import uk.gov.hmcts.reform.deedpoll.domain.auth.CurrentUser;
import uk.gov.hmcts.reform.deedpoll.domain.auth.Role;
import uk.gov.hmcts.reform.deedpoll.domain.gazette.GazetteSettingsService;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Full security context and PostgreSQL, including the first-write race and change audit. */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CaseworkerGazetteSettingsControllerTest extends AbstractPostgresContainerTest {

    private static final String PATH = "/caseworker/gazette-settings";
    private static final StubIdam IDAM = new StubIdam();

    static {
        IDAM.start();
    }

    @AfterAll
    static void stopIdam() {
        IDAM.stop();
    }

    @DynamicPropertySource
    static void idamProperties(DynamicPropertyRegistry registry) {
        registry.add("idam.api.issuer-url", IDAM::issuerUrl);
        registry.add("idam.api.audiences", () -> StubIdam.AUDIENCE);
    }

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private GazetteSettingsService service;

    @BeforeEach
    void setUp() {
        IDAM.stubDefaults();
        IDAM.stubUserInfo(StubIdam.SUBJECT, "Delivery Manager", List.of(Role.DELIVERY_MANAGER.claimValue()));
        jdbc.update("DELETE FROM deedpoll_gazette_settings_change");
        jdbc.update("UPDATE deedpoll_gazette_settings SET revision = 0, recipient_email = null, "
            + "updated_at = null, updated_by_id = null, updated_by_name = null WHERE id = 1");
    }

    @Test
    void startsUnconfiguredAndRequiresAuthenticationOnBothOperations() throws Exception {
        mvc.perform(get(PATH)).andExpect(status().isUnauthorized());
        mvc.perform(put(PATH).contentType("application/json").content("{}"))
            .andExpect(status().isUnauthorized());
        authorised(get(PATH)).andExpect(status().isOk())
            .andExpect(jsonPath("$.revision").value(0))
            .andExpect(jsonPath("$.recipientEmail").isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"caseworker-civil", "caseworker-deedpoll", "caseworker-deedpoll-judicial"})
    void refusesBothOperationsForNonDeliveryManagers(String role) throws Exception {
        IDAM.stubUserInfo(StubIdam.SUBJECT, "Delivery Manager", List.of(role));
        authorised(get(PATH)).andExpect(status().isForbidden());
        save("{\"recipientEmail\":\"gazette@example.com\",\"revision\":0}").andExpect(status().isForbidden());
        assertThat(auditCount()).isZero();
    }

    @Test
    void persistsChangesWithVerifiedAttributionAndDoesNotPublishSubjects() throws Exception {
        String body = save("{\"recipientEmail\":\"  gazette@example.com  \",\"revision\":0}")
            .andExpect(status().isOk()).andExpect(jsonPath("$.recipientEmail").value("gazette@example.com"))
            .andExpect(jsonPath("$.revision").value(1)).andExpect(jsonPath("$.updatedBy").value("Delivery Manager"))
            .andExpect(jsonPath("$.updatedAt").isNotEmpty()).andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain(StubIdam.SUBJECT, "updatedById");
        authorised(get(PATH)).andExpect(jsonPath("$.recipientEmail").value("gazette@example.com"));
        IDAM.stubUserInfo(StubIdam.SUBJECT, "Renamed Delivery Manager", List.of(Role.DELIVERY_MANAGER.claimValue()));
        save("{\"recipientEmail\":\"new@example.com\",\"revision\":1}")
            .andExpect(status().isOk()).andExpect(jsonPath("$.revision").value(2));
        assertThat(jdbc.queryForList("SELECT previous_email, recipient_email, changed_by_id "
            + "FROM deedpoll_gazette_settings_change ORDER BY revision")).hasSize(2);
        assertThat(jdbc.queryForMap("SELECT previous_email, recipient_email, changed_by_id "
            + "FROM deedpoll_gazette_settings_change WHERE revision = 2"))
            .containsEntry("previous_email", "gazette@example.com")
            .containsEntry("recipient_email", "new@example.com").containsEntry("changed_by_id", StubIdam.SUBJECT);
    }

    /*
      Release review F1, accepted 14 October 2026: with no name claims, IdamClaims' service-wide fallback makes the
      subject the attribution, so it is published here. Pinned so a change to either side is a decision, not a drift.
    */
    @Test
    void publishesTheSubjectAsAttributionWhenIdamSuppliesNoName() throws Exception {
        IDAM.stubUserInfoBody("""
            {"sub": "%s", "roles": ["%s"]}
            """.formatted(StubIdam.SUBJECT, Role.DELIVERY_MANAGER.claimValue()));

        save("{\"recipientEmail\":\"gazette@example.com\",\"revision\":0}")
            .andExpect(status().isOk()).andExpect(jsonPath("$.updatedBy").value(StubIdam.SUBJECT));
    }

    @Test
    void unchangedSaveIsANoopAndStaleSaveCannotOverwrite() throws Exception {
        String body = "{\"recipientEmail\":\"gazette@example.com\",\"revision\":0}";
        save(body).andExpect(status().isOk());
        save(body).andExpect(status().isConflict());
        save("{\"recipientEmail\":\"gazette@example.com\",\"revision\":1}")
            .andExpect(status().isOk()).andExpect(jsonPath("$.revision").value(1));
        save("{\"recipientEmail\":\"wrong@example.com\",\"revision\":0}").andExpect(status().isConflict());
        authorised(get(PATH)).andExpect(jsonPath("$.recipientEmail").value("gazette@example.com"));
        assertThat(auditCount()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"recipientEmail\":null,\"revision\":0}",
        "{\"recipientEmail\":123,\"revision\":0}", "{\"recipientEmail\":\"\",\"revision\":0}",
        "{\"recipientEmail\":\"a@example.com,b@example.com\",\"revision\":0}",
        "{\"recipientEmail\":\"Name <a@example.com>\",\"revision\":0}",
        "{\"recipientEmail\":\"a..b@example.com\",\"revision\":0}",
        "{\"recipientEmail\":\"a@-example.com\",\"revision\":0}",
        "{\"recipientEmail\":\"a@example.com\",\"revision\":\"0\"}",
        "{\"recipientEmail\":\"a@example.com\",\"revision\":0.1}",
        "{\"recipientEmail\":\"a@example.com\",\"revision\":-1}",
        "{\"recipientEmail\":\"a@example.com\",\"revision\":0,\"updatedBy\":\"forged\"}"})
    void invalidInputsDoNotChangeTheSettingOrAudit(String body) throws Exception {
        save(body).andExpect(status().isBadRequest());
        assertThat(auditCount()).isZero();
        authorised(get(PATH)).andExpect(jsonPath("$.revision").value(0));
    }

    @Test
    void rejectsOversizedAddressAndHeaderInjection() throws Exception {
        save("{\"recipientEmail\":\"" + "a".repeat(65) + "@example.com\",\"revision\":0}")
            .andExpect(status().isBadRequest());
        save("{\"recipientEmail\":\"a@example.com\\r\\nBcc: other@example.com\",\"revision\":0}")
            .andExpect(status().isBadRequest());
        assertThat(auditCount()).isZero();
    }

    @Test
    void concurrentFirstSavesHaveOnlyOneWinnerAndOneAuditRow() throws Exception {
        CurrentUser deliveryManager =
            new CurrentUser(List.of(Role.DELIVERY_MANAGER), true, "Delivery Manager", "subject");
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var attempts = List.of("one@example.com", "two@example.com").stream().map(email -> executor.submit(() -> {
                start.await();
                try {
                    service.update(deliveryManager, Map.of("recipientEmail", email, "revision", 0));
                    return "saved";
                } catch (uk.gov.hmcts.reform.deedpoll.domain.ConflictError expected) {
                    return "conflict";
                }
            })).toList();
            start.countDown();
            assertThat(List.of(attempts.get(0).get(10, TimeUnit.SECONDS), attempts.get(1).get(10, TimeUnit.SECONDS)))
                .containsExactlyInAnyOrder("saved", "conflict");
        }
        assertThat(auditCount()).isEqualTo(1);
    }

    @Test
    void failedAuditWriteLeavesTheSettingUnchanged() {
        CurrentUser deliveryManager =
            new CurrentUser(List.of(Role.DELIVERY_MANAGER), true, "a".repeat(300), "subject");
        assertThatThrownBy(() -> service.update(
            deliveryManager, Map.of("recipientEmail", "a@example.com", "revision", 0)))
            .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(jdbc.queryForObject("SELECT revision FROM deedpoll_gazette_settings", Integer.class)).isZero();
        assertThat(auditCount()).isZero();
    }

    private int auditCount() {
        return jdbc.queryForObject("SELECT count(*) FROM deedpoll_gazette_settings_change", Integer.class);
    }

    private ResultActions save(String body) throws Exception {
        return authorised(put(PATH).contentType("application/json").content(body));
    }

    private ResultActions authorised(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request.header(HttpHeaders.AUTHORIZATION, "Bearer " + IDAM.accessToken()));
    }
}
