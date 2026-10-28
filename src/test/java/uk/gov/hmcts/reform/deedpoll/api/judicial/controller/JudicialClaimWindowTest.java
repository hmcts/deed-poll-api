package uk.gov.hmcts.reform.deedpoll.api.judicial.controller;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
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
import uk.gov.hmcts.reform.deedpoll.domain.deedpoll.DeedPollPatch;
import uk.gov.hmcts.reform.deedpoll.domain.deedpoll.DeedPollService;
import uk.gov.hmcts.reform.deedpoll.domain.referrals.CreateReferralInput;
import uk.gov.hmcts.reform.deedpoll.domain.referrals.ReferralNotes;
import uk.gov.hmcts.reform.deedpoll.domain.referrals.ReferralReason;
import uk.gov.hmcts.reform.deedpoll.domain.referrals.ReferralService;
import uk.gov.hmcts.reform.deedpoll.entity.Referral;
import uk.gov.hmcts.reform.deedpoll.entity.enums.ReferralState;
import uk.gov.hmcts.reform.deedpoll.entity.enums.Urgency;
import uk.gov.hmcts.reform.deedpoll.repository.DeedPollNoteRepository;
import uk.gov.hmcts.reform.deedpoll.repository.DeedPollRepository;
import uk.gov.hmcts.reform.deedpoll.repository.ReferenceCounterRepository;
import uk.gov.hmcts.reform.deedpoll.repository.ReferralRepository;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The claim window is the Delivery Manager's stored setting, read live by every sweep: the judicial pool, My
 * referrals and claim. Real HTTP, real Postgres, and the window changed through {@code PUT
 * /caseworker/process-settings} rather than written onto the row.
 *
 * <p>A released claim is {@code Open} and nothing makes it {@code Claimed} again, so every step asks about a claim
 * that is still {@code Claimed}. Each fixture was last touched 25 hours ago: inside a two-day window, outside a
 * one-day one.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class JudicialClaimWindowTest extends AbstractPostgresContainerTest {

    private static final String POOL = "/judicial/referrals";
    private static final String MINE = POOL + "/mine";
    private static final String SETTINGS = "/caseworker/process-settings";
    private static final String RECEIVED = "2026-07-01T00:00:00.000Z";

    /** The judge the requests are made as. Their subject is the stub's own, so the token and userinfo agree. */
    private static final String JUDGE_NAME = "District Judge Bell";
    private static final String OTHER_JUDGE_NAME = "District Judge Okafor";
    private static final String OTHER_JUDGE_SUBJECT = "idam-judge-okafor";

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
    private DeedPollService deedPollService;
    @Autowired
    private ReferralService referralService;
    @Autowired
    private DeedPollRepository deedPolls;
    @Autowired
    private ReferralRepository referrals;
    @Autowired
    private DeedPollNoteRepository notes;
    @Autowired
    private ReferenceCounterRepository referenceCounters;

    @BeforeEach
    void setUp() throws Exception {
        IDAM.stubDefaults();
        clear();
        window(2880);
    }

    @AfterEach
    void clear() {
        referrals.deleteAll();
        notes.deleteAll();
        deedPolls.deleteAll();
        referenceCounters.deleteAll();
        jdbc.update("DELETE FROM deedpoll_process_settings_change");
        jdbc.update("""
            INSERT INTO deedpoll_process_settings (id, referral_claim_expiry_minutes, revision) VALUES (1, 10080, 0)
            ON CONFLICT (id) DO UPDATE SET referral_claim_expiry_minutes = 10080, revision = 0, updated_at = NULL,
                updated_by_id = NULL, updated_by_name = NULL
            """);
    }

    @Test
    @DisplayName("the pool: a PUT releases nothing, the next load does, and raising the window restores nothing")
    void poolReadsTheStoredWindow() throws Exception {
        String a = claimedBy(OTHER_JUDGE_NAME, OTHER_JUDGE_SUBJECT);

        asJudge();
        authorised(get(POOL)).andExpect(status().isOk());
        assertStillClaimed(a);

        window(1440);
        // The PUT is not a sweep.
        assertStillClaimed(a);

        asJudge();
        authorised(get(POOL)).andExpect(status().isOk())
            .andExpect(jsonPath("$.referrals[*].id", hasItem(a)));
        assertReleased(a);

        window(2880);
        asJudge();
        authorised(get(POOL)).andExpect(status().isOk());
        assertReleased(a);
    }

    @Test
    @DisplayName("the pool: a raised window keeps a claim the lower one would have released but no sweep had")
    void poolKeepsAClaimARaisedWindowCovers() throws Exception {
        window(1440);
        String b = claimedBy(OTHER_JUDGE_NAME, OTHER_JUDGE_SUBJECT);
        window(2880);

        asJudge();
        authorised(get(POOL)).andExpect(status().isOk())
            .andExpect(jsonPath("$.referrals[*].id", not(hasItem(b))));
        assertStillClaimed(b);
    }

    @Test
    @DisplayName("My referrals sweeps with the stored window")
    void mineReadsTheStoredWindow() throws Exception {
        String held = claimedBy(JUDGE_NAME, StubIdam.SUBJECT);

        asJudge();
        authorised(get(MINE)).andExpect(status().isOk())
            .andExpect(jsonPath("$.referrals[*].id", hasItem(held)));
        assertStillClaimed(held);

        window(1440);
        assertStillClaimed(held);

        asJudge();
        authorised(get(MINE)).andExpect(status().isOk())
            .andExpect(jsonPath("$.referrals[*].id", not(hasItem(held))));
        assertReleased(held);
    }

    @Test
    @DisplayName("claim sweeps with the stored window, so another judge's lapsed claim can be taken")
    void claimReadsTheStoredWindow() throws Exception {
        String other = claimedBy(OTHER_JUDGE_NAME, OTHER_JUDGE_SUBJECT);

        asJudge();
        // Inside the window the other judge still holds it, and to this judge it is not there.
        authorised(post(POOL + "/" + other + "/claim")).andExpect(status().isNotFound());
        assertStillClaimed(other);

        window(1440);
        assertStillClaimed(other);

        asJudge();
        authorised(post(POOL + "/" + other + "/claim")).andExpect(status().isOk())
            .andExpect(jsonPath("$.claimedBy").value(JUDGE_NAME));
        assertThat(stored(other).getClaimedBy()).isEqualTo(JUDGE_NAME);
        assertThat(lapseNotes(other)).isEqualTo(1);
    }

    @Test
    @DisplayName("with the settings row missing the pool load fails, and nothing is released, noted or refreshed")
    void missingSettingsReleaseNothing() throws Exception {
        window(1440);
        String a = claimedBy(OTHER_JUDGE_NAME, OTHER_JUDGE_SUBJECT);
        final int notesBefore = notesOn(a);
        jdbc.update("DELETE FROM deedpoll_process_settings");

        asJudge();
        assertThatThrownBy(() -> authorised(get(POOL)))
            .rootCause().isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("process settings row is missing");

        assertStillClaimed(a);
        assertThat(notesOn(a)).isEqualTo(notesBefore);
    }

    /** Sets the window as a Delivery Manager would, at the stored revision. */
    private void window(int minutes) throws Exception {
        IDAM.stubUserInfo(StubIdam.SUBJECT, "Delivery Manager", List.of(Role.DELIVERY_MANAGER.claimValue()));
        int revision = jdbc.queryForObject("SELECT revision FROM deedpoll_process_settings", Integer.class);
        authorised(put(SETTINGS).contentType("application/json")
                       .content("{\"referralClaimExpiryMinutes\":" + minutes + ",\"revision\":" + revision + "}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.referralClaimExpiryMinutes").value(minutes));
    }

    /** A referral on a case of its own, claimed by the named judge and last touched 25 hours ago. */
    private String claimedBy(String judge, String subject) {
        String caseId = deedPollService.createDeedPoll(DeedPollPatch.of(Map.of("dateReceived", RECEIVED)))
            .deedPoll().getId();
        String referralId = referralService.createReferral(
            caseId,
            new CreateReferralInput(List.of(ReferralReason.Minor), Optional.of("Applicant is 14"), Urgency.Normal,
                                    Optional.empty()),
            CurrentUser.currentUser(List.of(), "Ada Caseworker", "caseworker-1")).getId().toString();
        referralService.claim(caseId, referralId, CurrentUser.currentUser(List.of(Role.JUDICIAL), judge, subject));
        Referral referral = stored(referralId);
        referral.setLastTouchedAt(referral.getClaimedAt().minusHours(25));
        referrals.saveAndFlush(referral);
        return referralId;
    }

    private void assertStillClaimed(String referralId) {
        assertThat(stored(referralId).getState()).isEqualTo(ReferralState.Claimed);
        assertThat(lapseNotes(referralId)).isZero();
        assertThat(deedPolls.findById(caseOf(referralId)).orElseThrow().getCurrentReferralState())
            .isEqualTo(ReferralState.Claimed);
    }

    private void assertReleased(String referralId) {
        Referral referral = stored(referralId);
        assertThat(referral.getState()).isEqualTo(ReferralState.Open);
        assertThat(referral.getClaimedBy()).isNull();
        assertThat(lapseNotes(referralId)).isEqualTo(1);
        assertThat(deedPolls.findById(caseOf(referralId)).orElseThrow().getCurrentReferralState())
            .isEqualTo(ReferralState.Open);
    }

    private long lapseNotes(String referralId) {
        String lapsed = ReferralNotes.claimLapsed(OTHER_JUDGE_NAME);
        String lapsedMine = ReferralNotes.claimLapsed(JUDGE_NAME);
        return notes.findByDeedPollIdOrderByCreatedOnDesc(caseOf(referralId)).stream()
            .filter(note -> note.getNote().equals(lapsed) || note.getNote().equals(lapsedMine))
            .count();
    }

    private int notesOn(String referralId) {
        return notes.findByDeedPollIdOrderByCreatedOnDesc(caseOf(referralId)).size();
    }

    private String caseOf(String referralId) {
        return referrals.findWithCaseById(UUID.fromString(referralId)).orElseThrow().getDeedPoll().getId();
    }

    private Referral stored(String referralId) {
        return referrals.findById(UUID.fromString(referralId)).orElseThrow();
    }

    private static void asJudge() {
        IDAM.stubUserInfo(StubIdam.SUBJECT, JUDGE_NAME, List.of(Role.JUDICIAL.claimValue()));
    }

    private ResultActions authorised(MockHttpServletRequestBuilder request) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(IDAM.accessToken());
        return mvc.perform(request.headers(headers));
    }
}
