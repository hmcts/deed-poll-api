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
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Reassignment over HTTP (plan F1, decision F-10): {@code PUT …/assignment}, now {@code ALLOCATE_REFERRALS} only and
 * only of a referral already sent; the create's ignoring {@code masterId}; and the Masters list without the window,
 * against a real database and a stub IDAM. The rules themselves and their order are {@code ReferralAssignmentTest}'s;
 * this class shows the routes reach them, which status each refusal becomes, that a refusal writes nothing, and that
 * no response carries a subject or an email.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CaseworkerReferralAssignmentTest extends AbstractPostgresContainerTest {

    private static final String RECEIVED = "2026-07-01T00:00:00.000Z";
    private static final String SUBJECT = "sullivan-idam-subject";
    private static final String SULLIVAN_EMAIL = "master.sullivan@judiciary.example";

    /** When the fixture's referrals were sent: Monday 26 October 2026, 09:00 UTC, which is 09:00 in London. */
    private static final LocalDateTime SENT_AT = LocalDateTime.of(2026, 10, 26, 9, 0);
    private static final LocalDate SENT_DAY = LocalDate.of(2026, 10, 26);

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

    private UUID sullivan;
    private UUID okafor;

    @BeforeEach
    void setUp() {
        clearTables();
        IDAM.stubDefaults();
        asCaseworker();
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
        jdbc.update("DELETE FROM deedpoll_master_email");
        jdbc.update("DELETE FROM deedpoll_master");
    }

    @Test
    @DisplayName("a create is Open whatever masterId it carries, which is stripped as any unknown key is (F1)")
    void createIgnoresMasterId() throws Exception {
        UUID away = master("Master Away", "away@judiciary.example", false, null);

        for (String masterId : new String[] {"\"" + sullivan + "\"", "\"" + away + "\"", "\"nope\"", "7"}) {
            String caseId = newCase();
            authorised(post(referralsOf(caseId)), "{\"reasons\":[\"DomesticAbuse\"],\"masterId\":" + masterId + "}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.state").value("Open"))
                .andExpect(jsonPath("$.claimedBy").isEmpty())
                .andExpect(jsonPath("$.assignedMaster").isEmpty())
                .andExpect(jsonPath("$.assignedAt").isEmpty())
                .andExpect(jsonPath("$.windowDate").isEmpty())
                .andExpect(jsonPath("$.windowSlot").isEmpty())
                .andExpect(jsonPath("$.directionDueBy").isEmpty());
            assertThat(notes.findByDeedPollIdOrderByCreatedOnDesc(caseId)).singleElement()
                .satisfies(note -> assertThat(note.getNote())
                    .startsWith("Referral raised, waiting to be sent to a Master."));
            assertThat(deedPolls.findById(caseId).orElseThrow().getCurrentReferralState())
                .isEqualTo(ReferralState.Open);
        }
        assertThat(referrals.findAll()).hasSize(4).allSatisfy(referral -> {
            assertThat(referral.getAssignedMasterId()).isNull();
            assertThat(assignedEvents(referral.getId().toString())).isZero();
        });
    }

    @Test
    @DisplayName("a caseworker is a 403 before anything is read or written, whatever the body")
    void caseworkerRefused() throws Exception {
        String caseId = newCase();
        String referralId = sent(caseId, raise(caseId), sullivan);

        for (String body : new String[] {withMaster(okafor), "{}", "{\"masterId\":\"nope\"}"}) {
            authorised(put(assignmentOf(caseId, referralId)), body).andExpect(status().isForbidden());
        }
        // An unknown referral is the same 403, so the refusal says nothing about what exists.
        authorised(put(assignmentOf(caseId, UUID.randomUUID().toString())), withMaster(okafor))
            .andExpect(status().isForbidden());

        assertThat(stored(referralId).getAssignedMasterId()).isEqualTo(sullivan);
        assertThat(notes.findByDeedPollIdOrderByCreatedOnDesc(caseId)).hasSize(1);
        assertThat(assignedEvents(referralId)).isZero();
    }

    @Test
    @DisplayName("a Team leader reassigns: the new Master, the clock and reminders restarted, the window kept")
    void reassigned() throws Exception {
        String caseId = newCase();
        String referralId = sent(caseId, raise(caseId), sullivan);
        asTeamLeader();

        String body = authorised(put(assignmentOf(caseId, referralId)), withMaster(okafor))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.state").value("Claimed"))
            .andExpect(jsonPath("$.claimedBy").value("Master Okafor"))
            .andExpect(jsonPath("$.assignedMaster.id").value(okafor.toString()))
            .andExpect(jsonPath("$.assignedMaster.name").value("Master Okafor"))
            .andExpect(jsonPath("$.windowDate").value(SENT_DAY.toString()))
            .andExpect(jsonPath("$.windowSlot").value("AM"))
            .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain(SUBJECT).doesNotContain(SULLIVAN_EMAIL).doesNotContain("okafor@");
        JsonNode json = JSON.readTree(body);
        assertThat(json.get("assignedMaster").size()).isEqualTo(2);
        Referral stored = stored(referralId);
        assertThat(stored.getClaimedById()).isNull();
        assertThat(stored.getRemindersSent()).isZero();
        assertThat(stored.getAssignedAt()).isAfter(SENT_AT);
        assertThat(stored.getWindowDate()).isEqualTo(SENT_DAY);
        assertThat(stored.getWindowSlot()).isEqualTo(WindowSlot.AM);
        assertThat(LocalDateTime.parse(json.get("assignedAt").asText()))
            .isCloseTo(stored.getAssignedAt(), within(1, ChronoUnit.MILLIS));
        assertThat(LocalDateTime.parse(json.get("directionDueBy").asText()))
            .isCloseTo(stored.getAssignedAt().plus(Duration.ofDays(7)), within(1, ChronoUnit.MILLIS));
        assertThat(notes.findByDeedPollIdOrderByCreatedOnDesc(caseId)).hasSize(2)
            .anySatisfy(note -> assertThat(note.getNote())
                .endsWith("reassigned from Master Sullivan to Master Okafor."));
        assertThat(assignedEvents(referralId)).isOne();
        assertThat(deedPolls.findById(caseId).orElseThrow().getCurrentReferralState())
            .isEqualTo(ReferralState.Claimed);
        // One SENT email, to the new Master only (F-10), Skipped while the template is unprovisioned.
        assertThat(jdbc.queryForList("""
            SELECT master_id, kind, state, period_key, ? = ALL (referral_ids) AS only_this
              FROM deedpoll_master_email
            """, UUID.fromString(referralId))).singleElement().satisfies(row -> {
                assertThat(row).containsEntry("master_id", okafor).containsEntry("kind", "SENT")
                    .containsEntry("state", "Skipped").containsEntry("only_this", true);
                assertThat((String) row.get("period_key")).startsWith(referralId + "@");
            });
    }

    @Test
    @DisplayName("a Delivery Manager reassigns past a full window's cap: a reassignment takes no place")
    void noCap() throws Exception {
        jdbc.update("UPDATE deedpoll_process_settings SET window_cap = 1");
        String first = newCase();
        String firstReferral = sent(first, raise(first), sullivan);
        String second = newCase();
        sent(second, raise(second), sullivan);
        asDeliveryManager();

        authorised(put(assignmentOf(first, firstReferral)), withMaster(okafor))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.assignedMaster.id").value(okafor.toString()));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM deedpoll_referral WHERE \"windowDate\" = ?",
                                       Integer.class, SENT_DAY)).isEqualTo(2);
    }

    @Test
    @DisplayName("an unknown, unavailable or malformed Master is a 400 on masterId, and nothing is written")
    void refusedMaster() throws Exception {
        String caseId = newCase();
        String referralId = sent(caseId, raise(caseId), sullivan);
        UUID away = master("Master Away", "away@judiciary.example", false, null);
        asTeamLeader();

        for (String masterId : new String[] {"\"" + UUID.randomUUID() + "\"", "\"" + away + "\"", "\"nope\"", "7"}) {
            authorised(put(assignmentOf(caseId, referralId)), "{\"masterId\":" + masterId + "}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("masterId"))
                .andExpect(jsonPath("$.errors[0].message").value("Select a Master who is available"));
        }
        assertThat(stored(referralId).getAssignedMasterId()).isEqualTo(sullivan);
        assertThat(notes.findByDeedPollIdOrderByCreatedOnDesc(caseId)).hasSize(1);
    }

    @Test
    @DisplayName("an Open referral is a 409: it is sent from the queue")
    void openRefused() throws Exception {
        String caseId = newCase();
        String referralId = raise(caseId);
        asTeamLeader();

        authorised(put(assignmentOf(caseId, referralId)), withMaster(sullivan))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error").value(
                "Send this referral from the queue: it has not been sent to a Master yet."));

        assertThat(stored(referralId).getState()).isEqualTo(ReferralState.Open);
        assertThat(notes.findByDeedPollIdOrderByCreatedOnDesc(caseId)).hasSize(1);
        assertThat(assignedEvents(referralId)).isZero();
    }

    @Test
    @DisplayName("PUT to the Master it already has is a 200 that writes no note, no audit and no email")
    void sameMaster() throws Exception {
        String caseId = newCase();
        String referralId = sent(caseId, raise(caseId), sullivan);
        asTeamLeader();

        authorised(put(assignmentOf(caseId, referralId)), withMaster(sullivan))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.assignedMaster.id").value(sullivan.toString()));

        assertThat(stored(referralId).getAssignedAt()).isEqualTo(SENT_AT);
        assertThat(notes.findByDeedPollIdOrderByCreatedOnDesc(caseId)).hasSize(1);
        assertThat(assignedEvents(referralId)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM deedpoll_master_email", Integer.class)).isZero();
    }

    @Test
    @DisplayName("PUT on a Decided referral is a 409, and a body without masterId is a 400")
    void refusals() throws Exception {
        String caseId = newCase();
        String referralId = sent(caseId, raise(caseId), sullivan);
        asTeamLeader();

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
        String referralId = sent(caseId, raise(caseId), sullivan);
        asTeamLeader();

        authorised(put(assignmentOf(newCase(), referralId)), withMaster(okafor)).andExpect(status().isNotFound());
        assertThat(stored(referralId).getAssignedMasterId()).isEqualTo(sullivan);
    }

    @Test
    @DisplayName("the Masters list carries no window, cap or per-Master count any more, and no subject")
    void mastersList() throws Exception {
        String caseId = newCase();
        sent(caseId, raise(caseId), sullivan);

        String body = authorised(get("/caseworker/masters"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.window").doesNotExist())
            .andExpect(jsonPath("$.cap").doesNotExist())
            .andExpect(jsonPath("$.masters[0].assignedInWindow").doesNotExist())
            .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain(SUBJECT);
        JsonNode json = JSON.readTree(body);
        assertThat(json.fieldNames()).toIterable().containsExactly("masters");
        assertThat(json.get("masters")).hasSize(2);
        json.get("masters").forEach(entry -> assertThat(entry.fieldNames()).toIterable()
            .containsExactlyInAnyOrder("id", "name", "email", "available", "linked", "revision"));
    }

    @Test
    @DisplayName("the document publishes the reassignment, its refusals and the narrowed list, and no create masterId")
    void openApi() throws Exception {
        JsonNode document = JSON.readTree(mockMvc.perform(get("/v3/api-docs"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        JsonNode schemas = document.path("components").path("schemas");

        assertThat(schemas.path("CreateReferralRequest").path("properties").has("masterId")).isFalse();
        assertThat(schemas.path("CreateReferralRequest").path("description").asText()).contains("referral queue");
        assertThat(schemas.path("AssignReferralRequest").path("required").toString()).isEqualTo("[\"masterId\"]");
        assertThat(schemas.path("AssignReferralRequest").path("description").asText())
            .contains("ALLOCATE_REFERRALS", "no place in any window", "checks no cap");
        JsonNode assign = document.path("paths").path("/caseworker/cases/{id}/referrals/{referralId}/assignment")
            .path("put");
        assertThat(assign.path("responses").path("400").path("description").asText())
            .contains("Select a Master who is available");
        assertThat(assign.path("responses").path("403").isMissingNode()).isFalse();
        assertThat(assign.path("responses").path("409").path("description").asText())
            .contains("Send this referral from the queue")
            .doesNotContain("This Master already has");
        JsonNode create = document.path("paths").path("/caseworker/cases/{id}/referrals").path("post")
            .path("responses");
        assertThat(create.path("409").path("description").asText()).doesNotContain("cap");
        assertThat(create.path("400").path("description").asText()).doesNotContain("masterId");
        assertThat(schemas.path("ReferralResponse").path("properties").fieldNames()).toIterable()
            .contains("assignedMaster", "assignedAt", "windowDate", "windowSlot", "directionDueBy");
        assertThat(schemas.path("AssignedMasterResponse").path("properties").fieldNames()).toIterable()
            .containsExactlyInAnyOrder("id", "name");
        assertThat(schemas.path("MasterListResponse").path("properties").fieldNames()).toIterable()
            .containsExactly("masters");
        assertThat(schemas.path("MasterListEntryResponse").path("properties").fieldNames()).toIterable()
            .doesNotContain("assignedInWindow");
        assertThat(schemas.has("ReferralWindowResponse")).isFalse();
    }

    private void asCaseworker() {
        IDAM.stubUserInfo(StubIdam.SUBJECT, "Ada Caseworker", List.of("caseworker-deedpoll"));
    }

    private void asTeamLeader() {
        IDAM.stubUserInfo(StubIdam.SUBJECT, "Tia Leader", List.of("caseworker-deedpoll-team-leader"));
    }

    private void asDeliveryManager() {
        IDAM.stubUserInfo(StubIdam.SUBJECT, "Dee Manager", List.of("caseworker-deedpoll-delivery-manager"));
    }

    /**
     * A referral sent to {@code master} in Monday 26 October's morning window, written directly: the send itself is
     * {@code ReferralSendTest}'s, and over HTTP it depends on the clock being inside a window.
     */
    private String sent(String caseId, String referralId, UUID master) {
        Referral referral = stored(referralId);
        boolean linked = master.equals(sullivan);
        referral.setState(ReferralState.Claimed);
        referral.setClaimedBy(linked ? "Master Sullivan" : "Master Okafor");
        referral.setClaimedById(linked ? SUBJECT : null);
        referral.setClaimedAt(SENT_AT);
        referral.setAssignedAt(SENT_AT);
        referral.setLastTouchedAt(SENT_AT);
        referral.setAssignedMasterId(master);
        referral.setWindowDate(SENT_DAY);
        referral.setWindowSlot(WindowSlot.AM);
        referral.setRemindersSent(2);
        referrals.saveAndFlush(referral);
        jdbc.update("UPDATE deedpoll_deedpoll SET \"currentReferralState\" = 'Claimed' WHERE id = ?", caseId);
        return referralId;
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
        return "{\"masterId\":\"" + master + "\"}";
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

    /** A referral raised by the caseworker, waiting in the queue. */
    private String raise(String caseId) throws Exception {
        String created = authorised(post(referralsOf(caseId)), "{\"reasons\":[\"DomesticAbuse\"]}")
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
