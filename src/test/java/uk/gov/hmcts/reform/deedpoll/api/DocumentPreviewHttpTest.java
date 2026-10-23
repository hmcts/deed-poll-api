package uk.gov.hmcts.reform.deedpoll.api;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
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
import org.springframework.http.MediaType;
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
import uk.gov.hmcts.reform.deedpoll.domain.ForbiddenError;
import uk.gov.hmcts.reform.deedpoll.domain.NotFoundError;
import uk.gov.hmcts.reform.deedpoll.domain.PatchField;
import uk.gov.hmcts.reform.deedpoll.domain.UpstreamFailureError;
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
import uk.gov.hmcts.reform.deedpoll.domain.documents.preview.ConversionCapacity;
import uk.gov.hmcts.reform.deedpoll.domain.documents.preview.PreviewFailure;
import uk.gov.hmcts.reform.deedpoll.domain.documents.preview.PreviewFormat;
import uk.gov.hmcts.reform.deedpoll.domain.documents.preview.PreviewProperties;
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

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Both document previews over HTTP, through the real filter chain with the staff gate enforcing, against a real
 * database, a real filesystem store, the real renderers and a WireMock converter (Task 5 of the preview plan).
 *
 * <p>What is pinned here and not by {@code DocumentPreviewServiceTest}: the transport. Every refusal the chain or the
 * domain makes before a preview starts -- no token, the wrong staff area, the wrong parent, a referral this judge may
 * not see -- keeps its own status and {@link ApiError} body and reaches neither the store nor the converter; a
 * success is the whole PDF under the download's protective headers, whatever {@code Range} asks for; and a preview
 * that is not produced is the fixed {@link DocumentPreviewError} body under its failure's status. After every test,
 * every permit is free and no preview scratch directory is left, so writing the response released the rendition.
 *
 * <p>Not {@code @Transactional}: the read audit commits in its own transaction, and a preview refuses to run inside
 * one. Audit rows stay, as V36 requires, under case ids no other test uses.
 */
