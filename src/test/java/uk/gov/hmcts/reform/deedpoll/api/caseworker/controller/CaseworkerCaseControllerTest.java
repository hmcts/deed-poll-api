package uk.gov.hmcts.reform.deedpoll.api.caseworker.controller;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import uk.gov.hmcts.reform.deedpoll.auth.StubIdam;
import uk.gov.hmcts.reform.deedpoll.config.AbstractPostgresContainerTest;
import uk.gov.hmcts.reform.deedpoll.domain.auth.Role;
import uk.gov.hmcts.reform.deedpoll.domain.deedpoll.CaseState;
import uk.gov.hmcts.reform.deedpoll.domain.deedpoll.DeedPollPatch;
import uk.gov.hmcts.reform.deedpoll.domain.deedpoll.DeedPollService;
import uk.gov.hmcts.reform.deedpoll.domain.deedpoll.WriteContext;
import uk.gov.hmcts.reform.deedpoll.entity.DeedPoll;
import uk.gov.hmcts.reform.deedpoll.entity.DeedPollNote;
import uk.gov.hmcts.reform.deedpoll.entity.ReferenceCounter;
import uk.gov.hmcts.reform.deedpoll.entity.enums.DeedPollState;
import uk.gov.hmcts.reform.deedpoll.entity.enums.Privacy;
import uk.gov.hmcts.reform.deedpoll.repository.DeedPollNoteRepository;
import uk.gov.hmcts.reform.deedpoll.repository.DeedPollRepository;
import uk.gov.hmcts.reform.deedpoll.repository.ReferenceCounterRepository;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The two caseworker case writes, over HTTP, against a real database and a stub IDAM.
 *
 * <p>The whole context rather than a {@code @WebMvcTest} slice, and not {@code @Transactional}, for the reasons
 * {@code CaseworkerReadControllerTest} records: a slice asserts Spring Boot's default security rather than this
 * service's, and the writes have to really commit for an assertion to read the stored row back.
 *
 * <p><strong>Every assertion about what was written reads the stored row, not the response.</strong> A response
 * is built from the same objects the write path just mutated, so a mapping that never reached the database would
 * still answer correctly -- which is the failure mode the reference's own tests could not see, having asserted
 * the arguments a mocked Prisma client was called with. Where both matter, both are asserted.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CaseworkerCaseControllerTest extends AbstractPostgresContainerTest {

    /** A create cannot allocate a reference number without a Date Received to derive the year from. */
    private static final String RECEIVED = "2026-07-01T00:00:00.000Z";

    /** The first reference of 2026: prefix from the type, the year, and a 5-digit serial. */
    private static final String FIRST_REFERENCE = "KBDP2026-00001";

    private static final StubIdam IDAM = new StubIdam();

    static {
        // Started here rather than in @BeforeAll: @DynamicPropertySource is read while the context is being
        // built, which is before any @BeforeAll callback could have started the stub.
        IDAM.start();
    }

    @AfterAll
    static void stopIdam() {
        // A static start needs an explicit stop, or the class leaves a Jetty server, its thread pool and a
        // port behind for the life of the test JVM.
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
    private DeedPollNoteRepository notes;

    @Autowired
    private ReferenceCounterRepository referenceCounters;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    @AfterEach
    void clearTables() {
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
        @DisplayName("refuses a create")
        void refusesACreate() throws Exception {
            mockMvc.perform(post("/caseworker/cases")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"dateReceived\":\"" + RECEIVED + "\"}"))
                .andExpect(status().isUnauthorized());

            // The refusal has to happen before the handler, not inside it: a 401 that still wrote the row
            // would be the worst of both answers.
            assertThat(deedPolls.count()).isZero();
        }

        @Test
        @DisplayName("refuses a patch")
        void refusesAPatch() throws Exception {
            mockMvc.perform(patch("/caseworker/cases/any-id-at-all")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{}"))
                .andExpect(status().isUnauthorized());
        }
    }

    @Nested
    @DisplayName("POST /caseworker/cases")
    class CreateCase {

        @Test
        @DisplayName("creates the case, allocates its reference number and answers 201")
        void createsTheCase() throws Exception {
            String body = json(Map.of(
                "dateReceived", quoted(RECEIVED),
                "type", quoted("Adult"),
                "newForename", quoted("Ada"),
                "oldForename", quoted("Alan")));

            MvcResult result = authorised(post("/caseworker/cases"), body)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.referencenumber").value(1))
                .andExpect(jsonPath("$.displayReference").value(FIRST_REFERENCE))
                .andExpect(jsonPath("$.type").value("Adult"))
                .andExpect(jsonPath("$.newForename").value("Ada"))
                .andExpect(jsonPath("$.oldForename").value("Alan"))
                // Derived, not sent, and derived from the one date that was.
                .andExpect(jsonPath("$.state").value("Received"))
                .andExpect(jsonPath("$.dateReceived").value("2026-07-01T00:00:00"))
                // Both stamped on create: a create is the first modification, and the source header shows a
                // Modified By from the moment a record exists.
                .andExpect(jsonPath("$.createdBy").value("A Caseworker"))
                .andExpect(jsonPath("$.modifiedBy").value("A Caseworker"))
                .andReturn();

            String id = objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
            DeedPoll stored = deedPolls.findById(id).orElseThrow();
            assertThat(stored.getNewForename()).isEqualTo("Ada");
            assertThat(stored.getDisplayReference()).isEqualTo(FIRST_REFERENCE);
            assertThat(stored.getCreatedBy()).isEqualTo("A Caseworker");
        }

        @Test
        @DisplayName("400s with the failure attributed to dateReceived when there is none")
        void refusesACreateWithNoDateReceived() throws Exception {
            // The reference records this as a known sharp edge: thrown as a bare error it reached the error
            // middleware unrecognised and became a 500, so the most obvious request anyone makes of this API
            // answered "Internal server error".
            authorised(post("/caseworker/cases"), json(Map.of("newForename", quoted("Ada"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.length()").value(1))
                .andExpect(jsonPath("$.errors[0].field").value("dateReceived"))
                .andExpect(jsonPath("$.errors[0].message").exists());

            assertThat(deedPolls.count()).as("nothing written").isZero();
            assertThat(referenceCounters.count()).as("no serial consumed").isZero();
        }

        @Test
        @DisplayName("400s with every rejected field named, not one message for all of them")
        void refusesWithEveryFieldNamed() throws Exception {
            // The whole reason the domain carries FieldError[] rather than a message: the edit forms render
            // each failure against its own input, and a message-only error downgrades every one of them to an
            // unattributed banner.
            String body = json(Map.of(
                "dateReceived", quoted(RECEIVED),
                "newForename", quoted("A".repeat(1001)),
                "type", quoted("NotAType"),
                "ageOfMinor", "\"seven\""));

            authorised(post("/caseworker/cases"), body)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.length()").value(3))
                .andExpect(jsonPath("$.errors[*].field")
                    .value(containsInAnyOrder("newForename", "type", "ageOfMinor")));

            assertThat(deedPolls.count()).isZero();
        }

        @Test
        @DisplayName("400s for contradictory dates, and consumes no serial doing it")
        void refusesContradictoryDates() throws Exception {
            String body = json(Map.of(
                "dateReceived", quoted(RECEIVED),
                "dateEnroled", quoted("2026-07-11T00:00:00.000Z"),
                "dateReturned", quoted("2026-07-12T00:00:00.000Z")));

            authorised(post("/caseworker/cases"), body)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("state"))
                .andExpect(jsonPath("$.errors[0].message").value(CaseState.CONTRADICTION_MESSAGE));

            // The dates are checked before allocation, deliberately: a reference number is external-facing and
            // permanent, so a refused create must not leave a gap in the year's sequence.
            assertThat(referenceCounters.count()).as("no serial consumed").isZero();
        }

        @Test
        @DisplayName("strips the fields a caller may not set instead of refusing them")
        void stripsTheFieldsACallerMayNotSet() throws Exception {
            // The reference's "z.object() strips unknown keys", which the prototype got from a whitelisting
            // validation pipe. Stripped rather than refused because a caller echoing back a case it just read
            // is doing something reasonable -- but the reference number and the audit columns are not theirs.
            String body = json(Map.of(
                "dateReceived", quoted(RECEIVED),
                "displayReference", quoted("KBDP1999-00042"),
                "referencenumber", "42",
                "createdBy", quoted("Somebody Else"),
                "id", quoted("an-id-of-my-choosing"),
                "notAFieldAtAll", quoted("x")));

            authorised(post("/caseworker/cases"), body)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.displayReference").value(FIRST_REFERENCE))
                .andExpect(jsonPath("$.referencenumber").value(1))
                .andExpect(jsonPath("$.createdBy").value("A Caseworker"))
                .andExpect(jsonPath("$.id").value(not("an-id-of-my-choosing")));
        }

        @Test
        @DisplayName("overwrites a state a caller sent with the state the dates imply")
        void overwritesACallerSuppliedState() throws Exception {
            // state is accepted by the schema for seeding and import, and is then derived anyway: a caller
            // able to set it could put a case in Enroled with no enrolment date, which every list filter and
            // every report would then believe.
            String body = json(Map.of(
                "dateReceived", quoted(RECEIVED),
                "state", quoted("Enroled")));

            authorised(post("/caseworker/cases"), body)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.state").value("Received"));
        }

        @Test
        @DisplayName("prefixes a relinquishment KBDR, sharing the year's serial with the deed polls")
        void prefixesARelinquishment() throws Exception {
            authorised(post("/caseworker/cases"), json(Map.of(
                "dateReceived", quoted(RECEIVED),
                "type", quoted("Adult"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.displayReference").value("KBDP2026-00001"));

            authorised(post("/caseworker/cases"), json(Map.of(
                "dateReceived", quoted(RECEIVED),
                "type", quoted("DeedOfRelinquishment"))))
                .andExpect(status().isCreated())
                // 2, not 1: one counter per year, shared across the two prefixes, which is the reference's
                // decision and the source flow's.
                .andExpect(jsonPath("$.displayReference").value("KBDR2026-00002"))
                .andExpect(jsonPath("$.referencenumber").value(2));
        }

        @Test
        @DisplayName("drops a privacy a non-admin is not entitled to set")
        void dropsAPrivacyANonAdminMayNotSet() throws Exception {
            // Authorisation on the write path, not on the control: the edit form renders the input disabled for
            // a non-admin, and a disabled attribute is a courtesy to a cooperating browser. Dropped rather than
            // refused, so the rest of a legitimate save still lands.
            authorised(post("/caseworker/cases"), json(Map.of(
                "dateReceived", quoted(RECEIVED),
                "type", quoted("Adult"),
                "privacy", quoted("Private"),
                "newForename", quoted("Ada"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.privacy").value("Normal"))
                .andExpect(jsonPath("$.newForename").value("Ada"));
        }

        @Test
        @DisplayName("honours a privacy an admin sets")
        void honoursAPrivacyAnAdminSets() throws Exception {
            IDAM.stubUserInfo(StubIdam.SUBJECT, "An Admin", List.of(Role.DEEDPOLL_ADMIN.claimValue()));

            authorised(post("/caseworker/cases"), json(Map.of(
                "dateReceived", quoted(RECEIVED),
                "type", quoted("Adult"),
                "privacy", quoted("Private"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.privacy").value("Private"))
                .andExpect(jsonPath("$.createdBy").value("An Admin"));
        }

        @Test
        @DisplayName("does not publish the child collections, which a create did not read")
        void doesNotPublishChildCollections() throws Exception {
            // Absent rather than empty. A write does not fetch the children, so it has nothing true to say
            // about them; see SavedCaseResponse.
            authorised(post("/caseworker/cases"), json(Map.of("dateReceived", quoted(RECEIVED))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.notes").doesNotExist())
                .andExpect(jsonPath("$.witnesses").doesNotExist())
                .andExpect(jsonPath("$.documents").doesNotExist());
        }

        @Test
        @DisplayName("400s in the service's own error shape for a body that is not JSON")
        void refusesAnUnreadableBody() throws Exception {
            // Not a nicety: without the handler this is Spring Boot's default error attributes, so a client has
            // two things to parse for the same status. The reference answers 500 for it, which is a client
            // error reported as a server error.
            authorised(post("/caseworker/cases"), "{ not json")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Request body is missing or is not valid JSON"))
                .andExpect(jsonPath("$.errors").doesNotExist());

            authorised(post("/caseworker/cases"), "")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Request body is missing or is not valid JSON"));
        }
    }

    @Nested
    @DisplayName("PATCH /caseworker/cases/{id}")
    class UpdateCase {

        @Test
        @DisplayName("applies the fields it mentions and re-stamps the author")
        void appliesTheFieldsItMentions() throws Exception {
            DeedPoll record = newCase(Map.of("newForename", "Ada", "oldForename", "Alan"));
            IDAM.stubUserInfo(StubIdam.SUBJECT, "Another Caseworker", List.of("caseworker-civil"));

            authorised(patch("/caseworker/cases/" + record.getId()), json(Map.of(
                "newForename", quoted("Bea"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.newForename").value("Bea"))
                // Untouched by a patch that did not mention it, which is the whole of PATCH.
                .andExpect(jsonPath("$.oldForename").value("Alan"))
                .andExpect(jsonPath("$.modifiedBy").value("Another Caseworker"))
                // The fixture was created through the domain with no identified user, so it is attributed to
                // the service account -- and a patch by somebody else must not rewrite who created it. The
                // reference deletes createdBy from an update patch for this; here it cannot be in one.
                .andExpect(jsonPath("$.createdBy").value(WriteContext.SERVICE_ACCOUNT_NAME));

            DeedPoll stored = deedPolls.findById(record.getId()).orElseThrow();
            assertThat(stored.getNewForename()).isEqualTo("Bea");
            assertThat(stored.getOldForename()).isEqualTo("Alan");
        }

        @Test
        @DisplayName("clears a field sent as null, and leaves an omitted one alone")
        void clearsAnExplicitNullAndLeavesAnOmittedFieldAlone() throws Exception {
            // The distinction the reference gets free from JavaScript and this port had to build a keyed map to
            // keep. Both directions, because a request DTO of nullable components passes the first assertion
            // and fails the second -- it clears every field the caller never mentioned.
            DeedPoll record = newCase(Map.of(
                "newForename", "Ada",
                "dateEnroled", "2026-07-11T00:00:00.000Z"));
            assertThat(record.getState())
                .as("premise: the enrolment date drove the state")
                .isEqualTo(DeedPollState.Enroled);

            authorised(patch("/caseworker/cases/" + record.getId()), json(Map.of(
                "newForename", quoted("Bea"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dateEnroled").value("2026-07-11T00:00:00"))
                .andExpect(jsonPath("$.state").value("Enroled"));

            authorised(patch("/caseworker/cases/" + record.getId()), "{\"dateEnroled\":null}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dateEnroled").value(nullValue()))
                // Re-derived from what is left, so clearing the date really un-enrols the case rather than
                // leaving a state nothing supports.
                .andExpect(jsonPath("$.state").value("Received"))
                .andExpect(jsonPath("$.newForename").value("Bea"));

            assertThat(deedPolls.findById(record.getId()).orElseThrow().getDateEnroled()).isNull();
        }

        @Test
        @DisplayName("re-prefixes the reference when the type changes, without renumbering")
        void reprefixesWhenTheTypeChanges() throws Exception {
            DeedPoll record = newCase(Map.of("type", "Adult"));
            assertThat(record.getDisplayReference()).isEqualTo(FIRST_REFERENCE);

            authorised(patch("/caseworker/cases/" + record.getId()), json(Map.of(
                "type", quoted("DeedOfRelinquishment"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayReference").value("KBDR2026-00001"))
                // The serial is allocated once and never again: a case's reference is external-facing, and a
                // second allocation would both renumber it and burn a serial.
                .andExpect(jsonPath("$.referencenumber").value(1));

            assertThat(referenceCounters.findAll()).singleElement()
                .extracting(ReferenceCounter::getLastSerial)
                .isEqualTo(1);
        }

        @Test
        @DisplayName("404s for a case that does not exist, with the domain's message")
        void notFoundForAMissingCase() throws Exception {
            authorised(patch("/caseworker/cases/11111111-1111-1111-1111-111111111111"),
                json(Map.of("newForename", quoted("Ada"))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value(
                    "DeedPoll 11111111-1111-1111-1111-111111111111 not found"))
                .andExpect(jsonPath("$.errors").doesNotExist());
        }

        @Test
        @DisplayName("400s with the failure attributed to its field, leaving the row alone")
        void refusesAnInvalidFieldAndLeavesTheRowAlone() throws Exception {
            DeedPoll record = newCase(Map.of("newForename", "Ada"));

            authorised(patch("/caseworker/cases/" + record.getId()), json(Map.of(
                "newForename", quoted("Bea"),
                "type", quoted("NotAType"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.length()").value(1))
                .andExpect(jsonPath("$.errors[0].field").value("type"));

            // A rejected write must not have half-happened. The service validates before it touches the
            // entity for exactly this, rather than relying on the transaction rolling back.
            assertThat(deedPolls.findById(record.getId()).orElseThrow().getNewForename()).isEqualTo("Ada");
        }

        @Test
        @DisplayName("400s for dates that contradict each other once merged")
        void refusesDatesThatContradictOnceMerged() throws Exception {
            // The patch mentions one date and the rule is about a pair: the stored record supplies the other,
            // which is why the rules read the merged record rather than the payload.
            DeedPoll record = newCase(Map.of("signatureRequestedDate", "2026-07-05T00:00:00.000Z"));

            authorised(patch("/caseworker/cases/" + record.getId()), json(Map.of(
                "signatureReceivedDate", quoted("2026-07-02T00:00:00.000Z"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("signatureReceivedDate"))
                .andExpect(jsonPath("$.errors[0].message").value(CaseState.SIGNATURE_ORDER_MESSAGE));

            assertThat(deedPolls.findById(record.getId()).orElseThrow().getSignatureReceivedDate()).isNull();
        }

        @Test
        @DisplayName("drops a privacy a non-admin may not set while saving the rest of their patch")
        void dropsPrivacyForANonAdminAndSavesTheRest() throws Exception {
            // Not a 403. A form legitimately omitting a disabled input must still be able to save its tab, so
            // refusing the whole write would block a save the caseworker is entitled to make.
            DeedPoll record = newCase(Map.of("type", "Adult"));

            authorised(patch("/caseworker/cases/" + record.getId()), json(Map.of(
                "privacy", quoted("Private"),
                "newForename", quoted("Bea"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.privacy").value("Normal"))
                .andExpect(jsonPath("$.newForename").value("Bea"));

            assertThat(deedPolls.findById(record.getId()).orElseThrow().getPrivacy()).isEqualTo(Privacy.Normal);
        }

        @Test
        @DisplayName("honours a privacy an admin sets on an existing case")
        void honoursAPrivacyAnAdminSets() throws Exception {
            DeedPoll record = newCase(Map.of("type", "Adult"));
            IDAM.stubUserInfo(StubIdam.SUBJECT, "An Admin", List.of(Role.DEEDPOLL_ADMIN.claimValue()));

            authorised(patch("/caseworker/cases/" + record.getId()), json(Map.of(
                "privacy", quoted("Private"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.privacy").value("Private"));
        }

        @Test
        @DisplayName("accepts an empty patch, which re-stamps the author rather than doing nothing")
        void acceptsAnEmptyPatch() throws Exception {
            DeedPoll record = newCase(Map.of("newForename", "Ada"));
            IDAM.stubUserInfo(StubIdam.SUBJECT, "Another Caseworker", List.of("caseworker-civil"));

            authorised(patch("/caseworker/cases/" + record.getId()), "{}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.newForename").value("Ada"))
                .andExpect(jsonPath("$.modifiedBy").value("Another Caseworker"));
        }

        @Test
        @DisplayName("strips the fields a caller may not set instead of refusing them")
        void stripsTheFieldsACallerMayNotSet() throws Exception {
            DeedPoll record = newCase(Map.of("newForename", "Ada"));

            authorised(patch("/caseworker/cases/" + record.getId()), json(Map.of(
                "displayReference", quoted("KBDP1999-00042"),
                "createdBy", quoted("Somebody Else"),
                "newForename", quoted("Bea"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayReference").value(FIRST_REFERENCE))
                .andExpect(jsonPath("$.createdBy").value(WriteContext.SERVICE_ACCOUNT_NAME))
                .andExpect(jsonPath("$.newForename").value("Bea"));
        }

        @Test
        @DisplayName("does not publish the child collections, which a patch did not read")
        void doesNotPublishChildCollections() throws Exception {
            DeedPoll record = newCase(Map.of("newForename", "Ada"));
            addNote(record, "Called the applicant");

            // The case really has a note, so an empty array here would be a wrong answer rather than a vacuous
            // one -- which is why SavedCaseResponse omits the collections instead of publishing them empty.
            authorised(patch("/caseworker/cases/" + record.getId()), json(Map.of(
                "newForename", quoted("Bea"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.notes").doesNotExist())
                .andExpect(jsonPath("$.witnesses").doesNotExist())
                .andExpect(jsonPath("$.documents").doesNotExist());
        }
    }

    /** A stored case with a Date Received, plus whatever else the test needs on it. */
    private DeedPoll newCase(Map<String, Object> values) {
        Map<String, Object> patch = new LinkedHashMap<>();
        patch.put("dateReceived", RECEIVED);
        patch.putAll(values);
        String id = deedPollService.createDeedPoll(DeedPollPatch.of(patch)).deedPoll().getId();
        return deedPolls.findById(id).orElseThrow();
    }

    private void addNote(DeedPoll record, String text) {
        DeedPollNote note = new DeedPollNote();
        note.setDeedPoll(record);
        note.setNote(text);
        notes.save(note);
    }

    /**
     * A JSON object body from already-JSON values.
     *
     * <p>Assembled as text rather than serialised from a map, because half of what these tests are about is
     * what arrives on the wire -- an explicit null, a number where a string was expected, a key the schema does
     * not know. A serialiser would normalise several of those away.
     */
    private static String json(Map<String, String> fields) {
        return fields.entrySet().stream()
            .map(field -> "\"" + field.getKey() + "\":" + field.getValue())
            .reduce((left, right) -> left + "," + right)
            .map(body -> "{" + body + "}")
            .orElse("{}");
    }

    private static String quoted(String value) {
        return "\"" + value + "\"";
    }

    /** The request, carrying a valid forwarded access token and a JSON body. */
    private ResultActions authorised(MockHttpServletRequestBuilder request, String body) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(IDAM.accessToken());
        return mockMvc.perform(request.headers(headers).contentType(MediaType.APPLICATION_JSON).content(body));
    }
}
