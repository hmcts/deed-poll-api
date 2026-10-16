package uk.gov.hmcts.reform.deedpoll.api.caseworker.controller;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import uk.gov.hmcts.reform.deedpoll.auth.StubIdam;
import uk.gov.hmcts.reform.deedpoll.config.AbstractPostgresContainerTest;
import uk.gov.hmcts.reform.deedpoll.domain.PatchField;
import uk.gov.hmcts.reform.deedpoll.domain.deedpoll.DeedPollPatch;
import uk.gov.hmcts.reform.deedpoll.domain.deedpoll.DeedPollService;
import uk.gov.hmcts.reform.deedpoll.domain.documents.CreateDocumentInput;
import uk.gov.hmcts.reform.deedpoll.domain.documents.CreateDocumentTypeInput;
import uk.gov.hmcts.reform.deedpoll.domain.documents.DocumentSchemas;
import uk.gov.hmcts.reform.deedpoll.domain.documents.DocumentService;
import uk.gov.hmcts.reform.deedpoll.domain.documents.DocumentStore;
import uk.gov.hmcts.reform.deedpoll.domain.documents.DocumentTypeService;
import uk.gov.hmcts.reform.deedpoll.domain.documents.UpdateDocumentInput;
import uk.gov.hmcts.reform.deedpoll.domain.documents.UploadedFile;
import uk.gov.hmcts.reform.deedpoll.entity.DeedPollSupportingDocument;
import uk.gov.hmcts.reform.deedpoll.entity.enums.DocumentDirection;
import uk.gov.hmcts.reform.deedpoll.repository.DeedPollRepository;
import uk.gov.hmcts.reform.deedpoll.repository.DeedPollSupportingDocumentRepository;
import uk.gov.hmcts.reform.deedpoll.repository.DeedPollSupportingDocumentTypeRepository;
import uk.gov.hmcts.reform.deedpoll.repository.ReferenceCounterRepository;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The upload and the download, over HTTP, against a real database and the real local document store.
 *
 * <p>The whole context rather than a {@code @WebMvcTest} slice, and not {@code @Transactional}, for the reasons
 * the other caseworker controller tests record: a slice asserts Spring Boot's default security rather than this
 * service's, and a write has to really commit for an assertion to read the stored row back.
 *
 * <p><strong>The real store against a throwaway directory, not a mocked one.</strong> {@link DocumentStore} is a
 * Mockito <em>spy</em>, as in {@code DocumentServiceTest}: the metadata row and the blob are asserted to agree,
 * so an upload that recorded a row without writing the bytes -- or a download that answered a row's metadata over
 * somebody else's bytes -- fails rather than passing a metadata-only assertion. The spy is only used to prove a
 * negative, that a refused upload never reached storage.
 *
 * <p><strong>What this test cannot reach is the size limit</strong>, because MockMvc builds an
 * already-parsed multipart request and never invokes the multipart resolver that enforces one.
 * {@code CaseworkerDocumentUploadLimitTest} drives a real server for that one case.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CaseworkerDocumentControllerTest extends AbstractPostgresContainerTest {

    /** createDeedPoll cannot allocate a reference number without a Date Received to derive the year from. */
    private static final String RECEIVED = "2026-07-01T00:00:00.000Z";

    /** An id of the right shape that nothing was ever stored under. */
    private static final String UNKNOWN_ID = "11111111-1111-1111-1111-111111111111";

    private static final byte[] PDF_BYTES = "%PDF-1.4 fake".getBytes(StandardCharsets.UTF_8);

    /**
     * Blobs go to a throwaway directory, never to the developer's own {@code data/documents}.
     */
    private static final Path STORE_ROOT = temporaryStoreRoot();

    private static final StubIdam IDAM = new StubIdam();

    static {
        // Started here rather than in @BeforeAll: @DynamicPropertySource is read while the context is being
        // built, which is before any @BeforeAll callback could have started the stub. The store root is
        // created the same way and for the same reason.
        IDAM.start();
    }

    @AfterAll
    static void releaseTheStubAndTheStore() throws IOException {
        // A static start needs an explicit stop, or the class leaves a Jetty server, its thread pool and a
        // port behind for the life of the test JVM -- and the store root would stay in the build agent's
        // temp directory with a blob per upload test in it.
        IDAM.stop();
        deleteRecursively(STORE_ROOT);
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> entries = Files.walk(root)) {
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
    private DeedPollRepository deedPolls;

    @Autowired
    private DeedPollSupportingDocumentRepository documents;

    @Autowired
    private DeedPollSupportingDocumentTypeRepository types;

    @Autowired
    private ReferenceCounterRepository referenceCounters;

    @MockitoSpyBean
    private DocumentStore store;

    private static Path temporaryStoreRoot() {
        try {
            return Files.createTempDirectory("deed-poll-api-documents");
        } catch (IOException failed) {
            throw new UncheckedIOException("Could not create a temporary document store", failed);
        }
    }

    @BeforeEach
    void setUp() throws IOException {
        clearTables();
        resetIdam();
    }

    @AfterEach
    void tearDown() throws IOException {
        clearTables();
    }

    private void clearTables() throws IOException {
        documents.deleteAll();
        types.deleteAll();
        deedPolls.deleteAll();
        referenceCounters.deleteAll();
        // The blobs go too, not just their rows: leaving them makes the store order-dependent, so an
        // assertion about what is stored would pass or fail on what an earlier test left behind.
        clearStore();
    }

    private static void clearStore() throws IOException {
        try (Stream<Path> blobs = Files.list(STORE_ROOT)) {
            for (Path blob : blobs.toList()) {
                Files.deleteIfExists(blob);
            }
        }
    }

    private void resetIdam() {
        IDAM.stubDefaults();
    }

    @Nested
    @DisplayName("without a token")
    class Unauthenticated {

        @Test
        @DisplayName("refuses an upload, and stores neither a row nor a blob")
        void refusesAnUpload() throws Exception {
            String caseId = newCase();
            String typeId = newType();

            mockMvc.perform(multipart("/caseworker/cases/" + caseId + "/documents")
                    .file(pdf())
                    .param("documentTypeId", typeId))
                .andExpect(status().isUnauthorized());

            assertThat(documents.count()).isZero();
            verify(store, never()).save(any());
        }

        @Test
        @DisplayName("refuses a download")
        void refusesADownload() throws Exception {
            String caseId = newCase();
            String documentId = uploaded(caseId, "birth.pdf", "application/pdf");

            mockMvc.perform(get("/caseworker/cases/" + caseId + "/documents/" + documentId + "/file"))
                .andExpect(status().isUnauthorized());
        }
    }

    @Nested
    @DisplayName("POST /caseworker/cases/{id}/documents")
    class Upload {

        @Test
        @DisplayName("stores the blob and the row that points at it, and answers 201")
        void storesTheBlobAndTheRow() throws Exception {
            String caseId = newCase();
            String typeId = newType();

            authorised(multipart("/caseworker/cases/" + caseId + "/documents")
                .file(pdf())
                .param("documentTypeId", typeId))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").exists())
                // The file's own name, since the form named nothing, and the default direction.
                .andExpect(jsonPath("$.name").value("birth.pdf"))
                .andExpect(jsonPath("$.originalName").value("birth.pdf"))
                .andExpect(jsonPath("$.mimeType").value("application/pdf"))
                .andExpect(jsonPath("$.sizeBytes").value(PDF_BYTES.length))
                .andExpect(jsonPath("$.direction").value("Incoming"))
                .andExpect(jsonPath("$.documentType.id").value(typeId))
                .andExpect(jsonPath("$.documentType.name").value("Birth certificate"))
                .andExpect(jsonPath("$.hasFile").value(true));

            DeedPollSupportingDocument saved = onlyStoredDocument();
            assertThat(saved.getDeedPoll().getId()).isEqualTo(caseId);
            assertThat(saved.getStorageKey()).isNotBlank();
            // The bytes really reached storage, which a metadata-only assertion would not notice.
            assertThat(bytesInStore(saved.getStorageKey())).isEqualTo(PDF_BYTES);
        }

        @Test
        @DisplayName("does not publish the storage key, which is an internal locator")
        void doesNotPublishTheStorageKey() throws Exception {
            // A filename under the local store's root today and a CDAM document id after plan 03, so publishing
            // it would hand out a value about to change shape -- and name a path on the server for no gain.
            // hasFile is the only question a consumer actually asks of it.
            authorised(multipart("/caseworker/cases/" + newCase() + "/documents")
                .file(pdf())
                .param("documentTypeId", newType()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.storageKey").doesNotExist());
        }

        @Test
        @DisplayName("honours the name and direction the form supplied")
        void honoursTheFormFields() throws Exception {
            String caseId = newCase();

            authorised(multipart("/caseworker/cases/" + caseId + "/documents")
                .file(pdf())
                .param("documentTypeId", newType())
                .param("name", "Applicant's birth certificate")
                .param("direction", "Outgoing"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Applicant's birth certificate"))
                .andExpect(jsonPath("$.direction").value("Outgoing"));

            assertThat(onlyStoredDocument().getDirection()).isEqualTo(DocumentDirection.Outgoing);
        }

        @Test
        @DisplayName("takes the case from the path, never from a form field")
        void takesTheCaseFromThePath() throws Exception {
            String caseId = newCase();
            String otherCaseId = newCase();

            authorised(multipart("/caseworker/cases/" + caseId + "/documents")
                .file(pdf())
                .param("documentTypeId", newType())
                .param("deedPollId", otherCaseId))
                .andExpect(status().isCreated());

            // Structural rather than checked: UploadFormInput has no component a deedPollId could land in.
            assertThat(onlyStoredDocument().getDeedPoll().getId()).isEqualTo(caseId);
        }

        @Test
        @DisplayName("caps a very long filename at the column's width rather than failing on it")
        void capsALongFilename() throws Exception {
            String longName = "a".repeat(150) + ".pdf";

            authorised(multipart("/caseworker/cases/" + newCase() + "/documents")
                .file(new MockMultipartFile("file", longName, "application/pdf", PDF_BYTES))
                .param("documentTypeId", newType()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("a".repeat(DocumentSchemas.MAX_NAME_LENGTH)))
                // The full name is still kept, because it is what the download offers back.
                .andExpect(jsonPath("$.originalName").value(longName));
        }

        @Test
        @DisplayName("400s with the domain's own message when no file was sent")
        void refusesWhenNoFileWasSent() throws Exception {
            // Deliberately not pre-empted by the controller: the message belongs with the rule, so the SSR form
            // and this API give the same one. The reference makes the same point.
            authorised(multipart("/caseworker/cases/" + newCase() + "/documents")
                .param("documentTypeId", newType()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Select a file to upload"));

            assertThat(documents.count()).isZero();
            verify(store, never()).save(any());
        }

        @Test
        @DisplayName("treats an empty file part as no file, rather than storing a zero-byte blob")
        void treatsAnEmptyPartAsNoFile() throws Exception {
            // What a form submitted with its file input untouched sends. A stored empty blob would leave a row
            // that looks downloadable and is not.
            authorised(multipart("/caseworker/cases/" + newCase() + "/documents")
                .file(new MockMultipartFile("file", "", "application/pdf", new byte[0]))
                .param("documentTypeId", newType()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Select a file to upload"));

            assertThat(documents.count()).isZero();
        }

        @Test
        @DisplayName("400s a missing document type, attributed to the field, and stores nothing")
        void refusesAMissingDocumentType() throws Exception {
            // The reference records the prototype reading this as a raw body primitive its validation pipe
            // skipped, so an absent one came back as a foreign-key failure rather than as a 400.
            authorised(multipart("/caseworker/cases/" + newCase() + "/documents").file(pdf()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.length()").value(1))
                .andExpect(jsonPath("$.errors[0].field").value("documentTypeId"));

            assertThat(documents.count()).isZero();
            verify(store, never()).save(any());
        }

        @Test
        @DisplayName("400s a document type that is not a UUID")
        void refusesAMalformedDocumentType() throws Exception {
            authorised(multipart("/caseworker/cases/" + newCase() + "/documents")
                .file(pdf())
                .param("documentTypeId", "not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("documentTypeId"));
        }

        @Test
        @DisplayName("400s a direction that is not one of the accepted values")
        void refusesAnUnknownDirection() throws Exception {
            authorised(multipart("/caseworker/cases/" + newCase() + "/documents")
                .file(pdf())
                .param("documentTypeId", newType())
                .param("direction", "Sideways"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("direction"));

            assertThat(documents.count()).isZero();
        }

        @Test
        @DisplayName("400s a form field sent twice instead of silently taking the first value")
        void refusesARepeatedFormField() throws Exception {
            // The same trap task 13 records on ?type=: a caller who sent a field twice does not know which
            // value took effect, and a service that picks for them makes that unknowable rather than obvious.
            authorised(multipart("/caseworker/cases/" + newCase() + "/documents")
                .file(pdf())
                .param("documentTypeId", newType())
                .param("name", "One", "Two"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("name"));

            assertThat(documents.count()).isZero();
        }

        @Test
        @DisplayName("400s a file type not on the list, attributed to the file input")
        void refusesAnUnacceptableType() throws Exception {
            authorised(multipart("/caseworker/cases/" + newCase() + "/documents")
                .file(new MockMultipartFile("file", "evil.exe", "application/x-msdownload", new byte[]{'M', 'Z'}))
                .param("documentTypeId", newType()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("application/x-msdownload is not an accepted file type"))
                .andExpect(jsonPath("$.errors[0].field").value("file"))
                .andExpect(jsonPath("$.errors[0].message").value("Upload a PDF, image or Word document"));

            assertThat(documents.count()).isZero();
            verify(store, never()).save(any());
        }

        @Test
        @DisplayName("400s a part with no declared type at all, rather than failing on the null")
        void refusesAPartWithNoDeclaredType() throws Exception {
            // A client may legitimately send a part with no Content-Type. Set.of(...).contains(null) throws
            // rather than answering false, so the straight port was a 500 for what should be a 400.
            authorised(multipart("/caseworker/cases/" + newCase() + "/documents")
                .file(new MockMultipartFile("file", "birth.pdf", null, PDF_BYTES))
                .param("documentTypeId", newType()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("file"));
        }

        @ParameterizedTest(name = "accepts {0}")
        @CsvSource({
            "application/pdf, a.pdf",
            "image/jpeg, a.jpg",
            "image/png, a.png",
            "image/tiff, a.tif",
            "application/msword, a.doc",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document, a.docx"
        })
        @DisplayName("accepts every type the upload policy allows")
        void acceptsEveryAllowedType(String mimeType, String filename) throws Exception {
            authorised(multipart("/caseworker/cases/" + newCase() + "/documents")
                .file(new MockMultipartFile("file", filename, mimeType, PDF_BYTES))
                .param("documentTypeId", newType()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.mimeType").value(mimeType));
        }

        @Test
        @DisplayName("strips a content type's parameters before matching it, and stores the bare type")
        void stripsContentTypeParameters() throws Exception {
            // The policy matches exactly, never by prefix, so that "application/" cannot be read as one --
            // which means a browser's "application/pdf; charset=binary" has to be reduced first. Storing the
            // bare type keeps the row saying what was actually checked.
            authorised(multipart("/caseworker/cases/" + newCase() + "/documents")
                .file(new MockMultipartFile("file", "a.pdf", "application/pdf; charset=binary", PDF_BYTES))
                .param("documentTypeId", newType()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.mimeType").value("application/pdf"));

            assertThat(onlyStoredDocument().getMimeType()).isEqualTo("application/pdf");
        }

        @Test
        @DisplayName("400s two files rather than silently storing one of them")
        void refusesTwoFiles() throws Exception {
            authorised(multipart("/caseworker/cases/" + newCase() + "/documents")
                .file(new MockMultipartFile("file", "a.pdf", "application/pdf", PDF_BYTES))
                .file(new MockMultipartFile("file", "b.pdf", "application/pdf", PDF_BYTES))
                .param("documentTypeId", newType()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("file"))
                .andExpect(jsonPath("$.errors[0].message").value("Upload one file at a time"));

            assertThat(documents.count()).isZero();
            verify(store, never()).save(any());
        }

        @Test
        @DisplayName("404s for a case that does not exist, leaving no orphaned blob")
        void notFoundForAMissingCase() throws Exception {
            authorised(multipart("/caseworker/cases/" + UNKNOWN_ID + "/documents")
                .file(pdf())
                .param("documentTypeId", newType()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("DeedPoll " + UNKNOWN_ID + " not found"));

            // Both checks happen before the store is touched, deliberately: a refused upload that had already
            // written its bytes would leave a blob nothing references and nothing will ever delete.
            verify(store, never()).save(any());
        }

        @Test
        @DisplayName("404s for a document type that does not exist, leaving no orphaned blob")
        void notFoundForAMissingType() throws Exception {
            authorised(multipart("/caseworker/cases/" + newCase() + "/documents")
                .file(pdf())
                .param("documentTypeId", UNKNOWN_ID))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Document type " + UNKNOWN_ID + " not found"));

            verify(store, never()).save(any());
        }

        @Test
        @DisplayName("415s a JSON body, because there is no metadata-only create on this route")
        void refusesAJsonBody() throws Exception {
            // Worth pinning: the reference has POST /documents as a metadata create and the upload on a
            // separate /upload path. This service exposes only the upload, at the collection URL, so a client
            // written against the reference gets an unambiguous 415 rather than a silent 400.
            authorised(post("/caseworker/cases/" + newCase() + "/documents")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"direction\":\"Incoming\",\"name\":\"x\"}"))
                .andExpect(status().isUnsupportedMediaType());
        }
    }

    @Nested
    @DisplayName("GET /caseworker/cases/{id}/documents/{documentId}/file")
    class Download {

        @Test
        @DisplayName("streams the stored bytes with the stored content type")
        void streamsTheStoredBytes() throws Exception {
            String caseId = newCase();
            String documentId = uploaded(caseId, "birth.pdf", "application/pdf");

            authorised(get(fileUrl(caseId, documentId)))
                .andExpect(status().isOk())
                .andExpect(content().contentType("application/pdf"))
                .andExpect(content().bytes(PDF_BYTES));
        }

        @ParameterizedTest(name = "{0} is served inline")
        @CsvSource({"application/pdf, a.pdf", "image/jpeg, a.jpg", "image/png, a.png"})
        @DisplayName("serves the renderable types inline, so a caseworker can look without downloading")
        void servesRenderableTypesInline(String mimeType, String filename) throws Exception {
            String caseId = newCase();
            String documentId = uploaded(caseId, filename, mimeType);

            authorised(get(fileUrl(caseId, documentId)))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                    "inline; filename=\"" + filename + "\""))
                .andExpect(header().stringValues("X-Content-Type-Options", "nosniff"))
                .andExpect(header().stringValues("Content-Security-Policy", "default-src 'none'; sandbox"))
                .andExpect(header().stringValues(HttpHeaders.CACHE_CONTROL, "no-store"));
        }

        @ParameterizedTest(name = "{0} is served as an attachment")
        @CsvSource({
            "image/tiff, a.tif",
            "application/msword, a.doc",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document, a.docx"
        })
        @DisplayName("serves everything else as an attachment, because nothing renders it")
        void servesEverythingElseAsAnAttachment(String mimeType, String filename) throws Exception {
            String caseId = newCase();
            String documentId = uploaded(caseId, filename, mimeType);

            authorised(get(fileUrl(caseId, documentId)))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                    "attachment; filename=\"" + filename + "\""))
                .andExpect(header().stringValues("X-Content-Type-Options", "nosniff"))
                .andExpect(header().stringValues("Content-Security-Policy", "default-src 'none'; sandbox"))
                .andExpect(header().stringValues(HttpHeaders.CACHE_CONTROL, "no-store"));
        }

        @Test
        @DisplayName("does not let an uploaded filename inject into the header")
        void sanitisesTheFilename() throws Exception {
            // originalName is whatever the uploading client called the file. A quote breaks out of the quoted
            // value; a newline makes the header invalid, which the server rejects by throwing mid-response --
            // a 500 on a download that was working.
            String caseId = newCase();
            String documentId = uploaded(caseId, "evil\".pdf\r\nX-Injected: yes", "application/pdf");

            authorised(get(fileUrl(caseId, documentId)))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                    "inline; filename=\"evil.pdfX-Injected yes\""))
                .andExpect(header().doesNotExist("X-Injected"));
        }

        @Test
        @DisplayName("falls back to a generic name when sanitising leaves nothing")
        void fallsBackToAGenericFilename() throws Exception {
            String caseId = newCase();
            String documentId = uploaded(caseId, "日本語", "application/pdf");

            authorised(get(fileUrl(caseId, documentId)))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"document\""));
        }

        @Test
        @DisplayName("defaults to octet-stream and an attachment when the row records no type")
        void defaultsWhenTheRowHasNoType() throws Exception {
            String caseId = newCase();
            String documentId = uploaded(caseId, "birth.pdf", "application/pdf");
            clearMimeType(caseId, documentId);

            authorised(get(fileUrl(caseId, documentId)))
                .andExpect(status().isOk())
                .andExpect(content().contentType("application/octet-stream"))
                // Not inline: nothing is known about what this is, so asking a browser to render it is the one
                // answer that could be actively unsafe.
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                    "attachment; filename=\"birth.pdf\""));
        }

        @Test
        @DisplayName("treats a stored type that is not a media type as no type at all")
        void defaultsWhenTheStoredTypeWillNotParse() throws Exception {
            // Not hypothetical: an upload's type is checked against the allowlist, but the metadata path asks
            // only that mimeType is text. Handing an unparseable value to a response header is how a header
            // injection gets in.
            String caseId = newCase();
            String documentId = uploaded(caseId, "birth.pdf", "application/pdf");
            setMimeType(caseId, documentId, "application/pdf\r\nX-Injected: yes");

            authorised(get(fileUrl(caseId, documentId)))
                .andExpect(status().isOk())
                .andExpect(content().contentType("application/octet-stream"))
                .andExpect(header().doesNotExist("X-Injected"));
        }

        @Test
        @DisplayName("tells browsers not to sniff past the declared type, exactly once")
        void setsNosniffOnce() throws Exception {
            // What this pins is the response, not the controller: Spring Security's default header writers add
            // this to every response as well, so deleting the controller's own line keeps the test green --
            // checked, rather than assumed. The controller sets it anyway, and the reasoning is on the method.
            // Asserted as a single value because two sources setting one header is how a proxy or a client ends
            // up choosing between them, and that part the mutation does fire on.
            String caseId = newCase();
            String documentId = uploaded(caseId, "birth.pdf", "application/pdf");

            authorised(get(fileUrl(caseId, documentId)))
                .andExpect(status().isOk())
                .andExpect(header().stringValues("X-Content-Type-Options", "nosniff"));
        }

        @Test
        @DisplayName("renders a served document under a policy that lets it load and run nothing")
        void setsAnInertContentSecurityPolicy() throws Exception {
            // What makes inline serving defensible rather than nearly defensible: PDF is on the inline list
            // and browser PDF viewers execute embedded JavaScript, so an attacker-supplied "certificate" would
            // otherwise run in this origin's context when a caseworker previewed it.
            String caseId = newCase();
            String documentId = uploaded(caseId, "birth.pdf", "application/pdf");

            authorised(get(fileUrl(caseId, documentId)))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Security-Policy", "default-src 'none'; sandbox"));
        }

        @Test
        @DisplayName("sends the same policy on a download, not only on an inline render")
        void setsTheContentSecurityPolicyOnAnAttachmentToo() throws Exception {
            String caseId = newCase();
            String documentId = uploaded(caseId, "scan.tiff", "image/tiff");

            authorised(get(fileUrl(caseId, documentId)))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Security-Policy", "default-src 'none'; sandbox"));
        }

        @Test
        @DisplayName("tells caches not to keep case data")
        void setsNoStore() throws Exception {
            String caseId = newCase();
            String documentId = uploaded(caseId, "birth.pdf", "application/pdf");

            authorised(get(fileUrl(caseId, documentId)))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"));
        }

        @Test
        @DisplayName("sends no Content-Length, because the recorded size is not a measured one")
        void sendsNoContentLength() throws Exception {
            // sizeBytes is what was recorded on upload; a row whose blob moved through the metadata path can
            // disagree with it, and a wrong length truncates the download or hangs it. Chunked instead.
            String caseId = newCase();
            String documentId = uploaded(caseId, "birth.pdf", "application/pdf");

            authorised(get(fileUrl(caseId, documentId)))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist(HttpHeaders.CONTENT_LENGTH));
        }

        @Test
        @DisplayName("404s a metadata row with no file attached, which is a legitimate state")
        void notFoundForARowWithNoFile() throws Exception {
            // The source form lets a caseworker record that a document is expected before it arrives, so "this
            // document has no file yet" is a truer answer than an empty body.
            String caseId = newCase();
            String documentId = documentService
                .createDocument(caseId, CreateDocumentInput.metadata(
                    DocumentDirection.Incoming, "Expected birth certificate", newType()))
                .getId();

            authorised(get(fileUrl(caseId, documentId)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("This document has no attached file"));
        }

        @Test
        @DisplayName("404s a document downloaded through another case, so the scope is both ids")
        void notFoundThroughAnotherCase() throws Exception {
            String caseId = newCase();
            String otherCaseId = newCase();
            String documentId = uploaded(caseId, "birth.pdf", "application/pdf");

            authorised(get(fileUrl(otherCaseId, documentId)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Document " + documentId + " not found"));
        }

        @Test
        @DisplayName("404s a document that does not exist")
        void notFoundForAMissingDocument() throws Exception {
            authorised(get(fileUrl(newCase(), UNKNOWN_ID)))
                .andExpect(status().isNotFound());
        }
    }

    private static String fileUrl(String caseId, String documentId) {
        return "/caseworker/cases/" + caseId + "/documents/" + documentId + "/file";
    }

    private static MockMultipartFile pdf() {
        return new MockMultipartFile("file", "birth.pdf", "application/pdf", PDF_BYTES);
    }

    /** A stored case with a Date Received, which is all reference-number allocation needs. */
    private String newCase() {
        return deedPollService.createDeedPoll(DeedPollPatch.of(Map.of("dateReceived", RECEIVED)))
            .deedPoll()
            .getId();
    }

    /** A stored document type, since every document needs one. */
    private String newType() {
        return documentTypes.createDocumentType(CreateDocumentTypeInput.named("Birth certificate")).getId();
    }

    /**
     * A document uploaded through the domain, with a real blob behind it.
     *
     * <p>Through the service rather than over HTTP, so a download test does not depend on the upload test
     * passing -- and so a filename the transport would not accept (a newline, non-ASCII) can be stored, which is
     * exactly what the header-sanitising tests are about.
     */
    private String uploaded(String caseId, String filename, String mimeType) {
        return documentService
            .uploadDocument(caseId, newType(), UploadedFile.of(PDF_BYTES, filename, mimeType),
                Optional.empty(), Optional.empty())
            .getId();
    }

    private void setMimeType(String caseId, String documentId, String mimeType) {
        documentService.updateDocument(caseId, documentId, new UpdateDocumentInput(
            PatchField.absent(), PatchField.absent(), PatchField.absent(), PatchField.absent(),
            PatchField.absent(), PatchField.of(mimeType)));
    }

    private void clearMimeType(String caseId, String documentId) {
        documentService.updateDocument(caseId, documentId, new UpdateDocumentInput(
            PatchField.absent(), PatchField.absent(), PatchField.absent(), PatchField.absent(),
            PatchField.absent(), PatchField.cleared()));
    }

    private DeedPollSupportingDocument onlyStoredDocument() {
        assertThat(documents.findAll()).hasSize(1);
        return documents.findAll().get(0);
    }

    /** The bytes actually on disk under a row's storage key. */
    private byte[] bytesInStore(String storageKey) throws IOException {
        try (InputStream blob = store.open(storageKey)) {
            return blob.readAllBytes();
        }
    }

    /** The request, carrying a valid forwarded access token. */
    private ResultActions authorised(MockHttpServletRequestBuilder request) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(IDAM.accessToken());
        return mockMvc.perform(request.headers(headers));
    }

    /** The multipart request, carrying a valid forwarded access token. */
    private ResultActions authorised(MockMultipartHttpServletRequestBuilder request) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(IDAM.accessToken());
        return mockMvc.perform(request.headers(headers));
    }
}
