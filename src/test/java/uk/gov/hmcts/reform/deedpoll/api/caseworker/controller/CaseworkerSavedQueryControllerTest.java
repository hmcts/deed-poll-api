package uk.gov.hmcts.reform.deedpoll.api.caseworker.controller;

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
import org.springframework.http.MediaType;
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
import uk.gov.hmcts.reform.deedpoll.domain.savedqueries.CreateSavedQueryInput;
import uk.gov.hmcts.reform.deedpoll.domain.savedqueries.SavedQueryDefinition;
import uk.gov.hmcts.reform.deedpoll.domain.savedqueries.SavedQueryService;
import uk.gov.hmcts.reform.deedpoll.entity.SavedQuery;
import uk.gov.hmcts.reform.deedpoll.entity.SavedQuerySelection;
import uk.gov.hmcts.reform.deedpoll.repository.SavedQueryRepository;
import uk.gov.hmcts.reform.deedpoll.repository.SavedQuerySelectionRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The seven saved-query endpoints, over HTTP, against a real database and a stub IDAM.
 *
 * <p>The whole context rather than a {@code @WebMvcTest} slice, and not {@code @Transactional}, for the reasons
 * {@code CaseworkerNoteControllerTest} records: a slice does not get {@code SecurityConfiguration}, so it would
 * assert Spring Boot's default security rather than this service's, and the writes have to really commit for an
 * assertion to read the stored row back.
 *
 * <p><strong>Three callers throughout, not one.</strong> Every rule on this resource is about the relationship
 * between a caller and a row -- whose it is, and whether it is shared -- and a test with one caller cannot tell a
 * correctly scoped rule from no rule at all, because everything is theirs. So there is an owner, an unrelated
 * caseworker, and an Administrator, and each rule is asserted from every side of it that has a different answer.
 *
 * <p><strong>The permission truth table itself is in {@code SavedQueryPermissionsTest}</strong>, which needs no
 * container and therefore runs on the Jenkins agent, where this class is excluded. What is here is what only a
 * booted service can show: that the routes are wired to those rules, and which status code each answer becomes.
 *
 * <p><strong>The 404s are deliberate and a reviewer will read them as bugs.</strong> A query that is neither
 * shared nor the caller's answers 404 with the message an id that never existed gets, so no id is discoverable;
 * a query they can see but may not change answers 403, because they can already see it and a 404 would be a lie
 * they cannot act on. Both are asserted including the body, since the whole property is that the two answers are
 * indistinguishable.
 *
 * <p><strong>The selection sub-resource is the one exception, in one direction.</strong> Its {@code DELETE}
 * answers 204 for an id the caller cannot see, where every other operation would answer 404, and there is a test
 * for each half of why: the row is keyed by the caller so nothing else is reachable, and a visibility check would
 * trap a subscriber with a stale selection for a query its owner has unshared.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CaseworkerSavedQueryControllerTest extends AbstractPostgresContainerTest {

    /** An id of the right shape that nothing was ever stored under. */
    private static final String UNKNOWN_ID = "11111111-1111-1111-1111-111111111111";

    private static final Caller OWNER = new Caller("owner-1", "Olive Owner", List.of("caseworker-civil"));
    private static final Caller OTHER = new Caller("other-2", "Otto Other", List.of("caseworker-civil"));
    private static final Caller ADMIN =
        new Caller("admin-3", "An Admin", List.of(Role.DEEDPOLL_ADMIN.claimValue()));

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
    private SavedQueryService savedQueryService;

    @Autowired
    private SavedQueryRepository savedQueries;

    @Autowired
    private SavedQuerySelectionRepository selections;

    /**
     * One {@code @BeforeEach} calling both, rather than two of them.
     *
     * <p>JUnit does not order sibling {@code @BeforeEach} methods -- the sequence is deliberately
     * unspecified -- so two of them are two things that happen in no stated order. These two are
     * independent today, which is why the split passed, but a later dependency between them would hold by
     * luck and fail intermittently rather than at once. Stating the order costs a line.
     */
    @BeforeEach
    void freshFixture() {
        clearTables();
        IDAM.stubDefaults();
    }

    /** Cleared afterwards as well, because the container outlives this class and the next one starts empty. */
    @AfterEach
    void clearTables() {
        selections.deleteAll();
        savedQueries.deleteAll();
    }

    @Nested
    @DisplayName("without a token")
    class Unauthenticated {

        @Test
        @DisplayName("refuses to list saved queries")
        void refusesAList() throws Exception {
            mockMvc.perform(get("/caseworker/saved-queries")).andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("refuses to save one, and writes nothing")
        void refusesACreate() throws Exception {
            mockMvc.perform(post("/caseworker/saved-queries")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"name\":\"Ops\"}"))
                .andExpect(status().isUnauthorized());

            // The refusal has to happen before the handler, not inside it: a 401 that still wrote the row would
            // be the worst of both answers.
            assertThat(savedQueries.count()).isZero();
        }

        @Test
        @DisplayName("refuses to read one")
        void refusesARead() throws Exception {
            String id = save(OWNER, "Ops", true);

            mockMvc.perform(get("/caseworker/saved-queries/" + id)).andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("refuses to amend one, which is left as it was")
        void refusesAPatch() throws Exception {
            String id = save(OWNER, "Ops", true);

            mockMvc.perform(patch("/caseworker/saved-queries/" + id)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"name\":\"Renamed\"}"))
                .andExpect(status().isUnauthorized());

            assertThat(stored(id).getName()).isEqualTo("Ops");
        }

        @Test
        @DisplayName("refuses to delete one, which is still there")
        void refusesADelete() throws Exception {
            String id = save(OWNER, "Ops", true);

            mockMvc.perform(delete("/caseworker/saved-queries/" + id)).andExpect(status().isUnauthorized());

            assertThat(savedQueries.findById(UUID.fromString(id))).isPresent();
        }

        @Test
        @DisplayName("refuses to select one, and records no selection")
        void refusesASelect() throws Exception {
            String id = save(OWNER, "Ops", true);

            mockMvc.perform(put("/caseworker/saved-queries/" + id + "/selection"))
                .andExpect(status().isUnauthorized());

            // A selection belongs to somebody, so an unauthenticated one is not a selection at all -- and the
            // 401 has to come before the handler, not from inside it.
            assertThat(selections.count()).isZero();
        }

        @Test
        @DisplayName("refuses to unselect one, which stays selected")
        void refusesADeselect() throws Exception {
            String id = save(OWNER, "Ops", true);
            selections.save(new SavedQuerySelection(UUID.fromString(id), OWNER.subject()));

            // The one route that answers 204 to almost anything still answers 401 to a caller with no token: the
            // idempotence is about the id, never about identity.
            mockMvc.perform(delete("/caseworker/saved-queries/" + id + "/selection"))
                .andExpect(status().isUnauthorized());

            assertThat(selections.count()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("GET /caseworker/saved-queries")
    class ListSavedQueries {

        @Test
        @DisplayName("returns the shared library plus the caller's own, by name")
        void returnsSharedPlusTheirOwn() throws Exception {
            save(OWNER, "Mine, private", false);
            save(OWNER, "Aardvarks", true);
            save(OTHER, "Theirs, shared", true);
            save(OTHER, "Theirs, private", false);

            as(OWNER, get("/caseworker/saved-queries"))
                .andExpect(status().isOk())
                // Three of the four: somebody else's unshared query is not in anyone's library.
                .andExpect(jsonPath("$.length()").value(3))
                // By name, which is v1's ordering. Not by creation, or the first assertion would pass either way.
                .andExpect(jsonPath("$[0].name").value("Aardvarks"))
                .andExpect(jsonPath("$[1].name").value("Mine, private"))
                .andExpect(jsonPath("$[2].name").value("Theirs, shared"));
        }

        @Test
        @DisplayName("shows an unrelated caseworker only the shared ones")
        void showsAnUnrelatedCaseworkerOnlyTheSharedOnes() throws Exception {
            save(OWNER, "Mine, private", false);
            save(OWNER, "Mine, shared", true);

            as(OTHER, get("/caseworker/saved-queries"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].name").value("Mine, shared"));
        }

        @ParameterizedTest
        @EnumSource(value = Role.class, names = {"TEAM_LEADER", "DELIVERY_MANAGER", "DEEDPOLL_ADMIN"})
        @DisplayName("shows an Administrator no more than anyone else, so the override is not a visibility one")
        void showsAnAdministratorNoMore(Role role) throws Exception {
            Caller caller = new Caller(ADMIN.subject(), ADMIN.name(), List.of(role.claimValue()));
            save(OWNER, "Mine, private", false);
            save(OWNER, "Mine, shared", true);

            as(caller, get("/caseworker/saved-queries"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].name").value("Mine, shared"));
        }

        @Test
        @DisplayName("flags each query with whether this caller has selected it")
        void flagsWhetherThisCallerHasSelectedIt() throws Exception {
            String selected = save(OWNER, "Chosen", true);
            save(OWNER, "Not chosen", true);
            selections.save(new SavedQuerySelection(UUID.fromString(selected), OWNER.subject()));

            as(OWNER, get("/caseworker/saved-queries"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("Chosen"))
                .andExpect(jsonPath("$[0].selected").value(true))
                .andExpect(jsonPath("$[1].selected").value(false));

            // Per caller, which is the whole reason it cannot be a column: the same shared query is in one
            // caseworker's list and not another's.
            as(OTHER, get("/caseworker/saved-queries"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("Chosen"))
                .andExpect(jsonPath("$[0].selected").value(false));
        }

        @ParameterizedTest
        @EnumSource(value = Role.class, names = {"TEAM_LEADER", "DELIVERY_MANAGER", "DEEDPOLL_ADMIN"})
        @DisplayName("flags each query with whether this caller may change it")
        void flagsWhetherThisCallerMayChangeIt(Role role) throws Exception {
            Caller caller = new Caller(ADMIN.subject(), ADMIN.name(), List.of(role.claimValue()));
            save(OWNER, "Shared", true);

            // Published rather than left for the client to derive, because a client that reimplements the rule
            // drifts from it -- and then renders a button that 403s. Asserted from all three sides.
            as(OWNER, get("/caseworker/saved-queries"))
                .andExpect(jsonPath("$[0].editable").value(true));
            as(caller, get("/caseworker/saved-queries"))
                .andExpect(jsonPath("$[0].editable").value(true));
            as(OTHER, get("/caseworker/saved-queries"))
                .andExpect(jsonPath("$[0].editable").value(false));
        }

        @Test
        @DisplayName("answers an empty list where there is nothing to see")
        void answersAnEmptyList() throws Exception {
            save(OTHER, "Theirs, private", false);

            // The other half of the visibility assertions: without this, a 3-row expectation elsewhere could be
            // met by a route that answered everything and a coincidence.
            as(OWNER, get("/caseworker/saved-queries"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
        }
    }

    @Nested
    @DisplayName("POST /caseworker/saved-queries")
    class CreateSavedQuery {

        @ParameterizedTest
        @EnumSource(value = Role.class, names = {"TEAM_LEADER", "DELIVERY_MANAGER", "DEEDPOLL_ADMIN"})
        @DisplayName("saves the whole view and answers 201 with it")
        void savesTheWholeView(Role role) throws Exception {
            Caller caller = new Caller(ADMIN.subject(), ADMIN.name(), List.of(role.claimValue()));
            // As the Administrator, because this body publishes and only an Administrator may. Changed rather
            // than duplicated: an unpublished create is covered by `defaultsToUnshared` below, and a second
            // happy path differing only in caller would drift from this one.
            as(caller, post("/caseworker/saved-queries"), """
                {"name":"Adults received in June","description":"For the June audit","shared":true,\
                "filter":"received","conditions":["type:is:Adult","dateReceived:after:2026-06-01"],\
                "sortField":"dateReceived","sortDirection":"asc","searchTerm":"Smith"}""")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.name").value("Adults received in June"))
                .andExpect(jsonPath("$.description").value("For the June audit"))
                .andExpect(jsonPath("$.shared").value(true))
                .andExpect(jsonPath("$.filter").value("received"))
                // The wire strings as they were sent, which is what makes a saved query a list URL again.
                .andExpect(jsonPath("$.conditions[0]").value("type:is:Adult"))
                .andExpect(jsonPath("$.conditions[1]").value("dateReceived:after:2026-06-01"))
                .andExpect(jsonPath("$.sortField").value("dateReceived"))
                .andExpect(jsonPath("$.sortDirection").value("asc"))
                .andExpect(jsonPath("$.searchTerm").value("Smith"))
                .andExpect(jsonPath("$.editable").value(true))
                // Saving is not selecting: the query is in the library and in nobody's list, including its
                // owner's, because those are separate statements.
                .andExpect(jsonPath("$.selected").value(false));

            SavedQuery saved = savedQueries.findAll().get(0);
            assertThat(saved.getConditions()).containsExactly("type:is:Adult", "dateReceived:after:2026-06-01");
            assertThat(selections.count()).isZero();
        }

        @Test
        @DisplayName("takes the owner from the token and not from the body")
        void takesTheOwnerFromTheToken() throws Exception {
            // ownerId and ownerName are stripped as any unknown key is, so a caller cannot post a query
            // attributed to somebody else -- structurally, since the input record has nowhere to put one.
            as(OWNER, post("/caseworker/saved-queries"),
                "{\"name\":\"Ops\",\"ownerId\":\"other-2\",\"ownerName\":\"Otto Other\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.ownerId").value(OWNER.subject()))
                .andExpect(jsonPath("$.ownerName").value(OWNER.name()));

            assertThat(savedQueries.findAll().get(0).getOwnerId()).isEqualTo(OWNER.subject());
        }

        @Test
        @DisplayName("stamps the audit columns from the caller")
        void stampsTheAuditColumns() throws Exception {
            as(OWNER, post("/caseworker/saved-queries"), "{\"name\":\"Ops\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.createdBy").value(OWNER.name()))
                .andExpect(jsonPath("$.modifiedBy").value(OWNER.name()))
                .andExpect(jsonPath("$.createdOn").exists())
                .andExpect(jsonPath("$.modifiedOn").exists());
        }

        @Test
        @DisplayName("defaults to unshared, so publishing is a decision rather than an accident")
        void defaultsToUnshared() throws Exception {
            as(OWNER, post("/caseworker/saved-queries"), "{\"name\":\"Ops\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.shared").value(false));
        }

        @Test
        @DisplayName("a caseworker asking to publish is refused with 403, and nothing is written")
        void caseworkerMayNotPublish() throws Exception {
            as(OWNER, post("/caseworker/saved-queries"), "{\"name\":\"Ops\",\"shared\":true}")
                .andExpect(status().isForbidden());

            // The refusal comes before the write, not after it. A 403 that had already saved an unpublished
            // copy would be the worst of both answers, and the status code alone cannot tell the two apart --
            // the same property `SavedQuerySecurityTest` asserts for the amend and delete refusals.
            assertThat(savedQueries.findAll()).isEmpty();
        }

        @Test
        @DisplayName("and is refused rather than quietly given an unpublished query")
        void caseworkerIsRefusedRatherThanDowngraded() throws Exception {
            // The alternative considered and rejected: accept the request, ignore the flag, answer 201 with
            // `shared: false`. That tells the caller they published something they did not, and no client can
            // detect it. Stated as a test because it is a change somebody could make believing it kinder.
            as(OWNER, post("/caseworker/saved-queries"), "{\"name\":\"Ops\",\"shared\":true}")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.shared").doesNotExist());
        }

        @Test
        @DisplayName("an explicit shared:false from a caseworker is not a refusal, because it asks for nothing")
        void anExplicitFalseIsNotRefused() throws Exception {
            as(OWNER, post("/caseworker/saved-queries"), "{\"name\":\"Ops\",\"shared\":false}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.shared").value(false));
        }

        @Test
        @DisplayName("a caseworker with no deed-poll role may save one")
        void anyCaseworkerMaySaveOne() throws Exception {
            // Not role-gated, and asserted with the roles stated in so many words: gating this because the
            // note delete is gated would remove a capability every caseworker is meant to have.
            as(OTHER, post("/caseworker/saved-queries"), "{\"name\":\"Ops\"}")
                .andExpect(status().isCreated());

            assertThat(savedQueries.count()).isEqualTo(1);
        }

        @Test
        @DisplayName("400s a payload the schema refuses, and writes nothing")
        void refusesAnInvalidPayload() throws Exception {
            as(OWNER, post("/caseworker/saved-queries"),
                "{\"conditions\":[\"shoeSize:is:9\"],\"filter\":\"nonsense\"}")
                .andExpect(status().isBadRequest())
                // Every offending field at once, each under the name the validator that refused it chose --
                // `where.0` for a condition, because that is the address the live query's 400 uses too.
                .andExpect(jsonPath("$.errors[?(@.field == 'name')]").exists())
                .andExpect(jsonPath("$.errors[?(@.field == 'filter')]").exists())
                .andExpect(jsonPath("$.errors[?(@.field == 'where.0')]").exists());

            assertThat(savedQueries.count()).isZero();
        }
    }

    @Nested
    @DisplayName("GET /caseworker/saved-queries/{id}")
    class ReadSavedQuery {

        @Test
        @DisplayName("the owner reads their own, shared or not")
        void theOwnerReadsTheirOwn() throws Exception {
            as(OWNER, get("/caseworker/saved-queries/" + save(OWNER, "Private", false)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Private"));
        }

        @Test
        @DisplayName("anybody reads a shared one")
        void anybodyReadsASharedOne() throws Exception {
            as(OTHER, get("/caseworker/saved-queries/" + save(OWNER, "Shared", true)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Shared"))
                .andExpect(jsonPath("$.editable").value(false));
        }

        @Test
        @DisplayName("404s somebody else's unshared one, identically to an id that never existed")
        void notFoundForSomebodyElsesUnsharedOne() throws Exception {
            String id = save(OWNER, "Private", false);

            // The whole property is that these two are indistinguishable, so the body is asserted and not only
            // the status: a caller must not be able to probe for other people's private queries.
            as(OTHER, get("/caseworker/saved-queries/" + id))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Saved query " + id + " not found"));
            as(OTHER, get("/caseworker/saved-queries/" + UNKNOWN_ID))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Saved query " + UNKNOWN_ID + " not found"));
        }

        @ParameterizedTest
        @EnumSource(value = Role.class, names = {"TEAM_LEADER", "DELIVERY_MANAGER", "DEEDPOLL_ADMIN"})
        @DisplayName("404s an Administrator naming somebody else's unshared one")
        void notFoundForAnAdministratorNamingAnUnsharedOne(Role role) throws Exception {
            Caller caller = new Caller(ADMIN.subject(), ADMIN.name(), List.of(role.claimValue()));
            String id = save(OWNER, "Private", false);

            // Decision 2's refinement, at the route: the override reaches shared queries only, and it is the
            // visibility rule that enforces that rather than a second check. So an administrator gets the same
            // 404 as anyone else, and cannot tell it from an id that never existed either.
            as(caller, get("/caseworker/saved-queries/" + id))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Saved query " + id + " not found"));
        }

        @Test
        @DisplayName("404s an id that is not an identifier at all")
        void notFoundForAMalformedId() throws Exception {
            // Not a 400: it is an id nothing could ever have been stored under, so "not found" is the true
            // answer and the one a mistyped case id already gets on every other route here.
            as(OWNER, get("/caseworker/saved-queries/not-a-uuid"))
                .andExpect(status().isNotFound());
        }
    }

    @Nested
    @DisplayName("PATCH /caseworker/saved-queries/{id}")
    class UpdateSavedQuery {

        @Test
        @DisplayName("the owner amends it")
        void theOwnerAmendsIt() throws Exception {
            String id = save(OWNER, "Ops", true);

            as(OWNER, patch("/caseworker/saved-queries/" + id),
                "{\"name\":\"Ops, revised\",\"conditions\":[\"type:is:Adult\"],\"shared\":false}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Ops, revised"))
                .andExpect(jsonPath("$.shared").value(false));

            SavedQuery saved = stored(id);
            assertThat(saved.getName()).isEqualTo("Ops, revised");
            assertThat(saved.getConditions()).containsExactly("type:is:Adult");
            assertThat(saved.isShared()).isFalse();
        }

        @Test
        @DisplayName("the owner may unpublish their own without being an Administrator, because that is de-escalation")
        void theOwnerMayUnpublishWithoutBeingAnAdministrator() throws Exception {
            // The state this protects is reachable: an Administrator publishes a query and later loses the role,
            // keeping ownership. Gating the clear would strand it in the library with only another Administrator
            // able to withdraw it. `theOwnerAmendsIt` above happens to cover the same ground; this states it as
            // the rule rather than leaving it incidental to a broader assertion.
            String id = save(OWNER, "Ops", true);

            as(OWNER, patch("/caseworker/saved-queries/" + id), "{\"shared\":false}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.shared").value(false));

            assertThat(stored(id).isShared()).isFalse();
        }

        @Test
        @DisplayName("the owner may not publish their own, and the stored query is untouched")
        void theOwnerMayNotPublishTheirOwn() throws Exception {
            String id = save(OWNER, "Ops", false);

            as(OWNER, patch("/caseworker/saved-queries/" + id), "{\"name\":\"Ops, renamed\",\"shared\":true}")
                .andExpect(status().isForbidden());

            // Neither half of the patch landed. A refusal that had applied the name and dropped the flag would
            // be a partial write, which is worse than either answer.
            SavedQuery saved = stored(id);
            assertThat(saved.isShared()).isFalse();
            assertThat(saved.getName()).isEqualTo("Ops");
        }

        @ParameterizedTest
        @EnumSource(value = Role.class, names = {"TEAM_LEADER", "DELIVERY_MANAGER", "DEEDPOLL_ADMIN"})
        @DisplayName("an Administrator may publish an existing query")
        void anAdministratorMayPublishAnExistingQuery(Role role) throws Exception {
            Caller caller = new Caller(ADMIN.subject(), ADMIN.name(), List.of(role.claimValue()));
            String id = save(caller, "Ops", false);

            as(caller, patch("/caseworker/saved-queries/" + id), "{\"shared\":true}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.shared").value(true));

            assertThat(stored(id).isShared()).isTrue();
        }

        @Test
        @DisplayName("a caller who cannot see the query gets 404 rather than the publish refusal")
        void invisibilityAnswersBeforeThePublishGate() throws Exception {
            // Ordering: `editable` runs first, so a caller naming somebody else's unshared query is told it does
            // not exist rather than being refused for not being an Administrator. A 403 here would confirm the id
            // names something publishable, which is exactly what the 404-for-everything rule exists to prevent.
            String id = save(OWNER, "Ops", false);

            as(OTHER, patch("/caseworker/saved-queries/" + id), "{\"shared\":true}")
                .andExpect(status().isNotFound());

            assertThat(stored(id).isShared()).isFalse();
        }

        @ParameterizedTest
        @EnumSource(value = Role.class, names = {"TEAM_LEADER", "DELIVERY_MANAGER", "DEEDPOLL_ADMIN"})
        @DisplayName("an Administrator amends a shared one, and the owner is unchanged by it")
        void anAdministratorAmendsASharedOne(Role role) throws Exception {
            Caller caller = new Caller(ADMIN.subject(), ADMIN.name(), List.of(role.claimValue()));
            String id = save(OWNER, "Ops", true);

            as(caller, patch("/caseworker/saved-queries/" + id), "{\"name\":\"Ops, corrected\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Ops, corrected"))
                // Editing does not transfer ownership: ownerName is captured when the query is saved and never
                // refreshed, so a departed owner's query still says whose it was.
                .andExpect(jsonPath("$.ownerId").value(OWNER.subject()))
                .andExpect(jsonPath("$.ownerName").value(OWNER.name()))
                // And modifiedBy is the caller who actually changed it, which is the point of having both.
                .andExpect(jsonPath("$.modifiedBy").value(caller.name()));

            assertThat(stored(id).getOwnerId()).isEqualTo(OWNER.subject());
        }

        @Test
        @DisplayName("403s a caseworker amending somebody else's shared one, which is unchanged")
        void refusesACaseworkerAmendingSomebodyElsesSharedOne() throws Exception {
            String id = save(OWNER, "Ops", true);

            // 403 rather than 404 here: they can already see it, so there is nothing left to conceal, and a 404
            // would be a lie a client cannot act on.
            as(OTHER, patch("/caseworker/saved-queries/" + id), "{\"name\":\"Hijacked\"}")
                .andExpect(status().isForbidden());

            // The half that matters: the 403 alone would also pass against a handler that refused after writing.
            assertThat(stored(id).getName()).isEqualTo("Ops");
        }

        @Test
        @DisplayName("404s a caseworker amending somebody else's unshared one, which is unchanged")
        void notFoundAmendingSomebodyElsesUnsharedOne() throws Exception {
            String id = save(OWNER, "Ops", false);

            as(OTHER, patch("/caseworker/saved-queries/" + id), "{\"name\":\"Hijacked\"}")
                .andExpect(status().isNotFound());

            assertThat(stored(id).getName()).isEqualTo("Ops");
        }

        @ParameterizedTest
        @EnumSource(value = Role.class, names = {"TEAM_LEADER", "DELIVERY_MANAGER", "DEEDPOLL_ADMIN"})
        @DisplayName("404s an Administrator amending somebody else's unshared one, which is unchanged")
        void notFoundForAnAdministratorAmendingAnUnsharedOne(Role role) throws Exception {
            Caller caller = new Caller(ADMIN.subject(), ADMIN.name(), List.of(role.claimValue()));
            String id = save(OWNER, "Ops", false);

            as(caller, patch("/caseworker/saved-queries/" + id), "{\"name\":\"Tidied\"}")
                .andExpect(status().isNotFound());

            assertThat(stored(id).getName()).isEqualTo("Ops");
        }

        @Test
        @DisplayName("an explicit null clears a nullable field and leaves the rest alone")
        void anExplicitNullClears() throws Exception {
            String id = save(OWNER, "Ops", true);
            as(OWNER, patch("/caseworker/saved-queries/" + id),
                "{\"description\":\"For the audit\",\"filter\":\"received\",\"searchTerm\":\"Smith\"}")
                .andExpect(status().isOk());

            as(OWNER, patch("/caseworker/saved-queries/" + id), "{\"description\":null}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.description").doesNotExist())
                // The distinction the keyed body exists to carry: clearing one field must not blank the ones the
                // patch never mentioned.
                .andExpect(jsonPath("$.filter").value("received"))
                .andExpect(jsonPath("$.searchTerm").value("Smith"));

            assertThat(stored(id).getDescription()).isNull();
            assertThat(stored(id).getFilter()).isEqualTo("received");
        }

        @Test
        @DisplayName("clearing the order empties both of its columns, because it is one value")
        void clearingTheOrderEmptiesBothColumns() throws Exception {
            String id = save(OWNER, "Ops", true);
            as(OWNER, patch("/caseworker/saved-queries/" + id),
                "{\"sortField\":\"dateReceived\",\"sortDirection\":\"desc\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sortField").value("dateReceived"))
                .andExpect(jsonPath("$.sortDirection").value("desc"));

            as(OWNER, patch("/caseworker/saved-queries/" + id), "{\"sortField\":null}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sortField").doesNotExist())
                // A direction with no field is not a state CaseSort can hold, so it goes too rather than being
                // left behind pointing at nothing.
                .andExpect(jsonPath("$.sortDirection").doesNotExist());

            SavedQuery saved = stored(id);
            assertThat(saved.getSortField()).isNull();
            assertThat(saved.getSortDirection()).isNull();
        }

        @Test
        @DisplayName("an empty patch is accepted and re-stamps the audit columns")
        void anEmptyPatchIsAccepted() throws Exception {
            String id = save(OWNER, "Ops", true);

            as(OWNER, patch("/caseworker/saved-queries/" + id), "{}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Ops"))
                // Stamped explicitly rather than left to @PreUpdate, which Hibernate only fires when its dirty
                // check finds a changed property -- so an empty patch would otherwise leave modifiedOn behind.
                .andExpect(jsonPath("$.modifiedBy").value(OWNER.name()));

            assertThat(stored(id).getModifiedOn()).isNotNull();
        }

        @Test
        @DisplayName("400s a patch the schema refuses, and stores nothing of it")
        void refusesAnInvalidPatch() throws Exception {
            String id = save(OWNER, "Ops", true);

            as(OWNER, patch("/caseworker/saved-queries/" + id),
                "{\"name\":null,\"conditions\":[\"shoeSize:is:9\"]}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[?(@.field == 'name')]").exists())
                .andExpect(jsonPath("$.errors[?(@.field == 'where.0')]").exists());

            assertThat(stored(id).getName()).isEqualTo("Ops");
            assertThat(stored(id).getConditions()).isEmpty();
        }
    }

    @Nested
    @DisplayName("DELETE /caseworker/saved-queries/{id}")
    class DeleteSavedQuery {

        @Test
        @DisplayName("the owner deletes it, and the library no longer carries it")
        void theOwnerDeletesIt() throws Exception {
            String kept = save(OWNER, "Kept", true);
            String deleted = save(OWNER, "Deleted", true);

            as(OWNER, delete("/caseworker/saved-queries/" + deleted))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));

            // Through the list route as well as the repository, because the list is what the navigation reads.
            as(OWNER, get("/caseworker/saved-queries"))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(kept));

            assertThat(savedQueries.findById(UUID.fromString(deleted))).isEmpty();
        }

        @ParameterizedTest
        @EnumSource(value = Role.class, names = {"TEAM_LEADER", "DELIVERY_MANAGER", "DEEDPOLL_ADMIN"})
        @DisplayName("an Administrator deletes a shared one")
        void anAdministratorDeletesASharedOne(Role role) throws Exception {
            Caller caller = new Caller(ADMIN.subject(), ADMIN.name(), List.of(role.claimValue()));
            String id = save(OWNER, "Ops", true);

            as(caller, delete("/caseworker/saved-queries/" + id)).andExpect(status().isNoContent());

            assertThat(savedQueries.findById(UUID.fromString(id))).isEmpty();
        }

        @Test
        @DisplayName("deleting a query others have selected takes their selections with it")
        void deletingTakesSelectionsWithIt() throws Exception {
            String id = save(OWNER, "Ops", true);
            selections.save(new SavedQuerySelection(UUID.fromString(id), OTHER.subject()));

            // Permitted, and the cascade is what keeps it safe: an orphaned selection row would resurface as a
            // broken navigation entry in every one of the subscriber's lists.
            as(OWNER, delete("/caseworker/saved-queries/" + id)).andExpect(status().isNoContent());

            assertThat(selections.count()).isZero();
        }

        @Test
        @DisplayName("403s a caseworker deleting somebody else's shared one, which survives")
        void refusesACaseworkerDeletingSomebodyElsesSharedOne() throws Exception {
            String id = save(OWNER, "Ops", true);

            as(OTHER, delete("/caseworker/saved-queries/" + id)).andExpect(status().isForbidden());

            // The assertion that matters most on this verb: a refusal that had already deleted is a failure
            // nothing downstream could report or undo.
            assertThat(savedQueries.findById(UUID.fromString(id))).isPresent();
        }

        @ParameterizedTest
        @EnumSource(value = Role.class, names = {"TEAM_LEADER", "DELIVERY_MANAGER", "DEEDPOLL_ADMIN"})
        @DisplayName("404s anybody deleting somebody else's unshared one, Administrator included")
        void notFoundDeletingSomebodyElsesUnsharedOne(Role role) throws Exception {
            Caller caller = new Caller(ADMIN.subject(), ADMIN.name(), List.of(role.claimValue()));
            String id = save(OWNER, "Ops", false);

            as(OTHER, delete("/caseworker/saved-queries/" + id)).andExpect(status().isNotFound());
            as(caller, delete("/caseworker/saved-queries/" + id)).andExpect(status().isNotFound());

            assertThat(savedQueries.findById(UUID.fromString(id))).isPresent();
        }

        @Test
        @DisplayName("404s the second delete, because absence is reported by throwing")
        void notFoundOnTheSecondDelete() throws Exception {
            String id = save(OWNER, "Ops", true);

            as(OWNER, delete("/caseworker/saved-queries/" + id)).andExpect(status().isNoContent());
            // Not idempotent in the strict HTTP sense, and consistent with the reads -- deliberately unlike the
            // selection sub-resource, where unselecting something already unselected is not a mistake.
            as(OWNER, delete("/caseworker/saved-queries/" + id)).andExpect(status().isNotFound());
        }
    }

    @Nested
    @DisplayName("PUT /caseworker/saved-queries/{id}/selection")
    class SelectSavedQuery {

        @Test
        @DisplayName("adds the query to the caller's list and answers 204")
        void addsTheQueryToTheCallersList() throws Exception {
            String id = save(OWNER, "Ops", true);

            as(OWNER, put("/caseworker/saved-queries/" + id + "/selection"))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));

            // Through the list route, because that flag is what a navigation bar renders.
            as(OWNER, get("/caseworker/saved-queries"))
                .andExpect(jsonPath("$[0].selected").value(true));
            as(OWNER, get("/caseworker/saved-queries/" + id))
                .andExpect(jsonPath("$.selected").value(true));
        }

        @Test
        @DisplayName("a second call is another 204 and not a second row")
        void secondCallIsAnother204() throws Exception {
            String id = save(OWNER, "Ops", true);

            as(OWNER, put("/caseworker/saved-queries/" + id + "/selection"))
                .andExpect(status().isNoContent());
            // Idempotent: the request states the end state, which is true however many times it is sent, and a
            // client retrying a lost response must not be told it has a duplicate to clean up.
            as(OWNER, put("/caseworker/saved-queries/" + id + "/selection"))
                .andExpect(status().isNoContent());

            assertThat(selections.count()).isEqualTo(1);
        }

        @Test
        @DisplayName("selects somebody else's shared query without any right to edit it")
        void selectsSomebodyElsesSharedQuery() throws Exception {
            String id = save(OWNER, "Ops", true);

            // Decision 7, at the route: choosing to see a query is every caseworker's right, and keeping it in a
            // sub-resource is what stops "add this to my list" needing the owner's permission. Asserted with
            // editable false in the same breath, or the test would not be showing that the two are separate.
            as(OTHER, put("/caseworker/saved-queries/" + id + "/selection"))
                .andExpect(status().isNoContent());

            as(OTHER, get("/caseworker/saved-queries/" + id))
                .andExpect(jsonPath("$.selected").value(true))
                .andExpect(jsonPath("$.editable").value(false));
        }

        @Test
        @DisplayName("selections are per-user: one caller's does not appear in another's list")
        void selectionsArePerUser() throws Exception {
            String id = save(OWNER, "Ops", true);

            as(OWNER, put("/caseworker/saved-queries/" + id + "/selection"))
                .andExpect(status().isNoContent());

            // The same shared query, two callers, two answers -- which is why selected cannot be a column.
            as(OTHER, get("/caseworker/saved-queries/" + id))
                .andExpect(jsonPath("$.selected").value(false));
            assertThat(selections.existsByIdSavedQueryIdAndIdUserId(UUID.fromString(id), OTHER.subject()))
                .isFalse();
        }

        @Test
        @DisplayName("404s somebody else's unshared query, identically to an id that never existed")
        void notFoundForSomebodyElsesUnsharedQuery() throws Exception {
            String id = save(OWNER, "Private", false);

            // Selecting must not be a way to discover a private query, so the body is asserted too: the two
            // answers have to be indistinguishable.
            as(OTHER, put("/caseworker/saved-queries/" + id + "/selection"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Saved query " + id + " not found"));
            as(OTHER, put("/caseworker/saved-queries/" + UNKNOWN_ID + "/selection"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Saved query " + UNKNOWN_ID + " not found"));

            assertThat(selections.count()).isZero();
        }

        @ParameterizedTest
        @EnumSource(value = Role.class, names = {"TEAM_LEADER", "DELIVERY_MANAGER", "DEEDPOLL_ADMIN"})
        @DisplayName("404s an Administrator selecting somebody else's unshared query")
        void notFoundForAnAdministrator(Role role) throws Exception {
            Caller caller = new Caller(ADMIN.subject(), ADMIN.name(), List.of(role.claimValue()));
            String id = save(OWNER, "Private", false);

            as(caller, put("/caseworker/saved-queries/" + id + "/selection"))
                .andExpect(status().isNotFound());

            assertThat(selections.count()).isZero();
        }
    }

    @Nested
    @DisplayName("DELETE /caseworker/saved-queries/{id}/selection")
    class DeselectSavedQuery {

        @Test
        @DisplayName("removes the query from the caller's list and leaves the query alone")
        void removesTheQueryFromTheCallersList() throws Exception {
            String id = save(OWNER, "Ops", true);
            as(OWNER, put("/caseworker/saved-queries/" + id + "/selection"))
                .andExpect(status().isNoContent());

            as(OWNER, delete("/caseworker/saved-queries/" + id + "/selection"))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));

            // Unselecting is not deleting: the query is still in the library, just not in this list.
            as(OWNER, get("/caseworker/saved-queries"))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].selected").value(false));
            assertThat(selections.count()).isZero();
        }

        @Test
        @DisplayName("204s something that was never selected, because unselecting twice is not a mistake")
        void answers204ForSomethingNeverSelected() throws Exception {
            String id = save(OWNER, "Ops", true);

            as(OWNER, delete("/caseworker/saved-queries/" + id + "/selection"))
                .andExpect(status().isNoContent());
            as(OWNER, delete("/caseworker/saved-queries/" + id + "/selection"))
                .andExpect(status().isNoContent());
        }

        @Test
        @DisplayName("204s an unknown id, and an id that is not an identifier at all")
        void answers204ForAnUnknownId() throws Exception {
            // The answer does not vary with the id, which is what makes the missing visibility check disclose
            // nothing: there is no status code to probe with.
            as(OWNER, delete("/caseworker/saved-queries/" + UNKNOWN_ID + "/selection"))
                .andExpect(status().isNoContent());
            as(OWNER, delete("/caseworker/saved-queries/not-a-uuid/selection"))
                .andExpect(status().isNoContent());
        }

        @Test
        @DisplayName("one caller cannot unselect another's, even on a query they both see")
        void oneCallerCannotUnselectAnothers() throws Exception {
            String id = save(OWNER, "Ops", true);
            as(OWNER, put("/caseworker/saved-queries/" + id + "/selection"))
                .andExpect(status().isNoContent());

            // 204 rather than a refusal, and it removes nothing: the row is keyed by the caller, so the delete
            // reaches OTHER's own selection -- which is not there -- and cannot reach OWNER's.
            as(OTHER, delete("/caseworker/saved-queries/" + id + "/selection"))
                .andExpect(status().isNoContent());

            as(OWNER, get("/caseworker/saved-queries/" + id))
                .andExpect(jsonPath("$.selected").value(true));
            assertThat(selections.existsByIdSavedQueryIdAndIdUserId(UUID.fromString(id), OWNER.subject()))
                .isTrue();
        }

        @Test
        @DisplayName("clears a stale selection for a query the caller can no longer see")
        void clearsAStaleSelectionForAnInvisibleQuery() throws Exception {
            String id = save(OWNER, "Ops", true);
            as(OTHER, put("/caseworker/saved-queries/" + id + "/selection"))
                .andExpect(status().isNoContent());
            as(OWNER, patch("/caseworker/saved-queries/" + id), "{\"shared\":false}")
                .andExpect(status().isOk());

            // The reason the delete does not check visibility. The owner may re-share at any time, and a check
            // here would leave OTHER unable to clear the one selection they most need to -- the query they can no
            // longer see.
            as(OTHER, delete("/caseworker/saved-queries/" + id + "/selection"))
                .andExpect(status().isNoContent());

            assertThat(selections.count()).isZero();
        }
    }

    @Nested
    @DisplayName("a selected query whose owner then unshares it")
    class UnsharedAfterSelection {

        @Test
        @DisplayName("stops appearing for the subscriber, while their selection row survives")
        void stopsAppearingForTheSubscriber() throws Exception {
            String id = save(OWNER, "Ops", true);
            as(OTHER, put("/caseworker/saved-queries/" + id + "/selection"))
                .andExpect(status().isNoContent());
            as(OTHER, get("/caseworker/saved-queries"))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].selected").value(true));

            as(OWNER, patch("/caseworker/saved-queries/" + id), "{\"shared\":false}")
                .andExpect(status().isOk());

            // A selection is not a grant. Visibility is read afresh on every list, so unsharing takes the query
            // out of every subscriber's -- the alternative would let a row written while a query was public keep
            // a private one readable indefinitely.
            as(OTHER, get("/caseworker/saved-queries"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
            as(OTHER, get("/caseworker/saved-queries/" + id))
                .andExpect(status().isNotFound());

            // And the row stays rather than being tidied on unshare: the owner may re-share, and the subscriber's
            // choice is theirs to change. It is unreachable meanwhile, which is what the assertions above show.
            assertThat(selections.existsByIdSavedQueryIdAndIdUserId(UUID.fromString(id), OTHER.subject()))
                .isTrue();

            // The owner still sees it, selected or not, so the query itself was not lost.
            as(OWNER, get("/caseworker/saved-queries/" + id)).andExpect(status().isOk());
        }
    }

    /**
     * A saved query stored through the domain, published afterwards through the repository if asked.
     *
     * <p><strong>Created through the service rather than the repository</strong>, so a fixture cannot be a shape
     * the API could not have produced -- in particular it cannot have an owner no token would supply. That is
     * unchanged, and the create is still the caller's.
     *
     * <p><strong>But the publishing is the repository's, and that is not a way around the gate.</strong> Only an
     * Administrator may publish, and an Administrator who creates a query owns it -- so the service can no
     * longer produce the state most of this file needs: <em>a published query owned by somebody who is not
     * currently an Administrator</em>. That state is legitimate and reachable, because an Administrator who
     * publishes a query and later loses the role keeps owning it. Going through the service with
     * {@code shared = true} as {@link #OWNER} would now raise {@code ForbiddenError} in 34 fixtures and take
     * this file's visibility, selection and editability coverage with it.
     *
     * <p>The gate itself is tested where a caller asks for it over HTTP, not here -- see the create and patch
     * blocks.
     */
    private String save(Caller owner, String name, boolean shared) {
        CreateSavedQueryInput input = new CreateSavedQueryInput(
            name, Optional.empty(), false, SavedQueryDefinition.NONE);
        SavedQuery created = savedQueryService.createSavedQuery(input, owner.currentUser()).query();

        if (shared) {
            created.setShared(true);
            savedQueries.save(created);
        }

        return created.getId().toString();
    }

    private SavedQuery stored(String id) {
        return savedQueries.findById(UUID.fromString(id)).orElseThrow();
    }

    /** The request, as the given caller: a token carrying their subject, and userinfo that agrees with it. */
    private ResultActions as(Caller caller, MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request.headers(headersFor(caller)));
    }

    /** The request, as the given caller, with a JSON body. */
    private ResultActions as(Caller caller, MockHttpServletRequestBuilder request, String body) throws Exception {
        return mockMvc.perform(request.headers(headersFor(caller))
                                   .contentType(MediaType.APPLICATION_JSON)
                                   .content(body));
    }

    /**
     * Bearer headers for one caller, restubbing userinfo first.
     *
     * <p>Both halves are needed and they have to agree: {@code IdamUserInfoClient} refuses a userinfo response
     * whose {@code sub} differs from the validated token's, which is the check that stops a response for the
     * wrong user being accepted as this one's identity. So a test cannot fake a second caseworker by restubbing
     * userinfo alone -- it would be refused -- and this class needs three callers.
     */
    private HttpHeaders headersFor(Caller caller) {
        IDAM.stubUserInfo(caller.subject(), caller.name(), caller.roles());
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(IDAM.accessToken(claims -> claims.subject(caller.subject())));
        return headers;
    }

    /**
     * One caseworker, as both IDAM and the domain see them.
     *
     * @param subject the IDAM {@code sub}, which is what a saved query records as its owner
     * @param name the display name, which is audit text and never an owner key
     * @param roles the claim values IDAM asserts, of which this service recognises exactly one
     */
    private record Caller(String subject, String name, List<String> roles) {

        /** The same caller as the domain holds them, for a fixture written through a service. */
        CurrentUser currentUser() {
            return CurrentUser.currentUser(
                roles.stream().flatMap(role -> Role.fromClaimValue(role).stream()).toList(), name, subject);
        }
    }
}
