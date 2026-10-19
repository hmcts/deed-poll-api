package uk.gov.hmcts.reform.deedpoll.api.judicial.controller;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
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
import uk.gov.hmcts.reform.deedpoll.domain.referrals.DirectionInput;
import uk.gov.hmcts.reform.deedpoll.domain.referrals.JudicialReferralService;
import uk.gov.hmcts.reform.deedpoll.domain.referrals.ReferralReason;
import uk.gov.hmcts.reform.deedpoll.domain.referrals.ReferralService;
import uk.gov.hmcts.reform.deedpoll.entity.DeedPoll;
import uk.gov.hmcts.reform.deedpoll.entity.DeedPollSupportingDocument;
import uk.gov.hmcts.reform.deedpoll.entity.DeedPollSupportingDocumentType;
import uk.gov.hmcts.reform.deedpoll.entity.DeedPollWitness;
import uk.gov.hmcts.reform.deedpoll.entity.Referral;
import uk.gov.hmcts.reform.deedpoll.entity.enums.DeedPollType;
import uk.gov.hmcts.reform.deedpoll.entity.enums.DocumentDirection;
import uk.gov.hmcts.reform.deedpoll.entity.enums.MasterDecision;
import uk.gov.hmcts.reform.deedpoll.entity.enums.Privacy;
import uk.gov.hmcts.reform.deedpoll.entity.enums.ReferralState;
import uk.gov.hmcts.reform.deedpoll.entity.enums.Urgency;
import uk.gov.hmcts.reform.deedpoll.repository.DeedPollNoteRepository;
import uk.gov.hmcts.reform.deedpoll.repository.DeedPollRepository;
import uk.gov.hmcts.reform.deedpoll.repository.DeedPollSupportingDocumentRepository;
import uk.gov.hmcts.reform.deedpoll.repository.DeedPollSupportingDocumentTypeRepository;
import uk.gov.hmcts.reform.deedpoll.repository.DeedPollWitnessRepository;
import uk.gov.hmcts.reform.deedpoll.repository.ReferenceCounterRepository;
import uk.gov.hmcts.reform.deedpoll.repository.ReferralRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * All four judicial endpoints -- the two reads and the two writes -- over HTTP, against a real database and a
 * stub IDAM.
 *
 * <p>The whole context rather than a {@code @WebMvcTest} slice, for the reason every controller test here gives:
 * a slice does not get {@code SecurityConfiguration}, so it would assert Spring Boot's defaults rather than this
 * service's -- and the point of half these tests is which status code each refusal becomes.
 *
 * <p><strong>What is here is what only a booted service can show.</strong> The visibility rule itself is pinned
 * container-free in {@code JudicialReferralServiceTest}, which runs on the Jenkins agent where this class is
 * excluded ({@code INTEGRATION_TESTS_ENABLED=false}, no Docker). What this class adds is the wiring: that
 * {@code /judicial/**} is authenticated by omission, that the role gate is a 403 and an invisible referral a 404,
 * that the judicial projection really is what goes on the wire, and that the mutating read -- the sweep on the pool
 * and the claim refresh on the detail -- happens through a real transaction.
 *
 * <p><strong>The two writes are here for a third reason: which status code each of api-2's refusals becomes.</strong>
 * The transitions' own rules are pinned in {@code ReferralServiceTest} and their delegation in
 * {@code JudicialReferralServiceTest}; what only a booted service shows is that an invisible referral is a 404, a
 * visible one in the wrong state is api-2's 409, and a malformed direction is a 400 naming each offending field --
 * three answers to three different questions, which a judge on a stale dashboard has to be able to tell apart.
 *
 * <p><strong>The judge is a token holder here, unlike everywhere else in the suite.</strong> IDAM does not issue
 * {@code Role.JUDICIAL} yet, so the stub is asked for a userinfo document carrying the claim value -- which is the
 * only way to assert that a judicial <em>request</em> is admitted end to end rather than that a
 * {@code CurrentUser} constructed in a test is. The judge's identity is the token's {@code sub}, because that is
 * what {@code claimedById} holds, so the fixtures and the token have to agree on the subject and not merely on the
 * display name -- and {@code IdamUserInfoClient} requires the token and the userinfo document to agree on it too.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class JudicialReferralControllerTest extends AbstractPostgresContainerTest {

    private static final String POOL = "/judicial/referrals";

    /** The personal list, whose last segment is a literal and not a referral id. */
    private static final String MINE = POOL + "/mine";

    /** createDeedPoll cannot allocate a reference number without a Date Received to derive the year from. */
    private static final String RECEIVED = "2026-07-01T00:00:00.000Z";

    /** An id of the right shape that nothing was ever stored under. */
    private static final String UNKNOWN_ID = "11111111-1111-1111-1111-111111111111";

    /** The judge every request below is made as, unless a test says otherwise. */
    private static final String JUDGE_NAME = "District Judge Bell";

    /** A second judge, who exists only to hold referrals this one must not see. */
    private static final String OTHER_JUDGE_NAME = "District Judge Okafor";

    /**
     * The subject on the token the requests carry: the stub's own, because its tokens and its userinfo have to
     * agree on one and {@code IdamUserInfoClient} refuses the request if they do not.
     */
    private static final String JUDGE_SUBJECT = StubIdam.SUBJECT;

    /** The second judge's subject, which is the only thing that distinguishes them from the first. */
    private static final String OTHER_JUDGE_SUBJECT = "idam-judge-okafor";

    /**
     * A third judge who shares {@link #JUDGE_NAME}: a different person, and one the fixtures never hold anything
     * for. They exist to be refused.
     */
    private static final String NAMESAKE_JUDGE_SUBJECT = "idam-judge-namesake";

    /**
     * The subject of whoever the current userinfo stub describes, so the token minted for a request agrees with
     * it.
     *
     * <p>Mutable because a judge is switched mid-test -- which is the only way to ask whether a referral one judge
     * holds is visible to another over real HTTP -- and because ownership is now keyed on the subject, so
     * switching judges means switching subjects rather than only display names.
     */
    private static String tokenSubject = JUDGE_SUBJECT;

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
    private ReferralService referralService;

    @Autowired
    private DeedPollRepository deedPolls;

    @Autowired
    private ReferralRepository referrals;

    @Autowired
    private DeedPollNoteRepository notes;

    @Autowired
    private ReferenceCounterRepository referenceCounters;

    @Autowired
    private DeedPollWitnessRepository witnesses;

    @Autowired
    private DeedPollSupportingDocumentRepository documents;

    @Autowired
    private DeedPollSupportingDocumentTypeRepository documentTypes;

    @BeforeEach
    void setUp() {
        clearTables();
        IDAM.stubDefaults();
        asJudge(JUDGE_NAME);
    }

    @AfterEach
    void tearDown() {
        clearTables();
    }

    private void clearTables() {
        referrals.deleteAll();
        notes.deleteAll();
        witnesses.deleteAll();
        documents.deleteAll();
        documentTypes.deleteAll();
        deedPolls.deleteAll();
        referenceCounters.deleteAll();
    }

    @Nested
    @DisplayName("without a token")
    class Unauthenticated {

        @Test
        @DisplayName("refuses the pool, and does not run the sweep on the way out")
        void refusesThePool() throws Exception {
            String referralId = claimedBy(JUDGE_NAME);
            lapse(referralId);

            mockMvc.perform(get(POOL)).andExpect(status().isUnauthorized());

            // 401 before routing, so the mutating half of the read never happens: an unauthenticated caller must
            // not be able to release another judge's claim by polling a page they cannot see.
            assertThat(stored(referralId).getState()).isEqualTo(ReferralState.Claimed);
        }

        @Test
        @DisplayName("refuses one referral, and does not refresh its claim")
        void refusesAReferral() throws Exception {
            String referralId = claimedBy(JUDGE_NAME);
            LocalDateTime touched = lapse(referralId);

            // The route is protected by having been written rather than by having been annotated, which is exactly
            // why every new route gets one of these.
            mockMvc.perform(get(POOL + "/" + referralId)).andExpect(status().isUnauthorized());

            assertThat(stored(referralId).getLastTouchedAt()).isEqualTo(touched);
        }
    }

    @Nested
    @DisplayName("with a caseworker token")
    class AsACaseworker {

        @Test
        @DisplayName("is refused the pool with a 403 naming the role, and the pool is not swept")
        void isRefusedThePool() throws Exception {
            asCaseworker();
            String referralId = claimedBy(JUDGE_NAME);
            lapse(referralId);

            // 403 and not 404: the caller is authenticated and identified, and what they lack is the role. The
            // 404s on this surface are about a referral, never about the caller.
            authorised(get(POOL))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value(containsString(Role.JUDICIAL.claimValue())));

            assertThat(stored(referralId).getState()).isEqualTo(ReferralState.Claimed);
        }

        @Test
        @DisplayName("is refused a referral it could name, whatever state that referral is in")
        void isRefusedAReferral() throws Exception {
            String referralId = raise(newCase(), Urgency.Normal);
            asCaseworker();

            // A caseworker has their own, richer view of this very referral under /caseworker/**. What they may
            // not have is the judicial projection of it, because that view and the pool are the judicial
            // persona's -- so the refusal is the role gate rather than a data rule.
            authorised(get(POOL + "/" + referralId)).andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("is refused even holding the administrator role, because judicial is not seniority")
        void isRefusedAsAnAdministrator() throws Exception {
            IDAM.stubUserInfo(StubIdam.SUBJECT, "An Admin", List.of(Role.DEEDPOLL_ADMIN.claimValue()));

            authorised(get(POOL)).andExpect(status().isForbidden());
            authorised(get(POOL + "/" + UNKNOWN_ID)).andExpect(status().isForbidden());
        }
    }

    @Nested
    @DisplayName("GET /judicial/referrals")
    class Pool {

        @Test
        @DisplayName("returns the Open referrals as judicial summaries, with the pool's total")
        void returnsThePoolWithItsTotal() throws Exception {
            String waiting = raise(newCase(), Urgency.Normal);

            authorised(get(POOL))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.referrals.length()").value(1))
                .andExpect(jsonPath("$.referrals[0].id").value(waiting))
                .andExpect(jsonPath("$.referrals[0].state").value(ReferralState.Open.name()))
                .andExpect(jsonPath("$.referrals[0].reasons[0]").value(ReferralReason.Minor.name()))
                .andExpect(jsonPath("$.referrals[0].urgency").value(Urgency.Normal.name()))
                .andExpect(jsonPath("$.referrals[0].createdOn").exists())
                // The total is what makes ten a page size rather than a cap, so it is published even when the
                // whole pool fits on one page.
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.size").value(JudicialReferralService.POOL_PAGE_SIZE))
                .andExpect(jsonPath("$.totalPages").value(1));
        }

        @Test
        @DisplayName("publishes no case data on a pool row, not even the reference")
        void publishesNoCaseDataOnARow() throws Exception {
            raise(named(newCase(), "Robin"), Urgency.Normal);

            // A queue of referrals, not a list of applications: reaching the application is the detail read, which
            // runs the visibility check. A pool row carrying names would be a case list a judge was never asked to
            // see -- and this is the assertion that would catch CaseSummaryResponse being reused here.
            authorised(get(POOL))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.referrals[0].newForename").doesNotExist())
                .andExpect(jsonPath("$.referrals[0].displayReference").doesNotExist())
                .andExpect(jsonPath("$.referrals[0].application").doesNotExist())
                .andExpect(jsonPath("$.referrals[0].deedPoll").doesNotExist())
                .andExpect(jsonPath("$.referrals[0].deedPollId").doesNotExist())
                // Nor the referral's own audit trail, which decision 8 excludes.
                .andExpect(jsonPath("$.referrals[0].createdBy").doesNotExist())
                .andExpect(jsonPath("$.referrals[0].modifiedBy").doesNotExist())
                .andExpect(jsonPath("$.referrals[0].lastTouchedAt").doesNotExist());
        }

        @Test
        @DisplayName("orders the pool urgent first, then longest-waiting within each band")
        void ordersThePool() throws Exception {
            String urgentNewer = raise(newCase(), Urgency.Urgent);
            String urgentOlder = raise(newCase(), Urgency.Urgent);
            String normalOldest = raise(newCase(), Urgency.Normal);
            // Backdated rather than trusting three inserts to land in different microseconds.
            backdate(urgentNewer, LocalDateTime.of(2026, 8, 3, 9, 0));
            backdate(urgentOlder, LocalDateTime.of(2026, 8, 2, 9, 0));
            backdate(normalOldest, LocalDateTime.of(2026, 8, 1, 9, 0));

            // The normal referral is the oldest of the three and still comes last: urgency is the first key, and
            // oldest-first applies within a band rather than across the pool.
            authorised(get(POOL))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.referrals[0].id").value(urgentOlder))
                .andExpect(jsonPath("$.referrals[1].id").value(urgentNewer))
                .andExpect(jsonPath("$.referrals[2].id").value(normalOldest));
        }

        @Test
        @DisplayName("offers nothing a judge holds, a caseworker withdrew or the office finished")
        void offersOnlyOpenReferrals() throws Exception {
            String waiting = raise(newCase(), Urgency.Normal);
            claimedBy(JUDGE_NAME);
            withdrawn();

            authorised(get(POOL))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.referrals.length()").value(1))
                .andExpect(jsonPath("$.referrals[0].id").value(waiting))
                .andExpect(jsonPath("$.total").value(1));
        }

        @Test
        @DisplayName("pages, and says how much is waiting on every page")
        void pagesThePool() throws Exception {
            for (int referral = 0; referral < 3; referral++) {
                backdate(raise(newCase(), Urgency.Normal), LocalDateTime.of(2026, 8, 1 + referral, 9, 0));
            }

            authorised(get(POOL + "?page=2&size=2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.referrals.length()").value(1))
                .andExpect(jsonPath("$.page").value(2))
                .andExpect(jsonPath("$.size").value(2))
                .andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.totalPages").value(2));

            // A page past the end of a pool that has been worked down is an empty page and not a 404: the total is
            // what tells a judge where to look instead.
            authorised(get(POOL + "?page=9&size=2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.referrals.length()").value(0))
                .andExpect(jsonPath("$.total").value(3));
        }

        @Test
        @DisplayName("400s a page it cannot read, naming the parameter")
        void refusesAnUnreadablePage() throws Exception {
            authorised(get(POOL + "?page=lots"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("page"));
        }

        @Test
        @DisplayName("releases a lapsed claim and offers it in the same response that noticed")
        void releasesALapsedClaimInTheSameRequest() throws Exception {
            String lapsed = claimedBy(OTHER_JUDGE_NAME);
            lapse(lapsed);

            // Decision 22, end to end: the pool is the most-polled judicial page, so the sweep hangs off it, and
            // the referral is back in the pool in the request that observed the lapse rather than the next one.
            authorised(get(POOL))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.referrals.length()").value(1))
                .andExpect(jsonPath("$.referrals[0].id").value(lapsed));

            Referral released = stored(lapsed);
            assertThat(released.getState()).isEqualTo(ReferralState.Open);
            assertThat(released.getClaimedBy()).isNull();
            // And the timeline says why, because a lapse erases the fact it is about.
            assertThat(notes.findAll())
                .anySatisfy(note -> assertThat(note.getNote()).contains(OTHER_JUDGE_NAME));
        }
    }

    @Nested
    @DisplayName("GET /judicial/referrals/mine")
    class MyReferrals {

        @Test
        @DisplayName("returns this judge's claimed and decided referrals, with their own total")
        void returnsThisJudgesOwnWork() throws Exception {
            String holding = claimedBy(JUDGE_NAME);
            String directed = directedBy(JUDGE_NAME);

            authorised(get(MINE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.referrals.length()").value(2))
                // Claimed first, because unfinished judicial work comes before a direction the office is actioning.
                .andExpect(jsonPath("$.referrals[0].id").value(holding))
                .andExpect(jsonPath("$.referrals[0].state").value(ReferralState.Claimed.name()))
                .andExpect(jsonPath("$.referrals[1].id").value(directed))
                .andExpect(jsonPath("$.referrals[1].state").value(ReferralState.Decided.name()))
                // The state is on every row because both are here: a client that had to infer it from the endpoint
                // would be inferring one answer for two kinds of row.
                .andExpect(jsonPath("$.referrals[0].reasons[0]").value(ReferralReason.Minor.name()))
                .andExpect(jsonPath("$.referrals[0].urgency").value(Urgency.Normal.name()))
                .andExpect(jsonPath("$.referrals[0].createdOn").exists())
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.size").value(JudicialReferralService.POOL_PAGE_SIZE))
                .andExpect(jsonPath("$.totalPages").value(1));
        }

        @Test
        @DisplayName("is an empty page and not a 404 for a judge holding nothing")
        void isEmptyForAJudgeHoldingNothing() throws Exception {
            raise(newCase(), Urgency.Normal);

            // The collection always exists; what varies is whether the judge has anything in it. A 404 here would be
            // a screen that could not tell "you have no work" from "this page has gone".
            authorised(get(MINE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.referrals.length()").value(0))
                .andExpect(jsonPath("$.total").value(0))
                .andExpect(jsonPath("$.totalPages").value(0));
        }

        @Test
        @DisplayName("leaves out the shared pool, the terminal states and the rows another judge owns")
        void leavesOutEverythingThatIsNotThisJudgesOwnWork() throws Exception {
            raise(newCase(), Urgency.Normal);
            claimedBy(OTHER_JUDGE_NAME);
            directedBy(OTHER_JUDGE_NAME);
            moveTo(directedBy(JUDGE_NAME), ReferralState.Closed);
            moveTo(claimedBy(JUDGE_NAME), ReferralState.Withdrawn);
            String mine = claimedBy(JUDGE_NAME);

            // Exactly what visibleTo admits in the two states a judge owns: Open belongs to the pool, Closed means the
            // office has actioned the direction, Withdrawn means they pulled the referral back, and another judge's
            // work is theirs. Six referrals stored and one on the list.
            authorised(get(MINE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.referrals.length()").value(1))
                .andExpect(jsonPath("$.referrals[0].id").value(mine))
                .andExpect(jsonPath("$.total").value(1));
            assertThat(referrals.count()).isEqualTo(6);
        }

        @Test
        @DisplayName("leaves out a pre-migration row that names this judge but has no owner id")
        void leavesOutANullOwnerRow() throws Exception {
            String unowned = claimedBy(JUDGE_NAME);
            String unownedDirection = directedBy(JUDGE_NAME);
            disownAll();

            // Neither row is readable on its own, which is what visibleTo already said about a null owner.
            authorised(get(POOL + "/" + unowned)).andExpect(status().isNotFound());
            authorised(get(POOL + "/" + unownedDirection)).andExpect(status().isNotFound());

            // And neither is on the list, so the two agree. Fail closed, which is what V14 buys and the rollout
            // depends on: the display name on these rows is this judge's, and it authorises nothing. A fallback to
            // the name would put a namesake's outstanding work on this list precisely where identity is uncertain.
            authorised(get(MINE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.referrals.length()").value(0))
                .andExpect(jsonPath("$.total").value(0));
        }

        @Test
        @DisplayName("gives a judge who merely shares the holder's display name their own empty list")
        void leavesOutANamesakesWork() throws Exception {
            claimedBy(JUDGE_NAME);
            directedBy(JUDGE_NAME);
            asJudge(JUDGE_NAME, NAMESAKE_JUDGE_SUBJECT);

            // The disclosure this list would have been, asserted over real HTTP: keyed on the display name it would
            // have handed a namesake another judge's whole outstanding work without their needing a single referral
            // id. Keyed on the token's subject, they get their own list, which is empty.
            authorised(get(MINE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.referrals.length()").value(0))
                .andExpect(jsonPath("$.total").value(0));
        }

        @Test
        @DisplayName("publishes no case data on a row, and no owner id either")
        void publishesNoCaseDataOnARow() throws Exception {
            String caseId = newCase();
            named(caseId, "Robin");
            administrative(caseId);
            String referralId = raise(caseId, Urgency.Normal);
            referralService.claim(caseId, referralId, judicial(JUDGE_NAME));

            String body = authorised(get(MINE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.referrals.length()").value(1))
                // The same row the pool publishes and nothing more: this list is how a judge resumes work they have
                // already been admitted to, not a case list that arrives with the application attached.
                .andExpect(jsonPath("$.referrals[0].newForename").doesNotExist())
                .andExpect(jsonPath("$.referrals[0].displayReference").doesNotExist())
                .andExpect(jsonPath("$.referrals[0].application").doesNotExist())
                .andExpect(jsonPath("$.referrals[0].deedPollId").doesNotExist())
                .andExpect(jsonPath("$.referrals[0].caseNotes").doesNotExist())
                .andExpect(jsonPath("$.referrals[0].directionsText").doesNotExist())
                // And nothing of the ownership the membership rule is decided by: the subject is an identity IDAM
                // issued, and a list is no reason to publish one.
                .andExpect(jsonPath("$.referrals[0].claimedBy").doesNotExist())
                .andExpect(jsonPath("$.referrals[0].claimedById").doesNotExist())
                .andExpect(jsonPath("$.referrals[0].decidedById").doesNotExist())
                .andExpect(jsonPath("$.referrals[0].lastTouchedAt").doesNotExist())
                .andReturn()
                .getResponse()
                .getContentAsString();

            assertThat(body)
                .doesNotContain(JUDGE_SUBJECT)
                .doesNotContain("FEE-2026-1")
                .doesNotContain("applicant@example.com")
                .doesNotContain(caseId);
        }

        @Test
        @DisplayName("orders claimed before decided, urgent before normal, then oldest first")
        void ordersTheList() throws Exception {
            // Backdated rather than trusting five inserts to land in different microseconds, and the ages run
            // deliberately the wrong way round: the oldest referral of the five is a decided one, and it comes last.
            String claimedUrgent = claimedBy(JUDGE_NAME, Urgency.Urgent);
            backdate(claimedUrgent, LocalDateTime.of(2026, 8, 5, 9, 0));
            String claimedNormalOlder = claimedBy(JUDGE_NAME, Urgency.Normal);
            backdate(claimedNormalOlder, LocalDateTime.of(2026, 8, 3, 9, 0));
            String claimedNormalNewer = claimedBy(JUDGE_NAME, Urgency.Normal);
            backdate(claimedNormalNewer, LocalDateTime.of(2026, 8, 4, 9, 0));
            String decidedUrgent = directedBy(JUDGE_NAME, Urgency.Urgent);
            backdate(decidedUrgent, LocalDateTime.of(2026, 8, 2, 9, 0));
            String decidedNormal = directedBy(JUDGE_NAME, Urgency.Normal);
            backdate(decidedNormal, LocalDateTime.of(2026, 8, 1, 9, 0));

            authorised(get(MINE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.referrals[0].id").value(claimedUrgent))
                .andExpect(jsonPath("$.referrals[1].id").value(claimedNormalOlder))
                .andExpect(jsonPath("$.referrals[2].id").value(claimedNormalNewer))
                .andExpect(jsonPath("$.referrals[3].id").value(decidedUrgent))
                .andExpect(jsonPath("$.referrals[4].id").value(decidedNormal));
        }

        @Test
        @DisplayName("pages in the database, and says how much work there is on every page")
        void pagesTheList() throws Exception {
            for (int referral = 0; referral < 3; referral++) {
                backdate(claimedBy(JUDGE_NAME), LocalDateTime.of(2026, 8, 1 + referral, 9, 0));
            }
            // Another judge's row, present so a page of two cannot be right by accident: the predicate is in the
            // query, so their referral is not a row this paging had to skip.
            claimedBy(OTHER_JUDGE_NAME);

            authorised(get(MINE + "?page=2&size=2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.referrals.length()").value(1))
                .andExpect(jsonPath("$.page").value(2))
                .andExpect(jsonPath("$.size").value(2))
                .andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.totalPages").value(2));

            // A page past the end of a list that has been worked down is empty and not an error, as the pool's is.
            authorised(get(MINE + "?page=9&size=2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.referrals.length()").value(0))
                .andExpect(jsonPath("$.total").value(3));
        }

        @Test
        @DisplayName("400s a page it cannot read, naming the parameter")
        void refusesAnUnreadablePage() throws Exception {
            authorised(get(MINE + "?page=lots"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("page"));

            authorised(get(MINE + "?size=none"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("size"));
        }

        @Test
        @DisplayName("is dispatched by the literal segment, never as a referral id")
        void isNotDispatchedAsAReferralId() throws Exception {
            claimedBy(JUDGE_NAME);

            // Decision 5: a literal path segment beats a template one, so "mine" is a collection rather than an id
            // the detail read would have failed to parse. The envelope is the proof -- the other read answers with a
            // referral document and has no `referrals` array to offer.
            authorised(get(MINE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.referrals").isArray())
                .andExpect(jsonPath("$.total").exists())
                .andExpect(jsonPath("$.error").doesNotExist());

            // And any other word in that position is still the detail read's 404, so the dispatch above is this
            // mapping winning rather than the template having stopped matching anything.
            authorised(get(POOL + "/theirs"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Referral theirs not found"));
        }

        @Test
        @DisplayName("shows a referral from the moment it is claimed to the moment the office closes it")
        void followsTheReferralThroughItsLifecycle() throws Exception {
            String caseId = newCase();
            String referralId = raise(caseId, Urgency.Normal);

            // Raised and unclaimed: the pool's, not this judge's.
            authorised(get(MINE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.referrals.length()").value(0));

            // Claimed over HTTP, which is the failure business testing reported: the claim took it out of the pool,
            // and this is the list it arrives on.
            authorised(post(POOL + "/" + referralId + "/claim")).andExpect(status().isOk());
            authorised(get(MINE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.referrals.length()").value(1))
                .andExpect(jsonPath("$.referrals[0].id").value(referralId))
                .andExpect(jsonPath("$.referrals[0].state").value(ReferralState.Claimed.name()));

            // Reading the papers refreshes the claim and must not duplicate or reorder the row.
            authorised(get(POOL + "/" + referralId)).andExpect(status().isOk());
            authorised(get(MINE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.referrals.length()").value(1))
                .andExpect(jsonPath("$.referrals[0].id").value(referralId));

            // Directed: still theirs, now as Decided, which is what lets them re-read their direction while the
            // office actions it.
            authorised(post(POOL + "/" + referralId + "/direction")
                           .contentType(APPLICATION_JSON)
                           .content("""
                               {"decision": "Approved", "directionsText": "Granted", "publishToGazette": true}"""))
                .andExpect(status().isOk());
            authorised(get(MINE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.referrals.length()").value(1))
                .andExpect(jsonPath("$.referrals[0].id").value(referralId))
                .andExpect(jsonPath("$.referrals[0].state").value(ReferralState.Decided.name()));

            // Closed by the office: the direction has been actioned and judicial involvement is over.
            moveTo(referralId, ReferralState.Closed);
            authorised(get(MINE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.referrals.length()").value(0))
                .andExpect(jsonPath("$.total").value(0));
        }

        @Test
        @DisplayName("drops a referral the office withdrew from under the judge holding it")
        void dropsAWithdrawnReferral() throws Exception {
            String mine = claimedBy(JUDGE_NAME);

            moveTo(mine, ReferralState.Withdrawn);

            authorised(get(MINE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.referrals.length()").value(0));
        }

        @Test
        @DisplayName("sweeps first, so a lapsed claim leaves this list rather than waiting to be opened")
        void sweepsBeforeItAnswers() throws Exception {
            String lapsed = claimedBy(JUDGE_NAME);
            String kept = claimedBy(JUDGE_NAME);
            lapse(lapsed);

            // Decision 3 end to end. Without the sweep the lapsed row would still be Claimed by this judge and
            // therefore on this page, and opening it from here would call touch and revive a claim the expiry window
            // had already ended -- so whether a claim expired would depend on which page somebody loaded first.
            authorised(get(MINE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.referrals.length()").value(1))
                .andExpect(jsonPath("$.referrals[0].id").value(kept));

            Referral released = stored(lapsed);
            assertThat(released.getState()).isEqualTo(ReferralState.Open);
            assertThat(released.getClaimedById()).isNull();
            // Back in the shared pool in the same request, and the lapse is on the timeline, because releasing a
            // claim erases the fact it was about.
            authorised(get(POOL))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.referrals[*].id", hasItem(lapsed)));
            assertThat(notes.findAll())
                .anySatisfy(note -> assertThat(note.getNote()).contains(JUDGE_NAME));
        }

        @Test
        @DisplayName("401s without a token, and does not run the sweep on the way out")
        void refusesAnUnauthenticatedCaller() throws Exception {
            String referralId = claimedBy(JUDGE_NAME);
            lapse(referralId);

            mockMvc.perform(get(MINE)).andExpect(status().isUnauthorized());

            // 401 before routing, so the mutating half of this read never happens: an anonymous caller must not be
            // able to release a judge's claim by asking for a list they cannot see.
            assertThat(stored(referralId).getState()).isEqualTo(ReferralState.Claimed);
        }

        @Test
        @DisplayName("403s a caseworker token naming the role, and sweeps nothing")
        void refusesACaseworker() throws Exception {
            String referralId = claimedBy(JUDGE_NAME);
            lapse(referralId);
            asCaseworker();

            // 403 and not an empty list: a caseworker has no personal judicial work, and answering "you have none"
            // would be a surface they had been admitted to. Their own worklists are the caseworker surface's.
            authorised(get(MINE))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value(containsString(Role.JUDICIAL.claimValue())));

            assertThat(stored(referralId).getState()).isEqualTo(ReferralState.Claimed);
        }

        @Test
        @DisplayName("403s an administrator token too, because judicial is not seniority")
        void refusesAnAdministrator() throws Exception {
            IDAM.stubUserInfo(StubIdam.SUBJECT, "An Admin", List.of(Role.DEEDPOLL_ADMIN.claimValue()));

            authorised(get(MINE)).andExpect(status().isForbidden());
        }
    }

    @Nested
    @DisplayName("GET /judicial/referrals/{referralId}")
    class ReadReferral {

        @Test
        @DisplayName("returns the referral, the application view and the case's notes")
        void returnsTheReferralAndItsApplication() throws Exception {
            String caseId = named(newCase(), "Robin");
            String referralId = raise(caseId, Urgency.Urgent);

            authorised(get(POOL + "/" + referralId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(referralId))
                .andExpect(jsonPath("$.state").value(ReferralState.Open.name()))
                .andExpect(jsonPath("$.reasons[0]").value(ReferralReason.Minor.name()))
                .andExpect(jsonPath("$.notes").value("Applicant is 14"))
                .andExpect(jsonPath("$.urgency").value(Urgency.Urgent.name()))
                .andExpect(jsonPath("$.urgencyReason").value("Hearing listed"))
                .andExpect(jsonPath("$.createdOn").exists())
                // Which application, what kind, and which name is changing to which.
                .andExpect(jsonPath("$.application.displayReference").exists())
                .andExpect(jsonPath("$.application.type").value(DeedPollType.Adult.name()))
                .andExpect(jsonPath("$.application.oldForename").value("Robin"))
                .andExpect(jsonPath("$.application.oldMiddleName").value("Peter"))
                .andExpect(jsonPath("$.application.oldSurname").value("Smith"))
                .andExpect(jsonPath("$.application.newForename").value("Robin"))
                .andExpect(jsonPath("$.application.newMiddleName").value("Peter"))
                .andExpect(jsonPath("$.application.newSurname").value("Smyth"))
                // The timeline, read-only -- the referral's own "raised" note is on it, by the caseworker who
                // raised it.
                .andExpect(jsonPath("$.caseNotes.length()").value(1))
                .andExpect(jsonPath("$.caseNotes[0].note").value(containsString("Referred to the judiciary")))
                .andExpect(jsonPath("$.caseNotes[0].createdBy").value("Ada Caseworker"))
                .andExpect(jsonPath("$.caseNotes[0].createdOn").exists());
        }

        @Test
        @DisplayName("returns a private case's details, witnesses and attached documents, as the business decided")
        void returnsThePrivateCaseInFull() throws Exception {
            String caseId = named(newCase(), "Robin");
            administrative(caseId);
            witnessed(caseId);
            String attached = documented(caseId, "Birth certificate, scanned", "stored-blob");
            documented(caseId, "Not arrived yet", null);
            String referralId = raise(caseId, Urgency.Normal);

            authorised(get(POOL + "/" + referralId))
                .andExpect(status().isOk())
                // Business decisions 1 and 2 of 2026-10-19: contact details and privacy, shown read-only.
                .andExpect(jsonPath("$.application.privacy").value(Privacy.Private.name()))
                .andExpect(jsonPath("$.application.applicantEmail").value("applicant@example.com"))
                .andExpect(jsonPath("$.application.applicantTelephone").value("020 7946 0000"))
                .andExpect(jsonPath("$.application.dateReceived").exists())
                // The witness, with no id.
                .andExpect(jsonPath("$.application.witnesses.length()").value(1))
                .andExpect(jsonPath("$.application.witnesses[0].witnessName").value("A Witness"))
                .andExpect(jsonPath("$.application.witnesses[0].declarant").value(true))
                .andExpect(jsonPath("$.application.witnesses[0].id").doesNotExist())
                // Only the document with a file: the row with none would be a link whose download 404s.
                .andExpect(jsonPath("$.application.documents.length()").value(1))
                .andExpect(jsonPath("$.application.documents[0].id").value(attached))
                .andExpect(jsonPath("$.application.documents[0].name").value("Birth certificate, scanned"))
                .andExpect(jsonPath("$.application.documents[0].documentType").value("Birth certificate"))
                .andExpect(jsonPath("$.application.documents[0].uploadedOn").exists())
                .andExpect(jsonPath("$.application.documents[0].storageKey").doesNotExist());
        }

        @Test
        @DisplayName("publishes none of the case's identity, payment or processing fields, and no entity")
        void publishesNoneOfTheExcludedFields() throws Exception {
            String caseId = named(newCase(), "Robin");
            administrative(caseId);
            String referralId = raise(caseId, Urgency.Normal);

            String body = authorised(get(POOL + "/" + referralId))
                .andExpect(status().isOk())
                // Design decision 1's exclusions, asserted on the response rather than trusted to the record's
                // field list: no case identity, no payment or refund data, no processing record.
                .andExpect(jsonPath("$.application.id").doesNotExist())
                .andExpect(jsonPath("$.application.referencenumber").doesNotExist())
                .andExpect(jsonPath("$.application.feeReference").doesNotExist())
                .andExpect(jsonPath("$.application.refundReference").doesNotExist())
                .andExpect(jsonPath("$.application.refundAmount").doesNotExist())
                .andExpect(jsonPath("$.application.dateOfRefund").doesNotExist())
                .andExpect(jsonPath("$.application.returnReason").doesNotExist())
                .andExpect(jsonPath("$.application.onlineApplicationReference").doesNotExist())
                .andExpect(jsonPath("$.application.postCounter").doesNotExist())
                .andExpect(jsonPath("$.application.currentReferralState").doesNotExist())
                .andExpect(jsonPath("$.application.awaitingConfirmationSince").doesNotExist())
                .andExpect(jsonPath("$.application.dateReferred").doesNotExist())
                .andExpect(jsonPath("$.application.createdOn").doesNotExist())
                .andExpect(jsonPath("$.application.createdBy").doesNotExist())
                .andExpect(jsonPath("$.application.modifiedBy").doesNotExist())
                .andExpect(jsonPath("$.application.notes").doesNotExist())
                // Named by neither list, so left out until the business answers.
                .andExpect(jsonPath("$.application.applicantAddress1").doesNotExist())
                // The referral's own audit trail goes the same way, and so does the sweep's bookkeeping.
                .andExpect(jsonPath("$.createdBy").doesNotExist())
                .andExpect(jsonPath("$.modifiedBy").doesNotExist())
                .andExpect(jsonPath("$.modifiedOn").doesNotExist())
                .andExpect(jsonPath("$.lastTouchedAt").doesNotExist())
                .andExpect(jsonPath("$.migrated").doesNotExist())
                .andReturn()
                .getResponse()
                .getContentAsString();

            // Belt and braces over the whole document rather than field by field: the values are what a
            // disclosure would consist of, and a field renamed on the way out would slip past a name assertion.
            assertThat(body)
                .doesNotContain("FEE-2026-1")
                .doesNotContain("REFUND-2026-1")
                .doesNotContain(caseId);
        }

        @Test
        @DisplayName("404s an unknown id, an id that is not a UUID, and says the same thing to both")
        void notFoundForAnUnknownId() throws Exception {
            authorised(get(POOL + "/" + UNKNOWN_ID))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Referral " + UNKNOWN_ID + " not found"));

            // Not a 400: it is an id nothing could ever have been stored under, and a 400 would tell a caller
            // probing ids which of their guesses were at least the right shape.
            authorised(get(POOL + "/not-a-uuid"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Referral not-a-uuid not found"));
        }

        @ParameterizedTest
        @EnumSource(value = ReferralState.class, names = {"Closed", "Withdrawn"})
        @DisplayName("404s a terminal referral, even one this judge directed")
        void notFoundForATerminalReferral(ReferralState terminal) throws Exception {
            String referralId = directedBy(JUDGE_NAME);
            moveTo(referralId, terminal);

            // The judiciary's involvement is over. "I directed it, so I may re-read the case for good" is the
            // reading this refuses, and it refuses it in the words an unknown id gets.
            authorised(get(POOL + "/" + referralId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Referral " + referralId + " not found"));
        }

        @Test
        @DisplayName("404s a referral another judge holds, indistinguishably from one that never existed")
        void notFoundForAnotherJudgesClaim() throws Exception {
            String theirs = claimedBy(OTHER_JUDGE_NAME);

            String held = authorised(get(POOL + "/" + theirs))
                .andExpect(status().isNotFound())
                .andReturn().getResponse().getContentAsString();
            String never = authorised(get(POOL + "/" + UNKNOWN_ID))
                .andExpect(status().isNotFound())
                .andReturn().getResponse().getContentAsString();

            // Byte for byte apart from the id the caller themselves sent: one judge cannot learn that another
            // holds a case, and a referral id cannot be probed for by comparing refusals.
            assertThat(held).isEqualTo(never.replace(UNKNOWN_ID, theirs));
        }

        @Test
        @DisplayName("404s a referral held by a judge of the same display name, over a real token")
        void notFoundForANamesakeOfTheHolder() throws Exception {
            String theirs = claimedBy(JUDGE_NAME);
            String theirDirection = directedBy(JUDGE_NAME);
            // The same display name, a different subject: a different person, and the token says so. This is the
            // disclosure the ownership migration removes, asserted where a caller actually stands -- before it, the
            // referral, the application behind it and its whole notes timeline came back with a 200.
            asJudge(JUDGE_NAME, NAMESAKE_JUDGE_SUBJECT);

            authorised(get(POOL + "/" + theirs))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Referral " + theirs + " not found"));
            authorised(get(POOL + "/" + theirDirection))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Referral " + theirDirection + " not found"));

            // And nothing of theirs moved: a refused read must not refresh somebody else's inactivity clock.
            assertThat(stored(theirs).getClaimedBy()).isEqualTo(JUDGE_NAME);
            assertThat(stored(theirs).getState()).isEqualTo(ReferralState.Claimed);
        }

        @Test
        @DisplayName("404s a referral another judge decided, and does not publish their name")
        void notFoundForAnotherJudgesDecision() throws Exception {
            String theirs = directedBy(OTHER_JUDGE_NAME);

            authorised(get(POOL + "/" + theirs))
                .andExpect(status().isNotFound())
                .andExpect(content().string(not(containsString(OTHER_JUDGE_NAME))));
        }

        @Test
        @DisplayName("returns this judge's own claimed referral, carrying their claim and nobody else's name")
        void returnsThisJudgesOwnClaim() throws Exception {
            String mine = claimedBy(JUDGE_NAME);

            authorised(get(POOL + "/" + mine))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value(ReferralState.Claimed.name()))
                .andExpect(jsonPath("$.claimedBy").value(JUDGE_NAME))
                .andExpect(jsonPath("$.claimedAt").exists());
        }

        @Test
        @DisplayName("returns this judge's own decided referral, carrying the direction they recorded")
        void returnsThisJudgesOwnDecision() throws Exception {
            String mine = directedBy(JUDGE_NAME);

            // Visibility includes a judge's own Decided referrals so they can re-read their direction while the
            // office actions it. There is no history endpoint; this is what makes the data reachable.
            authorised(get(POOL + "/" + mine))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value(ReferralState.Decided.name()))
                .andExpect(jsonPath("$.decision").value(MasterDecision.Approved.name()))
                .andExpect(jsonPath("$.directionsText").value("Granted, publication as usual"))
                .andExpect(jsonPath("$.publishToGazette").value(true))
                .andExpect(jsonPath("$.decisionDate").exists())
                .andExpect(jsonPath("$.decidedBy").value(JUDGE_NAME));
        }

        @Test
        @DisplayName("refreshes the claim's inactivity clock when the reading judge holds the referral")
        void refreshesTheClaimOfTheHoldingJudge() throws Exception {
            String mine = claimedBy(JUDGE_NAME);
            LocalDateTime staleButNotLapsed = backdateTouch(mine, 1);

            authorised(get(POOL + "/" + mine)).andExpect(status().isOk());

            // Decision 7: a judge reading the papers is working the case, so the clock restarts -- which is what
            // stops decision 6 firing on somebody who is plainly still engaged. An hour back rather than nine, so
            // this is the refresh and not the sweep.
            assertThat(stored(mine).getLastTouchedAt()).isAfter(staleButNotLapsed);
            assertThat(stored(mine).getState()).isEqualTo(ReferralState.Claimed);
        }

        @Test
        @DisplayName("touches nothing when the referral is one in the pool")
        void touchesNothingOnAPoolReferral() throws Exception {
            String waiting = raise(newCase(), Urgency.Normal);
            LocalDateTime modified = stored(waiting).getModifiedOn();

            authorised(get(POOL + "/" + waiting)).andExpect(status().isOk());

            // A pool referral is visible to every judge, so a read that stamped it would let any of them move a
            // row they have not claimed, and make looking indistinguishable from working.
            Referral unchanged = stored(waiting);
            assertThat(unchanged.getLastTouchedAt()).isNull();
            assertThat(unchanged.getModifiedOn()).isEqualTo(modified);
            assertThat(unchanged.getState()).isEqualTo(ReferralState.Open);
        }

        @Test
        @DisplayName("touches nothing on this judge's own decided referral, and is still a 200")
        void touchesNothingOnADecidedReferral() throws Exception {
            String mine = directedBy(JUDGE_NAME);
            LocalDateTime touched = stored(mine).getLastTouchedAt();

            // Held is narrower than visible: there is no claim left on a Decided referral, and calling the touch
            // transition on one would turn a legitimate read into a 409.
            authorised(get(POOL + "/" + mine)).andExpect(status().isOk());

            assertThat(stored(mine).getLastTouchedAt()).isEqualTo(touched);
        }
    }

    @Nested
    @DisplayName("POST /judicial/referrals/{referralId}/claim")
    class ClaimReferral {

        @Test
        @DisplayName("takes a pool referral, records the claim under this judge, and answers with the papers")
        void claimsAPoolReferral() throws Exception {
            String caseId = named(newCase(), "Robin");
            String referralId = raise(caseId, Urgency.Normal);

            authorised(post(POOL + "/" + referralId + "/claim"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(referralId))
                .andExpect(jsonPath("$.state").value(ReferralState.Claimed.name()))
                .andExpect(jsonPath("$.claimedBy").value(JUDGE_NAME))
                .andExpect(jsonPath("$.claimedAt").exists())
                // The same document the read gives, because the screen after a claim is the papers -- so a client
                // renders them without a second call.
                .andExpect(jsonPath("$.application.newSurname").value("Smyth"))
                // Two notes: the caseworker's "referred to the judiciary" and this claim's own, written by the
                // transition in the same request and therefore already on the timeline it answers with.
                .andExpect(jsonPath("$.caseNotes.length()").value(2))
                .andExpect(jsonPath("$.caseNotes[*].note",
                                    hasItem(containsString("claimed by the judiciary"))));

            Referral claimed = stored(referralId);
            assertThat(claimed.getState()).isEqualTo(ReferralState.Claimed);
            assertThat(claimed.getClaimedBy()).isEqualTo(JUDGE_NAME);
            // Stamped at the claim as well, so a referral picked up and never touched again lapses two hours after
            // it was picked up rather than never.
            assertThat(claimed.getLastTouchedAt()).isNotNull();
            // And the claim note is the judge's own, over a real token rather than a CurrentUser a test built:
            // createdBy is where the name lives, which is why the prose does not repeat it.
            assertThat(notes.findAll())
                .filteredOn(note -> note.getNote().contains("claimed by the judiciary"))
                .singleElement()
                .satisfies(note -> assertThat(note.getCreatedBy()).isEqualTo(JUDGE_NAME));
        }

        @Test
        @DisplayName("drops it off the shared pool, so no second judge is offered it")
        void takesItOutOfThePool() throws Exception {
            String referralId = raise(newCase(), Urgency.Normal);

            authorised(post(POOL + "/" + referralId + "/claim")).andExpect(status().isOk());

            authorised(get(POOL))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.referrals.length()").value(0))
                .andExpect(jsonPath("$.total").value(0));
        }

        @Test
        @DisplayName("404s a referral another judge holds, and leaves their claim alone")
        void notFoundForAnotherJudgesClaim() throws Exception {
            String theirs = claimedBy(OTHER_JUDGE_NAME);

            // 404 rather than the 409 a claimable-state check would give, because the visibility rule answers
            // first: this judge is not entitled to learn that the referral exists, let alone who has it.
            authorised(post(POOL + "/" + theirs + "/claim"))
                .andExpect(status().isNotFound())
                .andExpect(content().string(not(containsString(OTHER_JUDGE_NAME))));

            assertThat(stored(theirs).getClaimedBy()).isEqualTo(OTHER_JUDGE_NAME);
        }

        @Test
        @DisplayName("409s this judge's own claim rather than silently re-claiming it")
        void conflictsOnItsOwnClaim() throws Exception {
            String mine = claimedBy(JUDGE_NAME);

            // Visible to them and not Open, which is where a 409 lives on this surface: they are looking at it, so
            // a 404 would be a lie they cannot act on. And no silent re-claim -- two judges each shown "claimed by
            // me" for different reasons is worse than one being told plainly that somebody got there first.
            authorised(post(POOL + "/" + mine + "/claim"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value(containsString(ReferralState.Open.name())));

            assertThat(stored(mine).getState()).isEqualTo(ReferralState.Claimed);
            assertThat(stored(mine).getClaimedBy()).isEqualTo(JUDGE_NAME);
        }

        @Test
        @DisplayName("409s a referral this judge already directed, because a direction is not re-openable")
        void conflictsOnItsOwnDecision() throws Exception {
            String mine = directedBy(JUDGE_NAME);

            authorised(post(POOL + "/" + mine + "/claim")).andExpect(status().isConflict());

            assertThat(stored(mine).getState()).isEqualTo(ReferralState.Decided);
        }

        @ParameterizedTest
        @EnumSource(value = ReferralState.class, names = {"Closed", "Withdrawn"})
        @DisplayName("404s a terminal referral, in the words an unknown id gets")
        void notFoundForATerminalReferral(ReferralState terminal) throws Exception {
            String referralId = raise(newCase(), Urgency.Normal);
            moveTo(referralId, terminal);

            authorised(post(POOL + "/" + referralId + "/claim"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Referral " + referralId + " not found"));

            assertThat(stored(referralId).getState()).isEqualTo(terminal);
        }

        @Test
        @DisplayName("404s an unknown id and one that is not a UUID at all")
        void notFoundForAnUnknownId() throws Exception {
            authorised(post(POOL + "/" + UNKNOWN_ID + "/claim")).andExpect(status().isNotFound());
            authorised(post(POOL + "/not-a-uuid/claim")).andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("claims a referral whose previous claim has just lapsed, rather than answering 404")
        void claimsAReferralWhoseClaimHasLapsed() throws Exception {
            String lapsed = claimedBy(OTHER_JUDGE_NAME);
            lapse(lapsed);

            // The sweep runs before the visibility check for exactly this: the row is still Claimed by the other
            // judge, so a check-first order would refuse a referral the pool would have offered.
            authorised(post(POOL + "/" + lapsed + "/claim"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.claimedBy").value(JUDGE_NAME));

            assertThat(stored(lapsed).getClaimedBy()).isEqualTo(JUDGE_NAME);
            // And the lapse is on the timeline, because releasing a claim erases the fact it is about.
            assertThat(notes.findAll())
                .anySatisfy(note -> assertThat(note.getNote()).contains(OTHER_JUDGE_NAME));
        }

        @Test
        @DisplayName("401s without a token, and claims nothing")
        void refusesAnUnauthenticatedCaller() throws Exception {
            String referralId = raise(newCase(), Urgency.Normal);

            mockMvc.perform(post(POOL + "/" + referralId + "/claim")).andExpect(status().isUnauthorized());

            assertThat(stored(referralId).getState()).isEqualTo(ReferralState.Open);
        }

        @Test
        @DisplayName("403s a caseworker token naming the role, and claims nothing")
        void refusesACaseworker() throws Exception {
            String referralId = raise(newCase(), Urgency.Normal);
            asCaseworker();

            authorised(post(POOL + "/" + referralId + "/claim"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value(containsString(Role.JUDICIAL.claimValue())));

            assertThat(stored(referralId).getClaimedBy()).isNull();
        }
    }

    @Nested
    @DisplayName("POST /judicial/referrals/{referralId}/direction")
    class RecordDirection {

        /** The shortest complete direction: a decision, what was directed, and the Gazette answer. */
        private static final String APPROVAL = """
            {"decision": "Approved", "directionsText": "Granted", "publishToGazette": true}""";

        @Test
        @DisplayName("records the direction on a referral this judge holds, under their own name")
        void recordsADirection() throws Exception {
            String mine = claimedBy(JUDGE_NAME);

            authorised(post(POOL + "/" + mine + "/direction").contentType(APPLICATION_JSON).content(APPROVAL))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value(ReferralState.Decided.name()))
                .andExpect(jsonPath("$.decision").value(MasterDecision.Approved.name()))
                .andExpect(jsonPath("$.directionsText").value("Granted"))
                .andExpect(jsonPath("$.publishToGazette").value(true))
                .andExpect(jsonPath("$.decisionDate").exists())
                // From the token and the clock, never from the payload: a direction that could name its own author
                // would be a signature anybody could forge.
                .andExpect(jsonPath("$.decidedBy").value(JUDGE_NAME));

            Referral directed = stored(mine);
            assertThat(directed.getState()).isEqualTo(ReferralState.Decided);
            assertThat(directed.getDecidedBy()).isEqualTo(JUDGE_NAME);
            assertThat(directed.getPublishToGazette()).isTrue();
            // The direction note, authored by the deciding judge and a summary rather than a copy: the decision
            // and the Gazette answer are in it and the directions text is not, because a direction may be twice
            // as long as a note may be.
            assertThat(notes.findAll())
                .filteredOn(note -> note.getNote().contains("Judicial direction recorded"))
                .singleElement()
                .satisfies(note -> {
                    assertThat(note.getCreatedBy()).isEqualTo(JUDGE_NAME);
                    assertThat(note.getNote()).contains("Decision: " + MasterDecision.Approved.name(),
                                                        "Publish to the London Gazette: Yes");
                    assertThat(note.getNote()).doesNotContain("Granted");
                });
        }

        @Test
        @DisplayName("ignores a payload's attempt to name the state, the author or the date")
        void stripsWhatTheJudgeMayNotSet() throws Exception {
            String mine = claimedBy(JUDGE_NAME);

            authorised(post(POOL + "/" + mine + "/direction")
                           .contentType(APPLICATION_JSON)
                           .content("""
                               {"decision": "Rejected", "publishToGazette": false, "state": "Closed",
                                "decidedBy": "Somebody Else", "decisionDate": "2020-01-01T00:00:00",
                                "deedPollId": "forged"}"""))
                .andExpect(status().isOk())
                // Stripped rather than refused, as any unknown key is -- and each absence is a rule: a judge does
                // not close their own referral, and cannot sign one as anybody else.
                .andExpect(jsonPath("$.state").value(ReferralState.Decided.name()))
                .andExpect(jsonPath("$.decidedBy").value(JUDGE_NAME))
                .andExpect(jsonPath("$.decision").value(MasterDecision.Rejected.name()))
                .andExpect(jsonPath("$.publishToGazette").value(false));

            assertThat(stored(mine).getState()).isEqualTo(ReferralState.Decided);
        }

        @Test
        @DisplayName("400s a direction the schema refuses, naming every offending field at once")
        void refusesAnInvalidDirection() throws Exception {
            String mine = claimedBy(JUDGE_NAME);

            authorised(post(POOL + "/" + mine + "/direction")
                           .contentType(APPLICATION_JSON)
                           .content("""
                               {"decision": "approved", "publishToGazette": "false"}"""))
                .andExpect(status().isBadRequest())
                // Both wrong, both reported: a decision matched exactly rather than case-insensitively, and a
                // Gazette flag that is a boolean rather than a string read truthily.
                .andExpect(jsonPath("$.errors.length()").value(2))
                .andExpect(jsonPath("$.errors[*].field")
                               .value(containsInAnyOrder("decision", "publishToGazette")));

            assertThat(stored(mine).getState()).isEqualTo(ReferralState.Claimed);
            assertThat(stored(mine).getDecision()).isNull();
        }

        @Test
        @DisplayName("409s a pool referral this judge has not claimed, and records nothing")
        void conflictsOnAReferralItDoesNotHold() throws Exception {
            String waiting = raise(newCase(), Urgency.Normal);

            // Visible to every judge and held by none: directing it would write a direction onto an unclaimed
            // referral, so the transition's holder check answers instead.
            authorised(post(POOL + "/" + waiting + "/direction").contentType(APPLICATION_JSON).content(APPROVAL))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value(containsString("not claimed by " + JUDGE_NAME)));

            assertThat(stored(waiting).getDecision()).isNull();
            assertThat(stored(waiting).getState()).isEqualTo(ReferralState.Open);
        }

        @Test
        @DisplayName("404s a referral withdrawn under the judge who was holding it")
        void notFoundForAReferralWithdrawnUnderThem() throws Exception {
            String mine = claimedBy(JUDGE_NAME);
            moveTo(mine, ReferralState.Withdrawn);

            // Withdrawn is invisible, so this is the 404 -- which is the honest answer: the referral the judge was
            // reading has been pulled back, and there is nothing of it left for them to direct.
            authorised(post(POOL + "/" + mine + "/direction").contentType(APPLICATION_JSON).content(APPROVAL))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Referral " + mine + " not found"));

            assertThat(stored(mine).getDecision()).isNull();
        }

        @Test
        @DisplayName("404s a referral another judge holds, and records nothing on it")
        void notFoundForAnotherJudgesClaim() throws Exception {
            String theirs = claimedBy(OTHER_JUDGE_NAME);

            // 404 and not 409, because the visibility rule answers before the holder check: this judge is not
            // entitled to know the referral exists, let alone that somebody else has it.
            authorised(post(POOL + "/" + theirs + "/direction").contentType(APPLICATION_JSON).content(APPROVAL))
                .andExpect(status().isNotFound())
                .andExpect(content().string(not(containsString(OTHER_JUDGE_NAME))));

            assertThat(stored(theirs).getDecision()).isNull();
            assertThat(stored(theirs).getClaimedBy()).isEqualTo(OTHER_JUDGE_NAME);
        }

        @Test
        @DisplayName("leaves the direction visible to the judge who recorded it, and to nobody else")
        void leavesTheDirectionReadableByItsAuthor() throws Exception {
            String mine = claimedBy(JUDGE_NAME);
            authorised(post(POOL + "/" + mine + "/direction").contentType(APPLICATION_JSON).content(APPROVAL))
                .andExpect(status().isOk());

            authorised(get(POOL + "/" + mine))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.decision").value(MasterDecision.Approved.name()));

            // And the other judge cannot, which is what makes "a judge's own decided referrals" a rule about
            // ownership rather than about the state.
            asJudge(OTHER_JUDGE_NAME);
            authorised(get(POOL + "/" + mine)).andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("401s without a token, and records nothing")
        void refusesAnUnauthenticatedCaller() throws Exception {
            String mine = claimedBy(JUDGE_NAME);

            mockMvc.perform(post(POOL + "/" + mine + "/direction")
                                .contentType(APPLICATION_JSON)
                                .content(APPROVAL))
                .andExpect(status().isUnauthorized());

            assertThat(stored(mine).getDecision()).isNull();
        }

        @Test
        @DisplayName("403s a caseworker token, even on a referral they could name, and records nothing")
        void refusesACaseworker() throws Exception {
            String mine = claimedBy(JUDGE_NAME);
            asCaseworker();

            // A caseworker does not decide a referral, and the refusal is the role gate rather than the holder
            // check: closing an actioned direction is their half of the loop, recording one is not.
            authorised(post(POOL + "/" + mine + "/direction").contentType(APPLICATION_JSON).content(APPROVAL))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value(containsString(Role.JUDICIAL.claimValue())));

            assertThat(stored(mine).getDecision()).isNull();
        }
    }

    /** A stored case with a Date Received, which is all reference-number allocation needs. */
    private String newCase() {
        return deedPollService.createDeedPoll(DeedPollPatch.of(Map.of("dateReceived", RECEIVED)))
            .deedPoll()
            .getId();
    }

    /**
     * The case, with the names and the type a judge is shown.
     *
     * <p>Written onto the row rather than patched through the domain, as the referral fixtures in the caseworker
     * controller test are: what matters here is that a populated case is narrowed on the way out, not how it came
     * to be populated.
     */
    private String named(String caseId, String forename) {
        DeedPoll application = deedPolls.findById(caseId).orElseThrow();
        application.setType(DeedPollType.Adult);
        application.setOldForename(forename);
        application.setOldMiddleName("Peter");
        application.setOldSurname("Smith");
        application.setNewForename(forename);
        application.setNewMiddleName("Peter");
        application.setNewSurname("Smyth");
        deedPolls.saveAndFlush(application);
        return caseId;
    }

    /**
     * Contact details, privacy, and payment data, populated so that the presence of the first two and the
     * absence of the third from the response each mean something.
     */
    private void administrative(String caseId) {
        DeedPoll application = deedPolls.findById(caseId).orElseThrow();
        application.setFeeReference("FEE-2026-1");
        application.setApplicantEmail("applicant@example.com");
        application.setApplicantTelephone("020 7946 0000");
        application.setPrivacy(Privacy.Private);
        application.setRefundReference("REFUND-2026-1");
        deedPolls.saveAndFlush(application);
    }

    /** One witness on the case, written onto the table as the case fixtures are. */
    private void witnessed(String caseId) {
        DeedPollWitness witness = new DeedPollWitness();
        witness.setDeedPoll(deedPolls.findById(caseId).orElseThrow());
        witness.setWitnessName("A Witness");
        witness.setWitnessAddress("1 High Street");
        witness.setWitnessOccupation("Teacher");
        witness.setDeclarant(true);
        witnesses.saveAndFlush(witness);
    }

    /**
     * A supporting document on the case, with a storage key or with none.
     *
     * <p>A key and no blob is enough here: the listing applies the download's file and store checks without
     * opening anything, and the download itself is another test's.
     */
    private String documented(String caseId, String name, String storageKey) {
        DeedPollSupportingDocumentType type = documentTypes.findAll().stream().findFirst().orElseGet(() -> {
            DeedPollSupportingDocumentType created = new DeedPollSupportingDocumentType();
            created.setName("Birth certificate");
            return documentTypes.saveAndFlush(created);
        });
        DeedPollSupportingDocument document = new DeedPollSupportingDocument();
        document.setDeedPoll(deedPolls.findById(caseId).orElseThrow());
        document.setDocumentType(type);
        document.setDirection(DocumentDirection.Incoming);
        document.setName(name);
        document.setStorageKey(storageKey);
        document.setMimeType(storageKey == null ? null : "application/pdf");
        return documents.saveAndFlush(document).getId();
    }

    /**
     * A referral raised on a case by a caseworker, which is the only way one is raised.
     *
     * <p>Through the domain rather than over HTTP, unlike the caseworker controller test's fixtures: raising is
     * the caseworker surface's operation and this class holds a judicial token, so going through the route would
     * mean re-stubbing IDAM for every fixture.
     */
    private String raise(String caseId, Urgency urgency) {
        CurrentUser caseworker = CurrentUser.currentUser(List.of(), "Ada Caseworker", "caseworker-1");
        Referral raised = referralService.createReferral(
            caseId,
            new CreateReferralInput(
                List.of(ReferralReason.Minor),
                Optional.of("Applicant is 14"),
                urgency,
                urgency == Urgency.Urgent ? Optional.of("Hearing listed") : Optional.empty()),
            caseworker);
        return raised.getId().toString();
    }

    /** A referral on a case of its own, claimed by the named judge through the api-2 transition. */
    private String claimedBy(String judge) {
        return claimedBy(judge, Urgency.Normal);
    }

    /** The same, at a chosen urgency, for the orderings in which urgency is the key above age. */
    private String claimedBy(String judge, Urgency urgency) {
        String caseId = newCase();
        String referralId = raise(caseId, urgency);
        referralService.claim(caseId, referralId, judicial(judge));
        return referralId;
    }

    /**
     * A referral on a case of its own, claimed and then directed by the named judge.
     *
     * <p>The case id is threaded through rather than read back off the stored referral, because
     * {@code Referral.deedPoll} is lazy and a fixture outside a transaction has no session to resolve it in.
     */
    private String directedBy(String judge) {
        return directedBy(judge, Urgency.Normal);
    }

    /** The same, at a chosen urgency. */
    private String directedBy(String judge, Urgency urgency) {
        String caseId = newCase();
        String referralId = raise(caseId, urgency);
        referralService.claim(caseId, referralId, judicial(judge));
        referralService.decide(
            caseId,
            referralId,
            new DirectionInput(MasterDecision.Approved, Optional.of("Granted, publication as usual"), true),
            judicial(judge));
        return referralId;
    }

    /**
     * Strips the owner subjects off every stored referral, leaving the display names behind.
     *
     * <p>The pre-migration row {@code V14} cannot backfill: a {@code Claimed} or {@code Decided} referral whose
     * {@code claimedBy} names a judge and whose {@code claimedById} is null, because the claim was made before the
     * column existed. There is no way to produce one through the transitions -- which is the point -- so it is
     * written onto the row.
     */
    private void disownAll() {
        referrals.findAll().forEach(referral -> {
            referral.setClaimedById(null);
            referral.setDecidedById(null);
            referrals.saveAndFlush(referral);
        });
    }

    /** A withdrawn referral on a case of its own, so the pool has something terminal to leave out. */
    private void withdrawn() {
        String caseId = newCase();
        String referralId = raise(caseId, Urgency.Normal);
        referralService.withdrawReferral(
            caseId, referralId, CurrentUser.currentUser(List.of(), "Ada Caseworker", "caseworker-1"));
    }

    /**
     * A judge as the domain sees one. Used only by the fixtures, which reach the transitions directly -- the
     * requests under test carry a real token instead.
     *
     * <p>The subject matters here, and it is the one thing the fixtures and the token have to agree on: a claim is
     * owned by the subject it was made under, so a fixture claiming as a name alone would produce a referral the
     * request under test does not own. The display name is carried too because it is what the response publishes.
     */
    private static CurrentUser judicial(String name) {
        return CurrentUser.currentUser(List.of(Role.JUDICIAL), name, subjectOf(name));
    }

    /** Which judge a fixture name means, as a subject. Two judges, two subjects, and never a name as an identity. */
    private static String subjectOf(String name) {
        return JUDGE_NAME.equals(name) ? JUDGE_SUBJECT : OTHER_JUDGE_SUBJECT;
    }

    private Referral stored(String referralId) {
        return referrals.findById(UUID.fromString(referralId)).orElseThrow();
    }

    /**
     * Backdates a claim past any configured expiry window, so the next sweep takes it back.
     *
     * <p>Off the row's own {@code claimedAt} rather than off {@code LocalDateTime.now()}, so the arithmetic
     * cannot depend on which clock the test and the service each read.
     */
    private LocalDateTime lapse(String referralId) {
        return backdateTouch(referralId, 9);
    }

    /** Moves a claim's last interaction the given number of hours into the past, and says where it landed. */
    private LocalDateTime backdateTouch(String referralId, int hours) {
        Referral referral = stored(referralId);
        referral.setLastTouchedAt(referral.getClaimedAt().minusHours(hours));
        referrals.saveAndFlush(referral);
        return referral.getLastTouchedAt();
    }

    /** Backdates a referral, so an ordering assertion does not depend on the clock's resolution. */
    private void backdate(String referralId, LocalDateTime createdOn) {
        Referral referral = stored(referralId);
        referral.setCreatedOn(createdOn);
        referrals.saveAndFlush(referral);
    }

    /**
     * Puts a referral into a given state directly, for the tests that are about visibility rather than about how
     * the state was reached.
     */
    private void moveTo(String referralId, ReferralState state) {
        Referral referral = stored(referralId);
        referral.setState(state);
        referrals.saveAndFlush(referral);
    }

    /** The token the requests carry is a judge's -- whichever of the two fixture judges is named. */
    private static void asJudge(String name) {
        asJudge(name, subjectOf(name));
    }

    /**
     * The token the requests carry is a judge's, under a subject the caller chooses.
     *
     * <p>The subject is separable from the name here because that separation is what the ownership migration is
     * about: the same display name under a different subject is a different judge, and asserting that over real
     * HTTP needs a token that says so.
     */
    private static void asJudge(String name, String subject) {
        tokenSubject = subject;
        IDAM.stubUserInfo(subject, name, List.of(Role.JUDICIAL.claimValue()));
    }

    /** The token the requests carry is an ordinary caseworker's, holding no role this surface asks about. */
    private static void asCaseworker() {
        tokenSubject = StubIdam.SUBJECT;
        IDAM.stubUserInfo(StubIdam.SUBJECT, "Ada Caseworker", List.of("caseworker-civil"));
    }

    /**
     * The request, carrying a valid forwarded access token for whoever the userinfo stub currently describes.
     *
     * <p>The subject is put on the token rather than left at the stub's default, because
     * {@code IdamUserInfoClient} refuses a userinfo document whose {@code sub} disagrees with the token's -- so
     * switching judges means minting a token for the judge being switched to.
     */
    private ResultActions authorised(MockHttpServletRequestBuilder request) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(IDAM.accessToken(claims -> claims.subject(tokenSubject)));
        return mockMvc.perform(request.headers(headers));
    }
}
