package uk.gov.hmcts.reform.deedpoll.api.caseworker.controller;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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
import uk.gov.hmcts.reform.deedpoll.domain.auth.Role;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code /caseworker/process-settings} through the full security context and PostgreSQL.
 *
 * <p>The audit rows cannot be reset (V36 refuses the delete), so each test counts the process-settings events it
 * adds rather than the total. Concurrency and forced-failure rollbacks are proven on the service, in
 * {@code ProcessSettingsPersistenceTest}.
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CaseworkerProcessSettingsControllerTest extends AbstractPostgresContainerTest {

    private static final String PATH = "/caseworker/process-settings";
    private static final String MINUTES = "referralClaimExpiryMinutes";
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

    private int auditBefore;

    @BeforeEach
    void setUp() {
        IDAM.stubDefaults();
        IDAM.stubUserInfo(StubIdam.SUBJECT, "Delivery Manager", List.of(Role.DELIVERY_MANAGER.claimValue()));
        reseed();
        auditBefore = auditEvents();
    }

    @AfterEach
    void reseed() {
        jdbc.update("DELETE FROM deedpoll_process_settings_change");
        jdbc.update("""
            INSERT INTO deedpoll_process_settings (id, referral_claim_expiry_minutes, revision) VALUES (1, 120, 0)
            ON CONFLICT (id) DO UPDATE SET referral_claim_expiry_minutes = 120, revision = 0, updated_at = NULL,
                updated_by_id = NULL, updated_by_name = NULL
            """);
    }

    @Test
    @DisplayName("a fresh database answers 120 minutes at revision 0 with no attribution, and no token is 401")
    void freshDatabase() throws Exception {
        mvc.perform(get(PATH)).andExpect(status().isUnauthorized());
        mvc.perform(put(PATH).contentType("application/json").content("{}")).andExpect(status().isUnauthorized());
        authorised(get(PATH)).andExpect(status().isOk())
            .andExpect(jsonPath("$." + MINUTES).value(120))
            .andExpect(jsonPath("$.revision").value(0))
            .andExpect(jsonPath("$.updatedAt").isEmpty())
            .andExpect(jsonPath("$.updatedBy").isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"caseworker-deedpoll", "caseworker-deedpoll-team-leader", "caseworker-deedpoll-admin",
        "caseworker-deedpoll-judicial", "caseworker-civil"})
    @DisplayName("everyone but a Delivery Manager is refused both operations, before any body check")
    void refusesEveryoneButADeliveryManager(String role) throws Exception {
        IDAM.stubUserInfo(StubIdam.SUBJECT, "Not a Delivery Manager", List.of(role));
        authorised(get(PATH)).andExpect(status().isForbidden());
        save("{\"" + MINUTES + "\":60,\"revision\":0}").andExpect(status().isForbidden());
        save("{\"" + MINUTES + "\":29,\"forged\":true}").andExpect(status().isForbidden());
        assertUnchanged();
    }

    @Test
    @DisplayName("a Team leader who is also a Delivery Manager is admitted")
    void admitsADeliveryManagerWhateverElseTheyHold() throws Exception {
        IDAM.stubUserInfo(StubIdam.SUBJECT, "Delivery Manager",
            List.of(Role.TEAM_LEADER.claimValue(), Role.DELIVERY_MANAGER.claimValue()));
        authorised(get(PATH)).andExpect(status().isOk());
    }

    @ParameterizedTest
    @ValueSource(ints = {30, 1440})
    @DisplayName("both bounds are accepted, with attribution, one history row and one audit event with no values")
    void acceptsTheBounds(int minutes) throws Exception {
        String body = save("{\"" + MINUTES + "\":" + minutes + ",\"revision\":0}")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$." + MINUTES).value(minutes))
            .andExpect(jsonPath("$.revision").value(1))
            .andExpect(jsonPath("$.updatedBy").value("Delivery Manager"))
            .andExpect(jsonPath("$.updatedAt").isNotEmpty())
            .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain(StubIdam.SUBJECT, "updatedById");
        String reread = authorised(get(PATH)).andExpect(jsonPath("$." + MINUTES).value(minutes))
            .andExpect(jsonPath("$.revision").value(1))
            .andExpect(jsonPath("$.updatedBy").value("Delivery Manager"))
            .andExpect(jsonPath("$.updatedAt").isNotEmpty())
            .andReturn().getResponse().getContentAsString();
        assertThat(reread).doesNotContain(StubIdam.SUBJECT, "updatedById");

        assertThat(jdbc.queryForList("SELECT * FROM deedpoll_process_settings_change")).singleElement()
            .satisfies(row -> assertThat(row)
                .containsEntry("revision", 1).containsEntry("field", MINUTES)
                .containsEntry("previous_value", "120").containsEntry("new_value", String.valueOf(minutes))
                .containsEntry("changed_by_id", StubIdam.SUBJECT));
        assertThat(auditEvents() - auditBefore).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM deedpoll_audit_event_value v JOIN deedpoll_audit_event e ON e.id = v.event_id
            WHERE e.entity_type = 'ProcessSettings'
            """, Integer.class)).isZero();
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "{\"referralClaimExpiryMinutes\":29,\"revision\":0}|referralClaimExpiryMinutes",
        "{\"referralClaimExpiryMinutes\":1441,\"revision\":0}|referralClaimExpiryMinutes",
        "{\"referralClaimExpiryMinutes\":0,\"revision\":0}|referralClaimExpiryMinutes",
        "{\"referralClaimExpiryMinutes\":-5,\"revision\":0}|referralClaimExpiryMinutes",
        "{\"referralClaimExpiryMinutes\":90.5,\"revision\":0}|referralClaimExpiryMinutes",
        "{\"referralClaimExpiryMinutes\":90.0,\"revision\":0}|referralClaimExpiryMinutes",
        "{\"referralClaimExpiryMinutes\":1.2e2,\"revision\":0}|referralClaimExpiryMinutes",
        "{\"referralClaimExpiryMinutes\":2147483648,\"revision\":0}|referralClaimExpiryMinutes",
        "{\"referralClaimExpiryMinutes\":\"120\",\"revision\":0}|referralClaimExpiryMinutes",
        "{\"referralClaimExpiryMinutes\":null,\"revision\":0}|referralClaimExpiryMinutes",
        "{\"revision\":0}|referralClaimExpiryMinutes",
        "{\"referralClaimExpiryMinutes\":60,\"revision\":0,\"updatedBy\":\"forged\"}|updatedBy",
        "{\"referralClaimExpiryMinutes\":60}|revision",
        "{\"referralClaimExpiryMinutes\":60,\"revision\":\"0\"}|revision",
        "{\"referralClaimExpiryMinutes\":60,\"revision\":-1}|revision"})
    @DisplayName("each refused body is a 400 naming its field, with nothing written")
    void refusesInvalidBodies(String body, String field) throws Exception {
        save(body).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.errors[0].field").value(field));
        assertUnchanged();
    }

    @Test
    @DisplayName("the out-of-range message states the bounds")
    void namesTheBounds() throws Exception {
        save("{\"" + MINUTES + "\":29,\"revision\":0}").andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.errors[0].message").value(allOf(containsString("30"), containsString("1440"))));
    }

    @Test
    @DisplayName("a stale revision is 409 with nothing written, even carrying the value the row now holds")
    void staleRevisionIsAConflict() throws Exception {
        save("{\"" + MINUTES + "\":60,\"revision\":0}").andExpect(status().isOk());
        int afterFirst = auditEvents();

        save("{\"" + MINUTES + "\":90,\"revision\":0}").andExpect(status().isConflict());
        save("{\"" + MINUTES + "\":60,\"revision\":0}").andExpect(status().isConflict());

        authorised(get(PATH)).andExpect(jsonPath("$." + MINUTES).value(60)).andExpect(jsonPath("$.revision").value(1));
        assertThat(historyRows()).isEqualTo(1);
        assertThat(auditEvents()).isEqualTo(afterFirst);
    }

    @Test
    @DisplayName("saving the current value at the current revision is a 200 no-op")
    void unchangedValueIsANoop() throws Exception {
        save("{\"" + MINUTES + "\":120,\"revision\":0}").andExpect(status().isOk())
            .andExpect(jsonPath("$." + MINUTES).value(120))
            .andExpect(jsonPath("$.revision").value(0))
            .andExpect(jsonPath("$.updatedBy").isEmpty());
        assertUnchanged();
    }

    private void assertUnchanged() {
        assertThat(jdbc.queryForMap(
            "SELECT referral_claim_expiry_minutes, revision, updated_by_id FROM deedpoll_process_settings"))
            .containsEntry("referral_claim_expiry_minutes", 120).containsEntry("revision", 0)
            .containsEntry("updated_by_id", null);
        assertThat(historyRows()).isZero();
        assertThat(auditEvents()).isEqualTo(auditBefore);
    }

    private int historyRows() {
        return jdbc.queryForObject("SELECT count(*) FROM deedpoll_process_settings_change", Integer.class);
    }

    private int auditEvents() {
        return jdbc.queryForObject(
            "SELECT count(*) FROM deedpoll_audit_event WHERE entity_type = 'ProcessSettings'", Integer.class);
    }

    private ResultActions save(String body) throws Exception {
        return authorised(put(PATH).contentType("application/json").content(body));
    }

    private ResultActions authorised(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request.header(HttpHeaders.AUTHORIZATION, "Bearer " + IDAM.accessToken()));
    }
}
