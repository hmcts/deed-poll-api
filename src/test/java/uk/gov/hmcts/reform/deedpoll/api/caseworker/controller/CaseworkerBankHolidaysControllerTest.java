package uk.gov.hmcts.reform.deedpoll.api.caseworker.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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
import uk.gov.hmcts.reform.deedpoll.domain.auth.Role;
import uk.gov.hmcts.reform.deedpoll.domain.settings.BankHolidays;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code /caseworker/process-settings/bank-holidays}, through the full security context and PostgreSQL.
 *
 * <p>Tests add and remove only 2029 dates, outside V42's seed, and put back any seeded date they remove. The audit
 * rows cannot be deleted (V36), so each test counts the events on its own dates.
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CaseworkerBankHolidaysControllerTest extends AbstractPostgresContainerTest {

    private static final String PATH = "/caseworker/process-settings/bank-holidays";
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
    private ObjectMapper json;
    @Autowired
    private BankHolidays holidays;

    @BeforeEach
    void setUp() {
        IDAM.stubDefaults();
        asStaff(Role.DELIVERY_MANAGER.claimValue());
        clearAdded();
    }

    @AfterEach
    void tearDown() {
        clearAdded();
    }

    @Test
    @DisplayName("no token is 401 on every operation")
    void unauthenticated() throws Exception {
        mvc.perform(get(PATH)).andExpect(status().isUnauthorized());
        mvc.perform(post(PATH).contentType("application/json").content("{}")).andExpect(status().isUnauthorized());
        mvc.perform(delete(PATH + "/2029-01-01")).andExpect(status().isUnauthorized());
    }

    @ParameterizedTest
    @ValueSource(strings = {"caseworker-deedpoll", "caseworker-deedpoll-team-leader", "caseworker-deedpoll-admin",
        "caseworker-deedpoll-delivery-manager"})
    @DisplayName("every staff role reads the seeded list, earliest first")
    void staffRead(String role) throws Exception {
        asStaff(role);

        JsonNode list = json.readTree(authorised(get(PATH)).andExpect(status().isOk()).andReturn().getResponse()
            .getContentAsString());
        assertThat(list).hasSize(24);
        assertThat(list.get(0).get("date").asText()).isEqualTo("2026-01-01");
        assertThat(list.get(0).get("name").asText()).isEqualTo("New Year's Day");
        assertThat(list.get(23).get("date").asText()).isEqualTo("2028-12-26");
        assertThat(list.get(0).fieldNames()).toIterable().containsExactlyInAnyOrder("date", "name");
    }

    @ParameterizedTest
    @ValueSource(strings = {"caseworker-deedpoll-judicial", "caseworker-civil"})
    @DisplayName("a judge and a caller with no deed poll role are refused the list")
    void nonStaffRefusedTheList(String role) throws Exception {
        asStaff(role);

        authorised(get(PATH)).andExpect(status().isForbidden());
    }

    @ParameterizedTest
    @ValueSource(strings = {"caseworker-deedpoll", "caseworker-deedpoll-team-leader", "caseworker-deedpoll-admin",
        "caseworker-deedpoll-judicial", "caseworker-civil"})
    @DisplayName("everyone but a Delivery Manager is refused both writes, before any body check")
    void writesRefused(String role) throws Exception {
        asStaff(role);

        send(post(PATH), "{\"date\":\"2029-01-01\",\"name\":\"New Year's Day\"}").andExpect(status().isForbidden());
        send(post(PATH), "{\"forged\":true}").andExpect(status().isForbidden());
        authorised(delete(PATH + "/2026-12-25")).andExpect(status().isForbidden());
        authorised(delete(PATH + "/not-a-date")).andExpect(status().isForbidden());
        assertThat(count()).isEqualTo(24);
    }

    @Test
    @DisplayName("a Delivery Manager adds a date, which stops being a working day, with names-only audit")
    void adds() throws Exception {
        LocalDate monday = LocalDate.of(2029, 1, 1);
        assertThat(holidays.isWorkingDay(monday)).isTrue();

        send(post(PATH), "{\"date\":\"2029-01-01\",\"name\":\" New Year's Day \"}").andExpect(status().isCreated())
            .andExpect(jsonPath("$.date").value("2029-01-01")).andExpect(jsonPath("$.name").value("New Year's Day"));

        assertThat(holidays.isWorkingDay(monday)).isFalse();
        assertThat(jdbc.queryForObject(
            "SELECT created_by_id FROM deedpoll_bank_holiday WHERE holiday_date = '2029-01-01'", String.class))
            .isEqualTo(StubIdam.SUBJECT);
        authorised(get(PATH)).andExpect(jsonPath("$[24].date").value("2029-01-01"));
        assertThat(jdbc.queryForObject("""
            SELECT array_to_string(fields, ',') FROM deedpoll_audit_event
            WHERE entity_type = 'BankHoliday' AND entity_id = '2029-01-01' AND action = 'BANK_HOLIDAY_ADDED'
            ORDER BY occurred_at DESC LIMIT 1
            """, String.class)).isEqualTo("date,name");
    }

    @Test
    @DisplayName("adding a listed date is 409 on the date and changes nothing; a bad body is 400")
    void refusals() throws Exception {
        send(post(PATH), "{\"date\":\"2026-12-25\",\"name\":\"Christmas again\"}").andExpect(status().isConflict())
            .andExpect(jsonPath("$.errors[0].field").value("date"));
        assertThat(jdbc.queryForObject(
            "SELECT name FROM deedpoll_bank_holiday WHERE holiday_date = '2026-12-25'", String.class))
            .isEqualTo("Christmas Day");
        send(post(PATH), "{\"date\":\"2029-02-30\",\"name\":\"Nope\"}").andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.errors[0].field").value("date"));
        send(post(PATH), "{\"date\":\"2029-01-01\",\"name\":\"\"}").andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.errors[0].field").value("name"));
        assertThat(count()).isEqualTo(24);
    }

    @Test
    @DisplayName("removing a seeded date makes it a working day again; removing it twice is 404")
    void removes() throws Exception {
        LocalDate christmas = LocalDate.of(2026, 12, 25);
        assertThat(holidays.isWorkingDay(christmas)).isFalse();
        try {
            authorised(delete(PATH + "/2026-12-25")).andExpect(status().isNoContent());

            assertThat(holidays.isWorkingDay(christmas)).isTrue();
            authorised(delete(PATH + "/2026-12-25")).andExpect(status().isNotFound());
            authorised(delete(PATH + "/not-a-date")).andExpect(status().isNotFound());
            assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM deedpoll_audit_event
                WHERE entity_type = 'BankHoliday' AND entity_id = '2026-12-25' AND action = 'BANK_HOLIDAY_REMOVED'
                """, Integer.class)).isPositive();
        } finally {
            jdbc.update("""
                INSERT INTO deedpoll_bank_holiday (holiday_date, name, created_at, created_by_id, created_by_name)
                VALUES ('2026-12-25', 'Christmas Day', now(), '# svc_HMCTS_LCPT_Dev', '# svc_HMCTS_LCPT_Dev')
                ON CONFLICT DO NOTHING
                """);
        }
    }

    @Test
    @DisplayName("a Saturday, a weekday and a seeded substitute day, against the real table")
    void workingDays() {
        assertThat(holidays.isWorkingDay(LocalDate.of(2026, 12, 26))).isFalse();
        assertThat(holidays.isWorkingDay(LocalDate.of(2026, 12, 28))).isFalse();
        assertThat(holidays.isWorkingDay(LocalDate.of(2026, 12, 29))).isTrue();
    }

    private void clearAdded() {
        jdbc.update("DELETE FROM deedpoll_bank_holiday WHERE holiday_date >= '2029-01-01'");
    }

    private int count() {
        return jdbc.queryForObject("SELECT count(*) FROM deedpoll_bank_holiday", Integer.class);
    }

    private void asStaff(String role) {
        IDAM.stubUserInfo(StubIdam.SUBJECT, "Staff", List.of(role));
    }

    private ResultActions send(MockHttpServletRequestBuilder request, String body) throws Exception {
        return authorised(request.contentType("application/json").content(body));
    }

    private ResultActions authorised(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request.header(HttpHeaders.AUTHORIZATION, "Bearer " + IDAM.accessToken()));
    }
}
