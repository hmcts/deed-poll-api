package uk.gov.hmcts.reform.deedpoll.storage;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.read.ListAppender;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;
import uk.gov.hmcts.ccd.sdk.CaseViewRequest;
import uk.gov.hmcts.reform.deedpoll.auth.StubIdam;
import uk.gov.hmcts.reform.deedpoll.auth.StubS2s;
import uk.gov.hmcts.reform.deedpoll.ccd.CaseDocumentsEvent;
import uk.gov.hmcts.reform.deedpoll.ccd.DeedPollCaseData;
import uk.gov.hmcts.reform.deedpoll.ccd.DeedPollCaseState;
import uk.gov.hmcts.reform.deedpoll.ccd.DeedPollCaseView;
import uk.gov.hmcts.reform.deedpoll.config.AbstractPostgresContainerTest;
import uk.gov.hmcts.reform.deedpoll.domain.deedpoll.DeedPollPatch;
import uk.gov.hmcts.reform.deedpoll.domain.deedpoll.DeedPollService;
import uk.gov.hmcts.reform.deedpoll.domain.documents.CreateDocumentTypeInput;
import uk.gov.hmcts.reform.deedpoll.domain.documents.CreateDocumentInput;
import uk.gov.hmcts.reform.deedpoll.domain.documents.DocumentService;
import uk.gov.hmcts.reform.deedpoll.domain.documents.DocumentStore;
import uk.gov.hmcts.reform.deedpoll.domain.documents.DocumentTypeService;
import uk.gov.hmcts.reform.deedpoll.entity.DeedPollSupportingDocument;
import uk.gov.hmcts.reform.deedpoll.entity.enums.DocumentStorageState;
import uk.gov.hmcts.reform.deedpoll.entity.enums.DocumentDirection;
import uk.gov.hmcts.reform.deedpoll.repository.DeedPollRepository;
import uk.gov.hmcts.reform.deedpoll.repository.DeedPollSupportingDocumentRepository;
import uk.gov.hmcts.reform.deedpoll.repository.DeedPollSupportingDocumentTypeRepository;
import uk.gov.hmcts.reform.deedpoll.repository.ReferenceCounterRepository;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.delete;
import static com.github.tomakehurst.wiremock.client.WireMock.deleteRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.notContaining;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Plan 03a, Task 4: the CDAM store switched on, over HTTP, against WireMock for CDAM and CCD data-store and a real
 * database for the rows.
 *
 * <p>Each scenario the plan names: the happy path end to end (upload, attach by event, download as the caller, soft
 * delete); each lost answer landing in its uncertain state; one lost upload then a good retry; two lost uploads and
 * never a third; a record not ready sending nothing; another record's document refused before CDAM is called; and
 * {@code permanent=true} never sent. Every scenario ends by checking that the hash token appears in no log line --
 * formatted message or exception -- that this service wrote.
 *
 * <p>A lost CDAM answer is a delay past a short read timeout, not a dropped connection: OkHttp, which the CDAM client
 * uses, silently retries some connection failures on its own, and that would count as an upload this service never
 * decided to send. {@link #resetUploadIsNotRepeatedByTheClient} pins what it does with a reset. The decisions
 * themselves are pinned container-free in {@code CdamDocumentUploadsTest} and {@code CdamDocumentStoreTest}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CdamDocumentScenariosTest extends AbstractPostgresContainerTest {

    private static final String HASH = "hash-token-3f1e-never-to-be-logged";
    private static final UUID FIRST = UUID.fromString("0b9fb4a8-b0c7-4c52-9f7f-5dc1a6b2d0e1");
    private static final UUID RETRY = UUID.fromString("5f0c2a7e-3a1b-4d2e-8c9f-0a1b2c3d4e5f");
    private static final String RECEIVED = "2026-07-01T00:00:00.000Z";
    private static final byte[] PDF_BYTES = "%PDF-1.4 fake".getBytes(StandardCharsets.UTF_8);
    private static final int READ_TIMEOUT_MS = 750;
    private static final int LOST_MS = 2_000;
    private static final AtomicLong REFERENCES = new AtomicLong(1_700_000_000_000_000L);

    private static final StubIdam IDAM = new StubIdam();
    private static final StubS2s S2S = new StubS2s();
    private static final WireMockServer CDAM = new WireMockServer(options().dynamicPort());
    private static final WireMockServer DATA_STORE = new WireMockServer(options().dynamicPort());
    private static final String LEASED;

    static {
        IDAM.start();
        S2S.start();
        CDAM.start();
        DATA_STORE.start();
        LEASED = IDAM.accessToken();
    }

    @AfterAll
    static void stopStubs() {
        IDAM.stop();
        S2S.stop();
        CDAM.stop();
        DATA_STORE.stop();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("idam.api.issuer-url", IDAM::issuerUrl);
        registry.add("idam.api.audiences", () -> StubIdam.AUDIENCE);
        registry.add("idam.api.url", IDAM::baseUrl);
        registry.add("idam.s2s-auth.url", S2S::baseUrl);
        registry.add("idam.s2s-auth.totp-secret", () -> "AAAAAAAAAAAAAAAA");
        registry.add("idam.client.id", () -> "deed-poll-test-client");
        registry.add("idam.client.secret", () -> "not-a-real-secret");
        registry.add("idam.client.redirect_uri", () -> "http://localhost/oauth2/callback");
        registry.add("idam.system-user.username", () -> "deed-poll-system-user");
        registry.add("idam.system-user.password", () -> "not-a-real-password");
        registry.add("core_case_data.api.url", DATA_STORE::baseUrl);
        registry.add("case_document_am.url", CDAM::baseUrl);
        registry.add("deedpoll.documents.store", () -> "cdam");
        registry.add("spring.cloud.openfeign.client.config.case-document-am-client-api.read-timeout",
            () -> String.valueOf(READ_TIMEOUT_MS));
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

    @Autowired
    private DocumentStore store;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private DeedPollCaseView caseView;

    private ListAppender<ILoggingEvent> logs;

    @BeforeEach
    void setUp() {
        clearTables();
        IDAM.stubDefaults();
        IDAM.stubPasswordGrant();
        S2S.reset();
        S2S.lease(LEASED);
        CDAM.resetAll();
        DATA_STORE.resetAll();
        DATA_STORE.stubFor(get(urlPathMatching("/cases/[0-9]+/event-triggers/" + CaseDocumentsEvent.EVENT_ID))
            .willReturn(okJson("{\"token\": \"event-token\"}")));
        DATA_STORE.stubFor(post(urlPathMatching("/cases/[0-9]+/events")).willReturn(okJson("{}")));
        CDAM.stubFor(get(urlPathMatching("/cases/documents/.+/binary"))
            .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/pdf").withBody(PDF_BYTES)));
        CDAM.stubFor(delete(urlPathMatching("/cases/documents/.+")).willReturn(aResponse().withStatus(204)));

        logs = new ListAppender<>();
        logs.start();
        root().addAppender(logs);
    }

    @AfterEach
    void tearDown() {
        root().detachAppender(logs);
        // Every scenario, whatever it exercised: the token went to CDAM and to data-store, and nowhere else.
        for (ILoggingEvent event : logs.list) {
            assertThat(event.getFormattedMessage()).doesNotContain(HASH);
            for (IThrowableProxy thrown = event.getThrowableProxy(); thrown != null; thrown = thrown.getCause()) {
                assertThat(String.valueOf(thrown.getMessage())).doesNotContain(HASH);
            }
        }
        clearTables();
    }

    private static Logger root() {
        return (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    }

    private void clearTables() {
        documents.deleteAll();
        types.deleteAll();
        deedPolls.deleteAll();
        referenceCounters.deleteAll();
    }

    @Test
    @DisplayName("the store switched on is the CDAM store")
    void theSwitchSelectsCdam() {
        assertThat(store).isInstanceOf(CdamDocumentStore.class);
    }

    @Test
    @DisplayName("happy path: uploaded as the system user, attached by event with its hash, streamed as the caller, "
        + "soft-deleted")
    void happyPath() throws Exception {
        String record = linkedRecord();
        uploadAnswers(FIRST);

        String body = upload(record)
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.hasFile").value(true))
            .andExpect(jsonPath("$.originalName").value("birth.pdf"))
            .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain(HASH).doesNotContain(FIRST.toString());
        DeedPollSupportingDocument row = onlyRow();
        assertThat(row.getStorageState()).isEqualTo(DocumentStorageState.Attached);
        assertThat(row.getCdamDocumentId()).isEqualTo(FIRST);
        assertThat(row.getCdamHashToken()).isNull();
        CDAM.verify(1, postRequestedFor(urlPathEqualTo("/cases/documents"))
            .withHeader("Authorization", matching("Bearer .+"))
            .withHeader("ServiceAuthorization", containing(LEASED))
            .withRequestBody(containing("RESTRICTED")));
        String reference = deedPolls.findById(record).orElseThrow().getCcdCaseReference();
        DATA_STORE.verify(1, postRequestedFor(urlPathEqualTo("/cases/" + reference + "/events"))
            .withRequestBody(containing("\"document_hash\":\"" + HASH + "\""))
            .withRequestBody(containing("/documents/" + FIRST))
            .withRequestBody(containing(CaseDocumentsEvent.FIELD))
            .withRequestBody(notContaining("attachToCase")));
        CDAM.verify(0, anyRequestedFor(urlPathMatching("/cases/documents/attachToCase.*")));

        String caller = IDAM.accessToken(claims -> claims.claim("caller", "the caseworker"));
        byte[] downloaded = mockMvc.perform(download(fileOf(record, row.getId()), caller))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsByteArray();
        assertThat(downloaded).isEqualTo(PDF_BYTES);
        CDAM.verify(1, getRequestedFor(urlPathEqualTo("/cases/documents/" + FIRST + "/binary"))
            .withHeader("Authorization", equalTo("Bearer " + caller)));

        documentService.removeDocument(record, row.getId());

        assertThat(documents.findAll()).isEmpty();
        CDAM.verify(1, deleteRequestedFor(urlPathEqualTo("/cases/documents/" + FIRST))
            .withQueryParam("permanent", equalTo("false")));
        CDAM.verify(0, deleteRequestedFor(urlPathMatching("/cases/documents/.*"))
            .withQueryParam("permanent", equalTo("true")));
    }

    @ParameterizedTest(name = "CDAM serves {0} as {2} with inert, non-cacheable headers")
    @CsvSource({
        "application/pdf, a.pdf, inline",
        "image/jpeg, a.jpg, inline",
        "image/png, a.png, inline",
        "image/tiff, a.tif, attachment",
        "application/msword, a.doc, attachment",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document, a.docx, attachment"
    })
    void downloadSecurityHeaders(String mimeType, String filename, String disposition) throws Exception {
        String href = CDAM.baseUrl() + "/cases/documents/" + FIRST;
        CDAM.stubFor(post(urlPathEqualTo("/cases/documents")).willReturn(okJson("""
            {"documents": [{
              "originalDocumentName": "%s", "mimeType": "%s", "hashToken": "%s",
              "_links": {"self": {"href": "%s"}, "binary": {"href": "%s/binary"}}
            }]}
            """.formatted(filename, mimeType, HASH, href, href))));
        // These tests exercise transport, not content verification. Distinct bytes prove the remote read.
        byte[] bytes = ("stored fixture for " + mimeType).getBytes(StandardCharsets.UTF_8);
        CDAM.stubFor(get(urlPathEqualTo("/cases/documents/" + FIRST + "/binary"))
            .willReturn(aResponse().withStatus(200).withBody(bytes)
                // Upstream headers must not replace the API's own document response policy.
                .withHeader("Content-Type", "text/html")
                .withHeader("Content-Disposition", "inline; filename=\"upstream.html\"")
                .withHeader("X-Content-Type-Options", "invalid")
                .withHeader("Content-Security-Policy", "default-src *")
                .withHeader("Cache-Control", "public, max-age=3600")));
        String record = linkedRecord();
        HttpHeaders headers = new HttpHeaders();
        String caller = IDAM.accessToken();
        headers.setBearerAuth(caller);
        mockMvc.perform(multipart("/caseworker/cases/" + record + "/documents")
                .file(new MockMultipartFile("file", filename, mimeType, bytes))
                .param("documentTypeId", newType()).headers(headers))
            .andExpect(status().isCreated());
        DeedPollSupportingDocument row = onlyRow();
        assertThat(row.getStorageState()).isEqualTo(DocumentStorageState.Attached);

        mockMvc.perform(download(fileOf(record, row.getId()), caller))
            .andExpect(status().isOk())
            .andExpect(content().bytes(bytes))
            .andExpect(content().contentType(mimeType))
            .andExpect(header().stringValues(HttpHeaders.CONTENT_DISPOSITION,
                disposition + "; filename=\"" + filename + "\""))
            .andExpect(header().stringValues("X-Content-Type-Options", "nosniff"))
            .andExpect(header().stringValues("Content-Security-Policy", "default-src 'none'; sandbox"))
            .andExpect(header().stringValues(HttpHeaders.CACHE_CONTROL, "no-store"));
        CDAM.verify(1, getRequestedFor(urlPathEqualTo("/cases/documents/" + FIRST + "/binary"))
            .withHeader("Authorization", equalTo("Bearer " + caller)));
    }

    @Test
    @DisplayName("a record with no CCD case is not ready: 409 in the published words, and nothing sent or stored")
    void caseNotReady() throws Exception {
        String record = newRecord();

        upload(record)
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error").value(DocumentStorageRule.CASE_NOT_READY));

        assertThat(documents.findAll()).isEmpty();
        assertThat(CDAM.getAllServeEvents()).isEmpty();
        assertThat(DATA_STORE.getAllServeEvents()).isEmpty();
    }

    @Test
    @DisplayName("a refused upload is Failed, a 502, and nothing is attached")
    void refusedUpload() throws Exception {
        String record = linkedRecord();
        CDAM.stubFor(post(urlPathEqualTo("/cases/documents"))
            .willReturn(aResponse().withStatus(400).withBody("{\"hashToken\": \"" + HASH + "\"}")));

        upload(record).andExpect(status().isBadGateway());

        assertThat(onlyRow().getStorageState()).isEqualTo(DocumentStorageState.Failed);
        CDAM.verify(1, postRequestedFor(urlPathEqualTo("/cases/documents")));
        assertThat(DATA_STORE.getAllServeEvents()).isEmpty();
    }

    @Test
    @DisplayName("one lost upload then a good one ends on the retry's id, from exactly two uploads")
    void oneLostUploadThenARetry() throws Exception {
        String record = linkedRecord();
        CDAM.stubFor(post(urlPathEqualTo("/cases/documents")).inScenario("upload")
            .whenScenarioStateIs(Scenario.STARTED).willSetStateTo("second")
            .willReturn(uploadAnswer(FIRST).withFixedDelay(LOST_MS)));
        CDAM.stubFor(post(urlPathEqualTo("/cases/documents")).inScenario("upload")
            .whenScenarioStateIs("second")
            .willReturn(uploadAnswer(RETRY)));

        upload(record).andExpect(status().isCreated());

        DeedPollSupportingDocument row = onlyRow();
        assertThat(row.getStorageState()).isEqualTo(DocumentStorageState.Attached);
        assertThat(row.getCdamDocumentId()).isEqualTo(RETRY);
        assertThat(row.getStorageUploadAttempts()).isEqualTo((short) 2);
        CDAM.verify(2, postRequestedFor(urlPathEqualTo("/cases/documents")));
        DATA_STORE.verify(1, postRequestedFor(urlPathMatching("/cases/[0-9]+/events"))
            .withRequestBody(containing("/documents/" + RETRY)));
    }

    @Test
    @DisplayName("two lost uploads stay UploadUncertain, after exactly two uploads and never a third")
    void twoLostUploads() throws Exception {
        String record = linkedRecord();
        CDAM.stubFor(post(urlPathEqualTo("/cases/documents")).willReturn(uploadAnswer(FIRST).withFixedDelay(LOST_MS)));

        upload(record)
            .andExpect(status().isBadGateway())
            .andExpect(jsonPath("$.error").value(CdamDocumentUploads.UPLOAD_LOST));

        DeedPollSupportingDocument row = onlyRow();
        assertThat(row.getStorageState()).isEqualTo(DocumentStorageState.UploadUncertain);
        assertThat(row.getCdamHashToken()).isNull();
        CDAM.verify(2, postRequestedFor(urlPathEqualTo("/cases/documents")));
        assertThat(DATA_STORE.getAllServeEvents()).isEmpty();

        // And the row is not served: an uncertain upload has no file this service will hand out.
        mockMvc.perform(download(fileOf(record, row.getId()), IDAM.accessToken()))
            .andExpect(status().isNotFound());
        CDAM.verify(0, getRequestedFor(urlPathMatching("/cases/documents/.+/binary")));
    }

    @Test
    @DisplayName("a reset upload counts once: the client does not send it again behind this service's back")
    void resetUploadIsNotRepeatedByTheClient() throws Exception {
        String record = linkedRecord();
        CDAM.stubFor(post(urlPathEqualTo("/cases/documents"))
            .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));

        upload(record).andExpect(status().isBadGateway());

        assertThat(onlyRow().getStorageState()).isEqualTo(DocumentStorageState.UploadUncertain);
        CDAM.verify(2, postRequestedFor(urlPathEqualTo("/cases/documents")));
    }

    @Test
    @DisplayName("a lost attach is AttachUncertain, and its one retry attaches it")
    void lostAttachThenAttached() throws Exception {
        uploadAnswers(FIRST);
        DATA_STORE.stubFor(post(urlPathMatching("/cases/[0-9]+/events")).inScenario("attach")
            .whenScenarioStateIs(Scenario.STARTED).willSetStateTo("second")
            .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));
        DATA_STORE.stubFor(post(urlPathMatching("/cases/[0-9]+/events")).inScenario("attach")
            .whenScenarioStateIs("second").willReturn(okJson("{}")));

        String record = linkedRecord();
        upload(record).andExpect(status().isCreated());

        DeedPollSupportingDocument row = onlyRow();
        assertThat(row.getStorageState()).isEqualTo(DocumentStorageState.Attached);
        assertThat(row.getCdamHashToken()).isNull();
        DATA_STORE.verify(2, postRequestedFor(urlPathMatching("/cases/[0-9]+/events"))
            .withRequestBody(containing(HASH)));
        CDAM.verify(1, postRequestedFor(urlPathEqualTo("/cases/documents")));
    }

    @Test
    @DisplayName("two lost attaches stay AttachUncertain, keeping the token for the retry and serving nothing")
    void twoLostAttaches() throws Exception {
        String record = linkedRecord();
        uploadAnswers(FIRST);
        DATA_STORE.stubFor(post(urlPathMatching("/cases/[0-9]+/events"))
            .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));

        upload(record)
            .andExpect(status().isBadGateway())
            .andExpect(jsonPath("$.error").value(CdamDocumentUploads.ATTACH_LOST));

        DeedPollSupportingDocument row = onlyRow();
        assertThat(row.getStorageState()).isEqualTo(DocumentStorageState.AttachUncertain);
        assertThat(row.getCdamHashToken()).isEqualTo(HASH);
        assertThat(row.getCdamDocumentId()).isEqualTo(FIRST);
    }

    @Test
    @DisplayName("an attach data-store refuses is Failed, and the token goes")
    void refusedAttach() throws Exception {
        String record = linkedRecord();
        uploadAnswers(FIRST);
        DATA_STORE.stubFor(post(urlPathMatching("/cases/[0-9]+/events"))
            .willReturn(aResponse().withStatus(422).withBody("{\"message\": \"" + HASH + "\"}")));

        upload(record)
            .andExpect(status().isBadGateway())
            .andExpect(jsonPath("$.error").value(CdamDocumentUploads.ATTACH_REFUSED));

        DeedPollSupportingDocument row = onlyRow();
        assertThat(row.getStorageState()).isEqualTo(DocumentStorageState.Failed);
        assertThat(row.getCdamHashToken()).isNull();
        assertThat(row.getStorageKey()).isNull();

        documentService.removeDocument(record, row.getId());

        assertThat(documents.findAll()).isEmpty();
        CDAM.verify(1, deleteRequestedFor(urlPathEqualTo("/cases/documents/" + FIRST))
            .withQueryParam("permanent", equalTo("false")));
    }

    @Test
    @DisplayName("deleting a Local metadata row never treats its supplied UUID key as a CDAM document")
    void localStorageKeyCannotDeleteCdamDocument() {
        String record = linkedRecord();
        var row = documentService.createDocument(record, new CreateDocumentInput(
            DocumentDirection.Incoming, "Legacy document", newType(), FIRST.toString(),
            "legacy.pdf", "application/pdf"));

        documentService.removeDocument(record, row.getId());

        assertThat(documents.findById(row.getId())).isEmpty();
        assertThat(CDAM.getAllServeEvents()).isEmpty();
        assertThat(DATA_STORE.getAllServeEvents()).isEmpty();
    }

    @Test
    @DisplayName("another record's document is a 404 through this record's URL, and CDAM is never asked")
    void anotherRecordsDocument() throws Exception {
        String owner = linkedRecord();
        String other = linkedRecord();
        uploadAnswers(FIRST);
        upload(owner).andExpect(status().isCreated());
        String documentId = onlyRow().getId();

        mockMvc.perform(download(fileOf(other, documentId), IDAM.accessToken()))
            .andExpect(status().isNotFound());

        CDAM.verify(0, getRequestedFor(urlPathMatching("/cases/documents/.+/binary")));
    }

    @Test
    @DisplayName("a soft delete CDAM refuses still deletes the row: best effort, after the commit")
    void deleteIsBestEffort() throws Exception {
        String record = linkedRecord();
        uploadAnswers(FIRST);
        upload(record).andExpect(status().isCreated());
        String documentId = onlyRow().getId();
        CDAM.stubFor(delete(urlPathMatching("/cases/documents/.+")).willReturn(aResponse().withStatus(500)));

        documentService.removeDocument(record, documentId);

        assertThat(documents.findAll()).isEmpty();
        CDAM.verify(1, deleteRequestedFor(urlPathEqualTo("/cases/documents/" + FIRST))
            .withQueryParam("permanent", equalTo("false")));
        assertThat(logs.list).anySatisfy(event -> assertThat(event.getFormattedMessage())
            .contains(documentId).contains(FIRST.toString()));
    }

    @Test
    @DisplayName("a delete takes the document off the case by event, with what the case keeps, before the soft delete")
    void deleteSendsTheEvent() throws Exception {
        String record = linkedRecord();
        uploadAnswers(FIRST);
        upload(record).andExpect(status().isCreated());
        uploadAnswers(RETRY);
        upload(record).andExpect(status().isCreated());
        String first = documents.findAll().stream().filter(d -> FIRST.equals(d.getCdamDocumentId())).findFirst()
            .orElseThrow().getId();
        String reference = deedPolls.findById(record).orElseThrow().getCcdCaseReference();
        DATA_STORE.resetRequests();

        documentService.removeDocument(record, first);

        DATA_STORE.verify(1, postRequestedFor(urlPathEqualTo("/cases/" + reference + "/events"))
            .withRequestBody(containing("/documents/" + RETRY))
            .withRequestBody(notContaining(FIRST.toString()))
            .withRequestBody(notContaining("document_hash")));
        CDAM.verify(1, deleteRequestedFor(urlPathEqualTo("/cases/documents/" + FIRST))
            .withQueryParam("permanent", equalTo("false")));
        assertThat(documents.findAll()).extracting(DeedPollSupportingDocument::getCdamDocumentId)
            .containsExactly(RETRY);
    }

    @Test
    @DisplayName("a delete whose event data-store refuses still deletes the row and soft-deletes the file")
    void deleteEventIsBestEffort() throws Exception {
        String record = linkedRecord();
        uploadAnswers(FIRST);
        upload(record).andExpect(status().isCreated());
        String documentId = onlyRow().getId();
        DATA_STORE.stubFor(post(urlPathMatching("/cases/[0-9]+/events"))
            .willReturn(aResponse().withStatus(422).withBody("{\"message\": \"" + HASH + "\"}")));

        documentService.removeDocument(record, documentId);

        assertThat(documents.findAll()).isEmpty();
        CDAM.verify(1, deleteRequestedFor(urlPathEqualTo("/cases/documents/" + FIRST))
            .withQueryParam("permanent", equalTo("false")));
        assertThat(logs.list).anySatisfy(event -> assertThat(event.getFormattedMessage())
            .contains(documentId).contains("could not take it off its CCD case"));
    }

    @Test
    @DisplayName("the case view, over real rows, carries the attached document and no Failed or uncertain one")
    void theViewCarriesOnlyAttachedRows() throws Exception {
        String record = linkedRecord();
        uploadAnswers(FIRST);
        upload(record).andExpect(status().isCreated());
        CDAM.stubFor(post(urlPathEqualTo("/cases/documents")).willReturn(aResponse().withStatus(400)));
        upload(record).andExpect(status().isBadGateway());
        uploadAnswers(RETRY);
        DATA_STORE.stubFor(post(urlPathMatching("/cases/[0-9]+/events"))
            .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));
        upload(record).andExpect(status().isBadGateway());
        assertThat(documents.findAll()).extracting(DeedPollSupportingDocument::getStorageState)
            .containsExactlyInAnyOrder(DocumentStorageState.Attached, DocumentStorageState.Failed,
                DocumentStorageState.AttachUncertain);
        long reference = Long.parseLong(deedPolls.findById(record).orElseThrow().getCcdCaseReference());

        DeedPollCaseData data = caseView.getCase(new CaseViewRequest<>(reference, DeedPollCaseState.Registered));

        assertThat(data.getSupportingDocuments()).singleElement().satisfies(item -> {
            assertThat(item.getValue().getUrl()).endsWith("/documents/" + FIRST);
            assertThat(item.getValue().getHashToken()).isNull();
        });
        assertThat(String.valueOf(data.getSupportingDocuments())).doesNotContain(HASH).doesNotContain(RETRY.toString());
    }

    private void uploadAnswers(UUID id) {
        CDAM.stubFor(post(urlPathEqualTo("/cases/documents")).willReturn(uploadAnswer(id)));
    }

    private static com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder uploadAnswer(UUID id) {
        String href = CDAM.baseUrl() + "/cases/documents/" + id;
        return okJson("""
            {"documents": [{
              "originalDocumentName": "birth.pdf",
              "mimeType": "application/pdf",
              "hashToken": "%s",
              "_links": {"self": {"href": "%s"}, "binary": {"href": "%s/binary"}}
            }]}
            """.formatted(HASH, href, href));
    }

    private String newRecord() {
        return deedPollService.createDeedPoll(DeedPollPatch.of(Map.of("dateReceived", RECEIVED))).deedPoll().getId();
    }

    /** A record with a CCD case, bound as the linking operation binds one. */
    private String linkedRecord() {
        String record = newRecord();
        String reference = String.valueOf(REFERENCES.incrementAndGet());
        transactions.executeWithoutResult(status -> deedPolls.bindCcdCaseReference(record, reference));
        return record;
    }

    private String newType() {
        return documentTypes.createDocumentType(CreateDocumentTypeInput.named("Birth certificate " + UUID.randomUUID()))
            .getId();
    }

    private DeedPollSupportingDocument onlyRow() {
        List<DeedPollSupportingDocument> rows = documents.findAll();
        assertThat(rows).hasSize(1);
        return rows.get(0);
    }

    private ResultActions upload(String record) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(IDAM.accessToken());
        return mockMvc.perform(multipart("/caseworker/cases/" + record + "/documents")
            .file(new MockMultipartFile("file", "birth.pdf", "application/pdf", PDF_BYTES))
            .param("documentTypeId", newType())
            .headers(headers));
    }

    private static String fileOf(String record, String documentId) {
        return "/caseworker/cases/" + record + "/documents/" + documentId + "/file";
    }

    private static MockHttpServletRequestBuilder download(String path, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path).headers(headers);
    }
}
