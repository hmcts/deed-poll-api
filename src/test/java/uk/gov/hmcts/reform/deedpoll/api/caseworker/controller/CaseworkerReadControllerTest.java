package uk.gov.hmcts.reform.deedpoll.api.caseworker.controller;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
import uk.gov.hmcts.reform.deedpoll.domain.deedpoll.DeedPollPatch;
import uk.gov.hmcts.reform.deedpoll.domain.deedpoll.DeedPollService;
import uk.gov.hmcts.reform.deedpoll.domain.deedpoll.FieldRule;
import uk.gov.hmcts.reform.deedpoll.entity.DeedPoll;
import uk.gov.hmcts.reform.deedpoll.entity.DeedPollNote;
import uk.gov.hmcts.reform.deedpoll.entity.DeedPollSupportingDocument;
import uk.gov.hmcts.reform.deedpoll.entity.DeedPollSupportingDocumentType;
import uk.gov.hmcts.reform.deedpoll.entity.DeedPollWitness;
import uk.gov.hmcts.reform.deedpoll.entity.enums.DeedPollType;
import uk.gov.hmcts.reform.deedpoll.entity.enums.DocumentDirection;
import uk.gov.hmcts.reform.deedpoll.repository.DeedPollNoteRepository;
import uk.gov.hmcts.reform.deedpoll.repository.DeedPollRepository;
import uk.gov.hmcts.reform.deedpoll.repository.DeedPollSupportingDocumentRepository;
import uk.gov.hmcts.reform.deedpoll.repository.DeedPollSupportingDocumentTypeRepository;
import uk.gov.hmcts.reform.deedpoll.repository.DeedPollWitnessRepository;
import uk.gov.hmcts.reform.deedpoll.repository.ReferenceCounterRepository;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The four caseworker read endpoints, over HTTP, against a real database and a stub IDAM.
 *
 * <p><strong>The whole context, not a {@code @WebMvcTest} slice.</strong> A slice does not get
 * {@code SecurityConfiguration} -- its include list covers {@code SecurityFilterChain} beans and a
 * {@code @Configuration} that merely declares one is not itself one -- so it falls back to Spring Boot's
 * defaults and asserts those instead of this service's rules, in both directions. Task 12 records where that
 * bit; a controller test that means to say "this route requires a token" has to boot everything.
 *
 * <p>Not {@code @Transactional}: the fixtures must really commit, because the request reads them through the
 * application's own connection. The tables are cleared around each test instead, in foreign-key order.
 *
 * <p>Where an assertion could be transcribed from the domain instead of read from it, it is read from it --
 * the field-rules matrix especially. A hand-copied expectation agrees with its source on the day it is
 * written, and the whole point of these endpoints is that they publish what the domain decided.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CaseworkerReadControllerTest extends AbstractPostgresContainerTest {

    /** createDeedPoll cannot allocate a reference number without a Date Received to derive the year from. */
    private static final String RECEIVED = "2026-07-01T00:00:00.000Z";

    private static final StubIdam IDAM = new StubIdam();

    static {
        IDAM.start();
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
    private DeedPollWitnessRepository witnesses;

    @Autowired
    private DeedPollSupportingDocumentRepository documents;

    @Autowired
    private DeedPollSupportingDocumentTypeRepository documentTypes;

    @Autowired
    private ReferenceCounterRepository referenceCounters;

    @BeforeEach
    @AfterEach
    void clearTables() {
        documents.deleteAll();
        documentTypes.deleteAll();
        notes.deleteAll();
        witnesses.deleteAll();
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

        /**
         * Every read route, refused. Listed rather than tested once, because "authenticated by omission" is
         * only true of a route if it is actually true of that route.
         */
        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {
            "/caseworker/cases",
            "/caseworker/cases/field-rules",
            "/caseworker/cases/any-id-at-all",
            "/caseworker/document-types"
        })
        @DisplayName("refuses every caseworker read route")
        void refusesEveryReadRoute(String path) throws Exception {
            mockMvc.perform(get(path)).andExpect(status().isUnauthorized());
        }
    }

    @Nested
    @DisplayName("GET /caseworker/cases")
    class CaseList {

        @Test
        @DisplayName("returns the cases newest first, with the columns the list renders")
        void returnsTheCasesNewestFirst() throws Exception {
            DeedPoll older = backdated(
                newCase(Map.of("newForename", "Older", "type", DeedPollType.Adult)),
                LocalDateTime.parse("2026-06-01T09:00:00"));
            DeedPoll newer = backdated(
                newCase(Map.of("newForename", "Newer", "type", DeedPollType.Minor)),
                LocalDateTime.parse("2026-08-01T09:00:00"));

            authorised(get("/caseworker/cases"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.filter").value("all"))
                .andExpect(jsonPath("$.cases.length()").value(2))
                .andExpect(jsonPath("$.cases[0].id").value(newer.getId()))
                .andExpect(jsonPath("$.cases[0].displayReference").value(newer.getDisplayReference()))
                .andExpect(jsonPath("$.cases[0].newForename").value("Newer"))
                .andExpect(jsonPath("$.cases[0].type").value("Minor"))
                .andExpect(jsonPath("$.cases[0].state").value("Received"))
                .andExpect(jsonPath("$.cases[0].privacy").value("Normal"))
                // The wire format, pinned deliberately: ISO-8601 with no offset, and the seconds field always
                // written even when it is zero -- which is Jackson's rendering and not LocalDateTime's own
                // toString(), so it is worth an assertion rather than an assumption. No offset because the
                // column is timestamp(3) without a time zone; inventing one would be a claim the data does
                // not make.
                .andExpect(jsonPath("$.cases[0].dateReceived").value("2026-07-01T00:00:00"))
                .andExpect(jsonPath("$.cases[1].id").value(older.getId()))
                .andExpect(jsonPath("$.cases[1].newForename").value("Older"));
        }

        @Test
        @DisplayName("publishes only the six columns and the id, not the whole case")
        void publishesOnlyTheListColumns() throws Exception {
            // The row shape is the data-minimisation claim CaseSummaryResponse makes, so it is asserted
            // rather than described: a list of a few thousand cases must not hand out every applicant's
            // contact details to render six columns.
            newCase(Map.of("applicantEmail", "ada@example.invalid", "referralNotes", "Sensitive"));

            authorised(get("/caseworker/cases"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cases[0].applicantEmail").doesNotExist())
                .andExpect(jsonPath("$.cases[0].applicantTelephone").doesNotExist())
                .andExpect(jsonPath("$.cases[0].referralNotes").doesNotExist())
                .andExpect(jsonPath("$.cases[0].notes").doesNotExist())
                .andExpect(jsonPath("$.cases[0].documents").doesNotExist());
        }

        @Test
        @DisplayName("the open filter excludes the two finished states")
        void theOpenFilterExcludesFinishedCases() throws Exception {
            newCase(Map.of("newForename", "Open"));
            newCase(Map.of("newForename", "Done", "dateEnroled", RECEIVED));
            newCase(Map.of("newForename", "Sent back", "dateReturned", RECEIVED));

            authorised(get("/caseworker/cases").param("filter", "open"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.filter").value("open"))
                .andExpect(jsonPath("$.cases.length()").value(1))
                .andExpect(jsonPath("$.cases[0].newForename").value("Open"))
                .andExpect(jsonPath("$.cases[0].state").value("Received"));

            authorised(get("/caseworker/cases").param("filter", "enroled"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cases.length()").value(1))
                .andExpect(jsonPath("$.cases[0].newForename").value("Done"));
        }

        @Test
        @DisplayName("an unrecognised filter falls back to all, and says which filter was applied")
        void anUnrecognisedFilterFallsBackAndSaysSo() throws Exception {
            newCase(Map.of("newForename", "Open"));
            newCase(Map.of("newForename", "Done", "dateEnroled", RECEIVED));

            // The fallback itself is ListFilters' decision and the right one -- the key comes from a URL a
            // caseworker may have bookmarked. What is asserted here is that it is not silent: a caller
            // showing "Enroled" as the active tab while listing everything is the failure the echo prevents.
            authorised(get("/caseworker/cases").param("filter", "nonsense"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.filter").value("all"))
                .andExpect(jsonPath("$.cases.length()").value(2));
        }

        @Test
        @DisplayName("answers an empty list rather than a 404 when no case matches")
        void answersAnEmptyListWhenNothingMatches() throws Exception {
            // A collection that exists and is empty is a 200. Only a child collection under a parent that
            // does not exist is a 404, which is task 15's business.
            authorised(get("/caseworker/cases"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cases").isArray())
                .andExpect(jsonPath("$.cases.length()").value(0));
        }
    }

    @Nested
    @DisplayName("GET /caseworker/cases/{id}")
    class CaseDetail {

        @Test
        @DisplayName("returns the case with its notes, witnesses and documents from the one call")
        void returnsTheCaseWithItsChildren() throws Exception {
            DeedPoll record = newCase(Map.of("newForename", "Ada", "oldForename", "Alan"));
            addNote(record, "Called the applicant");
            addWitness(record, "Wanda Witness", true);
            DeedPollSupportingDocumentType type = addDocumentType("Birth certificate");
            addDocument(record, type, "Birth certificate", "stored/key-1");

            authorised(get("/caseworker/cases/" + record.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(record.getId()))
                .andExpect(jsonPath("$.displayReference").value(record.getDisplayReference()))
                .andExpect(jsonPath("$.newForename").value("Ada"))
                .andExpect(jsonPath("$.oldForename").value("Alan"))
                .andExpect(jsonPath("$.notes.length()").value(1))
                .andExpect(jsonPath("$.notes[0].note").value("Called the applicant"))
                .andExpect(jsonPath("$.witnesses.length()").value(1))
                .andExpect(jsonPath("$.witnesses[0].witnessName").value("Wanda Witness"))
                .andExpect(jsonPath("$.witnesses[0].declarant").value(true))
                .andExpect(jsonPath("$.documents.length()").value(1))
                .andExpect(jsonPath("$.documents[0].name").value("Birth certificate"))
                .andExpect(jsonPath("$.documents[0].documentType.id").value(type.getId()))
                .andExpect(jsonPath("$.documents[0].documentType.name").value("Birth certificate"))
                .andExpect(jsonPath("$.documents[0].direction").value("Incoming"))
                .andExpect(jsonPath("$.documents[0].hasFile").value(true));
        }

        @Test
        @DisplayName("publishes the mixed-case field names exactly as the ported schema spells them")
        void publishesTheMixedCaseFieldNamesVerbatim() throws Exception {
            // Every column in this schema is mixed case, and two of the names are odd on purpose: the three
            // multiselects begin with a lower-case letter followed by a capital, and signatureReceivedDate
            // maps a column the source misspells. A serialiser convention that lower-cased or snake-cased a
            // key would break every consumer, and would do it silently.
            DeedPoll record = newCase(Map.of(
                "newForename", "Ada",
                "iShall", List.of(1),
                "signatureRequestedDate", "2026-07-02T00:00:00.000Z",
                "signatureReceivedDate", "2026-07-03T09:30:15.000Z"));

            authorised(get("/caseworker/cases/" + record.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.iShall").isArray())
                .andExpect(jsonPath("$.iShall[0]").value(1))
                .andExpect(jsonPath("$.iHaveSubscribed").isArray())
                .andExpect(jsonPath("$.andAlsoMyOld").isArray())
                .andExpect(jsonPath("$.signatureReceivedDate").value("2026-07-03T09:30:15"))
                .andExpect(jsonPath("$.newForename").value("Ada"))
                .andExpect(jsonPath("$.migratedRecord").value(false))
                .andExpect(jsonPath("$.publishToLondonGazette").value(true));
        }

        @Test
        @DisplayName("carries the derived day counts, which are not stored columns")
        void carriesTheDerivedDayCounts() throws Exception {
            DeedPoll record = newCase(Map.of("dateEnroled", "2026-07-11T00:00:00.000Z"));

            authorised(get("/caseworker/cases/" + record.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.daysReceivedToEnroled").value(10))
                // A null rather than an omitted key: the count is unknown because the case has not been
                // returned, which is a value a consumer renders as "Not provided", not an absence to branch
                // on -- the distinction ComputedFields records.
                .andExpect(jsonPath("$.daysReceivedToReturned").value(nullValue()));
        }

        @Test
        @DisplayName("does not publish a document's storage key, which is an internal locator")
        void doesNotPublishADocumentsStorageKey() throws Exception {
            DeedPoll record = newCase(Map.of());
            DeedPollSupportingDocumentType type = addDocumentType("Marriage certificate");
            addDocument(record, type, "Marriage certificate", "stored/key-2");

            authorised(get("/caseworker/cases/" + record.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.documents[0].storageKey").doesNotExist())
                .andExpect(jsonPath("$.documents[0].hasFile").value(true));
        }

        @Test
        @DisplayName("says a document has no file when no blob has arrived for it yet")
        void saysADocumentHasNoFileWhenNoBlobHasArrived() throws Exception {
            // A metadata row can legitimately exist before its file does, and a caller linking to a download
            // for one of those would 404. hasFile is the whole reason storageKey does not need publishing.
            DeedPoll record = newCase(Map.of());
            addDocument(record, addDocumentType("Statutory declaration"), "Awaiting scan", null);

            authorised(get("/caseworker/cases/" + record.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.documents[0].hasFile").value(false));
        }

        @Test
        @DisplayName("404s for a case that does not exist, with the domain's message")
        void notFoundForAMissingCase() throws Exception {
            authorised(get("/caseworker/cases/11111111-1111-1111-1111-111111111111"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value(
                    "DeedPoll 11111111-1111-1111-1111-111111111111 not found"))
                .andExpect(jsonPath("$.errors").doesNotExist());
        }
    }

    @Nested
    @DisplayName("GET /caseworker/cases/field-rules")
    class FieldRules {

        @Test
        @DisplayName("resolves ahead of the single-case route rather than being read as a case id")
        void resolvesAheadOfTheSingleCaseRoute() throws Exception {
            // The trap the reference records about its own router: a static segment that loses to a dynamic
            // one answers 404 for a route that exists. Spring orders a literal pattern first, and this is
            // what says so -- a 404 here would mean the request reached findDeedPoll("field-rules").
            authorised(get("/caseworker/cases/field-rules"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rules").isMap());
        }

        @Test
        @DisplayName("publishes the whole matrix the domain answers with, for a type")
        void publishesTheWholeMatrixForAType() throws Exception {
            Map<String, FieldRule> expected = deedPollService.getCaseFieldRules(DeedPollType.Adult, false, true);
            assertThat(expected).isNotEmpty();

            ResultActions result = authorised(get("/caseworker/cases/field-rules").param("type", "Adult"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rules.length()").value(expected.size()));

            // Every field, both answers, compared against the domain rather than against a transcription of
            // it: this endpoint's only job is to publish that map faithfully.
            for (Map.Entry<String, FieldRule> rule : expected.entrySet()) {
                result
                    .andExpect(jsonPath("$.rules." + rule.getKey() + ".visible").value(rule.getValue().visible()))
                    .andExpect(jsonPath("$.rules." + rule.getKey() + ".required")
                        .value(rule.getValue().required()));
            }
        }

        @Test
        @DisplayName("honours migrated, which relaxes the per-type required set")
        void honoursMigrated() throws Exception {
            String field = "maritalStatus";
            assertThat(deedPollService.getCaseFieldRules(DeedPollType.Adult, false, true).get(field).required())
                .as("premise: the field is required for an Adult case that was not migrated")
                .isTrue();
            assertThat(deedPollService.getCaseFieldRules(DeedPollType.Adult, true, true).get(field).required())
                .as("premise: migration relaxes it")
                .isFalse();

            authorised(get("/caseworker/cases/field-rules").param("type", "Adult").param("migrated", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rules." + field + ".required").value(false));
        }

        @Test
        @DisplayName("honours publishToGazette, which hides the notice date")
        void honoursPublishToGazette() throws Exception {
            String field = "dateNoticeSentToLondonGazette";
            assertThat(deedPollService.getCaseFieldRules(DeedPollType.Adult, false, true).get(field).visible())
                .as("premise: the notice date shows when the case is to be published")
                .isTrue();

            authorised(get("/caseworker/cases/field-rules")
                .param("type", "Adult")
                .param("publishToGazette", "false"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rules." + field + ".visible").value(false));
        }

        @Test
        @DisplayName("answers for no type at all, which is what a case with no type yet has")
        void answersForNoType() throws Exception {
            Map<String, FieldRule> expected = deedPollService.getCaseFieldRules(null, false, true);

            authorised(get("/caseworker/cases/field-rules"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rules.length()").value(expected.size()));
        }

        @Test
        @DisplayName("refuses an unrecognised type rather than answering for no type at all")
        void refusesAnUnrecognisedType() throws Exception {
            // The prototype cast an unvalidated query parameter, so ?type=nonsense fell through the matrix
            // to the rules for a typeless case -- a wrong answer presented as a right one.
            authorised(get("/caseworker/cases/field-rules").param("type", "nonsense"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Invalid request parameter"))
                .andExpect(jsonPath("$.errors.length()").value(1))
                .andExpect(jsonPath("$.errors[0].field").value("type"));
        }

        @Test
        @DisplayName("refuses a repeated type parameter rather than quietly picking the first")
        void refusesARepeatedTypeParameter() throws Exception {
            // Spring's own conversion takes the first value silently, which would answer the Adult rules for
            // a request that also asked for Minor -- the reference's "wrong answer presented as a right one"
            // reached by a different route. Hence the parameter is bound as a list and resolved explicitly.
            authorised(get("/caseworker/cases/field-rules").param("type", "Adult", "Minor"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Invalid request parameter"))
                .andExpect(jsonPath("$.errors.length()").value(1))
                .andExpect(jsonPath("$.errors[0].field").value("type"));
        }

        @Test
        @DisplayName("refuses a flag that is not a boolean, naming the flag")
        void refusesANonBooleanFlag() throws Exception {
            // The other half of the refusal: Spring cannot convert this one, so it arrives as a
            // MethodArgumentTypeMismatchException. Both paths answer with the same summary and the same
            // attribution, which is what the shared constant is for.
            authorised(get("/caseworker/cases/field-rules").param("migrated", "maybe"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Invalid request parameter"))
                .andExpect(jsonPath("$.errors[0].field").value("migrated"));
        }
    }

    @Nested
    @DisplayName("GET /caseworker/document-types")
    class DocumentTypes {

        @Test
        @DisplayName("returns every type alphabetically, with its applicability arrays")
        void returnsEveryTypeAlphabetically() throws Exception {
            DeedPollSupportingDocumentType marriage = addDocumentType("Marriage certificate");
            marriage.setApplicableMaritalStatuses(new Integer[] {2});
            marriage.setApplicableTypes(new Integer[] {1, 2});
            documentTypes.save(marriage);
            addDocumentType("Birth certificate");

            authorised(get("/caseworker/document-types"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].name").value("Birth certificate"))
                .andExpect(jsonPath("$[0].applicableMaritalStatuses").isArray())
                .andExpect(jsonPath("$[1].name").value("Marriage certificate"))
                .andExpect(jsonPath("$[1].applicableMaritalStatuses[0]").value(2))
                .andExpect(jsonPath("$[1].applicableTypes.length()").value(2));
        }

        @Test
        @DisplayName("answers an empty array when none are seeded, not a 404")
        void answersAnEmptyArrayWhenNoneAreSeeded() throws Exception {
            authorised(get("/caseworker/document-types"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
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

    /**
     * The same case with an explicit creation time.
     *
     * <p>Set explicitly rather than trusting two creates to land in different microseconds, which is what
     * an ordering assertion would otherwise be resting on.
     */
    private DeedPoll backdated(DeedPoll record, LocalDateTime createdOn) {
        record.setCreatedOn(createdOn);
        return deedPolls.save(record);
    }

    private void addNote(DeedPoll record, String text) {
        DeedPollNote note = new DeedPollNote();
        note.setDeedPoll(record);
        note.setNote(text);
        notes.save(note);
    }

    private void addWitness(DeedPoll record, String name, boolean declarant) {
        DeedPollWitness witness = new DeedPollWitness();
        witness.setDeedPoll(record);
        witness.setWitnessName(name);
        witness.setWitnessAddress("1 Test Street");
        witness.setWitnessOccupation("Tester");
        witness.setDeclarant(declarant);
        witnesses.save(witness);
    }

    private DeedPollSupportingDocumentType addDocumentType(String name) {
        DeedPollSupportingDocumentType type = new DeedPollSupportingDocumentType();
        type.setName(name);
        return documentTypes.save(type);
    }

    private void addDocument(DeedPoll record,
                            DeedPollSupportingDocumentType type,
                            String name,
                            String storageKey) {
        DeedPollSupportingDocument document = new DeedPollSupportingDocument();
        document.setDeedPoll(record);
        document.setDocumentType(type);
        document.setName(name);
        document.setDirection(DocumentDirection.Incoming);
        document.setStorageKey(storageKey);
        documents.save(document);
    }

    /** The request, carrying a valid forwarded access token. */
    private ResultActions authorised(MockHttpServletRequestBuilder request) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(IDAM.accessToken());
        return mockMvc.perform(request.headers(headers));
    }
}
