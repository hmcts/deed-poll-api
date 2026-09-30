package uk.gov.hmcts.reform.deedpoll.cftlib;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import uk.gov.hmcts.reform.authorisation.generators.AuthTokenGenerator;
import uk.gov.hmcts.reform.ccd.client.CoreCaseDataApi;
import uk.gov.hmcts.reform.deedpoll.ccd.CcdCreationAttempts;
import uk.gov.hmcts.reform.deedpoll.ccd.CcdLinkingSweep;
import uk.gov.hmcts.reform.deedpoll.ccd.SystemUserTokens;
import uk.gov.hmcts.reform.deedpoll.domain.documents.DocumentService;
import uk.gov.hmcts.reform.deedpoll.entity.DeedPollSupportingDocumentType;
import uk.gov.hmcts.reform.deedpoll.entity.enums.DocumentStorageState;
import uk.gov.hmcts.reform.deedpoll.repository.DeedPollRepository;
import uk.gov.hmcts.reform.deedpoll.repository.DeedPollSupportingDocumentRepository;
import uk.gov.hmcts.reform.deedpoll.repository.DeedPollSupportingDocumentTypeRepository;
import uk.gov.hmcts.reform.deedpoll.support.StubDmStore;
import uk.gov.hmcts.reform.idam.client.IdamClient;
import uk.gov.hmcts.rse.ccd.lib.test.CftlibTest;
import uk.gov.hmcts.rse.ccd.lib.ControlPlane;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static com.github.tomakehurst.wiremock.client.WireMock.deleteRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

