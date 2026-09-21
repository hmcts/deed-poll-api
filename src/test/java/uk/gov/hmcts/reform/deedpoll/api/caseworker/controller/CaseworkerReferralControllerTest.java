package uk.gov.hmcts.reform.deedpoll.api.caseworker.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import uk.gov.hmcts.reform.deedpoll.auth.StubIdam;
import uk.gov.hmcts.reform.deedpoll.config.AbstractPostgresContainerTest;
import uk.gov.hmcts.reform.deedpoll.domain.ConflictError;
import uk.gov.hmcts.reform.deedpoll.domain.auth.CurrentUser;
import uk.gov.hmcts.reform.deedpoll.domain.auth.Role;
import uk.gov.hmcts.reform.deedpoll.domain.deedpoll.DeedPollPatch;
import uk.gov.hmcts.reform.deedpoll.domain.deedpoll.DeedPollService;
import uk.gov.hmcts.reform.deedpoll.domain.referrals.DirectionInput;
import uk.gov.hmcts.reform.deedpoll.domain.referrals.ReferralReason;
import uk.gov.hmcts.reform.deedpoll.domain.referrals.ReferralService;
import uk.gov.hmcts.reform.deedpoll.entity.Referral;
import uk.gov.hmcts.reform.deedpoll.entity.enums.MasterDecision;
import uk.gov.hmcts.reform.deedpoll.entity.enums.ReferralState;
import uk.gov.hmcts.reform.deedpoll.entity.enums.Urgency;
import uk.gov.hmcts.reform.deedpoll.repository.DeedPollNoteRepository;
import uk.gov.hmcts.reform.deedpoll.repository.DeedPollRepository;
import uk.gov.hmcts.reform.deedpoll.repository.ReferenceCounterRepository;
import uk.gov.hmcts.reform.deedpoll.repository.ReferralRepository;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The five caseworker referral endpoints, over HTTP, against a real database and a stub IDAM.
 *
 * <p>The whole context rather than a {@code @WebMvcTest} slice, and not {@code @Transactional}, for the reasons
 * {@code CaseworkerNoteControllerTest} records: a slice does not get {@code SecurityConfiguration}, so it would
 * assert Spring Boot's default security rather than this service's, and the writes have to really commit for an
 * assertion to read the stored row back.
 *
 * <p><strong>What is here is what only a booted service can show</strong>, and deliberately not the rules
 * themselves. The reason validation, the one-live-referral guard and the withdrawable-state guard are pinned in
 * {@code ReferralSchemasTest} and {@code ReferralServiceTest}, which need no container and therefore run on the
 * Jenkins agent, where this class is excluded. This class asserts the routes are wired to those rules, which
 * status code each answer becomes, and that a referral is published as a DTO rather than as the entity.
 *
 * <p><strong>Two case fixtures throughout, not one.</strong> Every operation here is scoped to a parent, and a
 * test with one case cannot tell a correctly scoped lookup from an unscoped one -- both answer the same thing.
 * So the second case exists to be the wrong parent, and the scoping tests assert both halves: the referral is
 * not reachable through it, and the row that was addressed did not move.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CaseworkerReferralControllerTest extends AbstractPostgresContainerTest {

    /** createDeedPoll cannot allocate a reference number without a Date Received to derive the year from. */
    private static final String RECEIVED = "2026-07-01T00:00:00.000Z";

    /** An id of the right shape that nothing was ever stored under. */
    private static final String UNKNOWN_ID = "11111111-1111-1111-1111-111111111111";

    /** The smallest acceptable create body: one reason, and nothing that makes another field required. */
    private static final String ONE_REASON = "{\"reasons\":[\"Minor\"]}";

    /**
     * The judge the fixtures claim and direct as.
     *
     * <p>Not a token holder. {@code Role.JUDICIAL} is not issued by IDAM yet and the two transitions that need
     * it have no route here at all, so the fixture calls them on the service with a {@code CurrentUser} carrying
     * the role -- which is what lets this class reach a {@code Decided} referral the way a real one is reached,
     * rather than by writing the state into the row.
     */
    private static final String JUDGE_NAME = "District Judge Bell";

    /** Only to read the id back out of a create response, which is how a fixture addresses what it raised. */
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

    /**
     * Only to reach a Decided referral through the judicial transitions, which have no route here.
     */
    @Autowired
    private ReferralService referralService;

    @Autowired
    private DeedPollNoteRepository notes;

    @Autowired
    private ReferenceCounterRepository referenceCounters;

    @BeforeEach
    @AfterEach
    void clearTables() {
        referrals.deleteAll();
        notes.deleteAll();
        deedPolls.deleteAll();
        referenceCounters.deleteAll();
    }

    @BeforeEach
    void resetIdam() {
        IDAM.stubDefaults();
    }

    @Nested
    @DisplayName("without a token")
    class Unauthenticated {

        @Test
        @DisplayName("refuses to list a case's referrals")
        void refusesAList() throws Exception {
            mockMvc.perform(get("/caseworker/cases/" + newCase() + "/referrals"))
                .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("refuses to raise a referral, and writes nothing")
        void refusesACreate() throws Exception {
            mockMvc.perform(post("/caseworker/cases/" + newCase() + "/referrals")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(ONE_REASON))
                .andExpect(status().isUnauthorized());

            // The refusal has to happen before the handler, not inside it: a 401 that still wrote the row would
            // be the worst of both answers, and it would put a referral into the shared pool.
            assertThat(referrals.count()).isZero();
            assertThat(notes.count()).isZero();
        }

        @Test
        @DisplayName("refuses to withdraw a referral, and it stays open")
        void refusesAWithdrawal() throws Exception {
            String caseId = newCase();
            String referralId = raise(caseId, ONE_REASON);

            mockMvc.perform(post("/caseworker/cases/" + caseId + "/referrals/" + referralId + "/withdrawal"))
                .andExpect(status().isUnauthorized());

            assertThat(stored(referralId).getState()).isEqualTo(ReferralState.Open);
        }

        @Test
        @DisplayName("refuses to close a referral, and it stays Decided")
        void refusesAClosure() throws Exception {
            String caseId = newCase();
            String referralId = raise(caseId, ONE_REASON);
            moveTo(referralId, ReferralState.Decided);

            // The route is protected by having been written rather than by having been annotated -- there is no
            // declaration on the handler saying so -- which is exactly why every new route gets one of these.
            mockMvc.perform(post("/caseworker/cases/" + caseId + "/referrals/" + referralId + "/closure"))
                .andExpect(status().isUnauthorized());

            assertThat(stored(referralId).getState()).isEqualTo(ReferralState.Decided);
        }
    }

    @Nested
    @DisplayName("GET /caseworker/cases/{id}/referrals")
    class ListReferrals {

        @Test
        @DisplayName("lists the case's referrals, newest first")
        void listsNewestFirst() throws Exception {
            String caseId = newCase();
            String older = raise(caseId, ONE_REASON);
            withdraw(caseId, older);
            // A second referral is only permitted because the first is terminal, which is the one-live rule seen
            // from the permitting side -- and the whole reason a case can have a list to order at all.
            String newer = raise(caseId, "{\"reasons\":[\"Other\"],\"notes\":\"Needs a Master\"}");
            // Backdated rather than trusting two inserts to land in different microseconds, which is what the
            // notes controller test does and for the same reason.
            backdate(older, LocalDateTime.of(2026, 7, 1, 9, 0));
            backdate(newer, LocalDateTime.of(2026, 7, 2, 9, 0));

            authorised(get("/caseworker/cases/" + caseId + "/referrals"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(newer))
                .andExpect(jsonPath("$[0].state").value(ReferralState.Open.name()))
                .andExpect(jsonPath("$[1].id").value(older))
                .andExpect(jsonPath("$[1].state").value(ReferralState.Withdrawn.name()));
        }

        @Test
        @DisplayName("404s for a case that does not exist, rather than answering an empty list")
        void notFoundForAMissingCase() throws Exception {
            // Filtering by the parent id alone gives [] both for a mistyped case id and for a real case that was
            // never referred, and only one of those is true.
            authorised(get("/caseworker/cases/" + UNKNOWN_ID + "/referrals"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("DeedPoll " + UNKNOWN_ID + " not found"));
        }

        @Test
        @DisplayName("answers an empty list for a real case that has never been referred")
        void emptyListForACaseWithNoReferrals() throws Exception {
            // The other half of the pair above. Without this the 404 could be coming from something that refuses
            // every list, and "the case exists and has no referrals" would be unrepresentable.
            authorised(get("/caseworker/cases/" + newCase() + "/referrals"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
        }

        @Test
        @DisplayName("lists only the referrals of the case in the path")
        void listsOnlyThatCasesReferrals() throws Exception {
            String caseId = newCase();
            String otherCaseId = newCase();
            raise(caseId, "{\"reasons\":[\"Minor\"],\"notes\":\"Ours\"}");
            raise(otherCaseId, "{\"reasons\":[\"Minor\"],\"notes\":\"Theirs\"}");

            authorised(get("/caseworker/cases/" + caseId + "/referrals"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].notes").value("Ours"));
        }
    }

    @Nested
    @DisplayName("POST /caseworker/cases/{id}/referrals")
    class CreateReferral {

        @Test
        @DisplayName("raises the referral Open, attributes it to the caller and answers 201")
        void raisesTheReferral() throws Exception {
            String caseId = newCase();

            authorised(post("/caseworker/cases/" + caseId + "/referrals"),
                "{\"reasons\":[\"Minor\",\"DomesticAbuse\"],\"notes\":\"Applicant is 14\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").exists())
                // Open and unclaimed: the state is not the caller's to choose and there is no target judge.
                .andExpect(jsonPath("$.state").value(ReferralState.Open.name()))
                // In the order they were ticked, so a round trip renders the checkboxes as the caseworker left
                // them.
                .andExpect(jsonPath("$.reasons.length()").value(2))
                .andExpect(jsonPath("$.reasons[0]").value(ReferralReason.Minor.name()))
                .andExpect(jsonPath("$.reasons[1]").value(ReferralReason.DomesticAbuse.name()))
                .andExpect(jsonPath("$.notes").value("Applicant is 14"))
                // Defaulted rather than demanded: most referrals are not urgent.
                .andExpect(jsonPath("$.urgency").value(Urgency.Normal.name()))
                .andExpect(jsonPath("$.urgencyReason").doesNotExist())
                // Raised by a caseworker, so not migrated -- the flag exists to explain an empty reason set.
                .andExpect(jsonPath("$.migrated").value(false))
                // From the token, never from the body.
                .andExpect(jsonPath("$.createdBy").value("A Caseworker"))
                .andExpect(jsonPath("$.modifiedBy").value("A Caseworker"));

            assertThat(referrals.findAll()).singleElement().satisfies(referral -> {
                assertThat(referral.getState()).isEqualTo(ReferralState.Open);
                assertThat(referral.getReasons()).containsExactly("Minor", "DomesticAbuse");
                assertThat(referral.getDeedPoll().getId()).isEqualTo(caseId);
                assertThat(referral.isMigrated()).isFalse();
            });
        }

        @Test
        @DisplayName("publishes the referral as a DTO, not the entity behind it")
        void publishesADto() throws Exception {
            // A referral carrying its case, which carries its referrals, is a cycle a serialiser has to be told
            // about -- and the caller already knows which case it asked for. The read is one round trip for the
            // referral: the case detail endpoint is where the application's own data lives.
            authorised(post("/caseworker/cases/" + newCase() + "/referrals"), ONE_REASON)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.deedPoll").doesNotExist())
                .andExpect(jsonPath("$.deedPollId").doesNotExist())
                .andExpect(jsonPath("$.newForename").doesNotExist())
                .andExpect(jsonPath("$.createdOn").exists())
                .andExpect(jsonPath("$.modifiedOn").exists());
        }

        @Test
        @DisplayName("writes a case note attributed to the acting caseworker, not the service account")
        void writesACaseNote() throws Exception {
            // Decision 9: there is no timeline concept in this domain beyond the notes grid, so the referral has
            // to appear where caseworkers already look. Authored by the caller, unlike the system notes the
            // directions plans will write.
            String caseId = newCase();

            authorised(post("/caseworker/cases/" + caseId + "/referrals"),
                "{\"reasons\":[\"Minor\"],\"urgency\":\"Urgent\",\"urgencyReason\":\"Hearing on Friday\"}")
                .andExpect(status().isCreated());

            assertThat(notes.findAll()).singleElement().satisfies(note -> {
                assertThat(note.getNote()).contains("Referred to the judiciary", "Minor", "Hearing on Friday");
                assertThat(note.getCreatedBy()).isEqualTo("A Caseworker");
                assertThat(note.getDeedPoll().getId()).isEqualTo(caseId);
            });
        }

        @Test
        @DisplayName("409s a second referral while one is still live, and writes nothing")
        void refusesASecondLiveReferral() throws Exception {
            String caseId = newCase();
            raise(caseId, ONE_REASON);

            authorised(post("/caseworker/cases/" + caseId + "/referrals"), ONE_REASON)
                // 409 and not 400: nothing about the payload is wrong, and the same request succeeds unchanged
                // once the live referral is closed or withdrawn.
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("Case " + caseId
                    + " already has a referral that has not been closed or withdrawn"));

            assertThat(referrals.findByDeedPollIdOrderByCreatedOnDesc(caseId)).hasSize(1);
            // And no note claiming a referral was raised: the note is written inside the same transaction, so a
            // refused referral leaves no trace of one.
            assertThat(notes.count()).isOne();
        }

        @Test
        @DisplayName("409s while an existing referral is Claimed or Decided, not only while it is Open")
        void refusesWhileClaimedOrDecided() throws Exception {
            // The guard asks ReferralState.isTerminal() rather than naming Open, which is what makes the states
            // the directions plans add already covered. Asserted here because a route wired to a narrower guard
            // would pass every other test in this class.
            //
            // Derived from isTerminal() and deliberately NOT from canBeWithdrawn(): this is the one-live-referral
            // rule, not the withdrawal rule, and Decided parts the two -- a decided referral may not be
            // withdrawn and nonetheless still blocks a second one, because the case holds it until the direction
            // has been actioned.
            for (ReferralState live : Arrays.stream(ReferralState.values())
                .filter(state -> !state.isTerminal())
                .toList()) {

                String caseId = newCase();
                String referralId = raise(caseId, ONE_REASON);
                moveTo(referralId, live);

                authorised(post("/caseworker/cases/" + caseId + "/referrals"), ONE_REASON)
                    .andExpect(status().isConflict());
            }
        }

        @Test
        @DisplayName("permits a fresh referral once every existing one is terminal")
        void permitsAReferralAfterAWithdrawal() throws Exception {
            String caseId = newCase();
            withdraw(caseId, raise(caseId, ONE_REASON));

            authorised(post("/caseworker/cases/" + caseId + "/referrals"), ONE_REASON)
                .andExpect(status().isCreated());

            assertThat(referrals.findByDeedPollIdOrderByCreatedOnDesc(caseId)).hasSize(2);
        }

        @Test
        @DisplayName("400s a body with no reasons, attributed to the field a form renders")
        void refusesABodyWithNoReasons() throws Exception {
            // The rules themselves are ReferralSchemasTest's; what this asserts is that the route reaches them
            // and that a refusal is the repo's 400 shape rather than a Jackson message about a Java type.
            authorised(post("/caseworker/cases/" + newCase() + "/referrals"), "{}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("reasons"));

            assertThat(referrals.count()).isZero();
            assertThat(notes.count()).isZero();
        }

        @Test
        @DisplayName("400s an unknown reason rather than storing a name nothing matches")
        void refusesAnUnknownReason() throws Exception {
            authorised(post("/caseworker/cases/" + newCase() + "/referrals"), "{\"reasons\":[\"Whatever\"]}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("reasons"));

            assertThat(referrals.count()).isZero();
        }

        @Test
        @DisplayName("400s Other with no notes, and Urgent with no urgencyReason, naming both at once")
        void refusesTheConditionalFieldsTogether() throws Exception {
            // Both failures in one answer, which is what saves the caseworker a round trip per mistake.
            authorised(post("/caseworker/cases/" + newCase() + "/referrals"),
                "{\"reasons\":[\"" + ReferralReason.Other + "\"],\"urgency\":\"" + Urgency.Urgent + "\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.length()").value(2))
                .andExpect(jsonPath("$.errors[0].field").value("notes"))
                .andExpect(jsonPath("$.errors[1].field").value("urgencyReason"));

            assertThat(referrals.count()).isZero();
        }

        @Test
        @DisplayName("strips a state and a migrated flag a caller sent instead of honouring them")
        void stripsTheFieldsThatAreNotTheCallers() throws Exception {
            // Structural rather than checked: CreateReferralInput has nowhere to put either, which is what stops
            // a caller raising a referral that is already Closed and so never reaches the pool.
            authorised(post("/caseworker/cases/" + newCase() + "/referrals"),
                "{\"reasons\":[\"Minor\"],\"state\":\"Closed\",\"migrated\":true,\"masterName\":\"A Master\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.state").value(ReferralState.Open.name()))
                .andExpect(jsonPath("$.migrated").value(false));

            assertThat(referrals.findAll()).singleElement().satisfies(referral -> {
                assertThat(referral.getState()).isEqualTo(ReferralState.Open);
                assertThat(referral.isMigrated()).isFalse();
            });
        }

        @Test
        @DisplayName("404s for a case that does not exist, and writes nothing")
        void notFoundForAMissingCase() throws Exception {
            authorised(post("/caseworker/cases/" + UNKNOWN_ID + "/referrals"), ONE_REASON)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("DeedPoll " + UNKNOWN_ID + " not found"));

            assertThat(referrals.count()).isZero();
        }

        @Test
        @DisplayName("400s in the service's own error shape for a body that is not JSON")
        void refusesAnUnreadableBody() throws Exception {
            authorised(post("/caseworker/cases/" + newCase() + "/referrals"), "{ not json")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Request body is missing or is not valid JSON"))
                .andExpect(jsonPath("$.errors").doesNotExist());
        }
    }

    @Nested
    @DisplayName("GET /caseworker/cases/{id}/referrals/{referralId}")
    class ReadReferral {

        @Test
        @DisplayName("returns the referral")
        void returnsTheReferral() throws Exception {
            String caseId = newCase();
            String referralId = raise(caseId,
                "{\"reasons\":[\"NonPublication\"],\"urgency\":\"Urgent\",\"urgencyReason\":\"Listed Friday\"}");

            authorised(get("/caseworker/cases/" + caseId + "/referrals/" + referralId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(referralId))
                .andExpect(jsonPath("$.reasons.length()").value(1))
                .andExpect(jsonPath("$.reasons[0]").value(ReferralReason.NonPublication.name()))
                .andExpect(jsonPath("$.urgency").value(Urgency.Urgent.name()))
                .andExpect(jsonPath("$.urgencyReason").value("Listed Friday"));
        }

        @Test
        @DisplayName("404s for a referral read through another case, so the scope is both ids")
        void notFoundThroughAnotherCase() throws Exception {
            String caseId = newCase();
            String otherCaseId = newCase();
            String referralId = raise(caseId, ONE_REASON);

            authorised(get("/caseworker/cases/" + otherCaseId + "/referrals/" + referralId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Referral " + referralId + " not found"));
        }

        @Test
        @DisplayName("404s for a referral that does not exist")
        void notFoundForAMissingReferral() throws Exception {
            authorised(get("/caseworker/cases/" + newCase() + "/referrals/" + UNKNOWN_ID))
                .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("404s an id that is not a uuid at all, rather than 400ing on the shape")
        void notFoundForAnIdThatIsNotAUuid() throws Exception {
            // It is an id nothing could ever have been stored under, so "not found" is the true answer -- and a
            // 400 would make the path a probe for which ids are well formed.
            authorised(get("/caseworker/cases/" + newCase() + "/referrals/not-a-uuid"))
                .andExpect(status().isNotFound());
        }
    }

    @Nested
    @DisplayName("POST /caseworker/cases/{id}/referrals/{referralId}/withdrawal")
    class WithdrawReferral {

        @Test
        @DisplayName("moves the referral to Withdrawn, answers 200 with it, and re-stamps the modifier")
        void withdrawsTheReferral() throws Exception {
            String caseId = newCase();
            String referralId = raise(caseId, ONE_REASON);
            IDAM.stubUserInfo(StubIdam.SUBJECT, "Another Caseworker", List.of("caseworker-civil"));

            authorised(post("/caseworker/cases/" + caseId + "/referrals/" + referralId + "/withdrawal"))
                // 200 with the row rather than 204: it survives the transition, and its new state is the answer a
                // client re-rendering the tab needs.
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(referralId))
                .andExpect(jsonPath("$.state").value(ReferralState.Withdrawn.name()))
                .andExpect(jsonPath("$.modifiedBy").value("Another Caseworker"))
                // Withdrawing does not rewrite who raised it.
                .andExpect(jsonPath("$.createdBy").value("A Caseworker"));

            Referral withdrawn = stored(referralId);
            assertThat(withdrawn.getState()).isEqualTo(ReferralState.Withdrawn);
            assertThat(withdrawn.getModifiedBy()).isEqualTo("Another Caseworker");
            assertThat(withdrawn.getCreatedBy()).isEqualTo("A Caseworker");
        }

        @Test
        @DisplayName("keeps the row rather than deleting it, so the case's history still shows the referral")
        void doesNotDeleteTheRow() throws Exception {
            String caseId = newCase();
            String referralId = raise(caseId, ONE_REASON);

            authorised(post("/caseworker/cases/" + caseId + "/referrals/" + referralId + "/withdrawal"))
                .andExpect(status().isOk());

            // Withdrawn and not Closed: closed means a direction was actioned, and a report that could not tell
            // them apart would count abandoned referrals as work done.
            assertThat(referrals.findByDeedPollIdOrderByCreatedOnDesc(caseId)).singleElement()
                .extracting(Referral::getState)
                .isEqualTo(ReferralState.Withdrawn);
        }

        @Test
        @DisplayName("writes a second case note, naming when the referral was raised and what for")
        void writesACaseNote() throws Exception {
            String caseId = newCase();
            String referralId = raise(caseId, ONE_REASON);

            authorised(post("/caseworker/cases/" + caseId + "/referrals/" + referralId + "/withdrawal"))
                .andExpect(status().isOk());

            assertThat(notes.findAll()).hasSize(2);
            assertThat(notes.findAll()).anySatisfy(note -> {
                assertThat(note.getNote()).contains("withdrawn", "Minor");
                assertThat(note.getCreatedBy()).isEqualTo("A Caseworker");
            });
        }

        @Test
        @DisplayName("409s withdrawing the same referral twice, rather than answering 200 again")
        void refusesASecondWithdrawal() throws Exception {
            String caseId = newCase();
            String referralId = raise(caseId, ONE_REASON);

            authorised(post("/caseworker/cases/" + caseId + "/referrals/" + referralId + "/withdrawal"))
                .andExpect(status().isOk());

            // Not idempotent-and-silent: a second withdrawal is a caseworker acting on a stale screen, and a
            // success would tell them their click did something it did not.
            authorised(post("/caseworker/cases/" + caseId + "/referrals/" + referralId + "/withdrawal"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("Referral " + referralId + " is already "
                    + ReferralState.Withdrawn + " and cannot be withdrawn"));

            // And no third note: the refusal happens before anything is written.
            assertThat(notes.count()).isEqualTo(2);
        }

        @Test
        @DisplayName("409s withdrawing a Closed referral, so the guard is not Withdrawn alone")
        void refusesWithdrawingAClosedReferral() throws Exception {
            String caseId = newCase();
            String referralId = raise(caseId, ONE_REASON);
            moveTo(referralId, ReferralState.Closed);

            authorised(post("/caseworker/cases/" + caseId + "/referrals/" + referralId + "/withdrawal"))
                .andExpect(status().isConflict());

            assertThat(stored(referralId).getState()).isEqualTo(ReferralState.Closed);
        }

        @Test
        @DisplayName("withdraws a referral a judge is holding, and their late direction is then refused")
        void withdrawsAReferralAJudgeIsHolding() throws Exception {
            // Claimed is still withdrawable, and this is the route saying so: the office may need to correct or
            // cancel a referral while the papers are out, and the judge has recorded nothing yet. The rule the
            // next test states starts at the direction, not at the claim.
            String caseId = newCase();
            String referralId = raise(caseId, ONE_REASON);
            referralService.claim(caseId, referralId, judge());

            authorised(post("/caseworker/cases/" + caseId + "/referrals/" + referralId + "/withdrawal"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value(ReferralState.Withdrawn.name()))
                // The claim survives on the row: the office pulled the referral back, which is not the same
                // fact as the judge never having had it.
                .andExpect(jsonPath("$.claimedBy").value(JUDGE_NAME));

            assertThat(currentReferralState(caseId)).isNull();
            // And the transition is what makes withdrawing a held referral safe rather than a race: the judge
            // whose papers were open no longer holds a Claimed referral, so a direction arriving after the
            // withdrawal is refused instead of landing on a referral the office had pulled back.
            assertThatThrownBy(() -> referralService.decide(caseId, referralId, approval(), judge()))
                .isInstanceOf(ConflictError.class);
            assertThat(stored(referralId).getState()).isEqualTo(ReferralState.Withdrawn);
        }

        @Test
        @DisplayName("409s withdrawing a referral a judge has decided, naming what to do with it instead")
        void refusesWithdrawingADecidedReferral() throws Exception {
            // The rule this plan exists for, over the route business testing found it on. Reached through the
            // real claim and direction rather than by writing Decided into the row, so the referral the route
            // refuses carries an actual direction to be discarded.
            String caseId = newCase();
            String referralId = raise(caseId, ONE_REASON);
            decide(caseId, referralId, MasterDecision.Approved, "Approved. Publish in the Gazette.", true);

            authorised(post("/caseworker/cases/" + caseId + "/referrals/" + referralId + "/withdrawal"))
                // 409 and not 403: nothing about the caller is wrong -- any caseworker may withdraw a referral,
                // and this one may still close this referral -- it is the state that refuses.
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("Referral " + referralId + " is " + ReferralState.Decided
                    + " -- a direction has been recorded on it and must be actioned and the referral closed,"
                    + " rather than withdrawn"));

            Referral refused = stored(referralId);
            assertThat(refused.getState()).isEqualTo(ReferralState.Decided);
            assertThat(refused.getDecision()).isEqualTo(MasterDecision.Approved);
            assertThat(refused.getDirectionsText()).isEqualTo("Approved. Publish in the Gazette.");
            // And the case row is untouched too, so the case stays in the "returned -- action needed" queue: a
            // direction must not drop off the office's worklist because somebody clicked withdraw.
            assertThat(currentReferralState(caseId)).isEqualTo(ReferralState.Decided);
            // Only the note the create wrote: the refusal is a whole rolled-back transaction.
            assertThat(notes.count()).isOne();
        }

        @Test
        @DisplayName("leaves the referral it refused to withdraw closeable, which is the remaining move")
        void leavesADecidedReferralCloseable() throws Exception {
            String caseId = newCase();
            String referralId = raise(caseId, ONE_REASON);
            decide(caseId, referralId, MasterDecision.Rejected, "Refused. Write to the applicant.", false);

            authorised(post("/caseworker/cases/" + caseId + "/referrals/" + referralId + "/withdrawal"))
                .andExpect(status().isConflict());

            // The half that makes the refusal a redirection rather than a dead end, asserted after it so the
            // refusal is shown not to have consumed the referral: what the office may not withdraw it may
            // close, through the operation that already exists.
            authorised(post("/caseworker/cases/" + caseId + "/referrals/" + referralId + "/closure"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value(ReferralState.Closed.name()))
                .andExpect(jsonPath("$.decision").value(MasterDecision.Rejected.name()))
                .andExpect(jsonPath("$.directionsText").value("Refused. Write to the applicant."))
                .andExpect(jsonPath("$.decidedBy").value(JUDGE_NAME));

            // Closed is terminal, so the case has no referral in play again -- the same column value the
            // withdrawal would have produced, now reached only once the direction has been actioned.
            assertThat(currentReferralState(caseId)).isNull();
            assertThat(stored(referralId).getDecision()).isEqualTo(MasterDecision.Rejected);
        }

        @Test
        @DisplayName("404s withdrawing a referral through another case, and it stays open")
        void refusesAWithdrawalThroughAnotherCase() throws Exception {
            String caseId = newCase();
            String otherCaseId = newCase();
            String referralId = raise(caseId, ONE_REASON);

            authorised(post("/caseworker/cases/" + otherCaseId + "/referrals/" + referralId + "/withdrawal"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Referral " + referralId + " not found"));

            // Both halves: refused, and the row that was addressed did not move. A 404 that had already written
            // would be the worse of the two failures and the harder to notice.
            assertThat(stored(referralId).getState()).isEqualTo(ReferralState.Open);
        }

        @Test
        @DisplayName("404s for a referral that does not exist")
        void notFoundForAMissingReferral() throws Exception {
            authorised(post("/caseworker/cases/" + newCase() + "/referrals/" + UNKNOWN_ID + "/withdrawal"))
                .andExpect(status().isNotFound());
        }
    }

    @Nested
    @DisplayName("POST /caseworker/cases/{id}/referrals/{referralId}/closure")
    class CloseReferral {

        @Test
        @DisplayName("moves the referral to Closed, answers 200 with the direction still on it")
        void closesTheReferral() throws Exception {
            String caseId = newCase();
            String referralId = raise(caseId, ONE_REASON);
            decide(caseId, referralId, MasterDecision.Approved, "Approved. Publish in the Gazette.", true);
            IDAM.stubUserInfo(StubIdam.SUBJECT, "Another Caseworker", List.of("caseworker-civil"));

            authorised(post("/caseworker/cases/" + caseId + "/referrals/" + referralId + "/closure"))
                // 200 with the row rather than 204, as withdrawal answers: it survives the transition, and the
                // direction it carries is what the caseworker just actioned.
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(referralId))
                .andExpect(jsonPath("$.state").value(ReferralState.Closed.name()))
                .andExpect(jsonPath("$.decision").value(MasterDecision.Approved.name()))
                .andExpect(jsonPath("$.directionsText").value("Approved. Publish in the Gazette."))
                .andExpect(jsonPath("$.publishToGazette").value(true))
                .andExpect(jsonPath("$.decisionDate").exists())
                // The judge directed it and the caseworker closed it, and the response distinguishes them:
                // decidedBy is not modifiedBy.
                .andExpect(jsonPath("$.decidedBy").value(JUDGE_NAME))
                .andExpect(jsonPath("$.modifiedBy").value("Another Caseworker"))
                .andExpect(jsonPath("$.createdBy").value("A Caseworker"));

            Referral closed = stored(referralId);
            assertThat(closed.getState()).isEqualTo(ReferralState.Closed);
            // Round-tripped through the real "MasterDecision" Postgres enum, which is what the NAMED_ENUM
            // mapping on the column is for.
            assertThat(closed.getDecision()).isEqualTo(MasterDecision.Approved);
            assertThat(closed.getDecidedBy()).isEqualTo(JUDGE_NAME);
            assertThat(closed.getModifiedBy()).isEqualTo("Another Caseworker");
        }

        @Test
        @DisplayName("publishes the claim and the direction, but not the sweep's own bookkeeping")
        void publishesTheClaimAndTheDirection() throws Exception {
            String caseId = newCase();
            String referralId = raise(caseId, ONE_REASON);
            decide(caseId, referralId, MasterDecision.Rejected, null, false);

            authorised(post("/caseworker/cases/" + caseId + "/referrals/" + referralId + "/closure"))
                .andExpect(status().isOk())
                // Who held it and from when: what answers "has the judiciary picked this up, and how long have
                // they had it".
                .andExpect(jsonPath("$.claimedBy").value(JUDGE_NAME))
                .andExpect(jsonPath("$.claimedAt").exists())
                // An approval or refusal needing no comment is a real answer, so the column stays null rather
                // than becoming an empty string.
                .andExpect(jsonPath("$.directionsText").doesNotExist())
                .andExpect(jsonPath("$.publishToGazette").value(false))
                // lastTouchedAt is the inactivity sweep's bookkeeping, not a fact about the referral: it moves
                // every time a judge opens the page, so publishing it would invite it to be read as "when the
                // judge last worked on this".
                .andExpect(jsonPath("$.lastTouchedAt").doesNotExist())
                // And still a DTO rather than the entity behind it.
                .andExpect(jsonPath("$.deedPoll").doesNotExist())
                .andExpect(jsonPath("$.deedPollId").doesNotExist());
        }

        @Test
        @DisplayName("409s closing the same referral twice, rather than answering 200 again")
        void refusesASecondClosure() throws Exception {
            String caseId = newCase();
            String referralId = raise(caseId, ONE_REASON);
            decide(caseId, referralId, MasterDecision.Approved, "Approved", false);

            authorised(post("/caseworker/cases/" + caseId + "/referrals/" + referralId + "/closure"))
                .andExpect(status().isOk());

            authorised(post("/caseworker/cases/" + caseId + "/referrals/" + referralId + "/closure"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("Referral " + referralId + " is " + ReferralState.Closed
                    + " and can only be closed once it is " + ReferralState.Decided));
        }

        @Test
        @DisplayName("409s closing anything that is not Decided, and leaves the row where it was")
        void refusesAnythingThatIsNotDecided() throws Exception {
            // Every non-Decided state through the route, because a route wired to a wider guard would pass every
            // other test in this class: closing an Open or Claimed referral would take it out of the pool with
            // no direction on it, and closing a Withdrawn one would relabel an abandoned referral as actioned.
            for (ReferralState state : Arrays.stream(ReferralState.values())
                .filter(candidate -> candidate != ReferralState.Decided)
                .toList()) {

                String caseId = newCase();
                String referralId = raise(caseId, ONE_REASON);
                moveTo(referralId, state);

                authorised(post("/caseworker/cases/" + caseId + "/referrals/" + referralId + "/closure"))
                    .andExpect(status().isConflict());

                assertThat(stored(referralId).getState()).isEqualTo(state);
            }
        }

        @Test
        @DisplayName("closes for a caller holding no roles, because there is no may-close permission")
        void closesForACallerHoldingNoRoles() throws Exception {
            // The default stub token carries no role this service asks about, so this is the ordinary caseworker
            // path -- asserted rather than assumed, because the absence of a gate here is a decision.
            String caseId = newCase();
            String referralId = raise(caseId, ONE_REASON);
            moveTo(referralId, ReferralState.Decided);

            authorised(post("/caseworker/cases/" + caseId + "/referrals/" + referralId + "/closure"))
                .andExpect(status().isOk());
        }

        @Test
        @DisplayName("404s closing a referral through another case, and it stays Decided")
        void refusesAClosureThroughAnotherCase() throws Exception {
            String caseId = newCase();
            String otherCaseId = newCase();
            String referralId = raise(caseId, ONE_REASON);
            moveTo(referralId, ReferralState.Decided);

            authorised(post("/caseworker/cases/" + otherCaseId + "/referrals/" + referralId + "/closure"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Referral " + referralId + " not found"));

            // Both halves: refused, and the row that was addressed did not move.
            assertThat(stored(referralId).getState()).isEqualTo(ReferralState.Decided);
        }

        @Test
        @DisplayName("404s for a referral that does not exist")
        void notFoundForAMissingReferral() throws Exception {
            authorised(post("/caseworker/cases/" + newCase() + "/referrals/" + UNKNOWN_ID + "/closure"))
                .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("writes no case note, unlike raising and withdrawing")
        void writesNoCaseNote() throws Exception {
            String caseId = newCase();
            String referralId = raise(caseId, ONE_REASON);
            moveTo(referralId, ReferralState.Decided);

            authorised(post("/caseworker/cases/" + caseId + "/referrals/" + referralId + "/closure"))
                .andExpect(status().isOk());

            // Only the note the create wrote: Closed is on the row, and the "returned -- action needed" queue
            // keys off the state rather than off the timeline.
            assertThat(notes.count()).isOne();
        }
    }

    @Nested
    @DisplayName("the case's currentReferralState column")
    class CurrentReferralStateColumn {

        @Test
        @DisplayName("follows the referral through its whole lifecycle, and is null again once it is closed")
        void followsTheWholeLifecycle() throws Exception {
            // Read after each transition has committed, which is the point: every assertion here is of a row
            // written by the transition's own transaction, so a case can never be found holding a referral in
            // one state with its column naming another.
            String caseId = newCase();
            // Never referred. Null is a real answer -- "no referral in play" -- and not a value nobody got
            // round to writing.
            assertThat(currentReferralState(caseId)).isNull();

            String referralId = raise(caseId, ONE_REASON);
            assertThat(currentReferralState(caseId)).isEqualTo(ReferralState.Open);

            CurrentUser judge = judge();
            referralService.claim(caseId, referralId, judge);
            assertThat(currentReferralState(caseId)).isEqualTo(ReferralState.Claimed);

            referralService.decide(caseId, referralId, approval(), judge);
            // The state the dashboard's "returned -- action needed" count is a filtered list of.
            assertThat(currentReferralState(caseId)).isEqualTo(ReferralState.Decided);

            authorised(post("/caseworker/cases/" + caseId + "/referrals/" + referralId + "/closure"))
                .andExpect(status().isOk());
            // Closed is terminal, so the case has no referral in play again. A column left reading Closed would
            // keep every actioned case in that queue for good, which is the mistake a per-site push makes.
            assertThat(currentReferralState(caseId)).isNull();
            assertThat(stored(referralId).getState()).isEqualTo(ReferralState.Closed);
        }

        @Test
        @DisplayName("is null again after a withdrawal, and Open again when the case is referred a second time")
        void followsAWithdrawalAndASecondReferral() throws Exception {
            String caseId = newCase();
            String withdrawn = raise(caseId, ONE_REASON);

            withdraw(caseId, withdrawn);

            assertThat(currentReferralState(caseId)).isNull();

            // The history is now one Withdrawn referral, and the recompute reads past it: the case's answer is
            // the state of the referral in play, not of the last one to finish.
            raise(caseId, ONE_REASON);
            assertThat(currentReferralState(caseId)).isEqualTo(ReferralState.Open);
            assertThat(referrals.findByDeedPollIdOrderByCreatedOnDesc(caseId)).hasSize(2);
        }

        @Test
        @DisplayName("is written on the case the referral belongs to, and on no other")
        void writesOnlyTheReferralsOwnCase() throws Exception {
            String caseId = newCase();
            final String otherCaseId = newCase();

            String referralId = raise(caseId, ONE_REASON);
            referralService.claim(caseId, referralId, judge());
            referralService.decide(caseId, referralId, approval(), judge());

            assertThat(currentReferralState(caseId)).isEqualTo(ReferralState.Decided);
            // The count admin-2 draws off this column is a count of cases, so a transition that wrote a
            // neighbouring case's column would inflate it with cases that have no referral at all.
            assertThat(currentReferralState(otherCaseId)).isNull();
        }

        @Test
        @DisplayName("goes back to Open when the sweep hands a lapsed claim back to the pool")
        void followsTheSweep() throws Exception {
            String caseId = newCase();
            String referralId = raise(caseId, ONE_REASON);
            referralService.claim(caseId, referralId, judge());
            assertThat(currentReferralState(caseId)).isEqualTo(ReferralState.Claimed);
            lapse(referralId);

            assertThat(referralService.reclaimStaleClaims()).isEqualTo(1);

            // The one transition whose column write is a single set-based statement rather than the per-case
            // recompute, asserted through the real statement, a real referral row and a real commit.
            assertThat(currentReferralState(caseId)).isEqualTo(ReferralState.Open);
            assertThat(stored(referralId).getState()).isEqualTo(ReferralState.Open);
        }

        @Test
        @DisplayName("the sweep refreshes every case it released in one statement, and leaves the others alone")
        void followsTheSweepAcrossCases() throws Exception {
            String lapsedHere = newCase();
            String lapsedElsewhere = newCase();
            String stillHeld = newCase();
            String heldHere = claimed(lapsedHere);
            String heldElsewhere = claimed(lapsedElsewhere);
            claimed(stillHeld);
            // Backdated only once every claim has been made: claim() sweeps before it reads, so a claim lapsed
            // mid-fixture would be taken back by the next claim's own sweep rather than by the one under test.
            lapse(heldHere);
            lapse(heldElsewhere);

            assertThat(referralService.reclaimStaleClaims()).isEqualTo(2);

            assertThat(currentReferralState(lapsedHere)).isEqualTo(ReferralState.Open);
            assertThat(currentReferralState(lapsedElsewhere)).isEqualTo(ReferralState.Open);
            // Its judge is still working, so it is not in the set the statement was keyed on and its column is
            // untouched -- which is also what stops a pool load writing to every case that ever held a claim.
            assertThat(currentReferralState(stillHeld)).isEqualTo(ReferralState.Claimed);
        }

        @Test
        @DisplayName("ends at Claimed when the claim call's own sweep reclaimed the referral first")
        void endsAtClaimedWhenTheSweepRanFirst() throws Exception {
            String caseId = newCase();
            String referralId = raise(caseId, ONE_REASON);
            referralService.claim(caseId, referralId, judge());
            lapse(referralId);

            // The nested case: claim() sweeps before it reads, so this one transaction refreshes the column
            // twice -- the set-based statement takes it to Open and the claim's own recompute takes it on to
            // Claimed. In the wrong order it would commit a claimed referral whose case reads Open.
            referralService.claim(caseId, referralId, otherJudge());

            assertThat(currentReferralState(caseId)).isEqualTo(ReferralState.Claimed);
            assertThat(stored(referralId).getClaimedBy()).isEqualTo("District Judge Okafor");
        }

        @Test
        @DisplayName("is left null by a refused transition")
        void isNotWrittenByARefusedTransition() throws Exception {
            String caseId = newCase();
            String referralId = raise(caseId, ONE_REASON);
            withdraw(caseId, referralId);

            // A second withdrawal is refused, and the refusal is a whole rolled-back transaction: the column is
            // the answer the successful withdrawal left, not one a failed attempt half-wrote.
            authorised(post("/caseworker/cases/" + caseId + "/referrals/" + referralId + "/withdrawal"))
                .andExpect(status().isConflict());

            assertThat(currentReferralState(caseId)).isNull();
        }

        /** A referral of the given case, raised and then claimed by a judge. */
        private String claimed(String caseId) throws Exception {
            String referralId = raise(caseId, ONE_REASON);
            referralService.claim(caseId, referralId, judge());
            return referralId;
        }

        private CurrentUser otherJudge() {
            return CurrentUser.currentUser(List.of(Role.JUDICIAL), "District Judge Okafor", "judge-2");
        }

        /**
         * Backdates a claim past any configured expiry window, so the next sweep takes it back.
         *
         * <p>Off the row's own {@code claimedAt} rather than off {@code LocalDateTime.now()}, so the arithmetic
         * cannot depend on which clock the test and the service each read.
         */
        private void lapse(String referralId) {
            Referral referral = stored(referralId);
            referral.setLastTouchedAt(referral.getClaimedAt().minusHours(9));
            referrals.saveAndFlush(referral);
        }
    }

    /** A stored case with a Date Received, which is all reference-number allocation needs. */
    private String newCase() {
        return deedPollService.createDeedPoll(DeedPollPatch.of(Map.of("dateReceived", RECEIVED)))
            .deedPoll()
            .getId();
    }

    /**
     * A referral raised through the route under test, which is the only way one can be raised.
     *
     * <p>Through HTTP rather than the service, unlike the note fixtures: there is no author to distinguish here
     * -- every referral is raised by a caseworker -- and going through the route means a fixture cannot be a
     * shape the API could not have produced.
     */
    private String raise(String caseId, String body) throws Exception {
        String created = authorised(post("/caseworker/cases/" + caseId + "/referrals"), body)
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
        return JSON.readTree(created).get("id").asText();
    }

    private void withdraw(String caseId, String referralId) throws Exception {
        authorised(post("/caseworker/cases/" + caseId + "/referrals/" + referralId + "/withdrawal"))
            .andExpect(status().isOk());
    }

    /**
     * A referral claimed and directed by a judge, leaving it {@code Decided} -- which is the only state closure
     * accepts.
     *
     * <p>Through the transitions rather than by writing {@code Decided} into the row, so the fixture is a
     * referral the service could really have produced: it carries a claim, a decision, a decision date and a
     * deciding judge, which is what lets the closure response be asserted to publish them. Not through HTTP,
     * because there is no route to either transition on this surface -- they are the judicial plan's -- so the
     * fixture supplies the {@code JUDICIAL} role directly, which is the whole reason the role exists before IDAM
     * issues it.
     *
     * @param directionsText what the judge directed, or null for a direction that needed no comment
     */
    private void decide(String caseId,
                        String referralId,
                        MasterDecision decision,
                        String directionsText,
                        boolean publishToGazette) {

        referralService.claim(caseId, referralId, judge());
        referralService.decide(
            caseId,
            referralId,
            new DirectionInput(decision, Optional.ofNullable(directionsText), publishToGazette),
            judge());
    }

    /**
     * The judge the fixtures claim and direct as, carrying the role IDAM does not issue yet.
     *
     * <p>Shared by the withdrawal tests and the column tests rather than built twice: both need a referral that
     * reached {@code Claimed} or {@code Decided} through the real transitions, and the subject is the same judge
     * each time so that a claim made in one call is recognised as this judge's in the next.
     */
    private static CurrentUser judge() {
        return CurrentUser.currentUser(List.of(Role.JUDICIAL), JUDGE_NAME, "judge-1");
    }

    /** A direction that needs no comment, for the tests that are about a transition and not the direction. */
    private static DirectionInput approval() {
        return new DirectionInput(MasterDecision.Approved, Optional.of("Approved"), false);
    }

    /** The case's denormalised column as it stands committed, read in a session of its own. */
    private ReferralState currentReferralState(String caseId) {
        return deedPolls.findById(caseId).orElseThrow().getCurrentReferralState();
    }

    private Referral stored(String referralId) {
        return referrals.findById(UUID.fromString(referralId)).orElseThrow();
    }

    /**
     * Puts a referral into a given state directly, for the tests that are about a guard rather than about how the
     * state was reached.
     *
     * <p>Written into the row on purpose. {@code Claimed} and {@code Decided} do now have transitions -- see
     * {@link #decide} -- but reaching every state through them would make a guard test depend on the transition
     * chain in front of it, and two of the states a guard has to refuse ({@code Closed} through a second closure,
     * {@code Withdrawn} through a withdrawal) are cheaper to state than to arrange. Where the *content* of the
     * state matters, and not only its name, the fixture uses the transitions instead.
     */
    private void moveTo(String referralId, ReferralState state) {
        Referral referral = stored(referralId);
        referral.setState(state);
        referrals.saveAndFlush(referral);
    }

    /** Backdates a stored referral, so an ordering assertion does not depend on the clock's resolution. */
    private void backdate(String referralId, LocalDateTime createdOn) {
        Referral referral = stored(referralId);
        referral.setCreatedOn(createdOn);
        referrals.saveAndFlush(referral);
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
