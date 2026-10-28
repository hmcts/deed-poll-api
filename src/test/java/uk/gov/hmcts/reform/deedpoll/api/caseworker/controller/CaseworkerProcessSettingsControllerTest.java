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
    private static final String ALL_SEEDED = """
        {"referralClaimExpiryMinutes":10080,"reminderIntervalHours":48,"masterWindowCap":10,
         "morningWindowStart":"07:00","morningWindowEnd":"10:30","afternoonWindowStart":"14:00",
         "afternoonWindowEnd":"14:30","revision":0}
        """;
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
            INSERT INTO deedpoll_process_settings (id, referral_claim_expiry_minutes, revision) VALUES (1, 10080, 0)
            ON CONFLICT (id) DO UPDATE SET referral_claim_expiry_minutes = 10080, reminder_interval_hours = 48,
                master_window_cap = 10, morning_window_start = '07:00', morning_window_end = '10:30',
                afternoon_window_start = '14:00', afternoon_window_end = '14:30', revision = 0, updated_at = NULL,
                updated_by_id = NULL, updated_by_name = NULL
            """);
    }

    @Test
    @DisplayName("a fresh database answers the seeded settings at revision 0 with no attribution; no token is 401")
    void freshDatabase() throws Exception {
        mvc.perform(get(PATH)).andExpect(status().isUnauthorized());
        mvc.perform(put(PATH).contentType("application/json").content("{}")).andExpect(status().isUnauthorized());
        authorised(get(PATH)).andExpect(status().isOk())
            .andExpect(jsonPath("$." + MINUTES).value(10080))
            .andExpect(jsonPath("$.reminderIntervalHours").value(48))
            .andExpect(jsonPath("$.masterWindowCap").value(10))
            .andExpect(jsonPath("$.morningWindowStart").value("07:00"))
            .andExpect(jsonPath("$.morningWindowEnd").value("10:30"))
            .andExpect(jsonPath("$.afternoonWindowStart").value("14:00"))
            .andExpect(jsonPath("$.afternoonWindowEnd").value("14:30"))
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
        save("{\"" + MINUTES + "\":2880,\"revision\":0}").andExpect(status().isForbidden());
        save("{\"" + MINUTES + "\":1439,\"forged\":true}").andExpect(status().isForbidden());
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
    @ValueSource(ints = {1440, 43200})
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
                .containsEntry("previous_value", "10080").containsEntry("new_value", String.valueOf(minutes))
                .containsEntry("changed_by_id", StubIdam.SUBJECT));
        assertThat(auditEvents() - auditBefore).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM deedpoll_audit_event_value v JOIN deedpoll_audit_event e ON e.id = v.event_id
            WHERE e.entity_type = 'ProcessSettings'
            """, Integer.class)).isZero();
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "{\"referralClaimExpiryMinutes\":1439,\"revision\":0}|referralClaimExpiryMinutes",
        "{\"referralClaimExpiryMinutes\":43201,\"revision\":0}|referralClaimExpiryMinutes",
        "{\"referralClaimExpiryMinutes\":120,\"revision\":0}|referralClaimExpiryMinutes",
        "{\"referralClaimExpiryMinutes\":0,\"revision\":0}|referralClaimExpiryMinutes",
        "{\"referralClaimExpiryMinutes\":-5,\"revision\":0}|referralClaimExpiryMinutes",
        "{\"referralClaimExpiryMinutes\":90.5,\"revision\":0}|referralClaimExpiryMinutes",
        "{\"referralClaimExpiryMinutes\":90.0,\"revision\":0}|referralClaimExpiryMinutes",
        "{\"referralClaimExpiryMinutes\":1.2e2,\"revision\":0}|referralClaimExpiryMinutes",
        "{\"referralClaimExpiryMinutes\":2147483648,\"revision\":0}|referralClaimExpiryMinutes",
        "{\"referralClaimExpiryMinutes\":\"120\",\"revision\":0}|referralClaimExpiryMinutes",
        "{\"referralClaimExpiryMinutes\":null,\"revision\":0}|referralClaimExpiryMinutes",
        "{\"revision\":0}|referralClaimExpiryMinutes",
        "{\"referralClaimExpiryMinutes\":2880,\"revision\":0,\"updatedBy\":\"forged\"}|updatedBy",
        "{\"referralClaimExpiryMinutes\":2880}|revision",
        "{\"referralClaimExpiryMinutes\":2880,\"revision\":\"0\"}|revision",
        "{\"referralClaimExpiryMinutes\":2880,\"revision\":-1}|revision",
        "{\"referralClaimExpiryMinutes\":10080,\"reminderIntervalHours\":0,\"revision\":0}|reminderIntervalHours",
        "{\"referralClaimExpiryMinutes\":10080,\"reminderIntervalHours\":169,\"revision\":0}|reminderIntervalHours",
        "{\"referralClaimExpiryMinutes\":10080,\"reminderIntervalHours\":null,\"revision\":0}|reminderIntervalHours",
        "{\"referralClaimExpiryMinutes\":10080,\"reminderIntervalHours\":\"48\",\"revision\":0}|reminderIntervalHours",
        "{\"referralClaimExpiryMinutes\":10080,\"masterWindowCap\":0,\"revision\":0}|masterWindowCap",
        "{\"referralClaimExpiryMinutes\":10080,\"masterWindowCap\":101,\"revision\":0}|masterWindowCap",
        "{\"referralClaimExpiryMinutes\":10080,\"masterWindowCap\":1.5,\"revision\":0}|masterWindowCap",
        "{\"referralClaimExpiryMinutes\":10080,\"morningWindowStart\":\"07:10\",\"revision\":0}|morningWindowStart",
        "{\"referralClaimExpiryMinutes\":10080,\"morningWindowStart\":\"7:00\",\"revision\":0}|morningWindowStart",
        "{\"referralClaimExpiryMinutes\":10080,\"morningWindowEnd\":\"24:00\",\"revision\":0}|morningWindowEnd",
        "{\"referralClaimExpiryMinutes\":10080,\"afternoonWindowStart\":null,\"revision\":0}|afternoonWindowStart",
        "{\"referralClaimExpiryMinutes\":10080,\"afternoonWindowEnd\":1430,\"revision\":0}|afternoonWindowEnd",
        "{\"referralClaimExpiryMinutes\":10080,\"morningWindowEnd\":\"07:00\",\"revision\":0}|morningWindowEnd",
        "{\"referralClaimExpiryMinutes\":10080,\"morningWindowEnd\":\"14:00\",\"revision\":0}|afternoonWindowStart",
        "{\"referralClaimExpiryMinutes\":10080,\"afternoonWindowEnd\":\"13:45\",\"revision\":0}|afternoonWindowEnd"})
    @DisplayName("each refused body is a 400 naming its field, with nothing written")
    void refusesInvalidBodies(String body, String field) throws Exception {
        save(body).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.errors[0].field").value(field));
        assertUnchanged();
    }

    @Test
    @DisplayName("the out-of-range message states the bounds")
    void namesTheBounds() throws Exception {
        save("{\"" + MINUTES + "\":1439,\"revision\":0}").andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.errors[0].message").value(allOf(containsString("1440"), containsString("43200"))));
        save("{\"" + MINUTES + "\":10080,\"reminderIntervalHours\":169,\"revision\":0}")
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.errors[0].message").value(allOf(containsString("1"), containsString("168"))));
        save("{\"" + MINUTES + "\":10080,\"masterWindowCap\":101,\"revision\":0}")
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.errors[0].message").value(allOf(containsString("1"), containsString("100"))));
    }

    @Test
    @DisplayName("a stale revision is 409 with nothing written, even carrying the value the row now holds")
    void staleRevisionIsAConflict() throws Exception {
        save("{\"" + MINUTES + "\":2880,\"revision\":0}").andExpect(status().isOk());
        int afterFirst = auditEvents();

        save("{\"" + MINUTES + "\":4320,\"revision\":0}").andExpect(status().isConflict());
        save("{\"" + MINUTES + "\":2880,\"revision\":0}").andExpect(status().isConflict());
        save("{\"" + MINUTES + "\":2880,\"masterWindowCap\":10,\"revision\":0}").andExpect(status().isConflict());

        authorised(get(PATH)).andExpect(jsonPath("$." + MINUTES).value(2880))
            .andExpect(jsonPath("$.revision").value(1));
        assertThat(historyRows()).isEqualTo(1);
        assertThat(auditEvents()).isEqualTo(afterFirst);
    }

    @Test
    @DisplayName("saving the current value at the current revision is a 200 no-op")
    void unchangedValueIsANoop() throws Exception {
        save("{\"" + MINUTES + "\":10080,\"revision\":0}").andExpect(status().isOk())
            .andExpect(jsonPath("$." + MINUTES).value(10080))
            .andExpect(jsonPath("$.revision").value(0))
            .andExpect(jsonPath("$.updatedBy").isEmpty());
        save(ALL_SEEDED).andExpect(status().isOk()).andExpect(jsonPath("$.revision").value(0));
        assertUnchanged();
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "reminderIntervalHours|1|1", "reminderIntervalHours|168|168",
        "masterWindowCap|1|1", "masterWindowCap|100|100",
        "morningWindowStart|\"00:00\"|00:00", "morningWindowEnd|\"13:45\"|13:45",
        "afternoonWindowStart|\"10:45\"|10:45", "afternoonWindowEnd|\"23:45\"|23:45"})
    @DisplayName("each new setting is accepted at its bounds, alone beside the two required keys")
    void acceptsTheNewBounds(String field, String json, String stored) throws Exception {
        save("{\"" + MINUTES + "\":10080,\"" + field + "\":" + json + ",\"revision\":0}")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$." + field).value(field.endsWith("Hours") || field.endsWith("Cap")
                ? (Object) Integer.valueOf(stored) : stored))
            .andExpect(jsonPath("$.revision").value(1));
        assertThat(jdbc.queryForList("SELECT field, new_value FROM deedpoll_process_settings_change"))
            .singleElement().satisfies(row -> assertThat(row)
                .containsEntry("field", field).containsEntry("new_value", stored));
    }

    @Test
    @DisplayName("the morning window may end a quarter hour before the afternoon one starts, but not at its start")
    void windowsMayNotTouch() throws Exception {
        save("{\"" + MINUTES + "\":10080,\"morningWindowEnd\":\"14:00\",\"revision\":0}")
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.errors[0].field").value("afternoonWindowStart"));
        assertUnchanged();
        save("{\"" + MINUTES + "\":10080,\"morningWindowEnd\":\"13:45\",\"revision\":0}")
            .andExpect(status().isOk());
    }

    @Test
    @DisplayName("today's admin body, the two old keys, changes the window and leaves every new setting alone")
    void oldShapeBody() throws Exception {
        save("{\"" + MINUTES + "\":10080,\"masterWindowCap\":20,\"morningWindowStart\":\"08:00\",\"revision\":0}")
            .andExpect(status().isOk());

        save("{\"" + MINUTES + "\":2880,\"revision\":1}").andExpect(status().isOk())
            .andExpect(jsonPath("$." + MINUTES).value(2880))
            .andExpect(jsonPath("$.reminderIntervalHours").value(48))
            .andExpect(jsonPath("$.masterWindowCap").value(20))
            .andExpect(jsonPath("$.morningWindowStart").value("08:00"))
            .andExpect(jsonPath("$.morningWindowEnd").value("10:30"))
            .andExpect(jsonPath("$.afternoonWindowStart").value("14:00"))
            .andExpect(jsonPath("$.afternoonWindowEnd").value("14:30"))
            .andExpect(jsonPath("$.revision").value(2));
        assertThat(jdbc.queryForList("SELECT field FROM deedpoll_process_settings_change WHERE revision = 2"))
            .extracting(row -> row.get("field")).containsExactly(MINUTES);
    }

    @Test
    @DisplayName("one PUT changing every setting writes a history row each and one audit event naming them all")
    void everySettingAtOnce() throws Exception {
        save("""
            {"referralClaimExpiryMinutes":4320,"reminderIntervalHours":24,"masterWindowCap":5,
             "morningWindowStart":"08:00","morningWindowEnd":"11:00","afternoonWindowStart":"13:00",
             "afternoonWindowEnd":"15:15","revision":0}
            """).andExpect(status().isOk()).andExpect(jsonPath("$.revision").value(1));

        assertThat(jdbc.queryForList(
            "SELECT field, previous_value, new_value FROM deedpoll_process_settings_change ORDER BY field"))
            .extracting(row -> row.get("field") + "=" + row.get("previous_value") + ">" + row.get("new_value"))
            .containsExactly("afternoonWindowEnd=14:30>15:15", "afternoonWindowStart=14:00>13:00",
                             "masterWindowCap=10>5", "morningWindowEnd=10:30>11:00", "morningWindowStart=07:00>08:00",
                             "referralClaimExpiryMinutes=10080>4320", "reminderIntervalHours=48>24");
        assertThat(auditEvents() - auditBefore).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
            SELECT array_to_string(fields, ',') FROM deedpoll_audit_event
            WHERE entity_type = 'ProcessSettings' ORDER BY id DESC LIMIT 1
            """, String.class)).isEqualTo("referralClaimExpiryMinutes,reminderIntervalHours,masterWindowCap,"
                + "morningWindowStart,morningWindowEnd,afternoonWindowStart,afternoonWindowEnd");
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM deedpoll_audit_event_value v JOIN deedpoll_audit_event e ON e.id = v.event_id
            WHERE e.entity_type = 'ProcessSettings'
            """, Integer.class)).isZero();
    }

    private void assertUnchanged() {
        assertThat(jdbc.queryForMap(
            "SELECT referral_claim_expiry_minutes, revision, updated_by_id FROM deedpoll_process_settings"))
            .containsEntry("referral_claim_expiry_minutes", 10080).containsEntry("revision", 0)
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