/** Real CCD and CDAM, only dm-store stubbed. The first two ordered tests deliberately replace the API context. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
@ActiveProfiles({"cftlib", "development"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class DocumentCdamCftlibTest extends CftlibTest {

    private static final String CONTENT = "%PDF-1.4\n% Synthetic supporting document fixture\n%%EOF\n";
    private static final StubDmStore DM = new StubDmStore(4506);
    private final HttpClient http = HttpClient.newHttpClient();

    @Autowired
    private IdamClient idam;
    @Autowired
    private ObjectMapper json;
    @Autowired
    private DeedPollRepository cases;
    @Autowired
    private DeedPollSupportingDocumentRepository documents;
    @Autowired
    private DeedPollSupportingDocumentTypeRepository types;
    @Autowired
    private DocumentService documentService;
    @Autowired
    private CcdCreationAttempts attempts;
    @Autowired
    private CcdLinkingSweep sweep;
    @Autowired
    private CoreCaseDataApi ccd;
    @Autowired
    private SystemUserTokens systemUser;
    @Autowired
    private AuthTokenGenerator serviceTokens;
    @Autowired
    private ApplicationContext context;
    @Autowired
    private JdbcTemplate jdbc;

    private ApplicationContext firstContext;
    private String recordId;
    private String documentId;
    private String remoteId;
    private String reference;

    @AfterAll
    void stopDmStore() {
        DM.close();
    }

    @Test
    @Order(1)
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
    void uploadsAttachesAndDownloadsThroughRealCdamAndCcd() throws Exception {
        for (int run = 0; run < 20 && !attempts.dueForLinking(1).isEmpty(); run++) {
            sweep.sweep();
        }
        assertThat(attempts.dueForLinking(1)).isEmpty();
        recordId = createRecord();
        assertThat(cases.findCcdCaseReference(recordId)).isEmpty();
        assertThat(send("/caseworker/cases", "GET", "", "application/json", CftlibConfig.DOCUMENT_READER)
            .statusCode()).isEqualTo(200);
        reference = cases.findCcdCaseReference(recordId).orElseThrow();

        long started = System.nanoTime();
        HttpResponse<String> upload = upload(recordId);
        final long elapsed = Duration.ofNanos(System.nanoTime() - started).toMillis();
        assertThat(upload.statusCode()).as(upload.body()).isEqualTo(201);
        documentId = json.readTree(upload.body()).path("id").asText();
        var row = documents.findById(documentId).orElseThrow();
        remoteId = row.getCdamDocumentId().toString();
        assertThat(row.getStorageState()).isEqualTo(DocumentStorageState.Attached);
        assertThat(row.getCdamHashToken()).isNull();
        assertThat(upload.body()).doesNotContain(remoteId, "hashToken", "document_hash");
        assertDownload();
        assertThat(ccdDocuments().toString()).contains(remoteId).doesNotContain("document_hash");
        assertThat(latestSnapshot()).contains(remoteId).doesNotContain("document_hash");
        JsonNode metadata = json.readTree(http.send(HttpRequest.newBuilder(
            URI.create(DM.server().baseUrl() + "/documents/" + remoteId))
            .header("ServiceAuthorization", "fixture-inspection").GET().build(),
            HttpResponse.BodyHandlers.ofString()).body());
        assertThat(metadata.path("metadata").path("case_id").asText()).isEqualTo(reference);
        assertThat(metadata.path("ttl").isNull()).isTrue();
        firstContext = context;
        System.out.printf("cftlib: CDAM upload and CCD attach took %d ms%n", elapsed);
    }

    @Test
    @Order(2)
    void downloadsTheSameBytesAfterTheApiContextRestarts() throws Exception {
        assertThat(context).isNotSameAs(firstContext);
        DM.server().resetRequests();
        assertDownload();
        DM.server().verify(1, getRequestedFor(urlPathEqualTo("/documents/" + remoteId + "/binary")));
    }

    @Test
    @Order(3)
    void cdamRefusesACaseworkerWithoutCcdCaseTypeReadPermission() throws Exception {
        Path definition = Path.of("build/cftlib/definitions/DEED_POLL_PLACEHOLDER");
        Path grants = definition.resolve("AuthorisationCaseType.json");
        String original = Files.readString(grants);
        JsonNode withoutRead = json.readTree(original);
        for (JsonNode grant : withoutRead) {
            ((ObjectNode) grant).put("CRUD", grant.path("CRUD").asText().replace("R", ""));
        }
        try {
            Files.writeString(grants, withoutRead.toString());
            ControlPlane.getApi().importJsonDefinition(definition.toFile());
            // Data-store's latest-definition cache is set to one second in this local task only.
            Thread.sleep(1200);
            DM.server().resetRequests();
            HttpResponse<String> denied = send(filePath(recordId), "GET", "", "application/json",
                CftlibConfig.DOCUMENT_READER);
            assertThat(denied.statusCode()).as(denied.body()).isIn(403, 404);
            DM.server().verify(getRequestedFor(urlPathEqualTo("/documents/" + remoteId)));
            DM.server().verify(0, getRequestedFor(urlPathEqualTo("/documents/" + remoteId + "/binary")));
        } finally {
            Files.writeString(grants, original);
            ControlPlane.getApi().importJsonDefinition(definition.toFile());
            Thread.sleep(1200);
        }
        assertDownload();
    }

    @Test
    @Order(4)
    void anUnmappedUserIsDeniedButTheUpstreamStackReportsBadGateway() throws Exception {
        DM.server().resetRequests();
        HttpResponse<String> denied = send(filePath(recordId), "GET", "", "application/json", CftlibConfig.ADMIN_USER);
        // The persistence gate returns 403, data-store wraps that as 500, and CDAM returns 502. Pin this finding
        // separately; accepting 502 in the preceding test would not prove CCD's read-permission check.
        assertThat(denied.statusCode()).as(denied.body()).isEqualTo(502);
        DM.server().verify(getRequestedFor(urlPathEqualTo("/documents/" + remoteId)));
        DM.server().verify(0, getRequestedFor(urlPathEqualTo("/documents/" + remoteId + "/binary")));
    }

    @Test
    @Order(5)
    void refusesAnotherCasesDocumentAndAnUploadBeforeLinkingWithoutCallingTheStore() throws Exception {
        String other = createRecord();
        DM.server().resetRequests();
        assertThat(send(filePath(other), "GET", "", "application/json", CftlibConfig.DOCUMENT_READER)
            .statusCode()).isEqualTo(404);
        assertThat(upload(other).statusCode()).isEqualTo(409);
        assertThat(DM.server().getAllServeEvents()).isEmpty();
    }

    @Test
    @Order(6)
    void deletionRemovesTheCcdReferenceAndCdamCanNoLongerDownload() throws Exception {
        // There is deliberately no document-delete HTTP endpoint. Exercise its existing domain entry point.
        documentService.removeDocument(recordId, documentId);
        assertThat(documents.findById(documentId)).isEmpty();
        assertThat(ccdDocuments().toString()).doesNotContain(remoteId);
        assertThat(latestSnapshot()).doesNotContain(remoteId);
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM ccd.case_event e JOIN ccd.case_data c ON e.case_data_id = c.id
             WHERE c.reference = ? AND e.event_id = 'updateSupportingDocuments'
            """, Integer.class, Long.valueOf(reference))).isEqualTo(2);
        DM.server().verify(1, deleteRequestedFor(urlPathEqualTo("/documents/" + remoteId))
            .withQueryParam("permanent", equalTo("false")));
        HttpResponse<String> direct = http.send(HttpRequest.newBuilder(
            URI.create("http://localhost:4455/cases/documents/" + remoteId + "/binary"))
            .header("Authorization", idam.getAccessToken(CftlibConfig.DOCUMENT_READER, "password"))
            .header("ServiceAuthorization", serviceTokens.generate()).GET().build(),
            HttpResponse.BodyHandlers.ofString());
        assertThat(direct.statusCode()).isEqualTo(404);
        assertThat(send(filePath(recordId), "GET", "", "application/json", CftlibConfig.DOCUMENT_READER)
            .statusCode()).isEqualTo(404);
        assertThat(DM.server().findAllUnmatchedRequests()).isEmpty();
    }

    private JsonNode ccdDocuments() {
        return json.valueToTree(ccd.getCase(systemUser.accessToken(), serviceTokens.generate(), reference).getData());
    }

    private String latestSnapshot() {
        return jdbc.queryForObject("""
            SELECT e.data::text FROM ccd.case_event e JOIN ccd.case_data c ON e.case_data_id = c.id
             WHERE c.reference = ? ORDER BY e.id DESC LIMIT 1
            """, String.class, Long.valueOf(reference));
    }

    private void assertDownload() throws Exception {
        HttpResponse<String> download = send(filePath(recordId), "GET", "", "application/json",
            CftlibConfig.DOCUMENT_READER);
        assertThat(download.statusCode()).as(download.body()).isEqualTo(200);
        assertThat(download.body()).isEqualTo(CONTENT);
    }

    private String createRecord() throws Exception {
        HttpResponse<String> created = send("/caseworker/cases", "POST",
            "{\"dateReceived\":\"2026-07-01T00:00:00.000Z\"}", "application/json", CftlibConfig.DOCUMENT_READER);
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        return json.readTree(created.body()).path("id").asText();
    }

    private HttpResponse<String> upload(String owner) throws Exception {
        DeedPollSupportingDocumentType type = new DeedPollSupportingDocumentType();
        type.setName("cftlib supporting document");
        String typeId = types.save(type).getId();
        String boundary = "deed-poll-cftlib";
        String body = "--" + boundary + "\r\nContent-Disposition: form-data; name=\"documentTypeId\"\r\n\r\n"
            + typeId + "\r\n--" + boundary
            + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"support.pdf\""
            + "\r\nContent-Type: application/pdf\r\n\r\n" + CONTENT + "\r\n--" + boundary + "--\r\n";
        return send("/caseworker/cases/" + owner + "/documents", "POST", body,
            "multipart/form-data; boundary=" + boundary, CftlibConfig.DOCUMENT_READER);
    }

    private String filePath(String owner) {
        return "/caseworker/cases/" + owner + "/documents/" + documentId + "/file";
    }

    private HttpResponse<String> send(String path, String method, String body, String type, String user)
        throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:4550" + path))
            .header("Authorization", idam.getAccessToken(user, "password"))
            .header("Content-Type", type).method(method, HttpRequest.BodyPublishers.ofString(body)).build(),
            HttpResponse.BodyHandlers.ofString());
    }
}
