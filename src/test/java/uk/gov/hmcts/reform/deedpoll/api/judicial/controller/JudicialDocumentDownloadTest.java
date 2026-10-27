package uk.gov.hmcts.reform.deedpoll.api.judicial.controller;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import uk.gov.hmcts.reform.deedpoll.auth.StubIdam;
import uk.gov.hmcts.reform.deedpoll.config.AbstractPostgresContainerTest;
import uk.gov.hmcts.reform.deedpoll.domain.PatchField;
import uk.gov.hmcts.reform.deedpoll.domain.auth.CurrentUser;
import uk.gov.hmcts.reform.deedpoll.domain.auth.Role;
import uk.gov.hmcts.reform.deedpoll.domain.deedpoll.DeedPollPatch;
import uk.gov.hmcts.reform.deedpoll.domain.deedpoll.DeedPollService;
import uk.gov.hmcts.reform.deedpoll.domain.documents.CreateDocumentTypeInput;
import uk.gov.hmcts.reform.deedpoll.domain.documents.DocumentService;
import uk.gov.hmcts.reform.deedpoll.domain.documents.DocumentStore;
import uk.gov.hmcts.reform.deedpoll.domain.documents.DocumentTypeService;
import uk.gov.hmcts.reform.deedpoll.domain.documents.UpdateDocumentInput;
import uk.gov.hmcts.reform.deedpoll.domain.documents.UploadedFile;
import uk.gov.hmcts.reform.deedpoll.domain.referrals.CreateReferralInput;
import uk.gov.hmcts.reform.deedpoll.domain.referrals.DirectionInput;
import uk.gov.hmcts.reform.deedpoll.domain.referrals.ReferralReason;
import uk.gov.hmcts.reform.deedpoll.domain.referrals.ReferralService;
import uk.gov.hmcts.reform.deedpoll.entity.enums.MasterDecision;
import uk.gov.hmcts.reform.deedpoll.entity.enums.Urgency;
import uk.gov.hmcts.reform.deedpoll.repository.DeedPollNoteRepository;
import uk.gov.hmcts.reform.deedpoll.repository.DeedPollRepository;
import uk.gov.hmcts.reform.deedpoll.repository.DeedPollSupportingDocumentRepository;
import uk.gov.hmcts.reform.deedpoll.repository.DeedPollSupportingDocumentTypeRepository;
import uk.gov.hmcts.reform.deedpoll.repository.ReferenceCounterRepository;
import uk.gov.hmcts.reform.deedpoll.repository.ReferralRepository;
import uk.gov.hmcts.reform.deedpoll.support.DocumentFixtures;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A judge downloading a supporting document through a referral, and opening a referral recording
 * {@code CASE_VIEWED}, over HTTP against a real database and a real filesystem store (design decisions 3 and 4 of
 * the 2026-10-19 plan).
 *
 * <p><strong>The document is reached through the referral, never through the case.</strong> Visibility is
 * {@code JudicialReferralService.visibleTo}, unchanged: every judge for an {@code Open} referral, only the holder for
 * a {@code Claimed} one, and nobody once it is {@code Closed} or {@code Withdrawn}. Every refusal is the same 404 the
 * referral read gives, and a document of another case is the document's own 404, exactly as the caseworker route
 * answers it.
 *
 * <p><strong>The headers are asserted against the caseworker route's, not against constants.</strong> Both routes
 * use one helper, and the point of the test is that they cannot drift: the same document is fetched both ways and
 * every header the caseworker download sets is compared.
 *
 * <p>Not {@code @Transactional}: the read audit commits in its own transaction, and the download opens the store
 * outside any. Audit rows stay, as V36 requires, under case ids no other test uses.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class JudicialDocumentDownloadTest extends AbstractPostgresContainerTest {

    private static final String RECEIVED = "2026-07-01T00:00:00.000Z";
    private static final byte[] PDF_BYTES = "%PDF-1.4 fake".getBytes(StandardCharsets.UTF_8);

    /** A subject longer than the 255 characters actor_id holds: the one failure only the audit table refuses. */
    private static final String UNRECORDABLE = "j".repeat(256);

    private static final String JUDGE_NAME = "District Judge Bell";
    private static final String JUDGE_SUBJECT = "idam-judge-bell";
    private static final String OTHER_JUDGE_NAME = "District Judge Okafor";
    private static final String OTHER_JUDGE_SUBJECT = "idam-judge-okafor";

    private static final CurrentUser CASEWORKER =
        CurrentUser.currentUser(List.of(), "Ada Caseworker", "caseworker-1");

    /** The headers the caseworker download sets, every one of which the judicial download must match. */
    private static final List<String> DOWNLOAD_HEADERS = List.of(
        HttpHeaders.CONTENT_TYPE,
        HttpHeaders.CONTENT_DISPOSITION,
        "X-Content-Type-Options",
        "Content-Security-Policy",
        HttpHeaders.CACHE_CONTROL,
        HttpHeaders.CONTENT_LENGTH);

    private static final Path STORE_ROOT = temporaryStoreRoot();
    private static final StubIdam IDAM = new StubIdam();

    private static String tokenSubject = JUDGE_SUBJECT;

    static {
        IDAM.start();
    }

    @AfterAll
    static void releaseTheStubAndTheStore() throws IOException {
        IDAM.stop();
        try (Stream<Path> entries = Files.walk(STORE_ROOT)) {
            for (Path path : entries.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("idam.api.issuer-url", IDAM::issuerUrl);
        registry.add("idam.api.audiences", () -> StubIdam.AUDIENCE);
        registry.add("deedpoll.documents.local-store-root", STORE_ROOT::toString);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private DeedPollService deedPollService;

    @Autowired
    private DocumentService documentService;

    @Autowired
    private DocumentTypeService documentTypes;

    @Autowired
    private ReferralService referralService;

    @Autowired
    private DeedPollRepository deedPolls;

    @Autowired
    private ReferralRepository referrals;

    @Autowired
    private DeedPollNoteRepository notes;

    @Autowired
    private DeedPollSupportingDocumentRepository documents;

    @Autowired
    private DeedPollSupportingDocumentTypeRepository types;

    @Autowired
    private ReferenceCounterRepository referenceCounters;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoSpyBean
    private DocumentStore store;

    private String caseId;
    private String referralId;

    private static Path temporaryStoreRoot() {
        try {
            return Files.createTempDirectory("deed-poll-api-judicial-documents");
        } catch (IOException failed) {
            throw new UncheckedIOException("Could not create a temporary document store", failed);
        }
    }

    @BeforeEach
    void setUp() {
        clearTables();
        IDAM.stubDefaults();
        asJudge(JUDGE_NAME, JUDGE_SUBJECT);
        caseId = newCase();
        referralId = raise(caseId);
    }

    @AfterEach
    void tearDown() {
        clearTables();
    }

    private void clearTables() {
        referrals.deleteAll();
        notes.deleteAll();
        documents.deleteAll();
        types.deleteAll();
        deedPolls.deleteAll();
        referenceCounters.deleteAll();
    }

    @Nested
    @DisplayName("GET /judicial/referrals/{referralId}/documents/{documentId}/file")
    class Download {

        @Test
        @DisplayName("any judge downloads a document of an Open referral, not only the one who will claim it")
        void anyJudgeDownloadsFromAnOpenReferral() throws Exception {
            String documentId = uploaded(caseId, "birth.pdf", "application/pdf");

            authorised(get(fileUrl(referralId, documentId)))
                .andExpect(status().isOk())
                .andExpect(content().bytes(PDF_BYTES));

            asJudge(OTHER_JUDGE_NAME, OTHER_JUDGE_SUBJECT);
            authorised(get(fileUrl(referralId, documentId)))
                .andExpect(status().isOk())
                .andExpect(content().bytes(PDF_BYTES));
        }

        @Test
        @DisplayName("the holder downloads one of a Claimed referral, and another judge gets the referral's 404")
        void onlyTheHolderDownloadsFromAClaimedReferral() throws Exception {
            String documentId = uploaded(caseId, "birth.pdf", "application/pdf");
            referralService.claim(caseId, referralId, judge(JUDGE_NAME, JUDGE_SUBJECT));

            authorised(get(fileUrl(referralId, documentId)))
                .andExpect(status().isOk())
                .andExpect(content().bytes(PDF_BYTES));

            asJudge(OTHER_JUDGE_NAME, OTHER_JUDGE_SUBJECT);
            authorised(get(fileUrl(referralId, documentId)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Referral " + referralId + " not found"));
        }

        @Test
        @DisplayName("is 404 once the office closes the referral, in the referral's words")
        void notFoundOnceClosed() throws Exception {
            String documentId = uploaded(caseId, "birth.pdf", "application/pdf");
            CurrentUser judge = judge(JUDGE_NAME, JUDGE_SUBJECT);
            referralService.claim(caseId, referralId, judge);
            referralService.decide(caseId, referralId,
                new DirectionInput(MasterDecision.Approved, Optional.of("Granted"), true), judge);
            authorised(get(fileUrl(referralId, documentId))).andExpect(status().isOk());

            referralService.close(caseId, referralId, CASEWORKER);

            authorised(get(fileUrl(referralId, documentId)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Referral " + referralId + " not found"));
        }

        @Test
        @DisplayName("is 404 once the referral is withdrawn, in the referral's words")
        void notFoundOnceWithdrawn() throws Exception {
            String documentId = uploaded(caseId, "birth.pdf", "application/pdf");
            referralService.withdrawReferral(caseId, referralId, CASEWORKER);

            authorised(get(fileUrl(referralId, documentId)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Referral " + referralId + " not found"));
        }

        @Test
        @DisplayName("is the document's 404 for a document of another case, so a referral reaches only its own")
        void notFoundForAnotherCasesDocument() throws Exception {
            String elsewhere = uploaded(newCase(), "birth.pdf", "application/pdf");

            authorised(get(fileUrl(referralId, elsewhere)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Document " + elsewhere + " not found"));

            verify(store, never()).open(any());
            assertThat(reads("DOCUMENT_DOWNLOADED")).isEmpty();
        }

        @Test
        @DisplayName("records one DOCUMENT_DOWNLOADED row per download, as the judge, on the document")
        void recordsEachDownload() throws Exception {
            String documentId = uploaded(caseId, "birth.pdf", "application/pdf");

            authorised(get(fileUrl(referralId, documentId))).andExpect(status().isOk());

            assertThat(reads("DOCUMENT_DOWNLOADED")).singleElement().satisfies(row ->
                assertThat(row).containsEntry("actor_id", JUDGE_SUBJECT)
                    .containsEntry("actor_name", JUDGE_NAME)
                    .containsEntry("entity_type", "Document")
                    .containsEntry("entity_id", documentId));

            authorised(get(fileUrl(referralId, documentId))).andExpect(status().isOk());
            assertThat(reads("DOCUMENT_DOWNLOADED")).hasSize(2);
        }

        @Test
        @DisplayName("serves nothing when the audit insert fails, and never opens the file")
        void failsClosed() {
            String documentId = uploaded(caseId, "birth.pdf", "application/pdf");
            asJudge(JUDGE_NAME, UNRECORDABLE);

            // Nothing maps a database failure, so MockMvc surfaces it rather than a rendered 500.
            assertThatThrownBy(() -> authorised(get(fileUrl(referralId, documentId))))
                .hasCauseInstanceOf(DataAccessException.class);

            verify(store, never()).open(any());
            assertThat(reads("DOCUMENT_DOWNLOADED")).isEmpty();
        }

        @ParameterizedTest(name = "{0}")
        @CsvSource({
            "application/pdf, a.pdf",
            "image/jpeg, a.jpg",
            "image/png, a.png",
            "image/tiff, a.tif",
            "application/msword, a.doc",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document, a.docx"
        })
        @DisplayName("sends exactly the headers the caseworker download sends, for every type it covers")
        void matchesTheCaseworkerHeaders(String mimeType, String filename) throws Exception {
            String documentId = uploaded(caseId, filename, mimeType);

            MockHttpServletResponse judicial = authorised(get(fileUrl(referralId, documentId)))
                .andExpect(status().isOk())
                .andReturn().getResponse();
            asCaseworker();
            MockHttpServletResponse caseworker =
                authorised(get("/caseworker/cases/" + caseId + "/documents/" + documentId + "/file"))
                    .andExpect(status().isOk())
                    .andReturn().getResponse();

            for (String name : DOWNLOAD_HEADERS) {
                assertThat(judicial.getHeaders(name)).as(name).isEqualTo(caseworker.getHeaders(name));
            }
            // And the comparison is not of two empty lists: the caseworker route's own tests pin these values.
            assertThat(judicial.getHeader(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");
            assertThat(judicial.getHeader(HttpHeaders.CONTENT_DISPOSITION)).contains(filename);
        }

        @Test
        @DisplayName("refuses a caseworker with the judicial role's 403, and opens nothing")
        void refusesACaseworker() throws Exception {
            String documentId = uploaded(caseId, "birth.pdf", "application/pdf");
            asCaseworker();

            authorised(get(fileUrl(referralId, documentId)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value(containsString(Role.JUDICIAL.claimValue())));

            verify(store, never()).open(any());
        }

        @ParameterizedTest
        @EnumSource(value = Role.class, names = {"TEAM_LEADER", "DELIVERY_MANAGER", "DEEDPOLL_ADMIN"})
        @DisplayName("refuses a senior caseworker too, because judicial is not seniority")
        void refusesASeniorCaseworker(Role role) throws Exception {
            String documentId = uploaded(caseId, "birth.pdf", "application/pdf");
            tokenSubject = StubIdam.SUBJECT;
            IDAM.stubUserInfo(StubIdam.SUBJECT, "A Team Leader", List.of(role.claimValue()));

            authorised(get(fileUrl(referralId, documentId))).andExpect(status().isForbidden());

            verify(store, never()).open(any());
        }

        @Test
        @DisplayName("refuses a caller with no token before routing")
        void refusesAnUnauthenticatedCaller() throws Exception {
            String documentId = uploaded(caseId, "birth.pdf", "application/pdf");

            mockMvc.perform(get(fileUrl(referralId, documentId))).andExpect(status().isUnauthorized());

            verify(store, never()).open(any());
        }
    }

    @Nested
    @DisplayName("GET /judicial/referrals/{referralId}")
    class CaseViewed {

        @Test
        @DisplayName("records one CASE_VIEWED row on the case, as the judge, with no values")
        void recordsTheView() throws Exception {
            authorised(get("/judicial/referrals/" + referralId)).andExpect(status().isOk());

            assertThat(reads("CASE_VIEWED")).singleElement().satisfies(row ->
                assertThat(row).containsEntry("actor_id", JUDGE_SUBJECT)
                    .containsEntry("actor_name", JUDGE_NAME)
                    .containsEntry("entity_type", "DeedPoll")
                    .containsEntry("entity_id", caseId));
        }

        @Test
        @DisplayName("still serves the referral when the audit insert fails")
        void failsOpen() throws Exception {
            asJudge(JUDGE_NAME, UNRECORDABLE);

            authorised(get("/judicial/referrals/" + referralId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(referralId));

            assertThat(reads("CASE_VIEWED")).isEmpty();
        }

        @Test
        @DisplayName("records nothing for a referral this judge may not see")
        void recordsNothingWhenRefused() throws Exception {
            referralService.claim(caseId, referralId, judge(OTHER_JUDGE_NAME, OTHER_JUDGE_SUBJECT));

            authorised(get("/judicial/referrals/" + referralId)).andExpect(status().isNotFound());

            assertThat(reads("CASE_VIEWED")).isEmpty();
        }

        @Test
        @DisplayName("is not recorded again by a claim, which answers with the same papers")
        void claimRecordsNoView() throws Exception {
            authorised(post("/judicial/referrals/" + referralId + "/claim"))
                .andExpect(status().isOk());

            assertThat(reads("CASE_VIEWED")).isEmpty();
        }
    }

    private String newCase() {
        return deedPollService.createDeedPoll(DeedPollPatch.of(Map.of("dateReceived", RECEIVED)))
            .deedPoll().getId();
    }

    private String raise(String forCase) {
        return referralService.createReferral(
                forCase,
                new CreateReferralInput(List.of(ReferralReason.Minor), Optional.of("Applicant is 14"),
                    Urgency.Normal, Optional.empty()),
                CASEWORKER)
            .getId().toString();
    }

    /**
     * A document uploaded through the domain, with a real blob behind it, as the caseworker download tests store
     * theirs: legacy {@code .doc} is no longer admitted, so its row is uploaded as DOCX and relabelled.
     */
    private String uploaded(String forCase, String filename, String mimeType) {
        String typeId = documentTypes.createDocumentType(CreateDocumentTypeInput.named("Birth certificate")).getId();
        String uploadType = "application/msword".equals(mimeType) ? DocumentFixtures.DOCX : mimeType;
        byte[] bytes = "application/pdf".equals(uploadType) ? PDF_BYTES : DocumentFixtures.bytes(uploadType);
        String id = documentService
            .uploadDocument(forCase, typeId, UploadedFile.of(bytes, filename, uploadType),
                Optional.empty(), Optional.empty())
            .getId();
        if (!uploadType.equals(mimeType)) {
            documentService.updateDocument(forCase, id, new UpdateDocumentInput(
                PatchField.absent(), PatchField.absent(), PatchField.absent(), PatchField.absent(),
                PatchField.absent(), PatchField.of(mimeType)));
        }
        return id;
    }

    private static String fileUrl(String referral, String documentId) {
        return "/judicial/referrals/" + referral + "/documents/" + documentId + "/file";
    }

    private List<Map<String, Object>> reads(String action) {
        return jdbc.queryForList(
            "SELECT actor_id, actor_name, entity_type, entity_id FROM deedpoll_audit_event "
                + "WHERE case_id = ? AND action = ?", caseId, action);
    }

    private static CurrentUser judge(String name, String subject) {
        return CurrentUser.currentUser(List.of(Role.JUDICIAL), name, subject);
    }

    private static void asJudge(String name, String subject) {
        tokenSubject = subject;
        IDAM.stubUserInfo(subject, name, List.of(Role.JUDICIAL.claimValue()));
    }

    private static void asCaseworker() {
        tokenSubject = StubIdam.SUBJECT;
        IDAM.stubUserInfo(StubIdam.SUBJECT, "Ada Caseworker", List.of("caseworker-civil"));
    }

    private ResultActions authorised(MockHttpServletRequestBuilder request) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(IDAM.accessToken(claims -> claims.subject(tokenSubject)));
        return mockMvc.perform(request.headers(headers));
    }
}
