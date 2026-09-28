package uk.gov.hmcts.reform.deedpoll.api.caseworker.controller;

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
import uk.gov.hmcts.reform.deedpoll.domain.auth.Role;
import uk.gov.hmcts.reform.deedpoll.domain.deedpoll.DeedPollPatch;
import uk.gov.hmcts.reform.deedpoll.domain.deedpoll.DeedPollService;
import uk.gov.hmcts.reform.deedpoll.domain.witnesses.CreateWitnessInput;
import uk.gov.hmcts.reform.deedpoll.domain.witnesses.WitnessSchemas;
import uk.gov.hmcts.reform.deedpoll.domain.witnesses.WitnessService;
import uk.gov.hmcts.reform.deedpoll.entity.DeedPollWitness;
import uk.gov.hmcts.reform.deedpoll.repository.DeedPollRepository;
import uk.gov.hmcts.reform.deedpoll.repository.DeedPollWitnessRepository;
import uk.gov.hmcts.reform.deedpoll.repository.ReferenceCounterRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The five caseworker witness endpoints, over HTTP, against a real database and a stub IDAM.
 *
 * <p>The whole context rather than a {@code @WebMvcTest} slice, and not {@code @Transactional}, for the reasons
 * {@code CaseworkerNoteControllerTest} records: a slice does not get {@code SecurityConfiguration}, so it asserts
 * Spring Boot's default security rather than this service's, and the writes have to really commit for an assertion
 * to read the stored row back.
 *
 * <p><strong>Two case fixtures throughout, not one.</strong> Every operation here is scoped to a parent, and a
 * test with one case cannot tell a correctly scoped lookup from an unscoped one -- both answer the same thing. So
 * the second case exists to be the wrong parent, and the scoping tests assert both halves: the witness is not
 * reachable through it, and the row that was addressed did not move.
 *
 * <p><strong>Every caller here is a Basic User unless it says otherwise</strong>, and the delete group is where it
 * says otherwise. Create and edit are open to both roles -- the business document gives Basic User "Create, view
 * and edit witness records" -- so on those the default caseworker token is the interesting one, and a gate added
 * later would fail these tests rather than passing them silently. "Delete witnesses" is the one row marked for
 * Administrators alone, so {@link DeleteWitness} restubs the token for every test in it.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CaseworkerWitnessControllerTest extends AbstractPostgresContainerTest {

    /** createDeedPoll cannot allocate a reference number without a Date Received to derive the year from. */
    private static final String RECEIVED = "2026-07-01T00:00:00.000Z";

    /** An id of the right shape that nothing was ever stored under. */
    private static final String UNKNOWN_ID = "11111111-1111-1111-1111-111111111111";

    /** A complete create body, since all four fields are required and most tests want a valid one. */
    private static final String VALID_BODY = """
        {"witnessName":"Wilma Witness","witnessAddress":"1 Test Street, Testville",\
        "witnessOccupation":"Teacher","declarant":false}""";

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
    private WitnessService witnessService;

    @Autowired
    private DeedPollRepository deedPolls;

    @Autowired
    private DeedPollWitnessRepository witnesses;

    @Autowired
    private ReferenceCounterRepository referenceCounters;

    @BeforeEach
    void setUp() {
        clearTables();
        resetIdam();
    }

    @AfterEach
    void tearDown() {
        clearTables();
    }

    private void clearTables() {
        witnesses.deleteAll();
        deedPolls.deleteAll();
        referenceCounters.deleteAll();
    }

    private void resetIdam() {
        IDAM.stubDefaults();
    }

    @Nested
    @DisplayName("without a token")
    class Unauthenticated {

        @Test
        @DisplayName("refuses to list a case's witnesses")
        void refusesAList() throws Exception {
            mockMvc.perform(get("/caseworker/cases/" + newCase() + "/witnesses"))
                .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("refuses to add a witness, and writes nothing")
        void refusesACreate() throws Exception {
            mockMvc.perform(post("/caseworker/cases/" + newCase() + "/witnesses")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(VALID_BODY))
                .andExpect(status().isUnauthorized());

            // The refusal has to happen before the handler, not inside it: a 401 that still wrote the row would
            // be the worst of both answers.
            assertThat(witnesses.count()).isZero();
        }

        @Test
        @DisplayName("refuses to amend a witness, which is left as it was")
        void refusesAPatch() throws Exception {
            String caseId = newCase();
            String witnessId = addWitness(caseId, "Wilma Witness");

            mockMvc.perform(patch("/caseworker/cases/" + caseId + "/witnesses/" + witnessId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"witnessName\":\"Someone Else\"}"))
                .andExpect(status().isUnauthorized());

            assertThat(stored(witnessId).getWitnessName()).isEqualTo("Wilma Witness");
        }

        @Test
        @DisplayName("refuses to delete a witness, which is still there")
        void refusesADelete() throws Exception {
            String caseId = newCase();
            String witnessId = addWitness(caseId, "Wilma Witness");

            // 401 rather than the 403 a caseworker without the role gets: an unidentified caller is not told
            // which role would have been enough, and the row is untouched either way.
            mockMvc.perform(delete("/caseworker/cases/" + caseId + "/witnesses/" + witnessId))
                .andExpect(status().isUnauthorized());

            assertThat(witnesses.findById(witnessId)).isPresent();
        }
    }

    @Nested
    @DisplayName("GET /caseworker/cases/{id}/witnesses")
    class ListWitnesses {

        @Test
        @DisplayName("lists the case's witnesses, oldest first")
        void listsTheCasesWitnesses() throws Exception {
            String caseId = newCase();
            String older = addWitness(caseId, "First Witness");
            String newer = addWitness(caseId, "Second Witness");
            // Backdated rather than trusting two inserts to land in different microseconds.
            backdate(older, LocalDateTime.of(2026, 7, 1, 9, 0));
            backdate(newer, LocalDateTime.of(2026, 7, 2, 9, 0));

            authorised(get("/caseworker/cases/" + caseId + "/witnesses"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                // The other way round from the notes list, and deliberately: a witness list is read the way a
                // declaration is laid out, which is the order the case detail page's subgrid already uses.
                .andExpect(jsonPath("$[0].id").value(older))
                .andExpect(jsonPath("$[0].witnessName").value("First Witness"))
                .andExpect(jsonPath("$[1].id").value(newer));
        }

        @Test
        @DisplayName("404s for a case that does not exist, rather than answering an empty list")
        void notFoundForAMissingCase() throws Exception {
            // Filtering by the parent id alone gives [] both for a mistyped case id and for a real case with no
            // witnesses, so a caseworker who mistyped would be told the case has none.
            authorised(get("/caseworker/cases/" + UNKNOWN_ID + "/witnesses"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("DeedPoll " + UNKNOWN_ID + " not found"));
        }

        @Test
        @DisplayName("answers an empty list for a real case with no witnesses")
        void emptyListForACaseWithNoWitnesses() throws Exception {
            // The other half of the pair above. Without this the 404 could be coming from something that refuses
            // every list, and "the case exists and has no witnesses" would be unrepresentable.
            authorised(get("/caseworker/cases/" + newCase() + "/witnesses"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
        }

        @Test
        @DisplayName("lists only the witnesses of the case in the path")
        void listsOnlyThatCasesWitnesses() throws Exception {
            String caseId = newCase();
            addWitness(caseId, "Ours");
            addWitness(newCase(), "Theirs");

            authorised(get("/caseworker/cases/" + caseId + "/witnesses"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].witnessName").value("Ours"));
        }

        @Test
        @DisplayName("publishes the timestamps and not the parent case")
        void publishesTheTimestampsAndNotTheParent() throws Exception {
            String caseId = newCase();
            addWitness(caseId, "Wilma Witness");

            authorised(get("/caseworker/cases/" + caseId + "/witnesses"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].createdOn").exists())
                .andExpect(jsonPath("$[0].modifiedOn").exists())
                // No author columns on this table, so there is nothing for the response to claim. Asserted, not
                // assumed: a DTO that grew one would be inventing attribution.
                .andExpect(jsonPath("$[0].createdBy").doesNotExist())
                .andExpect(jsonPath("$[0].modifiedBy").doesNotExist())
                // A witness carrying its case, which carries its witnesses, is a cycle a serialiser has to be
                // told about -- and the caller already knows which case it asked for.
                .andExpect(jsonPath("$[0].deedPoll").doesNotExist())
                .andExpect(jsonPath("$[0].deedPollId").doesNotExist());
        }
    }

    @Nested
    @DisplayName("POST /caseworker/cases/{id}/witnesses")
    class CreateWitness {

        @Test
        @DisplayName("adds the witness, round-trips all four fields and answers 201")
        void addsTheWitness() throws Exception {
            String caseId = newCase();

            authorised(post("/caseworker/cases/" + caseId + "/witnesses"),
                """
                {"witnessName":"Wilma Witness","witnessAddress":"1 Test Street, Testville",\
                "witnessOccupation":"Teacher","declarant":true}""")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.witnessName").value("Wilma Witness"))
                .andExpect(jsonPath("$.witnessAddress").value("1 Test Street, Testville"))
                .andExpect(jsonPath("$.witnessOccupation").value("Teacher"))
                .andExpect(jsonPath("$.declarant").value(true))
                .andExpect(jsonPath("$.createdOn").exists());

            assertThat(witnesses.findAll()).singleElement().satisfies(witness -> {
                assertThat(witness.getWitnessName()).isEqualTo("Wilma Witness");
                assertThat(witness.getWitnessAddress()).isEqualTo("1 Test Street, Testville");
                assertThat(witness.getWitnessOccupation()).isEqualTo("Teacher");
                assertThat(witness.isDeclarant()).isTrue();
                assertThat(witness.getDeedPoll().getId()).isEqualTo(caseId);
            });
        }

        @Test
        @DisplayName("stores the value as submitted, without trimming it")
        void doesNotTrim() throws Exception {
            // The opposite of the notes route, and deliberately: the reference declares a plain
            // z.string().max(...) on each witness field and its test asserts parse returns what it was given.
            // Trimming here would make this port store something different from its own specification.
            authorised(post("/caseworker/cases/" + newCase() + "/witnesses"),
                """
                {"witnessName":"  Wilma Witness  ","witnessAddress":"1 Test Street",\
                "witnessOccupation":"Teacher","declarant":false}""")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.witnessName").value("  Wilma Witness  "));

            assertThat(witnesses.findAll()).singleElement()
                .extracting(DeedPollWitness::getWitnessName)
                .isEqualTo("  Wilma Witness  ");
        }

        @Test
        @DisplayName("400s a field over its cap, attributed to that field and naming the limit")
        void refusesAnOverLongField() throws Exception {
            String tooLong = "x".repeat(WitnessSchemas.MAX_WITNESS_NAME_LENGTH + 1);

            authorised(post("/caseworker/cases/" + newCase() + "/witnesses"),
                """
                {"witnessName":"%s","witnessAddress":"1 Test Street",\
                "witnessOccupation":"Teacher","declarant":false}""".formatted(tooLong))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.length()").value(1))
                .andExpect(jsonPath("$.errors[0].field").value("witnessName"))
                // Rejected here rather than by the driver, so a caseworker gets a message naming the field
                // instead of an error naming the column.
                .andExpect(jsonPath("$.errors[0].message")
                    .value("witnessName must be " + WitnessSchemas.MAX_WITNESS_NAME_LENGTH
                        + " characters or fewer"));

            assertThat(witnesses.count()).isZero();
        }

        @Test
        @DisplayName("accepts a field exactly at its cap, so the boundary is inclusive")
        void acceptsAFieldAtItsCap() throws Exception {
            // The pair to the test above: an off-by-one in the check would refuse a value the column can hold.
            String atTheCap = "x".repeat(WitnessSchemas.MAX_WITNESS_ADDRESS_LENGTH);

            authorised(post("/caseworker/cases/" + newCase() + "/witnesses"),
                """
                {"witnessName":"Wilma Witness","witnessAddress":"%s",\
                "witnessOccupation":"Teacher","declarant":false}""".formatted(atTheCap))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.witnessAddress").value(atTheCap));
        }

        @Test
        @DisplayName("400s a body with no fields at all, naming every one of the four")
        void refusesAnEmptyBody() throws Exception {
            authorised(post("/caseworker/cases/" + newCase() + "/witnesses"), "{}")
                .andExpect(status().isBadRequest())
                // Every offending field, not only the first: a caseworker retyping a declaration should see all
                // of its problems at once, and the errors are in the schema's declaration order so a form can
                // list them the way it lays them out.
                .andExpect(jsonPath("$.errors.length()").value(4))
                .andExpect(jsonPath("$.errors[0].field").value("witnessName"))
                .andExpect(jsonPath("$.errors[1].field").value("witnessAddress"))
                .andExpect(jsonPath("$.errors[2].field").value("witnessOccupation"))
                .andExpect(jsonPath("$.errors[3].field").value("declarant"));

            assertThat(witnesses.count()).isZero();
        }

        @Test
        @DisplayName("400s a create missing only declarant, so no field is quietly defaulted")
        void refusesACreateMissingDeclarant() throws Exception {
            // The one a default would hide: a missing boolean defaulting to false would record a declarant as an
            // ordinary witness, which changes what the case means.
            authorised(post("/caseworker/cases/" + newCase() + "/witnesses"),
                """
                {"witnessName":"Wilma Witness","witnessAddress":"1 Test Street",\
                "witnessOccupation":"Teacher"}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.length()").value(1))
                .andExpect(jsonPath("$.errors[0].field").value("declarant"));

            assertThat(witnesses.count()).isZero();
        }

        @Test
        @DisplayName("400s an explicit null rather than storing one in a NOT NULL column")
        void refusesAnExplicitNull() throws Exception {
            authorised(post("/caseworker/cases/" + newCase() + "/witnesses"),
                """
                {"witnessName":null,"witnessAddress":"1 Test Street",\
                "witnessOccupation":"Teacher","declarant":false}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("witnessName"));

            assertThat(witnesses.count()).isZero();
        }

        @Test
        @DisplayName("400s a truthy string for declarant rather than coercing it")
        void refusesATruthyStringDeclarant() throws Exception {
            // The reference asserts this specifically, because the one string that would coerce wrongly --
            // "false" -- is truthy in JavaScript.
            authorised(post("/caseworker/cases/" + newCase() + "/witnesses"),
                """
                {"witnessName":"Wilma Witness","witnessAddress":"1 Test Street",\
                "witnessOccupation":"Teacher","declarant":"true"}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("declarant"))
                .andExpect(jsonPath("$.errors[0].message").value("declarant must be true or false"));

            assertThat(witnesses.count()).isZero();
        }

        @Test
        @DisplayName("strips an id and a deedPollId a caller sent instead of honouring them")
        void stripsCallerSuppliedKeys() throws Exception {
            // The reference's "z.object() strips unknown keys", and here it is what stops a caller posting a
            // witness onto another case. Structural rather than checked: CreateWitnessInput has four components
            // and the parent is an argument.
            String caseId = newCase();
            String otherCaseId = newCase();

            authorised(post("/caseworker/cases/" + caseId + "/witnesses"),
                """
                {"witnessName":"Wilma Witness","witnessAddress":"1 Test Street",\
                "witnessOccupation":"Teacher","declarant":false,\
                "id":"an-id-of-my-choosing","deedPollId":"%s"}""".formatted(otherCaseId))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").exists());

            assertThat(witnesses.findAll()).singleElement().satisfies(witness -> {
                assertThat(witness.getId()).isNotEqualTo("an-id-of-my-choosing");
                assertThat(witness.getDeedPoll().getId()).isEqualTo(caseId);
            });
        }

        @Test
        @DisplayName("404s for a case that does not exist, and writes nothing")
        void notFoundForAMissingCase() throws Exception {
            authorised(post("/caseworker/cases/" + UNKNOWN_ID + "/witnesses"), VALID_BODY)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("DeedPoll " + UNKNOWN_ID + " not found"));

            // The foreign key would refuse it anyway, but as a constraint violation naming a column rather than
            // a 404 naming the case.
            assertThat(witnesses.count()).isZero();
        }

        @Test
        @DisplayName("400s in the service's own error shape for a body that is not JSON")
        void refusesAnUnreadableBody() throws Exception {
            authorised(post("/caseworker/cases/" + newCase() + "/witnesses"), "{ not json")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Request body is missing or is not valid JSON"))
                .andExpect(jsonPath("$.errors").doesNotExist());
        }
    }

    @Nested
    @DisplayName("GET /caseworker/cases/{id}/witnesses/{witnessId}")
    class ReadWitness {

        @Test
        @DisplayName("returns the witness")
        void returnsTheWitness() throws Exception {
            String caseId = newCase();
            String witnessId = addWitness(caseId, "Wilma Witness");

            authorised(get("/caseworker/cases/" + caseId + "/witnesses/" + witnessId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(witnessId))
                .andExpect(jsonPath("$.witnessName").value("Wilma Witness"));
        }

        @Test
        @DisplayName("404s for a witness read through another case, so the scope is both ids")
        void notFoundThroughAnotherCase() throws Exception {
            String caseId = newCase();
            String otherCaseId = newCase();
            String witnessId = addWitness(caseId, "Ours");

            authorised(get("/caseworker/cases/" + otherCaseId + "/witnesses/" + witnessId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Witness " + witnessId + " not found"));
        }

        @Test
        @DisplayName("404s for a witness that does not exist")
        void notFoundForAMissingWitness() throws Exception {
            authorised(get("/caseworker/cases/" + newCase() + "/witnesses/" + UNKNOWN_ID))
                .andExpect(status().isNotFound());
        }
    }

    @Nested
    @DisplayName("PATCH /caseworker/cases/{id}/witnesses/{witnessId}")
    class UpdateWitness {

        @Test
        @DisplayName("amends the named field and leaves the other three exactly as they were")
        void amendsOnlyTheNamedField() throws Exception {
            String caseId = newCase();
            String witnessId = addWitness(caseId, "Wilma Witness");

            authorised(patch("/caseworker/cases/" + caseId + "/witnesses/" + witnessId),
                "{\"witnessOccupation\":\"Retired teacher\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.witnessOccupation").value("Retired teacher"))
                // The absent/value split, which is the whole reason the body binds as a keyed map: an omitted
                // field is "leave it alone" and not "clear it".
                .andExpect(jsonPath("$.witnessName").value("Wilma Witness"))
                .andExpect(jsonPath("$.witnessAddress").value("1 Test Street"))
                .andExpect(jsonPath("$.declarant").value(false));

            DeedPollWitness amended = stored(witnessId);
            assertThat(amended.getWitnessOccupation()).isEqualTo("Retired teacher");
            assertThat(amended.getWitnessName()).isEqualTo("Wilma Witness");
            assertThat(amended.getWitnessAddress()).isEqualTo("1 Test Street");
            assertThat(amended.isDeclarant()).isFalse();
        }

        @Test
        @DisplayName("amends declarant on its own, so a witness can become the declarant")
        void amendsDeclarant() throws Exception {
            String caseId = newCase();
            String witnessId = addWitness(caseId, "Wilma Witness");

            authorised(patch("/caseworker/cases/" + caseId + "/witnesses/" + witnessId),
                "{\"declarant\":true}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.declarant").value(true))
                .andExpect(jsonPath("$.witnessName").value("Wilma Witness"));

            assertThat(stored(witnessId).isDeclarant()).isTrue();
        }

        @Test
        @DisplayName("amends all four at once")
        void amendsEveryField() throws Exception {
            String caseId = newCase();
            String witnessId = addWitness(caseId, "Wilma Witness");

            authorised(patch("/caseworker/cases/" + caseId + "/witnesses/" + witnessId),
                """
                {"witnessName":"Walter Witness","witnessAddress":"2 Other Road",\
                "witnessOccupation":"Plumber","declarant":true}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.witnessName").value("Walter Witness"))
                .andExpect(jsonPath("$.witnessAddress").value("2 Other Road"))
                .andExpect(jsonPath("$.witnessOccupation").value("Plumber"))
                .andExpect(jsonPath("$.declarant").value(true));
        }

        @Test
        @DisplayName("accepts an empty patch as a no-op rather than refusing it")
        void acceptsAnEmptyPatch() throws Exception {
            String caseId = newCase();
            String witnessId = addWitness(caseId, "Wilma Witness");
            LocalDateTime createdOn = stored(witnessId).getCreatedOn();

            // UpdateWitnessInput.EMPTY and isEmpty() already name this state, so the behaviour they imply is that
            // an empty body is accepted and changes nothing -- not a 400 for a body with no fields, which is what
            // a create with the same body gets.
            authorised(patch("/caseworker/cases/" + caseId + "/witnesses/" + witnessId), "{}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.witnessName").value("Wilma Witness"))
                .andExpect(jsonPath("$.witnessAddress").value("1 Test Street"))
                .andExpect(jsonPath("$.witnessOccupation").value("Teacher"))
                .andExpect(jsonPath("$.declarant").value(false));

            DeedPollWitness unchanged = stored(witnessId);
            assertThat(unchanged.getWitnessName()).isEqualTo("Wilma Witness");
            assertThat(unchanged.getWitnessOccupation()).isEqualTo("Teacher");
            assertThat(unchanged.getCreatedOn()).isEqualTo(createdOn);
            // That modifiedOn *moves* on an empty patch is WitnessServiceTest's assertion, where a sleep can make
            // the two writes land in different milliseconds -- the column is timestamp(3). Here the weaker
            // ordering is all that can be asserted without one, and the stronger claim is already covered.
            assertThat(unchanged.getModifiedOn()).isAfterOrEqualTo(createdOn);
        }

        @Test
        @DisplayName("400s an explicit null instead of clearing a NOT NULL column")
        void refusesAnExplicitNull() throws Exception {
            // Why UpdateWitnessRequest is keyed rather than a record of four nullable components: a record makes
            // {"witnessName": null} and {} the same body, so a client clearing a field would get a success and an
            // unchanged row.
            String caseId = newCase();
            String witnessId = addWitness(caseId, "Wilma Witness");

            authorised(patch("/caseworker/cases/" + caseId + "/witnesses/" + witnessId),
                "{\"witnessName\":null}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("witnessName"))
                .andExpect(jsonPath("$.errors[0].message").value("witnessName cannot be null"));

            assertThat(stored(witnessId).getWitnessName()).isEqualTo("Wilma Witness");
        }

        @Test
        @DisplayName("400s a patched field over its cap, applying the same rule as create")
        void refusesAnOverLongPatchedField() throws Exception {
            String caseId = newCase();
            String witnessId = addWitness(caseId, "Wilma Witness");

            authorised(patch("/caseworker/cases/" + caseId + "/witnesses/" + witnessId),
                "{\"witnessOccupation\":\"%s\"}"
                    .formatted("x".repeat(WitnessSchemas.MAX_WITNESS_OCCUPATION_LENGTH + 1)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("witnessOccupation"));

            assertThat(stored(witnessId).getWitnessOccupation()).isEqualTo("Teacher");
        }

        @Test
        @DisplayName("404s amending a witness through another case, and leaves it alone")
        void refusesAnAmendmentThroughAnotherCase() throws Exception {
            String caseId = newCase();
            String otherCaseId = newCase();
            String witnessId = addWitness(caseId, "Ours");

            authorised(patch("/caseworker/cases/" + otherCaseId + "/witnesses/" + witnessId),
                "{\"witnessName\":\"Theirs\"}")
                .andExpect(status().isNotFound());

            // Both halves: refused, and the row that was addressed did not move. A 404 that had already written
            // would be the worse of the two failures and the harder to notice.
            assertThat(stored(witnessId).getWitnessName()).isEqualTo("Ours");
        }

        @Test
        @DisplayName("404s for a witness that does not exist")
        void notFoundForAMissingWitness() throws Exception {
            authorised(patch("/caseworker/cases/" + newCase() + "/witnesses/" + UNKNOWN_ID),
                "{\"witnessName\":\"Wilma Witness\"}")
                .andExpect(status().isNotFound());
        }
    }

    /**
     * The one privileged operation on this surface.
     *
     * <p>The other four are open to both roles, so this is the only group that has to say who the caller is --
     * the opposite emphasis from everywhere else in this class. Every test here is an Administrator unless it
     * restubs, as {@code CaseworkerNoteControllerTest}'s delete group does, which puts the requirement in one
     * place: a delete test added later is authorised the way the route expects, and a refusal has to ask for it
     * in so many words.
     *
     * <p><strong>There is no test asserting the absence of an ungated two-argument {@code removeWitness}.</strong>
     * That is a compile-time fact rather than a runtime one: a test calling the two-argument form would fail to
     * compile, so it cannot be written as a failing assertion. The overload's absence is enforced by there being
     * nothing to call, and {@code WitnessService.removeWitness}'s docblock records why.
     */
    @Nested
    @DisplayName("DELETE /caseworker/cases/{id}/witnesses/{witnessId}")
    class DeleteWitness {

        @BeforeEach
        void administrator() {
            // After the outer setUp(), which restores the non-admin default -- JUnit runs the enclosing
            // class's callbacks first, so this overrides it rather than racing it.
            IDAM.stubUserInfo(StubIdam.SUBJECT, "An Admin", List.of(Role.DEEDPOLL_ADMIN.claimValue()));
        }

        @Test
        @DisplayName("an Administrator deletes the witness, and the case's list no longer carries it")
        void deletesTheWitness() throws Exception {
            String caseId = newCase();
            String keptId = addWitness(caseId, "Kept");
            String deletedId = addWitness(caseId, "Deleted");

            authorised(delete("/caseworker/cases/" + caseId + "/witnesses/" + deletedId))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));

            // Through the list route as well as the repository, because the list is what the case detail page
            // reads: a delete that removed the row but left it reachable through the parent's collection would
            // be a hard delete the UI could not see.
            authorised(get("/caseworker/cases/" + caseId + "/witnesses"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(keptId));

            assertThat(witnesses.findById(deletedId)).isEmpty();
            assertThat(stored(keptId).getWitnessName()).isEqualTo("Kept");
            // The parent survives a child delete. Worth asserting rather than assuming: the association is
            // where a stray cascade would show up, and it would show up as data loss.
            assertThat(deedPolls.findById(caseId)).isPresent();
        }

        @Test
        @DisplayName("403s a caseworker without the Administrator role, and the witness survives")
        void refusesACaseworkerWithoutTheRole() throws Exception {
            String caseId = newCase();
            String witnessId = addWitness(caseId, "Wilma Witness");
            IDAM.stubUserInfo(StubIdam.SUBJECT, "A Caseworker", List.of("caseworker-civil"));

            authorised(delete("/caseworker/cases/" + caseId + "/witnesses/" + witnessId))
                .andExpect(status().isForbidden())
                // 403 and not 404: a caseworker is entitled to know the witness is there and that they may not
                // remove it. Disguising the refusal as an absence would send them to look for a witness they
                // can see on the page.
                .andExpect(jsonPath("$.error")
                    .value("Requires one of the following roles: " + Role.DEEDPOLL_ADMIN.claimValue()));

            // The half that matters: the 403 alone would also pass against a handler that refused after
            // deleting, and that is the failure nothing downstream could report or undo.
            assertThat(witnesses.findById(witnessId)).isPresent();
        }

        @Test
        @DisplayName("403s a refused caller addressing a witness that does not exist, so the check runs first")
        void refusesBeforeResolving() throws Exception {
            // The ordering guard, and the test that actually pins it -- without it a later refactor can swap
            // the two lines in removeWitness and nothing complains. Paired with the 404 below: the same URL is
            // a 404 for an Administrator and a 403 for everybody else, so the status code is not an existence
            // oracle over witnesses the caller may not touch.
            IDAM.stubUserInfo(StubIdam.SUBJECT, "A Caseworker", List.of("caseworker-civil"));

            authorised(delete("/caseworker/cases/" + newCase() + "/witnesses/" + UNKNOWN_ID))
                .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("404s an Administrator addressing a witness that does not exist")
        void notFoundForAMissingWitness() throws Exception {
            // Also the proof that the role check did not absorb the not-found: the caller holds the role, so a
            // 403 here would mean the gate was refusing everyone and a 204 would mean it had swallowed the
            // absence. Compare the 403 the same request gets without the role, above.
            authorised(delete("/caseworker/cases/" + newCase() + "/witnesses/" + UNKNOWN_ID))
                .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("404s an Administrator deleting a witness through another case, and does not delete it")
        void refusesADeleteThroughAnotherCase() throws Exception {
            // Scoping still applies to someone who is allowed to delete: the gate and the scope are separate
            // rules, and holding the role does not widen which rows the route reaches.
            String caseId = newCase();
            String otherCaseId = newCase();
            String witnessId = addWitness(caseId, "Ours");

            authorised(delete("/caseworker/cases/" + otherCaseId + "/witnesses/" + witnessId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Witness " + witnessId + " not found"));

            // The scoping assertion that matters most on this verb: an unscoped delete would answer 204 and
            // destroy another case's witness, and nothing downstream would report it.
            assertThat(witnesses.findById(witnessId)).isPresent();
        }
    }

    /**
     * The regression guard for "do not gate create or edit".
     *
     * <p>The business gives both roles "Create, view and edit witness records", so the tidy-minded mistake --
     * gating a write because the note delete is gated -- would remove a capability caseworkers are meant to have.
     * Asserted in a group of its own, with the caller's roles stated in so many words, because everywhere else in
     * this class the roles are whatever {@code stubDefaults} happens to give.
     */
    @Nested
    @DisplayName("neither write is role-gated")
    class NotGated {

        @Test
        @DisplayName("a caseworker with no deed-poll role at all may add a witness")
        void basicUserMayCreate() throws Exception {
            IDAM.stubUserInfo(StubIdam.SUBJECT, "A Caseworker", List.of("caseworker-civil"));

            authorised(post("/caseworker/cases/" + newCase() + "/witnesses"), VALID_BODY)
                .andExpect(status().isCreated());

            assertThat(witnesses.count()).isEqualTo(1);
        }

        @Test
        @DisplayName("a caseworker with no deed-poll role at all may amend one")
        void basicUserMayAmend() throws Exception {
            String caseId = newCase();
            String witnessId = addWitness(caseId, "Wilma Witness");
            IDAM.stubUserInfo(StubIdam.SUBJECT, "A Caseworker", List.of("caseworker-civil"));

            authorised(patch("/caseworker/cases/" + caseId + "/witnesses/" + witnessId),
                "{\"witnessName\":\"Walter Witness\"}")
                .andExpect(status().isOk());

            assertThat(stored(witnessId).getWitnessName()).isEqualTo("Walter Witness");
        }

        @Test
        @DisplayName("an Administrator may too, so nothing was inverted")
        void anAdministratorMayAlso() throws Exception {
            // The other half. Without it, a handler that admitted only non-admins would pass the two above.
            IDAM.stubUserInfo(StubIdam.SUBJECT, "An Admin", List.of(Role.DEEDPOLL_ADMIN.claimValue()));

            authorised(post("/caseworker/cases/" + newCase() + "/witnesses"), VALID_BODY)
                .andExpect(status().isCreated());
        }
    }

    /** A stored case with a Date Received, which is all reference-number allocation needs. */
    private String newCase() {
        return deedPollService.createDeedPoll(DeedPollPatch.of(Map.of("dateReceived", RECEIVED)))
            .deedPoll()
            .getId();
    }

    /**
     * A witness stored through the domain.
     *
     * <p>Through the service rather than the repository, so a fixture cannot be a shape the API could not have
     * produced.
     */
    private String addWitness(String caseId, String name) {
        return witnessService
            .createWitness(caseId, new CreateWitnessInput(name, "1 Test Street", "Teacher", false))
            .getId();
    }

    private DeedPollWitness stored(String witnessId) {
        return witnesses.findById(witnessId).orElseThrow();
    }

    /** Backdates a stored witness, so an ordering assertion does not depend on the clock's resolution. */
    private void backdate(String witnessId, LocalDateTime createdOn) {
        DeedPollWitness witness = stored(witnessId);
        witness.setCreatedOn(createdOn);
        witnesses.saveAll(List.of(witness));
        witnesses.flush();
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