@SpringBootTest(properties = "deedpoll.security.staff-gate=enforce")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DocumentPreviewHttpTest extends AbstractPostgresContainerTest {

    private static final String RECEIVED = "2026-07-01T00:00:00.000Z";
    private static final String CONVERT = "/rs/convert";
    private static final String DOCX = PreviewFormat.DOCX.mimeType();

    /** A subject longer than the 255 characters actor_id holds: the one failure only the audit table refuses. */
    private static final String UNRECORDABLE = "h".repeat(256);

    private static final String CASEWORKER_NAME = "Ada Caseworker";
    private static final String STORE_FAILED = "The document store did not return this document's file. Try again.";
    private static final String STORE_FORBIDDEN = "You do not have permission to open this document's file";
    private static final String CASEWORKER_SUBJECT = "caseworker-preview-http";
    private static final String JUDGE_NAME = "District Judge Bell";
    private static final String JUDGE_SUBJECT = "idam-judge-bell";
    private static final String OTHER_JUDGE_NAME = "District Judge Okafor";
    private static final String OTHER_JUDGE_SUBJECT = "idam-judge-okafor";

    private static final CurrentUser OFFICE =
        CurrentUser.currentUser(List.of(Role.CASEWORKER), CASEWORKER_NAME, CASEWORKER_SUBJECT);

    /** The headers a successful preview sends, which both surfaces must send alike. */
    private static final List<String> PREVIEW_HEADERS = List.of(
        HttpHeaders.CONTENT_TYPE,
        HttpHeaders.CONTENT_DISPOSITION,
        HttpHeaders.CONTENT_LENGTH,
        HttpHeaders.CACHE_CONTROL,
        HttpHeaders.ACCEPT_RANGES,
        "X-Content-Type-Options",
        "Content-Security-Policy");

    private static final Path STORE_ROOT = temporaryStoreRoot();
    private static final Path SCRATCH = Path.of(System.getProperty("java.io.tmpdir"));
    private static final StubIdam IDAM = new StubIdam();
    private static final WireMockServer CONVERTER =
        new WireMockServer(WireMockConfiguration.options().dynamicPort());

    private static String tokenSubject = CASEWORKER_SUBJECT;

    static {
        IDAM.start();
        CONVERTER.start();
    }

    @AfterAll
    static void releaseTheStubsAndTheStore() throws IOException {
        IDAM.stop();
        CONVERTER.stop();
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
        registry.add("deedpoll.documents.preview.converter.url", () -> "http://localhost:" + CONVERTER.port());
        registry.add("deedpoll.documents.preview.converter.access-key", () -> "synthetic-preview-key");
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ConversionCapacity capacity;

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
    private String typeId;
    private List<Path> scratchBefore;

    private static Path temporaryStoreRoot() {
        try {
            return Files.createTempDirectory("deed-poll-api-preview-http");
        } catch (IOException failed) {
            throw new UncheckedIOException("Could not create a temporary document store", failed);
        }
    }

    @BeforeEach
    void setUp() {
        clearTables();
        IDAM.stubDefaults();
        CONVERTER.resetAll();
        CONVERTER.stubFor(post(urlPathEqualTo(CONVERT)).willReturn(aResponse().withStatus(200)
            .withHeader("Content-Type", "application/pdf").withBody(fixture("three-pages.pdf"))));
        asCaseworker();
        caseId = newCase();
        typeId = documentTypes.createDocumentType(CreateDocumentTypeInput.named("Evidence")).getId();
        scratchBefore = scratch();
    }

    @AfterEach
    void tearDown() {
        // Whatever the test did, the response released its rendition and its permit.
        assertThat(capacity.available()).isEqualTo(PreviewProperties.MAX_CONCURRENT_CONVERSIONS);
        assertThat(scratch()).isEqualTo(scratchBefore);
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
    @DisplayName("GET /caseworker/cases/{id}/documents/{documentId}/preview")
    class Caseworker {

        @ParameterizedTest(name = "{0}")
        @CsvSource({
            "three-pages.pdf, application/pdf, 3",
            "photo.jpeg, image/jpeg, 1",
            "scan.png, image/png, 1",
            "three-frames.tiff, image/tiff, 3",
            "three-pages.docx, application/vnd.openxmlformats-officedocument.wordprocessingml.document, 3"
        })
        @DisplayName("answers every allowed format with the whole PDF under the protective headers")
        void previewsEveryFormat(String fixture, String mimeType, int pages) throws Exception {
            byte[] original = fixture(fixture);
            String documentId = uploaded(caseId, fixture, mimeType, original);

            MockHttpServletResponse response = authorised(get(caseworkerUrl(caseId, documentId)))
                .andExpect(status().isOk())
                .andReturn().getResponse();

            assertSuccessfulPreview(response, pages);
            assertThat(reads("DOCUMENT_PREVIEWED")).singleElement().satisfies(row ->
                assertThat(row).containsEntry("actor_id", CASEWORKER_SUBJECT)
                    .containsEntry("entity_type", "Document")
                    .containsEntry("entity_id", documentId));
            assertThat(reads("DOCUMENT_DOWNLOADED")).isEmpty();
            assertThat(CONVERTER.findAll(anyRequestedFor(anyUrl())))
                .hasSize(PreviewFormat.DOCX.mimeType().equals(mimeType) ? 1 : 0);

            // The original is untouched and still served as itself.
            authorised(get("/caseworker/cases/" + caseId + "/documents/" + documentId + "/file"))
                .andExpect(status().isOk())
                .andExpect(content().bytes(original));
        }

        @Test
        @DisplayName("ignores a Range header and sends the whole PDF with 200, never a partial 206")
        void ignoresRange() throws Exception {
            String documentId = uploaded(caseId, "three-pages.pdf", "application/pdf", fixture("three-pages.pdf"));

            MockHttpServletResponse response = authorised(get(caseworkerUrl(caseId, documentId))
                    .header(HttpHeaders.RANGE, "bytes=0-99"))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist(HttpHeaders.CONTENT_RANGE))
                .andReturn().getResponse();

            assertSuccessfulPreview(response, 3);
            assertThat(response.getContentAsByteArray().length).isGreaterThan(100);
        }

        @Test
        @DisplayName("refuses a caller with no token with 401, opening and converting nothing")
        void refusesAnonymous() throws Exception {
            String documentId = uploaded(caseId, "three-pages.docx", DOCX, fixture("three-pages.docx"));

            mockMvc.perform(get(caseworkerUrl(caseId, documentId))).andExpect(status().isUnauthorized());

            assertNothingReadOrSent();
        }

        @Test
        @DisplayName("refuses a judge on the caseworker surface with the chain's 403")
        void refusesTheWrongArea() throws Exception {
            String documentId = uploaded(caseId, "three-pages.docx", DOCX, fixture("three-pages.docx"));
            asJudge(JUDGE_NAME, JUDGE_SUBJECT);

            authorised(get(caseworkerUrl(caseId, documentId)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").isString())
                .andExpect(jsonPath("$.code").doesNotExist());

            assertNothingReadOrSent();
        }

        @Test
        @DisplayName("is the document's 404 through another case's URL, and for an unknown document")
        void notFoundThroughTheWrongParent() throws Exception {
            String documentId = uploaded(caseId, "three-pages.docx", DOCX, fixture("three-pages.docx"));
            String otherCase = newCase();

            authorised(get(caseworkerUrl(otherCase, documentId)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Document " + documentId + " not found"))
                .andExpect(jsonPath("$.code").doesNotExist());
            authorised(get(caseworkerUrl(caseId, "00000000-0000-0000-0000-000000000000")))
                .andExpect(status().isNotFound());

            assertNothingReadOrSent();
        }

        @Test
        @DisplayName("is 422 PREVIEW_INVALID_DOCUMENT for a stored type with no preview, in the fixed body alone")
        void invalidDocument() throws Exception {
            String documentId = uploaded(caseId, "three-pages.docx", DOCX, fixture("three-pages.docx"));
            relabel(documentId, "application/msword");

            authorised(get(caseworkerUrl(caseId, documentId)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.code").value("PREVIEW_INVALID_DOCUMENT"))
                .andExpect(jsonPath("$.error").value(PreviewFailure.INVALID_DOCUMENT.message()))
                .andExpect(jsonPath("$.*", hasSize(2)));

            assertNothingReadOrSent();
        }

        @Test
        @DisplayName("is 422 PREVIEW_INVALID_DOCUMENT for stored bytes that are not the stored type")
        void damagedBytesAreInvalid() throws Exception {
            // A PNG row whose stored bytes were replaced by a PDF behind the upload's checks.
            String documentId = uploaded(caseId, "scan.png", "image/png", fixture("scan.png"));
            Files.write(storedFile(documentId), fixture("three-pages.pdf"));

            authorised(get(caseworkerUrl(caseId, documentId)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("PREVIEW_INVALID_DOCUMENT"));
        }

        @Test
        @DisplayName("is 503 PREVIEW_UNAVAILABLE when the converter fails, and the original still downloads")
        void converterUnavailable() throws Exception {
            CONVERTER.stubFor(post(urlPathEqualTo(CONVERT)).willReturn(aResponse().withStatus(503)
                .withBody("Tornado says: internal detail that must not leak")));
            String documentId = uploaded(caseId, "three-pages.docx", DOCX, fixture("three-pages.docx"));

            String body = authorised(get(caseworkerUrl(caseId, documentId)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("PREVIEW_UNAVAILABLE"))
                .andExpect(jsonPath("$.error").value(PreviewFailure.UNAVAILABLE.message()))
                .andReturn().getResponse().getContentAsString();
            assertThat(body).doesNotContain("Tornado", "localhost", "three-pages", "synthetic-preview-key");

            authorised(get("/caseworker/cases/" + caseId + "/documents/" + documentId + "/file"))
                .andExpect(status().isOk())
                .andExpect(content().bytes(fixture("three-pages.docx")));
        }

        @Test
        @DisplayName("is 429 PREVIEW_BUSY with Retry-After when every permit is held, and a PDF still passes")
        void busy() throws Exception {
            String png = uploaded(caseId, "scan.png", "image/png", fixture("scan.png"));
            String pdf = uploaded(caseId, "three-pages.pdf", "application/pdf", fixture("three-pages.pdf"));
            List<ConversionCapacity.Permit> held = new ArrayList<>();
            try {
                for (int i = 0; i < PreviewProperties.MAX_CONCURRENT_CONVERSIONS; i++) {
                    held.add(capacity.acquire(PreviewFormat.PNG));
                }

                authorised(get(caseworkerUrl(caseId, png)))
                    .andExpect(status().isTooManyRequests())
                    .andExpect(header().string(HttpHeaders.RETRY_AFTER, "5"))
                    .andExpect(jsonPath("$.code").value("PREVIEW_BUSY"));
                assertThat(reads("DOCUMENT_PREVIEWED")).as("busy is decided after the audited source read")
                    .hasSize(1);

                authorised(get(caseworkerUrl(caseId, pdf))).andExpect(status().isOk());
            } finally {
                held.forEach(ConversionCapacity.Permit::close);
            }
        }

        @Test
        @DisplayName("keeps the store's own 403 and 404 with every permit held, never 429")
        void storeRefusalOutranksBusy() throws Exception {
            String png = uploaded(caseId, "scan.png", "image/png", fixture("scan.png"));
            List<ConversionCapacity.Permit> held = new ArrayList<>();
            try {
                for (int i = 0; i < PreviewProperties.MAX_CONCURRENT_CONVERSIONS; i++) {
                    held.add(capacity.acquire(PreviewFormat.PNG));
                }
                doThrow(new ForbiddenError(STORE_FORBIDDEN)).when(store).open(any());
                authorised(get(caseworkerUrl(caseId, png)).accept(MediaType.APPLICATION_PDF))
                    .andExpect(status().isForbidden())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                    .andExpect(jsonPath("$.error").value(STORE_FORBIDDEN));
                doThrow(new NotFoundError("Stored file not found")).when(store).open(any());
                authorised(get(caseworkerUrl(caseId, png)).accept(MediaType.APPLICATION_PDF))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error").value("Stored file not found"));
                assertThat(reads("DOCUMENT_PREVIEWED")).as("each refused read is recorded once, as a download's is")
                    .hasSize(2);
                assertThat(CONVERTER.findAll(anyRequestedFor(anyUrl()))).isEmpty();
            } finally {
                held.forEach(ConversionCapacity.Permit::close);
                reset(store);
            }
        }

        @Test
        @DisplayName("answers a refusal in JSON even to a client that asked only for application/pdf")
        void refusesInJsonWhateverTheAccept() throws Exception {
            String documentId = uploaded(caseId, "three-pages.docx", DOCX, fixture("three-pages.docx"));
            relabel(documentId, "application/msword");

            authorised(get(caseworkerUrl(caseId, documentId)).accept(MediaType.APPLICATION_PDF))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("PREVIEW_INVALID_DOCUMENT"));
            String elsewhere = newCase();
            authorised(get(caseworkerUrl(elsewhere, documentId)).accept(MediaType.APPLICATION_PDF))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.error").value("Document " + documentId + " not found"));

            String referralId = raise(caseId);
            asCaseworker();
            authorised(get(judicialUrl(referralId, documentId)).accept(MediaType.APPLICATION_PDF))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON));
        }

        @Test
        @DisplayName("keeps a store that would not return the original as the download's 502, in JSON, never sent")
        void storeFailureIsTheDownloadsBadGateway() throws Exception {
            String documentId = uploaded(caseId, "three-pages.docx", DOCX, fixture("three-pages.docx"));
            doThrow(new UpstreamFailureError(STORE_FAILED)).when(store).open(any());
            try {
                authorised(get(caseworkerUrl(caseId, documentId)).accept(MediaType.APPLICATION_PDF))
                    .andExpect(status().isBadGateway())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                    .andExpect(jsonPath("$.error").value(STORE_FAILED));
                assertThat(CONVERTER.findAll(anyRequestedFor(anyUrl()))).isEmpty();
            } finally {
                reset(store);
            }
        }

        @Test
        @DisplayName("serves nothing when the audit insert fails, and never opens the file")
        void failsClosed() {
            String documentId = uploaded(caseId, "three-pages.docx", DOCX, fixture("three-pages.docx"));
            asCaseworker(UNRECORDABLE);

            // Nothing maps a database failure, so MockMvc surfaces it rather than a rendered 500.
            assertThatThrownBy(() -> authorised(get(caseworkerUrl(caseId, documentId))))
                .hasCauseInstanceOf(DataAccessException.class);

            assertNothingReadOrSent();
        }
    }

    @Nested
    @DisplayName("GET /judicial/referrals/{referralId}/documents/{documentId}/preview")
    class Judicial {

        private String referralId;

        @BeforeEach
        void raiseAReferral() {
            referralId = raise(caseId);
            asJudge(JUDGE_NAME, JUDGE_SUBJECT);
        }

        @ParameterizedTest(name = "{0}")
        @CsvSource({
            "three-pages.pdf, application/pdf, 3",
            "photo.jpeg, image/jpeg, 1",
            "scan.png, image/png, 1",
            "two-frames.tiff, image/tiff, 2",
            "table-and-image.docx, application/vnd.openxmlformats-officedocument.wordprocessingml.document, 3"
        })
        @DisplayName("answers any judge on an Open referral with the whole PDF, recorded as the judge")
        void previewsEveryFormat(String fixture, String mimeType, int pages) throws Exception {
            String documentId = uploaded(caseId, fixture, mimeType, fixture(fixture));

            MockHttpServletResponse response = authorised(get(judicialUrl(referralId, documentId)))
                .andExpect(status().isOk())
                .andReturn().getResponse();

            assertSuccessfulPreview(response, pages);
            assertThat(reads("DOCUMENT_PREVIEWED")).singleElement().satisfies(row ->
                assertThat(row).containsEntry("actor_id", JUDGE_SUBJECT).containsEntry("entity_id", documentId));

            asJudge(OTHER_JUDGE_NAME, OTHER_JUDGE_SUBJECT);
            authorised(get(judicialUrl(referralId, documentId))).andExpect(status().isOk());
        }

        @Test
        @DisplayName("sends exactly the caseworker preview's headers")
        void matchesTheCaseworkerHeaders() throws Exception {
            String documentId = uploaded(caseId, "scan.png", "image/png", fixture("scan.png"));

            MockHttpServletResponse judicial = authorised(get(judicialUrl(referralId, documentId)))
                .andExpect(status().isOk()).andReturn().getResponse();
            asCaseworker();
            MockHttpServletResponse caseworker = authorised(get(caseworkerUrl(caseId, documentId)))
                .andExpect(status().isOk()).andReturn().getResponse();

            for (String name : PREVIEW_HEADERS) {
                assertThat(judicial.getHeaders(name)).as(name).isNotEmpty().isEqualTo(caseworker.getHeaders(name));
            }
        }

        @Test
        @DisplayName("is the referral's 404 to another judge once one has claimed it, and the holder's 200")
        void onlyTheHolderOfAClaim() throws Exception {
            String documentId = uploaded(caseId, "three-pages.docx", DOCX, fixture("three-pages.docx"));
            referralService.claim(caseId, referralId, judge(JUDGE_NAME, JUDGE_SUBJECT));

            asJudge(OTHER_JUDGE_NAME, OTHER_JUDGE_SUBJECT);
            authorised(get(judicialUrl(referralId, documentId)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Referral " + referralId + " not found"));
            assertNothingReadOrSent();

            asJudge(JUDGE_NAME, JUDGE_SUBJECT);
            authorised(get(judicialUrl(referralId, documentId))).andExpect(status().isOk());
        }

        @Test
        @DisplayName("is the referral's 404 once the office closes it, or once it is withdrawn")
        void hiddenOnceClosedOrWithdrawn() throws Exception {
            final String documentId = uploaded(caseId, "three-pages.docx", DOCX, fixture("three-pages.docx"));
            CurrentUser judge = judge(JUDGE_NAME, JUDGE_SUBJECT);
            referralService.claim(caseId, referralId, judge);
            referralService.decide(caseId, referralId,
                new DirectionInput(MasterDecision.Approved, Optional.of("Granted"), false), judge);
            referralService.close(caseId, referralId, OFFICE);

            authorised(get(judicialUrl(referralId, documentId)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Referral " + referralId + " not found"));

            String withdrawn = raise(caseId);
            referralService.withdrawReferral(caseId, withdrawn, OFFICE);
            authorised(get(judicialUrl(withdrawn, documentId)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Referral " + withdrawn + " not found"));

            assertNothingReadOrSent();
        }

        @Test
        @DisplayName("is the document's 404 for another case's document, so a referral reaches only its own")
        void notFoundForAnotherCasesDocument() throws Exception {
            String elsewhere = uploaded(newCase(), "three-pages.docx", DOCX, fixture("three-pages.docx"));

            authorised(get(judicialUrl(referralId, elsewhere)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Document " + elsewhere + " not found"));

            assertNothingReadOrSent();
        }

        @ParameterizedTest
        @EnumSource(value = Role.class, names = {"CASEWORKER", "TEAM_LEADER", "DELIVERY_MANAGER", "DEEDPOLL_ADMIN"})
        @DisplayName("refuses every staff role on the judicial surface with 403")
        void refusesStaff(Role role) throws Exception {
            String documentId = uploaded(caseId, "three-pages.docx", DOCX, fixture("three-pages.docx"));
            tokenSubject = CASEWORKER_SUBJECT;
            IDAM.stubUserInfo(CASEWORKER_SUBJECT, CASEWORKER_NAME, List.of(role.claimValue()));

            authorised(get(judicialUrl(referralId, documentId)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value(containsString(Role.JUDICIAL.claimValue())));

            assertNothingReadOrSent();
        }

        @Test
        @DisplayName("refuses a caller with no token with 401")
        void refusesAnonymous() throws Exception {
            String documentId = uploaded(caseId, "three-pages.docx", DOCX, fixture("three-pages.docx"));

            mockMvc.perform(get(judicialUrl(referralId, documentId))).andExpect(status().isUnauthorized());

            assertNothingReadOrSent();
        }

        @Test
        @DisplayName("is 503 PREVIEW_UNAVAILABLE in the same fixed body when the converter fails")
        void converterUnavailable() throws Exception {
            CONVERTER.stubFor(post(urlPathEqualTo(CONVERT)).willReturn(aResponse().withStatus(500)));
            String documentId = uploaded(caseId, "three-pages.docx", DOCX, fixture("three-pages.docx"));

            authorised(get(judicialUrl(referralId, documentId)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.code").value("PREVIEW_UNAVAILABLE"))
                .andExpect(jsonPath("$.*", hasSize(2)));
        }
    }

    private void assertSuccessfulPreview(MockHttpServletResponse response, int pages) throws IOException {
        byte[] body = response.getContentAsByteArray();
        assertThat(response.getContentType()).isEqualTo(MediaType.APPLICATION_PDF_VALUE);
        assertThat(response.getHeader(HttpHeaders.CONTENT_DISPOSITION))
            .isEqualTo("inline; filename=\"" + DocumentFileResponses.PREVIEW_FILENAME + "\"");
        assertThat(response.getHeader(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");
        assertThat(response.getHeaders("X-Content-Type-Options")).containsExactly("nosniff");
        assertThat(response.getHeader("Content-Security-Policy")).isEqualTo("default-src 'none'; sandbox");
        assertThat(response.getHeader(HttpHeaders.ACCEPT_RANGES)).isEqualTo("none");
        assertThat(response.getHeader(HttpHeaders.CONTENT_LENGTH)).isEqualTo(Integer.toString(body.length));
        try (PDDocument pdf = Loader.loadPDF(body)) {
            assertThat(pdf.getNumberOfPages()).isEqualTo(pages);
        }
    }

    private void assertNothingReadOrSent() {
        verify(store, never()).open(any());
        assertThat(CONVERTER.findAll(anyRequestedFor(anyUrl()))).isEmpty();
        assertThat(reads("DOCUMENT_PREVIEWED")).isEmpty();
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
                OFFICE)
            .getId().toString();
    }

    private String uploaded(String forCase, String filename, String mimeType, byte[] bytes) {
        String id = documentService
            .uploadDocument(forCase, typeId, UploadedFile.of(bytes, filename, mimeType),
                Optional.empty(), Optional.empty())
            .getId();
        clearInvocations(store);
        return id;
    }

    private void relabel(String documentId, String mimeType) {
        documentService.updateDocument(caseId, documentId, new UpdateDocumentInput(
            PatchField.absent(), PatchField.absent(), PatchField.absent(), PatchField.absent(),
            PatchField.absent(), PatchField.of(mimeType)));
    }

    /** The filesystem store's copy of a document: its storage key under the store's root. */
    private Path storedFile(String documentId) {
        String key = jdbc.queryForObject(
            "SELECT \"storageKey\" FROM deedpoll_supportingdocument WHERE id::text = ?", String.class, documentId);
        return STORE_ROOT.resolve(key);
    }

    private static byte[] fixture(String name) {
        try (InputStream in = DocumentPreviewHttpTest.class.getResourceAsStream("/document-previews/" + name)) {
            if (in == null) {
                throw new IllegalStateException("no fixture " + name);
            }
            return in.readAllBytes();
        } catch (IOException unreadable) {
            throw new UncheckedIOException(unreadable);
        }
    }

    private static String caseworkerUrl(String forCase, String documentId) {
        return "/caseworker/cases/" + forCase + "/documents/" + documentId + "/preview";
    }

    private static String judicialUrl(String referral, String documentId) {
        return "/judicial/referrals/" + referral + "/documents/" + documentId + "/preview";
    }

    private static List<Path> scratch() {
        try (Stream<Path> entries = Files.list(SCRATCH)) {
            return entries.filter(path -> path.getFileName().toString().startsWith("preview-")).sorted().toList();
        } catch (IOException unreadable) {
            throw new UncheckedIOException(unreadable);
        }
    }

    private List<Map<String, Object>> reads(String action) {
        return jdbc.queryForList(
            "SELECT actor_id, entity_type, entity_id FROM deedpoll_audit_event WHERE case_id = ? AND action = ?",
            caseId, action);
    }

    private static CurrentUser judge(String name, String subject) {
        return CurrentUser.currentUser(List.of(Role.JUDICIAL), name, subject);
    }

    private static void asJudge(String name, String subject) {
        tokenSubject = subject;
        IDAM.stubUserInfo(subject, name, List.of(Role.JUDICIAL.claimValue()));
    }

    private static void asCaseworker() {
        asCaseworker(CASEWORKER_SUBJECT);
    }

    private static void asCaseworker(String subject) {
        tokenSubject = subject;
        IDAM.stubUserInfo(subject, CASEWORKER_NAME, List.of(Role.CASEWORKER.claimValue()));
    }

    private ResultActions authorised(MockHttpServletRequestBuilder request) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(IDAM.accessToken(claims -> claims.subject(tokenSubject)));
        return mockMvc.perform(request.headers(headers));
    }
}
