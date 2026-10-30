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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import uk.gov.hmcts.reform.deedpoll.auth.StubIdam;
import uk.gov.hmcts.reform.deedpoll.config.AbstractPostgresContainerTest;
import uk.gov.hmcts.reform.deedpoll.domain.deedpoll.DeedPollPatch;
import uk.gov.hmcts.reform.deedpoll.domain.deedpoll.DeedPollService;
import uk.gov.hmcts.reform.deedpoll.domain.referrals.ReferralWindow;
import uk.gov.hmcts.reform.deedpoll.domain.referrals.ReferralWindows;
import uk.gov.hmcts.reform.deedpoll.domain.settings.BankHolidays;
import uk.gov.hmcts.reform.deedpoll.domain.settings.ProcessSettingsService;
import uk.gov.hmcts.reform.deedpoll.entity.AuditClock;
import uk.gov.hmcts.reform.deedpoll.entity.enums.WindowSlot;
import uk.gov.hmcts.reform.deedpoll.repository.DeedPollNoteRepository;
import uk.gov.hmcts.reform.deedpoll.repository.DeedPollRepository;
import uk.gov.hmcts.reform.deedpoll.repository.ReferenceCounterRepository;
import uk.gov.hmcts.reform.deedpoll.repository.ReferralRepository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code GET /caseworker/referral-queue} over HTTP (plan F1), against a real database and a stub IDAM: the
 * {@code ALLOCATE_REFERRALS} gate before any read, the 400 on {@code state}, both lists' rows, the envelope against
 * the clock, no subject or email in the JSON, and the document. The orders, paging and the envelope at chosen
 * moments are {@code ReferralQueueTest}'s.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CaseworkerReferralQueueControllerTest extends AbstractPostgresContainerTest {

    private static final String PATH = "/caseworker/referral-queue";
    private static final String RECEIVED = "2026-07-01T00:00:00.000Z";
    private static final String SUBJECT = "sullivan-idam-subject";
    private static final String SULLIVAN_EMAIL = "master.sullivan@judiciary.example";
    private static final String AWAY_EMAIL = "master.away@judiciary.example";

    private static final ObjectMapper JSON = new ObjectMapper();
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
    private MockMvc mockMvc;
    @Autowired
    private DeedPollService deedPollService;
    @Autowired
    private DeedPollRepository deedPolls;
    @Autowired
    private ReferralRepository referrals;
    @Autowired
    private DeedPollNoteRepository notes;
    @Autowired
    private ReferenceCounterRepository referenceCounters;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ProcessSettingsService settings;
    @Autowired
    private BankHolidays bankHolidays;

    private UUID sullivan;
    private String currentRole;

    @BeforeEach
    void setUp() {
        clearTables();
        IDAM.stubDefaults();
        asStaff("caseworker-deedpoll-team-leader");
        sullivan = master("Master Sullivan", SULLIVAN_EMAIL, true, SUBJECT);
        master("Master Away", AWAY_EMAIL, false, null);
    }

    @AfterEach
    void tearDown() {
        clearTables();
    }

    private void clearTables() {
        referrals.deleteAll();
        notes.deleteAll();
        deedPolls.deleteAll();
        referenceCounters.deleteAll();
        jdbc.update("DELETE FROM deedpoll_master");
    }

    @Test
    @DisplayName("no token is 401")
    void unauthenticated() throws Exception {
        mockMvc.perform(get(PATH)).andExpect(status().isUnauthorized());
    }

    @ParameterizedTest
    @ValueSource(strings = {"caseworker-deedpoll", "caseworker-deedpoll-judicial", "caseworker-civil"})
    @DisplayName("a caller without ALLOCATE_REFERRALS is 403, whatever the parameters, before anything is read")
    void refused(String role) throws Exception {
        raise(newCase());
        asStaff(role);

        authorised(get(PATH)).andExpect(status().isForbidden());
        authorised(get(PATH + "?state=Decided&page=nope&size=0")).andExpect(status().isForbidden())
            .andExpect(jsonPath("$.errors").doesNotExist());
    }

    @ParameterizedTest
    @ValueSource(strings = {"caseworker-deedpoll-team-leader", "caseworker-deedpoll-delivery-manager",
        "caseworker-deedpoll-admin"})
    @DisplayName("a Team leader, a Delivery Manager and the legacy admin read the queue")
    void admitted(String role) throws Exception {
        String caseId = newCase();
        String referralId = raise(caseId);
        asStaff(role);

        authorised(get(PATH))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.referrals[0].id").value(referralId))
            .andExpect(jsonPath("$.referrals[0].caseId").value(caseId))
            .andExpect(jsonPath("$.total").value(1));
    }

    @Test
    @DisplayName("an unknown state is a 400 on state, and the paging is refused as the case list refuses it")
    void badParameters() throws Exception {
        authorised(get(PATH + "?state=Decided"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.errors[0].field").value("state"))
            .andExpect(jsonPath("$.errors[0].message").value("state is Open or Claimed"));
        authorised(get(PATH + "?page=nope")).andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Open rows carry the case, the applicant's names, type and nationality, the reasons and who raised "
        + "it, and no assignment")
    void openRow() throws Exception {
        String caseId = newCase();
        String referralId = raise(caseId);

        String body = authorised(get(PATH + "?state=Open"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.page").value(1))
            .andExpect(jsonPath("$.size").value(25))
            .andExpect(jsonPath("$.total").value(1))
            .andExpect(jsonPath("$.totalPages").value(1))
            .andExpect(jsonPath("$.referrals[0].id").value(referralId))
            .andExpect(jsonPath("$.referrals[0].state").value("Open"))
            .andExpect(jsonPath("$.referrals[0].newForename").value("Ada"))
            .andExpect(jsonPath("$.referrals[0].newSurname").value("Newname"))
            .andExpect(jsonPath("$.referrals[0].oldSurname").value("Oldname"))
            .andExpect(jsonPath("$.referrals[0].type").value("Adult"))
            .andExpect(jsonPath("$.referrals[0].reasons[0]").value("DomesticAbuse"))
            .andExpect(jsonPath("$.referrals[0].urgency").value("Urgent"))
            .andExpect(jsonPath("$.referrals[0].urgencyReason").value("Hearing on Friday"))
            .andExpect(jsonPath("$.referrals[0].createdBy").value("Tia Leader"))
            .andExpect(jsonPath("$.referrals[0].assignedMaster").isEmpty())
            .andExpect(jsonPath("$.referrals[0].assignedAt").isEmpty())
            .andExpect(jsonPath("$.referrals[0].directionDueBy").isEmpty())
            .andReturn().getResponse().getContentAsString();

        JsonNode row = JSON.readTree(body).get("referrals").get(0);
        assertThat(row.get("displayReference").asText())
            .isEqualTo(deedPolls.findById(caseId).orElseThrow().getDisplayReference());
        assertThat(row.fieldNames()).toIterable().containsExactlyInAnyOrder(
            "id", "state", "caseId", "displayReference", "newForename", "newMiddleName", "newSurname", "oldForename",
            "oldMiddleName", "oldSurname", "type", "nationality", "reasons", "urgency", "urgencyReason", "createdBy",
            "createdOn", "assignedMaster", "assignedAt", "windowDate", "windowSlot", "directionDueBy");
        assertThat(body).doesNotContain(SUBJECT).doesNotContain(SULLIVAN_EMAIL).doesNotContain(AWAY_EMAIL);
    }

    @Test
    @DisplayName("Claimed rows carry the Master as its list entry, when it was sent, the window and when it is due")
    void claimedRow() throws Exception {
        String caseId = newCase();
        String referralId = raise(caseId);
        authorised(put("/caseworker/cases/" + caseId + "/referrals/" + referralId + "/assignment"),
            "{\"masterId\":\"" + sullivan + "\"}").andExpect(status().isOk());

        authorised(get(PATH)).andExpect(status().isOk()).andExpect(jsonPath("$.total").value(0));
        String body = authorised(get(PATH + "?state=Claimed"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.total").value(1))
            .andExpect(jsonPath("$.referrals[0].state").value("Claimed"))
            .andExpect(jsonPath("$.referrals[0].assignedMaster.id").value(sullivan.toString()))
            .andExpect(jsonPath("$.referrals[0].assignedMaster.name").value("Master Sullivan"))
            .andExpect(jsonPath("$.referrals[0].assignedAt").isNotEmpty())
            .andExpect(jsonPath("$.referrals[0].windowDate").isNotEmpty())
            .andExpect(jsonPath("$.referrals[0].windowSlot").isNotEmpty())
            .andExpect(jsonPath("$.referrals[0].directionDueBy").isNotEmpty())
            .andReturn().getResponse().getContentAsString();

        assertThat(JSON.readTree(body).at("/referrals/0/assignedMaster").size()).isEqualTo(2);
        assertThat(body).doesNotContain(SUBJECT).doesNotContain(SULLIVAN_EMAIL);
    }

    @Test
    @DisplayName("the envelope agrees with the clock, the cap is the setting, and only available Masters are listed")
    void envelope() throws Exception {
        LocalDateTime before = AuditClock.now();
        String body = authorised(get(PATH)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        LocalDateTime after = AuditClock.now();

        JsonNode window = JSON.readTree(body).get("window");
        assertThat(window.get("cap").asInt()).isEqualTo(settings.windowCap());
        assertThat(window.get("sent").asLong()).isZero();
        assertThat(window.get("masters")).hasSize(1);
        assertThat(window.at("/masters/0/id").asText()).isEqualTo(sullivan.toString());
        assertThat(window.at("/masters/0/name").asText()).isEqualTo("Master Sullivan");
        assertThat(window.at("/masters/0/sentInWindow").asLong()).isZero();
        assertThat(window.get("masters").get(0).fieldNames()).toIterable()
            .containsExactlyInAnyOrder("id", "name", "sentInWindow");

        ReferralWindow next = new ReferralWindow(LocalDate.parse(window.at("/next/date").asText()),
            WindowSlot.valueOf(window.at("/next/slot").asText()));
        assertThat(next).isIn(nextAt(before), nextAt(after));
        assertThat(LocalDateTime.parse(window.at("/next/opensAt").asText()))
            .isEqualTo(LocalDateTime.ofInstant(ReferralWindows.opensAt(next, settings.referralWindows()),
                                               ZoneOffset.UTC));
        if (window.get("current").isNull()) {
            assertThat(openAt(before).isEmpty() || openAt(after).isEmpty()).isTrue();
        } else {
            ReferralWindow current = new ReferralWindow(LocalDate.parse(window.at("/current/date").asText()),
                WindowSlot.valueOf(window.at("/current/slot").asText()));
            assertThat(Optional.of(current)).isIn(openAt(before), openAt(after));
            assertThat(LocalDateTime.parse(window.at("/current/closesAt").asText()))
                .isEqualTo(LocalDateTime.ofInstant(ReferralWindows.closesAt(current, settings.referralWindows()),
                                                   ZoneOffset.UTC));
        }
        assertThat(body).doesNotContain(SUBJECT).doesNotContain(AWAY_EMAIL).doesNotContain("Master Away");
    }

    @Test
    @DisplayName("the document publishes the operation, its parameters, the 403 and the envelope")
    void openApi() throws Exception {
        JsonNode document = JSON.readTree(mockMvc.perform(get("/v3/api-docs"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());

        JsonNode path = document.path("paths").path(PATH);
        assertThat(path.fieldNames()).toIterable().containsExactly("get");
        JsonNode operation = path.path("get");
        assertThat(operation.path("operationId").asText()).isEqualTo("listReferralQueue");
        assertThat(operation.path("description").asText()).contains("ALLOCATE_REFERRALS", "403");
        assertThat(operation.path("parameters").findValuesAsText("name"))
            .containsExactlyInAnyOrder("state", "page", "size");
        assertThat(operation.path("responses").has("403")).isTrue();
        JsonNode schemas = document.path("components").path("schemas");
        assertThat(schemas.path("ReferralQueueResponse").path("properties").fieldNames()).toIterable()
            .containsExactlyInAnyOrder("referrals", "page", "size", "total", "totalPages", "window");
        assertThat(schemas.path("ReferralQueueWindowResponse").path("properties").fieldNames()).toIterable()
            .containsExactlyInAnyOrder("current", "next", "cap", "sent", "masters");
        assertThat(schemas.path("MasterInWindow").path("properties").fieldNames()).toIterable()
            .containsExactlyInAnyOrder("id", "name", "sentInWindow");
    }

    private ReferralWindow nextAt(LocalDateTime utc) {
        return ReferralWindows.nextToOpen(utc.toInstant(ZoneOffset.UTC), settings.referralWindows(),
                                          bankHolidays::isWorkingDay);
    }

    private Optional<ReferralWindow> openAt(LocalDateTime utc) {
        return ReferralWindows.openAt(utc.toInstant(ZoneOffset.UTC), settings.referralWindows(),
                                      bankHolidays::isWorkingDay);
    }

    private UUID master(String name, String email, boolean available, String subject) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO deedpoll_master (id, name, email, available, idam_subject, linked_at, created_at,
                created_by_id, created_by_name)
            VALUES (?, ?, ?, ?, ?, CASE WHEN ? THEN now() END, now(), 'subject-dm', 'Dee Manager')
            """, id, name, email, available, subject, subject != null);
        return id;
    }

    private String newCase() {
        return deedPollService.createDeedPoll(DeedPollPatch.of(Map.of(
            "dateReceived", RECEIVED, "type", "Adult", "newForename", "Ada", "newSurname", "Newname",
            "oldForename", "Ada", "oldSurname", "Oldname"))).deedPoll().getId();
    }

    private String raise(String caseId) throws Exception {
        String previous = currentRole;
        asStaff("caseworker-deedpoll-team-leader");
        String created = authorised(post("/caseworker/cases/" + caseId + "/referrals"),
            "{\"reasons\":[\"DomesticAbuse\"],\"urgency\":\"Urgent\",\"urgencyReason\":\"Hearing on Friday\"}")
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        asStaff(previous);
        return JSON.readTree(created).get("id").asText();
    }

    private void asStaff(String role) {
        currentRole = role;
        IDAM.stubUserInfo(StubIdam.SUBJECT, "Tia Leader", List.of(role));
    }

    private ResultActions authorised(MockHttpServletRequestBuilder request) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(IDAM.accessToken());
        return mockMvc.perform(request.headers(headers));
    }

    private ResultActions authorised(MockHttpServletRequestBuilder request, String body) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(IDAM.accessToken());
        return mockMvc.perform(request.headers(headers).contentType(MediaType.APPLICATION_JSON).content(body));
    }
}
