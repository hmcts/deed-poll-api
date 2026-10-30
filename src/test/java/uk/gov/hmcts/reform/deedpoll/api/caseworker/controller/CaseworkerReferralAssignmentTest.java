package uk.gov.hmcts.reform.deedpoll.api.caseworker.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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
import uk.gov.hmcts.reform.deedpoll.entity.Referral;
import uk.gov.hmcts.reform.deedpoll.entity.enums.ReferralState;
import uk.gov.hmcts.reform.deedpoll.entity.enums.WindowSlot;
import uk.gov.hmcts.reform.deedpoll.repository.DeedPollNoteRepository;
import uk.gov.hmcts.reform.deedpoll.repository.DeedPollRepository;
import uk.gov.hmcts.reform.deedpoll.repository.ReferenceCounterRepository;
import uk.gov.hmcts.reform.deedpoll.repository.ReferralRepository;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Assignment over HTTP (plan D2): {@code masterId} on the create, {@code PUT …/assignment}, the new response fields
 * and the Masters list's window, against a real database and a stub IDAM. The rules themselves, their order and the
 * window arithmetic are {@code ReferralAssignmentTest}'s; this class shows the routes reach them, which status each
 * refusal becomes, that a refusal writes nothing, and that no response carries a subject or an email.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CaseworkerReferralAssignmentTest extends AbstractPostgresContainerTest {

    private static final String RECEIVED = "2026-07-01T00:00:00.000Z";
    private static final String SUBJECT = "sullivan-idam-subject";
    private static final String SULLIVAN_EMAIL = "master.sullivan@judiciary.example";

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
    private UUID okafor;

    @BeforeEach
    void setUp() {
        clearTables();
        IDAM.stubDefaults();
        IDAM.stubUserInfo(StubIdam.SUBJECT, "Ada Caseworker", List.of("caseworker-deedpoll"));
        sullivan = master("Master Sullivan", SULLIVAN_EMAIL, true, SUBJECT);
        okafor = master("Master Okafor", "master.okafor@judiciary.example", true, null);
    }

    @AfterEach
    void tearDown() {
        clearTables();
        jdbc.update("UPDATE deedpoll_process_settings SET window_cap = 10");
    }

    private void clearTables() {
        referrals.deleteAll();
        notes.deleteAll();
        deedPolls.deleteAll();
        referenceCounters.deleteAll();
        jdbc.update("DELETE FROM deedpoll_master");
    }

    @Test
    @DisplayName("a create with masterId is assigned: fields, note and audit, and no subject or email in the JSON")
    void createAssigned() throws Exception {
        String caseId = newCase();

        String body = authorised(post(referralsOf(caseId)), withMaster(sullivan))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.state").value("Claimed"))
            .andExpect(jsonPath("$.claimedBy").value("Master Sullivan"))
            .andExpect(jsonPath("$.assignedMaster.id").value(sullivan.toString()))
            .andExpect(jsonPath("$.assignedMaster.name").value("Master Sullivan"))
            .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain(SUBJECT).doesNotContain(SULLIVAN_EMAIL);
        JsonNode json = JSON.readTree(body);
        assertThat(json.get("assignedMaster").size()).isEqualTo(2);
        Referral stored = stored(json.get("id").asText());
        assertThat(stored.getClaimedById()).isEqualTo(SUBJECT);
        ReferralWindow window = windowAt(stored.getAssignedAt());
        assertThat(json.get("windowDate").asText()).isEqualTo(window.date().toString());
        assertThat(json.get("windowSlot").asText()).isEqualTo(window.slot().name());
        assertThat(LocalDateTime.parse(json.get("assignedAt").asText()))
            .isCloseTo(stored.getAssignedAt(), within(1, ChronoUnit.MILLIS));
        assertThat(LocalDateTime.parse(json.get("directionDueBy").asText()))
            .isCloseTo(stored.getAssignedAt().plus(Duration.ofDays(7)), within(1, ChronoUnit.MILLIS));
        assertThat(notes.findByDeedPollIdOrderByCreatedOnDesc(caseId)).hasSize(2)
            .anySatisfy(note -> assertThat(note.getNote()).endsWith("assigned to Master Sullivan."));
        assertThat(assignedEvents(stored.getId().toString())).isOne();
        assertThat(deedPolls.findById(caseId).orElseThrow().getCurrentReferralState())
            .isEqualTo(ReferralState.Claimed);
    }

    @Test
    @DisplayName("a create without masterId is Open, waiting for assignment, with no assignment fields")
    void createOpen() throws Exception {
        authorised(post(referralsOf(newCase())), "{\"reasons\":[\"DomesticAbuse\"]}")
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.state").value("Open"))
            .andExpect(jsonPath("$.assignedMaster").isEmpty())
            .andExpect(jsonPath("$.assignedAt").isEmpty())
            .andExpect(jsonPath("$.windowDate").isEmpty())
            .andExpect(jsonPath("$.windowSlot").isEmpty())
            .andExpect(jsonPath("$.directionDueBy").isEmpty());
    }

    @Test
    @DisplayName("an unknown, unavailable or malformed Master is a 400 on masterId, and nothing is written")
    void createRefusedMaster() throws Exception {
        String caseId = newCase();
        UUID away = master("Master Away", "away@judiciary.example", false, null);

        for (String masterId : new String[] {"\"" + UUID.randomUUID() + "\"", "\"" + away + "\"", "\"nope\"", "7"}) {
            authorised(post(referralsOf(caseId)), "{\"reasons\":[\"DomesticAbuse\"],\"masterId\":" + masterId + "}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("masterId"))
                .andExpect(jsonPath("$.errors[0].message").value("Select a Master who is available"));
        }
        assertThat(referrals.count()).isZero();
        assertThat(notes.findByDeedPollIdOrderByCreatedOnDesc(caseId)).isEmpty();
    }

    @Test
    @DisplayName("at the cap a create is a 409 on masterId naming the window, and nothing is written")
    void createAtCap() throws Exception {
        jdbc.update("UPDATE deedpoll_process_settings SET window_cap = 1");
        ReferralWindow before = windowAt(AuditClock.now());
        authorised(post(referralsOf(newCase())), withMaster(sullivan)).andExpect(status().isCreated());
        String second = newCase();

        ResultActions refused = authorised(post(referralsOf(second)), withMaster(sullivan));
        // The two creates count towards the window of the moment each was made, so a boundary between them is no
        // test of the cap.
        assumeTrue(before.equals(windowAt(AuditClock.now())), "a referral window boundary fell between the creates");
        refused
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.errors[0].field").value("masterId"))
            .andExpect(jsonPath("$.errors[0].message").value(Matchers.matchesPattern(
                "This Master already has 1 referral for the (morning|afternoon) of \\d{1,2} \\w+ \\d{4}\\. "
                    + "Choose another Master\\.")));

        assertThat(referrals.count()).isOne();
        assertThat(notes.findByDeedPollIdOrderByCreatedOnDesc(second)).isEmpty();
        // Another Master is not full.
        authorised(post(referralsOf(second)), withMaster(okafor)).andExpect(status().isCreated());
    }

    @Test
    @DisplayName("PUT assignment assigns an Open referral and reassigns a Claimed one; the old Master's count falls")
    void reassign() throws Exception {
        String caseId = newCase();
        String referralId = raise(caseId, "{\"reasons\":[\"DomesticAbuse\"]}");

        authorised(put(assignmentOf(caseId, referralId)), withMaster(sullivan))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.state").value("Claimed"))
            .andExpect(jsonPath("$.assignedMaster.id").value(sullivan.toString()));
        assertThat(countFor(sullivan)).isOne();

        String body = authorised(put(assignmentOf(caseId, referralId)), withMaster(okafor))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.assignedMaster.id").value(okafor.toString()))
            .andExpect(jsonPath("$.claimedBy").value("Master Okafor"))
            .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain(SUBJECT);
        assertThat(stored(referralId).getClaimedById()).isNull();
        assertThat(countFor(sullivan)).isZero();
        assertThat(countFor(okafor)).isOne();
        assertThat(assignedEvents(referralId)).isEqualTo(2);
        assertThat(notes.findByDeedPollIdOrderByCreatedOnDesc(caseId))
            .anySatisfy(note -> assertThat(note.getNote())
                .endsWith("reassigned from Master Sullivan to Master Okafor."));
    }

    @Test
    @DisplayName("PUT to the Master it already has is a 200 that writes no note and no audit")
    void sameMaster() throws Exception {
        String caseId = newCase();
        String referralId = raise(caseId, withMaster(sullivan));
        LocalDateTime assignedAt = stored(referralId).getAssignedAt();
        int noteCount = notes.findByDeedPollIdOrderByCreatedOnDesc(caseId).size();

        authorised(put(assignmentOf(caseId, referralId)), withMaster(sullivan))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.assignedMaster.id").value(sullivan.toString()));

        assertThat(stored(referralId).getAssignedAt()).isEqualTo(assignedAt);
        assertThat(notes.findByDeedPollIdOrderByCreatedOnDesc(caseId)).hasSize(noteCount);
        assertThat(assignedEvents(referralId)).isOne();
    }

    @Test
    @DisplayName("PUT on a Decided referral is a 409, and a body without masterId is a 400")
    void refusals() throws Exception {
        String caseId = newCase();
        String referralId = raise(caseId, withMaster(sullivan));

        authorised(put(assignmentOf(caseId, referralId)), "{}")
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.errors[0].field").value("masterId"));

        Referral referral = stored(referralId);
        referral.setState(ReferralState.Decided);
        referrals.saveAndFlush(referral);
        authorised(put(assignmentOf(caseId, referralId)), withMaster(okafor))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error").value(Matchers.endsWith(
                "only a referral waiting for a direction can be assigned")));
        assertThat(stored(referralId).getAssignedMasterId()).isEqualTo(sullivan);
    }

    @Test
    @DisplayName("PUT through the wrong case is a 404")
    void wrongCase() throws Exception {
        String caseId = newCase();
        String referralId = raise(caseId, "{\"reasons\":[\"DomesticAbuse\"]}");

        authorised(put(assignmentOf(newCase(), referralId)), withMaster(sullivan)).andExpect(status().isNotFound());
        assertThat(stored(referralId).getState()).isEqualTo(ReferralState.Open);
    }

    @Test
    @DisplayName("the Masters list carries the current window, the cap and each Master's count, and no subject")
    void mastersList() throws Exception {
        raise(newCase(), withMaster(sullivan));
        ReferralWindow before = windowAt(AuditClock.now());

        String body = authorised(get("/caseworker/masters"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.cap").value(10))
            .andReturn().getResponse().getContentAsString();
        ReferralWindow after = windowAt(AuditClock.now());

        assertThat(body).doesNotContain(SUBJECT);
        JsonNode json = JSON.readTree(body);
        ReferralWindow published = new ReferralWindow(LocalDate.parse(json.get("window").get("date").asText()),
            WindowSlot.valueOf(json.get("window").get("slot").asText()));
        assertThat(published).isIn(before, after);
        Map<String, Integer> counts = new HashMap<>();
        json.get("masters").forEach(entry -> counts.put(entry.get("id").asText(),
                                                        entry.get("assignedInWindow").asInt()));
        // The assignment counted towards the window of the moment it was made, which is the published one unless a
        // window boundary fell between the two.
        if (published.equals(windowAt(stored(onlyReferral()).getAssignedAt()))) {
            assertThat(counts).containsEntry(sullivan.toString(), 1);
        }
        assertThat(counts).containsEntry(okafor.toString(), 0);
    }

    @Test
    @DisplayName("the document publishes masterId, the assignment operation, both refusals and the list envelope")
    void openApi() throws Exception {
        JsonNode document = JSON.readTree(mockMvc.perform(get("/v3/api-docs"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        JsonNode schemas = document.path("components").path("schemas");

        assertThat(schemas.path("CreateReferralRequest").path("properties").path("masterId").path("format").asText())
            .isEqualTo("uuid");
        assertThat(schemas.path("CreateReferralRequest").path("required").toString()).doesNotContain("masterId");
        assertThat(schemas.path("AssignReferralRequest").path("required").toString()).isEqualTo("[\"masterId\"]");
        JsonNode assign = document.path("paths").path("/caseworker/cases/{id}/referrals/{referralId}/assignment")
            .path("put");
        assertThat(assign.path("responses").path("400").path("description").asText())
            .contains("Select a Master who is available");
        assertThat(assign.path("responses").path("409").path("description").asText())
            .contains("This Master already has");
        assertThat(document.path("paths").path("/caseworker/cases/{id}/referrals").path("post").path("responses")
            .path("409").path("description").asText()).contains("per-Master cap");
        assertThat(schemas.path("ReferralResponse").path("properties").fieldNames()).toIterable()
            .contains("assignedMaster", "assignedAt", "windowDate", "windowSlot", "directionDueBy");
        assertThat(schemas.path("AssignedMasterResponse").path("properties").fieldNames()).toIterable()
            .containsExactlyInAnyOrder("id", "name");
        assertThat(schemas.path("MasterListResponse").path("properties").fieldNames()).toIterable()
            .containsExactlyInAnyOrder("masters", "window", "cap");
        assertThat(schemas.path("MasterListEntryResponse").path("properties").fieldNames()).toIterable()
            .contains("assignedInWindow");
    }

    private String onlyReferral() {
        return referrals.findAll().get(0).getId().toString();
    }

    private ReferralWindow windowAt(LocalDateTime utc) {
        return ReferralWindows.windowFor(utc.toInstant(ZoneOffset.UTC), settings.referralWindows(),
                                         bankHolidays::isWorkingDay);
    }

    private long countFor(UUID master) {
        Referral referral = referrals.findAll().stream()
            .filter(row -> master.equals(row.getAssignedMasterId())).findFirst().orElse(null);
        if (referral == null) {
            return 0;
        }
        return referrals.countByStateAndAssignedMasterIdAndWindowDateAndWindowSlot(
            ReferralState.Claimed, master, referral.getWindowDate(), referral.getWindowSlot());
    }

    private int assignedEvents(String referralId) {
        return jdbc.queryForObject(
            "SELECT count(*) FROM deedpoll_audit_event WHERE action = 'REFERRAL_ASSIGNED' AND entity_id = ?",
            Integer.class, referralId);
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

    private static String withMaster(UUID master) {
        return "{\"reasons\":[\"DomesticAbuse\"],\"masterId\":\"" + master + "\"}";
    }

    private static String referralsOf(String caseId) {
        return "/caseworker/cases/" + caseId + "/referrals";
    }

    private static String assignmentOf(String caseId, String referralId) {
        return referralsOf(caseId) + "/" + referralId + "/assignment";
    }

    private String newCase() {
        return deedPollService.createDeedPoll(DeedPollPatch.of(Map.of("dateReceived", RECEIVED))).deedPoll().getId();
    }

    private String raise(String caseId, String body) throws Exception {
        String created = authorised(post(referralsOf(caseId)), body)
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return JSON.readTree(created).get("id").asText();
    }

    private Referral stored(String referralId) {
        return referrals.findById(UUID.fromString(referralId)).orElseThrow();
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
