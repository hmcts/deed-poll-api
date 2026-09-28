package uk.gov.hmcts.reform.deedpoll.api.caseworker.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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
import uk.gov.hmcts.reform.deedpoll.api.caseworker.dto.CaseSummaryResponse;
import uk.gov.hmcts.reform.deedpoll.auth.StubIdam;
import uk.gov.hmcts.reform.deedpoll.config.AbstractPostgresContainerTest;
import uk.gov.hmcts.reform.deedpoll.domain.deedpoll.DeedPollFields;
import uk.gov.hmcts.reform.deedpoll.domain.deedpoll.DeedPollPatch;
import uk.gov.hmcts.reform.deedpoll.domain.deedpoll.DeedPollService;
import uk.gov.hmcts.reform.deedpoll.domain.deedpoll.FieldRule;
import uk.gov.hmcts.reform.deedpoll.domain.deedpoll.ListFilters;
import uk.gov.hmcts.reform.deedpoll.entity.DeedPoll;
import uk.gov.hmcts.reform.deedpoll.entity.DeedPollNote;
import uk.gov.hmcts.reform.deedpoll.entity.DeedPollSupportingDocument;
import uk.gov.hmcts.reform.deedpoll.entity.DeedPollSupportingDocumentType;
import uk.gov.hmcts.reform.deedpoll.entity.DeedPollWitness;
import uk.gov.hmcts.reform.deedpoll.entity.enums.DeedPollState;
import uk.gov.hmcts.reform.deedpoll.entity.enums.DeedPollType;
import uk.gov.hmcts.reform.deedpoll.entity.enums.DocumentDirection;
import uk.gov.hmcts.reform.deedpoll.repository.DeedPollNoteRepository;
import uk.gov.hmcts.reform.deedpoll.repository.DeedPollRepository;
import uk.gov.hmcts.reform.deedpoll.repository.DeedPollSupportingDocumentRepository;
import uk.gov.hmcts.reform.deedpoll.repository.DeedPollSupportingDocumentTypeRepository;
import uk.gov.hmcts.reform.deedpoll.repository.DeedPollWitnessRepository;
import uk.gov.hmcts.reform.deedpoll.repository.ReferenceCounterRepository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
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
    private DeedPollWitnessRepository witnesses;

    @Autowired
    private DeedPollSupportingDocumentRepository documents;

    @Autowired
    private DeedPollSupportingDocumentTypeRepository documentTypes;

    @Autowired
    private ReferenceCounterRepository referenceCounters;

    /**
     * The application's own mapper, not a fresh one.
     *
     * <p>Used where an assertion compares a response body against what the domain would serialise: taking
     * the configured bean means the comparison is made in the service's wire format, including whatever
     * modules and date handling that format has, rather than in a default one that happens to agree today.
     */
    @Autowired
    private ObjectMapper mapper;

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
        documents.deleteAll();
        documentTypes.deleteAll();
        notes.deleteAll();
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
        @DisplayName("publishes only the list columns and the id, not the whole case")
        void publishesOnlyTheListColumns() throws Exception {
            // The row shape is the data-minimisation claim CaseSummaryResponse makes, so it is asserted
            // rather than described: a list of a few thousand cases must not hand out every applicant's
            // contact details to render seven columns.
            newCase(Map.of(
                "newSurname", "Threepwood",
                "applicantEmail", "ada@example.invalid",
                "referralNotes", "Sensitive"));

            authorised(get("/caseworker/cases"))
                .andExpect(status().isOk())
                // The one column beyond CASE_LIST_COLUMNS, and a correctness requirement of the search: a
                // term matching a surname must return a row that can show the surname it matched.
                .andExpect(jsonPath("$.cases[0].newSurname").value("Threepwood"))
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
        @DisplayName("the awaiting-confirmation filter is the waiting cases, longest wait first, with the days")
        void theAwaitingConfirmationFilterIsTheWaitingCases() throws Exception {
            // A case asked in March and one asked in May, plus one that is waiting for nothing. The two waiting
            // ones are backdated in the OPPOSITE order to their stamps, so a list that fell back to newest-first
            // would return them the other way round and this would fail rather than pass by coincidence.
            DeedPoll longest = backdated(
                awaiting(Map.of("newForename", "Asked in March"), LocalDateTime.of(2026, 3, 1, 9, 0)),
                LocalDateTime.parse("2026-08-01T09:00:00"));
            DeedPoll recent = backdated(
                awaiting(Map.of("newForename", "Asked in May"), LocalDateTime.of(2026, 5, 1, 9, 0)),
                LocalDateTime.parse("2026-06-01T09:00:00"));
            newCase(Map.of("newForename", "Waiting for nothing"));

            JsonNode rows = mapper.readTree(
                authorised(get("/caseworker/cases").param("filter", "awaitingConfirmation"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.filter").value("awaitingConfirmation"))
                    // The case nobody asked is not waiting, and neither is a case that has answered.
                    .andExpect(jsonPath("$.cases.length()").value(2))
                    .andExpect(jsonPath("$.cases[0].id").value(longest.getId()))
                    .andExpect(jsonPath("$.cases[1].id").value(recent.getId()))
                    .andReturn().getResponse().getContentAsString()).at("/cases");

            // The row says how long, so a caseworker does not have to work it out against today's date. Counted
            // from the stamp: the difference between the two rows is exactly the sixty-one days between 1 March
            // and 1 May whatever day the test runs, which a count from createdOn or dateReceived would not give.
            int longestDays = rows.get(0).at("/daysAwaitingConfirmation").asInt();
            int recentDays = rows.get(1).at("/daysAwaitingConfirmation").asInt();
            assertThat(longestDays - recentDays).isEqualTo(61);
            assertThat(longestDays).isEqualTo(daysSince(LocalDateTime.of(2026, 3, 1, 9, 0)));
        }

        @Test
        @DisplayName("a case whose answer has been recorded drops out of the waiting list")
        void anAnsweredCaseDropsOutOfTheWaitingList() throws Exception {
            DeedPoll waiting = awaiting(Map.of("newForename", "Still waiting"),
                                        LocalDateTime.of(2026, 3, 1, 9, 0));

            authorised(get("/caseworker/cases").param("filter", "awaitingConfirmation"))
                .andExpect(jsonPath("$.cases.length()").value(1));

            // What recording an outcome does to the case: the read-model goes null, which is the only thing that
            // takes a case off this list. CaseworkerReferralControllerTest drives the real recording.
            waiting.setAwaitingConfirmationSince(null);
            deedPolls.save(waiting);

            authorised(get("/caseworker/cases").param("filter", "awaitingConfirmation"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cases.length()").value(0))
                .andExpect(jsonPath("$.total").value(0));
            // And it is still a case, findable under every other tab: the answer arriving is not the case closing.
            authorised(get("/caseworker/cases"))
                .andExpect(jsonPath("$.cases.length()").value(1))
                .andExpect(jsonPath("$.cases[0].daysAwaitingConfirmation").value(nullValue()));
        }

        @Test
        @DisplayName("a sort the caller names beats the waiting list's own order")
        void namedSortBeatsTheWaitingListsOrder() throws Exception {
            // The default is the tab's, not a fixed order: a caseworker who clicked a column header asked a
            // question this service can answer, and a tab that overrode them would make its headers unclickable.
            awaiting(Map.of("newForename", "Zoe"), LocalDateTime.of(2026, 3, 1, 9, 0));
            awaiting(Map.of("newForename", "Ada"), LocalDateTime.of(2026, 5, 1, 9, 0));

            authorised(get("/caseworker/cases")
                           .param("filter", "awaitingConfirmation")
                           .param("sort", "newForename:asc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cases.length()").value(2))
                .andExpect(jsonPath("$.cases[0].newForename").value("Ada"))
                .andExpect(jsonPath("$.cases[1].newForename").value("Zoe"));
        }

        @Test
        @DisplayName("the waiting-list criterion ANDs with a condition rather than replacing it")
        void theWaitingCriterionAndsWithACondition() throws Exception {
            awaiting(Map.of("newForename", "Adult", "type", DeedPollType.Adult),
                     LocalDateTime.of(2026, 3, 1, 9, 0));
            awaiting(Map.of("newForename", "Minor", "type", DeedPollType.Minor),
                     LocalDateTime.of(2026, 4, 1, 9, 0));

            authorised(get("/caseworker/cases")
                           .param("filter", "awaitingConfirmation")
                           .param("where", "type:is:Minor"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cases.length()").value(1))
                .andExpect(jsonPath("$.cases[0].newForename").value("Minor"));
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

    /**
     * The paging envelope: {@code page}, {@code size}, {@code total} and {@code totalPages}.
     *
     * <p>The four members are asserted through HTTP rather than only on {@code CaseResults}, because the
     * thing a consumer renders "page 3 of 7" from is the JSON. {@code DeedPollServiceTest} pins which rows a
     * page holds; what is at stake here is that the numbers describing them reach the wire, and reach it
     * saying what was <em>applied</em> rather than what was asked for -- the clamped size especially.
     */
    @Nested
    @DisplayName("GET /caseworker/cases paging")
    class CaseListPaging {

        @Test
        @DisplayName("a bare list is page one of the default size, and says how many there are")
        void bareListIsPageOneOfTheDefaultSize() throws Exception {
            threeCases();

            authorised(get("/caseworker/cases"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cases.length()").value(3))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.size").value(25))
                .andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.totalPages").value(1));
        }

        @Test
        @DisplayName("a middle page is the rows between, and neither end of the set")
        void theMiddlePageIsTheRowsBetween() throws Exception {
            threeCases();

            // Three pages of one, asked for the second: the failure a first-and-last-page test would miss is
            // an offset computed as page * size rather than (page - 1) * size, which is right for page 1 and
            // for the final page of a set that divides evenly, and wrong in between.
            authorised(get("/caseworker/cases").param("size", "1").param("page", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cases.length()").value(1))
                .andExpect(jsonPath("$.cases[0].newForename").value("Middle"))
                .andExpect(jsonPath("$.page").value(2))
                .andExpect(jsonPath("$.size").value(1))
                .andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.totalPages").value(3));
        }

        @Test
        @DisplayName("the last page is partial, and the total still counts the whole result set")
        void theLastPageIsPartial() throws Exception {
            threeCases();

            // Two pages of two out of three rows: the second holds one row, and total is 3 rather than the
            // 1 a caller could infer from the page it is holding. Getting this wrong is how a list reports
            // "1 case" on its final page.
            authorised(get("/caseworker/cases").param("size", "2").param("page", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cases.length()").value(1))
                .andExpect(jsonPath("$.cases[0].newForename").value("Oldest"))
                .andExpect(jsonPath("$.page").value(2))
                .andExpect(jsonPath("$.size").value(2))
                .andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.totalPages").value(2));
        }

        @Test
        @DisplayName("an empty result set is zero pages, not one empty one")
        void anEmptyResultSetIsZeroPages() throws Exception {
            authorised(get("/caseworker/cases"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cases.length()").value(0))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.size").value(25))
                .andExpect(jsonPath("$.total").value(0))
                // "Page 1 of 1" would claim a page that has no rows on it. See CaseResults.of.
                .andExpect(jsonPath("$.totalPages").value(0));
        }

        @Test
        @DisplayName("a page past the end is an empty page and not an error")
        void pagePastTheEndIsAnEmptyPage() throws Exception {
            threeCases();

            // A caseworker who bookmarked page 9 of a list that has since shrunk has asked a question with
            // an answer, and total is what tells them where to go instead.
            authorised(get("/caseworker/cases").param("page", "9"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cases.length()").value(0))
                .andExpect(jsonPath("$.page").value(9))
                .andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.totalPages").value(1));
        }

        @Test
        @DisplayName("an over-cap size is clamped, and the response says the size that was applied")
        void anOverCapSizeIsClampedAndSaidSo() throws Exception {
            threeCases();

            // The echo is what makes the clamp survivable: a caller that asked for 1,000 and was given 100
            // must be able to see it, or it reads a full page as the whole result set and stops paging.
            authorised(get("/caseworker/cases").param("size", "1000"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(100))
                .andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.totalPages").value(1));
        }

        @Test
        @DisplayName("the filter that was applied is echoed alongside the page it produced")
        void theFilterIsEchoedAlongsideThePage() throws Exception {
            newCase(Map.of("newForename", "Open"));
            newCase(Map.of("newForename", "Done", "dateEnroled", RECEIVED));

            // total counts the criterion the page came from -- the filter's rows, not the table's. A total
            // taken from an unfiltered count is how "page 1 of 2" appears above a single-page list.
            authorised(get("/caseworker/cases").param("filter", "enroled"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.filter").value("enroled"))
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.totalPages").value(1));
        }

        /**
         * The query, sort and paging parameters reach the domain from the request.
         *
         * <p>Three assertions and not the operator matrix, which is task 5's: what is checked here is the
         * wiring -- that {@code where}, {@code sort}, {@code page} and {@code size} are read at all, and
         * that a refusal from any of them becomes a 400 rather than an unfiltered list.
         */
        @Test
        @DisplayName("applies a where condition and a sort, and refuses one it cannot read")
        void appliesAWhereConditionAndASort() throws Exception {
            newCase(Map.of("newForename", "Adult one", "type", DeedPollType.Adult));
            newCase(Map.of("newForename", "Minor one", "type", DeedPollType.Minor));

            authorised(get("/caseworker/cases").param("where", "type:is:Adult"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cases.length()").value(1))
                .andExpect(jsonPath("$.cases[0].newForename").value("Adult one"))
                .andExpect(jsonPath("$.total").value(1));

            authorised(get("/caseworker/cases").param("sort", "newForename:asc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cases[0].newForename").value("Adult one"))
                .andExpect(jsonPath("$.cases[1].newForename").value("Minor one"));

            authorised(get("/caseworker/cases").param("where", "nosuchfield:is:Adult"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("where.0"));

            authorised(get("/caseworker/cases").param("page", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("page"));
        }

        /**
         * A parameter that changes the answer, sent twice, is refused rather than picked between.
         *
         * <p>Spring binds a repeated parameter by taking the first value, so {@code ?page=1&page=9} would
         * answer page 1 to a caller who does not know which page they are looking at. {@code where} is the
         * one parameter that legitimately repeats.
         */
        @Test
        @DisplayName("refuses a sort, page or size given twice")
        void refusesAParameterGivenTwice() throws Exception {
            authorised(get("/caseworker/cases").param("page", "1", "9"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("page"));

            authorised(get("/caseworker/cases").param("sort", "createdOn:asc", "createdOn:desc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("sort"));

            authorised(get("/caseworker/cases").param("size", "5", "10"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("size"));
        }

        /** Three cases in a known order, newest first: "Newest", "Middle", "Oldest". */
        private void threeCases() {
            backdated(newCase(Map.of("newForename", "Oldest")),
                LocalDateTime.parse("2026-06-01T09:00:00"));
            backdated(newCase(Map.of("newForename", "Middle")),
                LocalDateTime.parse("2026-07-01T09:00:00"));
            backdated(newCase(Map.of("newForename", "Newest")),
                LocalDateTime.parse("2026-08-01T09:00:00"));
        }
    }

    /**
     * The field-level query surface, over HTTP: the {@code where} parameter end to end.
     *
     * <p>{@code CaseQueryTest} pins the parsing and {@code CaseSpecificationsTest} pins the SQL, both against
     * the domain directly. What is at stake here is the whole path -- a repeated URL parameter, through
     * validation, into a query, back as JSON -- and the things only that path can get wrong: a condition that
     * validates and then translates to nothing, a refusal that reaches a caller as a 500 or as an unfiltered
     * 200, and a delimiter that survives one layer and not the next.
     *
     * <p>Two fixtures cover all six kinds between them, so a condition on any kind has one case that matches
     * it and one that does not. Both are backdated to distinct times: the assertions read the rows back by
     * {@code newForename}, and two cases created in the same millisecond would tie on {@code createdOn}.
     */
    @Nested
    @DisplayName("GET /caseworker/cases where")
    class CaseListQuery {

        @BeforeEach
        void twoCasesCoveringEveryKind() {
            backdated(queryCase("Alexandra",
                "newSurname", "MacDonald",
                "referralNotes", "Ref: 12/34",
                "type", DeedPollType.Adult,
                "inPerson", true,
                "iShall", List.of(1, 4)),
                LocalDateTime.parse("2026-06-01T09:00:00"));
            backdated(queryCase("Barnaby",
                "newSurname", "Donaldson",
                "type", DeedPollType.Minor,
                "ageOfMinor", 8,
                "inPerson", false,
                "iShall", List.of(5),
                "dateReceived", LocalDateTime.parse("2026-08-05T09:00:00")),
                LocalDateTime.parse("2026-07-01T09:00:00"));
        }

        /**
         * The regression that matters most: a request with none of the new parameters answers what it did
         * before they existed.
         *
         * <p>Asserted against the untouched unpaged finder rather than against a transcription of its output,
         * because "the same as today" is a claim about that method and nothing else. Its rows, mapped by the
         * same DTO, must be the {@code cases} array of a bare request node for node -- which is what makes the
         * switch to serving every list through the specification (task 3) safe rather than merely tested.
         */
        @Test
        @DisplayName("a bare list is the same rows in the same order as the unpaged finder answers")
        void bareListMatchesTheUnpagedFinder() throws Exception {
            String expected = mapper.writeValueAsString(CaseSummaryResponse.from(
                deedPollService.findDeedPolls(ListFilters.filterFor("all"))));

            String body = authorised(get("/caseworker/cases"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

            assertThat(mapper.readTree(body).get("cases")).isEqualTo(mapper.readTree(expected));
        }

        /**
         * Decision 5's table, executed over HTTP with rows that discriminate.
         *
         * <p>{@code CaseSpecificationsTest} runs the same sweep for translation coverage and asserts nothing
         * about which rows come back. This one asserts exactly that, because the failure it is looking for is
         * different: a condition that reaches the database intact and answers the wrong question -- a
         * {@code lt} bound read as {@code le}, an {@code isEmpty} that misses a null column, a multiselect
         * {@code contains} matching every row.
         */
        @ParameterizedTest(name = "{0}")
        @CsvSource({
            "'newForename:is:Alexandra', Alexandra",
            "'newForename:contains:LEXAND', Alexandra",
            "'newForename:startsWith:barn', Barnaby",
            "'newForename:isNotEmpty', 'Alexandra,Barnaby'",
            "'newSurname:contains:CDON', Alexandra",
            "'referralNotes:isEmpty', Barnaby",
            "'referralNotes:isNotEmpty', Alexandra",
            "'referralNotes:contains:12/34', Alexandra",
            "'dateReceived:is:2026-07-01T00:00', Alexandra",
            "'dateReceived:before:2026-08-01T00:00', Alexandra",
            "'dateReceived:after:2026-08-01T00:00', Barnaby",
            "'dateReceived:between:2026-07-01T00:00,2026-08-05T09:00', 'Alexandra,Barnaby'",
            "'dateReceived:isNotEmpty', 'Alexandra,Barnaby'",
            "'ageOfMinor:is:8', Barnaby",
            "'ageOfMinor:gt:7', Barnaby",
            "'ageOfMinor:lt:9', Barnaby",
            "'ageOfMinor:between:7,9', Barnaby",
            "'ageOfMinor:isEmpty', Alexandra",
            "'ageOfMinor:isNotEmpty', Barnaby",
            "'type:is:Minor', Barnaby",
            "'type:isOneOf:Adult,Minor', 'Alexandra,Barnaby'",
            "'masterDecision:isEmpty', 'Alexandra,Barnaby'",
            "'masterDecision:isNotEmpty', ''",
            "'inPerson:is:true', Alexandra",
            "'inPerson:is:false', Barnaby",
            "'iShall:contains:4', Alexandra",
            "'iShall:contains:5', Barnaby",
            "'iShall:isNotEmpty', 'Alexandra,Barnaby'",
            "'iShall:isEmpty', ''"
        })
        @DisplayName("every operator of every kind answers the rows it should")
        void answersEveryOperatorOfEveryKind(String where, String expected) throws Exception {
            assertThat(forenamesMatching(where))
                .containsExactlyInAnyOrderElementsOf(names(expected));
        }

        @Test
        @DisplayName("contains and startsWith ignore the capitalisation the value was typed in")
        void containsAndStartsWithIgnoreCase() throws Exception {
            // Both directions of both operators, because the predicate lowers both sides and a test that only
            // sent a lower-case value would pass on a query that lowered the column alone.
            assertThat(forenamesMatching("newSurname:contains:cdon")).containsExactly("Alexandra");
            assertThat(forenamesMatching("newSurname:contains:CDON")).containsExactly("Alexandra");
            assertThat(forenamesMatching("newSurname:startsWith:macd")).containsExactly("Alexandra");
            assertThat(forenamesMatching("newSurname:startsWith:MACD")).containsExactly("Alexandra");
        }

        @Test
        @DisplayName("a value containing a colon or a comma survives the round trip")
        void keepsAValueContainingAColonOrAComma() throws Exception {
            // The wire format's two delimiters, each inside a value: the entry is split on the first two
            // colons only, and no text operator takes a list, so neither is ambiguous. A third colon read as
            // a separator would look for a field called "Ref" and 400 on a condition that is well formed.
            queryCase("Comma", "referralNotes", "Smith, A");

            assertThat(forenamesMatching("referralNotes:is:Ref: 12/34")).containsExactly("Alexandra");
            assertThat(forenamesMatching("referralNotes:contains:Smith, A")).containsExactly("Comma");
        }

        @Test
        @DisplayName("refuses an unknown field, a wrong operator and an invalid enum member, naming each")
        void refusesAConditionItCannotRead() throws Exception {
            badCondition("stat:is:Received", "names no case field");
            badCondition("newForename:before:2026-01-01T00:00", "does not apply");
            badCondition("type:is:Nonsense", "has no value");
        }

        /**
         * Every mistake in one answer, not the first one.
         *
         * <p>A query builder sends several conditions at once, so failing on the first would make correcting a
         * request a round trip per mistake -- and the position in {@code where} is the only thing that says
         * which condition each refusal is about.
         */
        @Test
        @DisplayName("reports every bad condition in one response, addressed to its position")
        void reportsEveryBadConditionRatherThanTheFirst() throws Exception {
            authorised(get("/caseworker/cases")
                .param("where", "stat:is:Received")
                .param("where", "type:is:Nonsense")
                .param("where", "newForename:before:2026-01-01T00:00"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Case query is not valid"))
                .andExpect(jsonPath("$.errors.length()").value(3))
                .andExpect(jsonPath("$.errors[0].field").value("where.0"))
                .andExpect(jsonPath("$.errors[1].field").value("where.1"))
                .andExpect(jsonPath("$.errors[2].field").value("where.2"));
        }

        @Test
        @DisplayName("refuses a between whose bounds are reversed, and one that has only a bound")
        void refusesABetweenThatIsReversedOrIncomplete() throws Exception {
            // Reversed is refused rather than swapped: a caller who filled two controls the wrong way round
            // needs telling, and a query that can only ever match nothing answered as an empty list looks
            // exactly like a query that matched nothing.
            badCondition("ageOfMinor:between:9,7", "reversed");
            badCondition("ageOfMinor:between:7", "two comma-separated");
        }

        @Test
        @DisplayName("applies a filter tab and a condition together")
        void appliesTheFilterAndTheConditionTogether() throws Exception {
            // Decision 3: the five tabs are ported system views, so filter=open&where=type:is:Adult means
            // both. Alexandra is the open Adult; Barnaby is an open Minor; this one satisfies the condition
            // and not the tab. Its state is set explicitly, because the derivation runs on a write through
            // the service and a fixture that only set dateEnroled would still be Received -- and would then
            // be admitted by the open tab, which is the assertion passing for the wrong reason.
            queryCase("Enroled adult",
                "type", DeedPollType.Adult,
                "dateEnroled", LocalDateTime.parse("2026-08-01T09:00:00"),
                "state", DeedPollState.Enroled);

            authorised(get("/caseworker/cases").param("filter", "open").param("where", "type:is:Adult"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.filter").value("open"))
                .andExpect(jsonPath("$.cases.length()").value(1))
                .andExpect(jsonPath("$.cases[0].newForename").value("Alexandra"))
                .andExpect(jsonPath("$.total").value(1));
        }

        /**
         * The security-relevant assertion of the whole surface.
         *
         * <p>A field name reaches a query, so a name crafted to look like SQL has to be refused by validation
         * rather than escaped afterwards -- and the list still answering afterwards is what says the statement
         * was never issued. A <em>value</em> is the other half: it is bound by the Criteria API, so one that
         * reads as SQL is a string that matches nothing rather than a predicate that matches everything.
         */
        @Test
        @DisplayName("refuses a field name crafted to look like SQL, and binds a value that reads as SQL")
        void refusesAFieldNameThatLooksLikeSql() throws Exception {
            badCondition("newForename\"; drop table \"DeedPoll\"; --:is:Ada", "names no case field");

            authorised(get("/caseworker/cases"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cases.length()").value(2));

            authorised(get("/caseworker/cases").param("where", "newForename:is:' OR 1=1 --"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cases.length()").value(0))
                .andExpect(jsonPath("$.total").value(0));
        }

        /**
         * The {@code newForename} of every case one condition admits, in the order the response gave them.
         */
        private List<String> forenamesMatching(String where) throws Exception {
            String body = authorised(get("/caseworker/cases").param("where", where))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

            List<String> forenames = new ArrayList<>();
            mapper.readTree(body).get("cases").forEach(row -> forenames.add(row.get("newForename").asText()));
            return forenames;
        }

        /** One condition refused, with the refusal addressed to it and saying what is wrong. */
        private void badCondition(String where, String saying) throws Exception {
            authorised(get("/caseworker/cases").param("where", where))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Case query is not valid"))
                .andExpect(jsonPath("$.errors.length()").value(1))
                .andExpect(jsonPath("$.errors[0].field").value("where.0"))
                .andExpect(jsonPath("$.errors[0].message").value(containsString(saying)));
        }

        /** A comma-separated expectation as a list, where nothing at all means no rows. */
        private List<String> names(String expected) {
            if (expected == null || expected.isBlank()) {
                return List.of();
            }
            return List.of(expected.split(","));
        }
    }

    /**
     * The search box, over HTTP: the {@code q} parameter end to end.
     *
     * <p>{@code CaseSearchTest} pins how a term is read and {@code CaseSpecificationsTest} pins what SQL does
     * with it. What is at stake here is the whole path, and the two things only it can get wrong: the
     * bracketing -- a term that flattens into an OR with the conditions returns plausible rows and makes every
     * one of them ineffective -- and {@code referenceMatch}, which no layer below the response carries.
     */
    @Nested
    @DisplayName("GET /caseworker/cases q")
    class CaseListSearch {

        @Test
        @DisplayName("matches a surname, and shows the surname it matched on")
        void matchesASurnameAndShowsIt() throws Exception {
            // Decision 13, executed: without newSurname on the row a caseworker searching "Smith" gets a
            // table of forenames and cannot see why any row is in it.
            queryCase("Ada", "newSurname", "Smithson");
            queryCase("Barnaby", "newSurname", "Jones");

            authorised(get("/caseworker/cases").param("q", "smith"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cases.length()").value(1))
                .andExpect(jsonPath("$.cases[0].newForename").value("Ada"))
                .andExpect(jsonPath("$.cases[0].newSurname").value("Smithson"))
                .andExpect(jsonPath("$.total").value(1));
        }

        @Test
        @DisplayName("matches across the name columns, and not an address or a note")
        void matchesAcrossTheNameColumnsOnly() throws Exception {
            queryCase("Ada", "oldSurname", "Threepwood");
            queryCase("Barnaby", "parents", "Threepwood and Threepwood");
            queryCase("Cyril", "representativeName", "Threepwood & Co");
            queryCase("Dahlia", "applicantAddress1", "12 Threepwood Avenue");
            queryCase("Elsie", "referralNotes", "Spoke to Threepwood");

            assertThat(forenamesSearching("Threepwood"))
                .containsExactlyInAnyOrder("Ada", "Barnaby", "Cyril");
        }

        @Test
        @DisplayName("ANDs the term with the filter tab and with a where condition")
        void andsTheTermWithTheFilterAndTheCondition() throws Exception {
            // The bracketing, over HTTP. Under a flat OR every one of these four rows comes back: two for the
            // term, one for the condition and one for the tab -- and the response would look right.
            queryCase("Wanted", "newSurname", "Threepwood", "type", DeedPollType.Adult);
            queryCase("Wrong type", "newSurname", "Threepwood", "type", DeedPollType.Minor);
            queryCase("Wrong name", "newSurname", "Elsewhere", "type", DeedPollType.Adult);
            queryCase("Wrong state",
                "newSurname", "Threepwood",
                "type", DeedPollType.Adult,
                "state", DeedPollState.Enroled);

            authorised(get("/caseworker/cases")
                .param("filter", "open")
                .param("where", "type:is:Adult")
                .param("q", "threepwood"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cases.length()").value(1))
                .andExpect(jsonPath("$.cases[0].newForename").value("Wanted"))
                // The total counts what all three admit, which is the other half of the bracketing being
                // right: a flattened OR would report four here while showing one.
                .andExpect(jsonPath("$.total").value(1));
        }

        @Test
        @DisplayName("finds a case by its reference, however it was typed, and says which case it was")
        void findsACaseByItsReference() throws Exception {
            DeedPoll record = newCase(Map.of("newForename", "Ada"));
            String reference = record.getDisplayReference();
            assertThat(reference).as("premise: the create allocated a rendered reference").isNotBlank();

            for (String typed : List.of(reference, reference.toLowerCase(Locale.ROOT),
                reference.replace("-", ""), " " + reference + " ")) {
                authorised(get("/caseworker/cases").param("q", typed))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.cases.length()").value(1))
                    .andExpect(jsonPath("$.cases[0].id").value(record.getId()))
                    // Decision 11: the caseworker wants the case, and the UI must not have to infer that
                    // from total == 1 -- which is also true of a surname only one case happens to carry.
                    .andExpect(jsonPath("$.referenceMatch").value(record.getId()));
            }
        }

        @Test
        @DisplayName("finds a case by the serial component alone")
        void findsACaseByTheSerialAlone() throws Exception {
            DeedPoll record = newCase(Map.of("newForename", "Ada"));
            assertThat(record.getReferencenumber()).as("premise: the create allocated a serial").isNotNull();

            authorised(get("/caseworker/cases").param("q", String.valueOf(record.getReferencenumber())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cases.length()").value(1))
                .andExpect(jsonPath("$.cases[0].id").value(record.getId()))
                .andExpect(jsonPath("$.referenceMatch").value(record.getId()));
        }

        @Test
        @DisplayName("referenceMatch is null for a name search, and for a request that did not search")
        void referenceMatchIsNullWithoutAReferenceHit() throws Exception {
            queryCase("Ada", "newSurname", "Threepwood");

            // A name search that returns exactly one row is precisely the case a consumer would otherwise
            // have inferred a reference hit from, so it is the one worth asserting.
            authorised(get("/caseworker/cases").param("q", "threepwood"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cases.length()").value(1))
                .andExpect(jsonPath("$.referenceMatch").value(nullValue()));

            authorised(get("/caseworker/cases"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.referenceMatch").value(nullValue()));

            authorised(get("/caseworker/cases").param("q", "nobody at all"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cases.length()").value(0))
                .andExpect(jsonPath("$.referenceMatch").value(nullValue()));
        }

        @Test
        @DisplayName("referenceMatch is null for a reference the filter excludes, and the list is empty")
        void referenceMatchRespectsTheFilter() throws Exception {
            DeedPoll enroled = newCase(Map.of("newForename", "Done", "dateEnroled", RECEIVED));

            // A redirect to a case the tab does not show would be a disclosure by another route, and it would
            // also be a redirect out of the list the caseworker was looking at.
            authorised(get("/caseworker/cases")
                .param("filter", "returned")
                .param("q", enroled.getDisplayReference()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cases.length()").value(0))
                .andExpect(jsonPath("$.referenceMatch").value(nullValue()));
        }

        @Test
        @DisplayName("a term that reads as SQL matches nothing rather than everything")
        void bindsATermThatReadsAsSql() throws Exception {
            // The most obviously attacker-controlled value this service accepts. It is bound by the Criteria
            // API, so it is a string that matches nothing -- and the list still answering afterwards is what
            // says no statement was issued.
            queryCase("Ada", "newSurname", "Threepwood");

            authorised(get("/caseworker/cases").param("q", "' OR 1=1 --"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cases.length()").value(0))
                .andExpect(jsonPath("$.total").value(0));

            authorised(get("/caseworker/cases").param("q", "%"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cases.length()").value(0));

            authorised(get("/caseworker/cases"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cases.length()").value(1));
        }

        @Test
        @DisplayName("refuses q given twice rather than quietly picking the first")
        void refusesQGivenTwice() throws Exception {
            authorised(get("/caseworker/cases").param("q", "Smith", "Jones"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("q"))
                .andExpect(jsonPath("$.errors[0].message").value("must be given at most once"));
        }

        @Test
        @DisplayName("a blank q is the unnarrowed list rather than a search for nothing")
        void blankTermIsTheUnnarrowedList() throws Exception {
            queryCase("Ada", "newSurname", "Threepwood");
            queryCase("Barnaby", "newSurname", "Jones");

            authorised(get("/caseworker/cases").param("q", "   "))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cases.length()").value(2))
                .andExpect(jsonPath("$.referenceMatch").value(nullValue()));
        }

        /**
         * The {@code newForename} of every case one term admits.
         */
        private List<String> forenamesSearching(String term) throws Exception {
            String body = authorised(get("/caseworker/cases").param("q", term))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

            List<String> forenames = new ArrayList<>();
            mapper.readTree(body).get("cases").forEach(row -> forenames.add(row.get("newForename").asText()));
            return forenames;
        }
    }

    /**
     * The order the list came back in: {@code sort}, over HTTP.
     *
     * <p>Both directions, because a translation that ignored the direction would still pass a test that only
     * asked for the default one -- and the default is descending, which is the direction a request that sends
     * nothing already gets.
     */
    @Nested
    @DisplayName("GET /caseworker/cases sort")
    class CaseListOrder {

        @Test
        @DisplayName("sorts ascending and descending by a named field")
        void sortsAscendingAndDescending() throws Exception {
            // Backdated in the opposite order to the alphabet, so an assertion about the requested order
            // cannot be satisfied by the default one.
            backdated(queryCase("Ada"), LocalDateTime.parse("2026-08-01T09:00:00"));
            backdated(queryCase("Beatrice"), LocalDateTime.parse("2026-07-01T09:00:00"));
            backdated(queryCase("Cyril"), LocalDateTime.parse("2026-06-01T09:00:00"));

            authorised(get("/caseworker/cases").param("sort", "newForename:asc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cases[0].newForename").value("Ada"))
                .andExpect(jsonPath("$.cases[1].newForename").value("Beatrice"))
                .andExpect(jsonPath("$.cases[2].newForename").value("Cyril"));

            authorised(get("/caseworker/cases").param("sort", "newForename:desc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cases[0].newForename").value("Cyril"))
                .andExpect(jsonPath("$.cases[1].newForename").value("Beatrice"))
                .andExpect(jsonPath("$.cases[2].newForename").value("Ada"));
        }

        @Test
        @DisplayName("refuses a sort field that is not one, or that has no column")
        void refusesASortFieldItCannotOrderBy() throws Exception {
            badSort("nosuchfield:asc", "names no sortable case field");
            // Queryable and not sortable, in the other direction from a multiselect: the day counts are
            // derived at read time, so there is no column to order by.
            badSort("daysReceivedToEnroled:asc", "derived, not stored");
        }

        @Test
        @DisplayName("refuses a multiselect sort field, which is queryable but has no order")
        void refusesAMultiselectSortField() throws Exception {
            // Ordering option-value sets means deciding whether {1,3} precedes {2}, and every answer to that
            // is arbitrary -- so it is refused rather than silently ignored. A list returned in an order the
            // caller did not ask for, with nothing said, is what this whole surface exists to avoid.
            badSort("iShall:asc", "several values at once");
        }

        @Test
        @DisplayName("refuses a direction that is not asc or desc")
        void refusesAnUnreadableDirection() throws Exception {
            badSort("newForename:sideways", "takes a direction of asc, desc");
        }

        /**
         * One order refused, attributed to {@code sort} and saying what is wrong with it.
         */
        private void badSort(String sort, String saying) throws Exception {
            authorised(get("/caseworker/cases").param("sort", sort))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Case list sort is not valid"))
                .andExpect(jsonPath("$.errors.length()").value(1))
                .andExpect(jsonPath("$.errors[0].field").value("sort"))
                .andExpect(jsonPath("$.errors[0].message").value(containsString(saying)));
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
            // Read as false is what Boolean.parseBoolean would have done, and the false rule set is a
            // different answer -- so the flag is resolved explicitly and an unintelligible value is refused
            // with the same summary and attribution an unrecognised type gets.
            authorised(get("/caseworker/cases/field-rules").param("migrated", "maybe"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Invalid request parameter"))
                .andExpect(jsonPath("$.errors[0].field").value("migrated"));
        }

        @Test
        @DisplayName("refuses a repeated flag rather than quietly picking the first")
        void refusesARepeatedFlag() throws Exception {
            // The same trap as the repeated type, on the parameter that decides whether the notice date is
            // visible at all: Spring's array-to-boolean conversion takes element 0 and discards the rest, so
            // ?publishToGazette=true&publishToGazette=false would answer the "publish" rules.
            authorised(get("/caseworker/cases/field-rules")
                .param("publishToGazette", "true", "false"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Invalid request parameter"))
                .andExpect(jsonPath("$.errors.length()").value(1))
                .andExpect(jsonPath("$.errors[0].field").value("publishToGazette"))
                .andExpect(jsonPath("$.errors[0].message").value("must be given at most once"));
        }

        @Test
        @DisplayName("accepts a flag in either case, since a URL is not case-normalised for the caller")
        void acceptsAFlagInEitherCase() throws Exception {
            authorised(get("/caseworker/cases/field-rules")
                .param("type", "Adult")
                .param("publishToGazette", "FALSE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rules.dateNoticeSentToLondonGazette.visible").value(false));
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
     * A stored case with fields written straight onto the record, by the names the query uses.
     *
     * <p>Written through {@link DeedPollFields} after the create rather than as a patch through it, so a
     * fixture can set a field regardless of what the per-type rules would make of it on a form -- an
     * {@code ageOfMinor} on an Adult case, for one. What is under test is the query, not what a caseworker
     * would have been allowed to type, and a condition on {@code ageOfMinor} needs a row that has one.
     */
    private DeedPoll queryCase(String forename, Object... fields) {
        DeedPoll record = newCase(Map.of("newForename", forename));
        for (int i = 0; i < fields.length; i += 2) {
            DeedPollFields.write(record, String.valueOf(fields[i]), fields[i + 1]);
        }
        return deedPolls.save(record);
    }

    /**
     * A stored case waiting for the applicant's confirmation since the given instant.
     *
     * <p>The column is set with the entity's own setter, which is the only way: it is a read-model of the referral
     * table and {@link DeedPollFields} deliberately has no writer for it, so a caller could not put a case into the
     * waiting list even by patching. What is under test here is the list; the send that really stamps it is
     * {@code CaseworkerEmailControllerTest}'s and the recording that clears it is
     * {@code CaseworkerReferralControllerTest}'s.
     */
    private DeedPoll awaiting(Map<String, Object> values, LocalDateTime since) {
        DeedPoll record = newCase(values);
        record.setAwaitingConfirmationSince(since);
        return deedPolls.save(record);
    }

    /** Whole calendar days from then until now in UTC -- the count the row publishes, computed the same way. */
    private static int daysSince(LocalDateTime since) {
        return (int) ChronoUnit.DAYS.between(since.toLocalDate(), LocalDate.now(ZoneOffset.UTC));
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
