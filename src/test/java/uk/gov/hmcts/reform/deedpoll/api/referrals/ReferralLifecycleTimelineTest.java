package uk.gov.hmcts.reform.deedpoll.api.referrals;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import uk.gov.hmcts.reform.deedpoll.domain.auth.Role;
import uk.gov.hmcts.reform.deedpoll.domain.deedpoll.DeedPollPatch;
import uk.gov.hmcts.reform.deedpoll.domain.deedpoll.DeedPollService;
import uk.gov.hmcts.reform.deedpoll.entity.DeedPollNote;
import uk.gov.hmcts.reform.deedpoll.entity.Referral;
import uk.gov.hmcts.reform.deedpoll.entity.enums.MasterDecision;
import uk.gov.hmcts.reform.deedpoll.entity.enums.ReferralState;
import uk.gov.hmcts.reform.deedpoll.repository.DeedPollNoteRepository;
import uk.gov.hmcts.reform.deedpoll.repository.DeedPollRepository;
import uk.gov.hmcts.reform.deedpoll.repository.ReferenceCounterRepository;
import uk.gov.hmcts.reform.deedpoll.repository.ReferralRepository;

import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * One referral through its whole life over HTTP -- raised and assigned to a Master, linked by that Master's first
 * judicial request, directed, actioned -- and the case timeline the four notes leave behind, read back through the
 * caseworker surface, the one that publishes it. Plan D2 replaced the claim from a shared pool with the assignment.
 *
 * <p><strong>Why a class of its own.</strong> The lifecycle crosses the two surfaces: raising and closing are
 * caseworker routes and directing is a judicial one, so neither
 * {@code CaseworkerReferralControllerTest} nor {@code JudicialReferralControllerTest} can walk it -- each holds
 * one persona's token and reaches the other surface's transitions through the service instead. Those classes
 * pin what each transition writes; this one pins that the four notes are <em>one chronology</em>, written in
 * lifecycle order, each to the actor who caused it, and readable in full afterwards.
 *
 * <p><strong>The token changes persona between steps, which is the point.</strong> Every request here is a real
 * forwarded access token whose userinfo document is re-stubbed as the journey hands the referral over: the
 * caseworker who raises it is not the Master who directs it, and the caseworker who actions the
 * direction is a third person. That is what makes each note's {@code createdBy} evidence rather than a constant
 * -- four notes all reading "A Caseworker" would pass a journey that attributed every transition to whoever
 * happened to be first.
 *
 * <p><strong>The Master's surface carries no timeline</strong> since 2026-10-27 (Master Sullivan's feedback), and the
 * journey asserts that it is absent mid-journey. <strong>The caseworker surfaces order the chronology from opposite
 * ends, and both are asserted.</strong> The caseworker notes grid is newest first -- the grid is a page
 * of recent activity -- while {@code CaseDetailResponse.notes} is oldest first, because it comes off the case's
 * own {@code @OrderBy("createdOn ASC")} collection, reproducing the {@code orderBy: asc} the reference declares.
 * Neither is a preference to be tidied up: a client rendering one of them with the other's assumption would show
 * the lifecycle backwards, so the order each surface actually publishes is stated here rather than inferred from
 * the other.
 *
 * <p><strong>The judicial read happens mid-journey on purpose.</strong> A Master may see only their own
 * {@code Claimed}/{@code Decided} referrals, so {@code /judicial/referrals/{id}} is only reachable while the referral
 * is still live to them -- once the office closes it the answer is a 404, which {@code JudicialReferralControllerTest}
 * pins. So the journey reads the judicial projection after the direction and the caseworker projection after the
 * closure, which is also the order the two screens are really used in.
 *
 * <p>The lapse and re-claim branch is deliberately absent: it needs a claim backdated past the expiry window,
 * which is time travel rather than a journey, and it is covered over the transitions in
 * {@code ReferralServiceTest} -- claimed, lapsed, claimed again, with the service account authoring the middle
 * one. See {@code docs/plans/referrals-api-5-lifecycle-case-notes.md}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ReferralLifecycleTimelineTest extends AbstractPostgresContainerTest {

    private static final String POOL = "/judicial/referrals";

    /** createDeedPoll cannot allocate a reference number without a Date Received to derive the year from. */
    private static final String RECEIVED = "2026-07-01T00:00:00.000Z";

    /** The caseworker who refers the case to the judiciary. */
    private static final String REFERRING_CASEWORKER = "Ada Caseworker";

    /** The Master the referral is assigned to, who directs it: the Masters-list name and the IDAM name agree. */
    private static final String JUDGE = "District Judge Bell";

    /** The Master's address, on the Masters list and in IDAM's userinfo, which is how linking finds the entry. */
    private static final String JUDGE_EMAIL = "judge.bell@judiciary.example";

    /** A third person: the office actions the direction, and need not be whoever raised the referral. */
    private static final String ACTIONING_CASEWORKER = "Bo Caseworker";

    /** The only reason the referral is raised for, so the shared referral identity is short enough to read. */
    private static final String REASON = "DomesticAbuse";

    /**
     * {@link #REASON} as the notes write it: in the screens' words (Steve, 2026-10-27).
     */
    private static final String REASON_LABEL = "Domestic abuse considerations";

    /**
     * What the judge directs, long enough and distinctive enough to be found if it were ever copied.
     *
     * <p>The direction note is a summary: a direction may be twice as long as a note may be, so the text lives
     * on the referral and the note points at it. Asserted absent from the caseworker timeline, because
     * this is the journey where a truncating composer would look harmless.
     */
    private static final String DIRECTIONS = "Granted. Serve the order on the applicant and publish as usual.";

    /** Dates as the composer formats them, so the expected prose is the prose and not a substring of it. */
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMMM yyyy");

    /** Only to read the referral id back out of the create response, which is how the journey addresses it. */
    private static final ObjectMapper JSON = new ObjectMapper();

    private static final StubIdam IDAM = new StubIdam();

    static {
        // Started here rather than in @BeforeAll: @DynamicPropertySource is read while the context is being
        // built, which is before any @BeforeAll callback could have started the stub.
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

    @BeforeEach
    void setUp() {
        clearTables();
        IDAM.stubDefaults();
    }

    @AfterEach
    void tearDown() {
        clearTables();
    }

    private void clearTables() {
        referrals.deleteAll();
        jdbc.update("DELETE FROM deedpoll_master");
        notes.deleteAll();
        deedPolls.deleteAll();
        referenceCounters.deleteAll();
    }

    @Test
    @DisplayName("writes the whole lifecycle to the case timeline, and both surfaces read it back")
    void writesTheWholeLifecycleAndBothSurfacesReadItBack() throws Exception {
        String caseId = newCase();

        UUID master = listedMaster();

        asCaseworker(REFERRING_CASEWORKER);
        String referralId = raise(caseId, master);

        // The Master's first judicial request links their entry, and with it the referral assigned while it was
        // unlinked, so the list that request answers already carries it.
        asJudge();
        authorised(get(POOL + "/mine"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.referrals[0].id").value(referralId))
            .andExpect(jsonPath("$.referrals[0].directionDueBy").exists());
        authorised(post(POOL + "/" + referralId + "/direction"),
            "{\"decision\":\"" + MasterDecision.Approved + "\",\"directionsText\":\"" + DIRECTIONS
                + "\",\"changesToStandardPublication\":false}")
            .andExpect(status().isOk());

        // Three of the four, in the order they happened and each to the actor who caused it. Exact prose rather
        // than keywords: the notes have to pair up in a grid that may hold several referrals, so the referral
        // identity in each of them is the same words about the same referral.
        String raisedOn = DAY.format(stored(referralId).getCreatedOn());
        assertThat(timeline(caseId)).satisfiesExactly(
            raised -> {
                assertThat(raised.getNote()).isEqualTo("Referred to a Master. Reasons: " + REASON_LABEL + ".");
                assertThat(raised.getCreatedBy()).isEqualTo(REFERRING_CASEWORKER);
            },
            assigned -> {
                assertThat(assigned.getNote()).isEqualTo("Referral raised on " + raisedOn + ", reasons: "
                    + REASON_LABEL + ", assigned to " + JUDGE + ".");
                assertThat(assigned.getCreatedBy()).isEqualTo(REFERRING_CASEWORKER);
            },
            directed -> {
                assertThat(directed.getNote()).isEqualTo("Master’s direction recorded for referral raised on "
                    + raisedOn + ", reasons: " + REASON_LABEL + ". Decision: " + MasterDecision.Approved
                    + ". Changes to standard publication in the Gazette: No. See the referral for the full direction.");
                assertThat(directed.getCreatedBy()).isEqualTo(JUDGE);
            });

        // The judicial projection, while the referral is still this Master's: the direction is on the referral, and
        // the three notes the journey has written so far are not published here at all.
        authorised(get(POOL + "/" + referralId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.state").value(ReferralState.Decided.name()))
            .andExpect(jsonPath("$.directionsText").value(DIRECTIONS))
            .andExpect(jsonPath("$.caseNotes").doesNotExist());

        asCaseworker(ACTIONING_CASEWORKER);
        authorised(post("/caseworker/cases/" + caseId + "/referrals/" + referralId + "/closure"))
            .andExpect(status().isOk());

        // The fourth note, by the office rather than by the judge: the row's decidedBy still says who directed
        // it, and the note says who carried it out.
        assertThat(timeline(caseId)).hasSize(4);
        assertThat(timeline(caseId).get(3)).satisfies(actioned -> {
            assertThat(actioned.getNote()).isEqualTo("Master’s direction for referral raised on " + raisedOn
                + ", reasons: " + REASON_LABEL + ", marked as actioned.");
            assertThat(actioned.getCreatedBy()).isEqualTo(ACTIONING_CASEWORKER);
        });
        Referral closed = stored(referralId);
        assertThat(closed.getState()).isEqualTo(ReferralState.Closed);
        assertThat(closed.getDecidedBy()).isEqualTo(JUDGE);
        assertThat(closed.getDirectionsText()).isEqualTo(DIRECTIONS);
        // And the case has no referral in play again, which is the state closing is the only way to reach.
        assertThat(deedPolls.findById(caseId).orElseThrow().getCurrentReferralState()).isNull();

        // The caseworker case detail: the completed lifecycle, oldest first, because that collection is the
        // reference's ascending one -- see the class comment.
        authorised(get("/caseworker/cases/" + caseId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.notes.length()").value(4))
            .andExpect(jsonPath("$.notes[0].note").value(containsString("Referred to a Master")))
            .andExpect(jsonPath("$.notes[0].createdBy").value(REFERRING_CASEWORKER))
            .andExpect(jsonPath("$.notes[1].note").value(containsString("assigned to " + JUDGE)))
            .andExpect(jsonPath("$.notes[1].createdBy").value(REFERRING_CASEWORKER))
            .andExpect(jsonPath("$.notes[2].note").value(containsString("Master’s direction recorded")))
            .andExpect(jsonPath("$.notes[2].createdBy").value(JUDGE))
            .andExpect(jsonPath("$.notes[3].note").value(containsString("marked as actioned")))
            .andExpect(jsonPath("$.notes[3].createdBy").value(ACTIONING_CASEWORKER))
            .andExpect(jsonPath("$.notes[2].note").value(not(containsString(DIRECTIONS))));

        // And the notes grid the office actually works from, which is the same chronology newest first.
        authorised(get("/caseworker/cases/" + caseId + "/notes"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(4))
            .andExpect(jsonPath("$[0].note").value(containsString("marked as actioned")))
            .andExpect(jsonPath("$[3].note").value(containsString("Referred to a Master")));

        // Four notes: the reads above and the linking added nothing, because neither is a transition -- and a note
        // per read would bury the lifecycle in the grid it is written to.
        assertThat(notes.count()).isEqualTo(4);
    }

    /** A stored case with a Date Received, which is all reference-number allocation needs. */
    private String newCase() {
        return deedPollService.createDeedPoll(DeedPollPatch.of(Map.of("dateReceived", RECEIVED)))
            .deedPoll()
            .getId();
    }

    /**
     * The referral, raised over the caseworker route, which is the only way one is raised.
     *
     * <p>Over HTTP like every other step of the journey, rather than through the service: the whole claim of this
     * class is that the four notes appear when the four <em>requests</em> are made by four real tokens.
     */
    private String raise(String caseId, UUID master) throws Exception {
        String created = authorised(post("/caseworker/cases/" + caseId + "/referrals"),
            "{\"reasons\":[\"" + REASON + "\"],\"masterId\":\"" + master + "\"}")
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
        return JSON.readTree(created).get("id").asText();
    }

    /**
     * The case's notes oldest first, which is the order the lifecycle happened in.
     *
     * <p>Read through the repository rather than off either response, so the assertion is of what was stored
     * and not of what one projection chose to publish -- the two surfaces are then asserted separately, each in
     * the order it really answers.
     */
    private List<DeedPollNote> timeline(String caseId) {
        return notes.findByDeedPollIdOrderByCreatedOnDesc(caseId).reversed();
    }

    private Referral stored(String referralId) {
        return referrals.findById(UUID.fromString(referralId)).orElseThrow();
    }

    /** The token the next requests carry is a caseworker's, holding no role this service asks about. */
    private static void asCaseworker(String name) {
        IDAM.stubUserInfo(StubIdam.SUBJECT, name, List.of("caseworker-civil"));
    }

    /** The token the next requests carry is a judge's, with the email linking matches on the Masters list. */
    private static void asJudge() {
        IDAM.stubUserInfoBody("""
            {"sub": "%s", "name": "%s", "email": "%s", "roles": ["%s"]}
            """.formatted(StubIdam.SUBJECT, JUDGE, JUDGE_EMAIL, Role.JUDICIAL.claimValue()));
    }

    /** The Master on the Delivery Manager's list, not yet linked: they have never used the service. */
    private UUID listedMaster() {
        UUID id = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO deedpoll_master (id, name, email, available, created_at, created_by_id, created_by_name)
            VALUES (?, ?, ?, true, now(), 'dm-subject', 'Dee Manager')
            """, id, JUDGE, JUDGE_EMAIL);
        return id;
    }

    /** The request, carrying a valid forwarded access token. */
    private ResultActions authorised(MockHttpServletRequestBuilder request) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(IDAM.accessToken());
        return mockMvc.perform(request.headers(headers));
    }

    /** The request, carrying a valid forwarded access token and a JSON body. */
    private ResultActions authorised(MockHttpServletRequestBuilder request, String body) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(IDAM.accessToken());
        return mockMvc.perform(request.headers(headers).contentType(MediaType.APPLICATION_JSON).content(body));
    }
}
