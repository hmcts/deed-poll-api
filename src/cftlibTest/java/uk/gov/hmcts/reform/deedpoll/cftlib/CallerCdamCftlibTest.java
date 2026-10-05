package uk.gov.hmcts.reform.deedpoll.cftlib;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.tomakehurst.wiremock.WireMockServer;
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
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import uk.gov.hmcts.reform.authorisation.generators.AuthTokenGenerator;
import uk.gov.hmcts.reform.ccd.client.CoreCaseDataApi;
import uk.gov.hmcts.reform.deedpoll.ccd.CcdCreationAttempts;
import uk.gov.hmcts.reform.deedpoll.ccd.CcdLinkingSweep;
import uk.gov.hmcts.reform.deedpoll.ccd.SystemUserTokens;
import uk.gov.hmcts.reform.deedpoll.domain.ConflictError;
import uk.gov.hmcts.reform.deedpoll.domain.auth.CurrentUser;
import uk.gov.hmcts.reform.deedpoll.domain.deedpoll.DeedPollPatch;
import uk.gov.hmcts.reform.deedpoll.domain.deedpoll.DeedPollService;
import uk.gov.hmcts.reform.deedpoll.domain.documents.UploadedFile;
import uk.gov.hmcts.reform.deedpoll.domain.staging.OwnedStaging;
import uk.gov.hmcts.reform.deedpoll.domain.staging.StageOwner;
import uk.gov.hmcts.reform.deedpoll.entity.DeedPollSupportingDocumentType;
import uk.gov.hmcts.reform.deedpoll.entity.enums.DocumentDirection;
import uk.gov.hmcts.reform.deedpoll.entity.enums.DocumentStorageState;
import uk.gov.hmcts.reform.deedpoll.notify.CaseEmailService;
import uk.gov.hmcts.reform.deedpoll.notify.EmailTemplate;
import uk.gov.hmcts.reform.deedpoll.notify.NotificationOutbox;
import uk.gov.hmcts.reform.deedpoll.repository.DeedPollRepository;
import uk.gov.hmcts.reform.deedpoll.repository.DeedPollSupportingDocumentRepository;
import uk.gov.hmcts.reform.deedpoll.repository.DeedPollSupportingDocumentTypeRepository;
import uk.gov.hmcts.reform.deedpoll.repository.NotificationRepository;
import uk.gov.hmcts.reform.deedpoll.storage.CorrespondenceDocumentReader;
import uk.gov.hmcts.reform.deedpoll.storage.StagedPayloads;
import uk.gov.hmcts.reform.deedpoll.support.StubDmStore;
import uk.gov.hmcts.rse.ccd.lib.ControlPlane;
import uk.gov.hmcts.rse.ccd.lib.test.CftlibTest;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Local permission/attachment proof, not deployed onboarding or citizen authority. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT, properties = {
    "deedpoll.documents.staging.enabled=true",
    "deedpoll.documents.staging.max-total-bytes=104857600",
    "deedpoll.documents.staging.record-max-files=10",
    "deedpoll.documents.staging.record-max-bytes=52428800",
    "deedpoll.documents.staging.draft-max-bytes=52428800",
    "deedpoll.documents.staging.record-expiry=PT1H",
    "deedpoll.documents.staging.draft-expiry=PT1H",
    "deedpoll.documents.staging.cleanup-interval-ms=3600000",
    "deedpoll.documents.staging.cleanup-rows=10",
    "deedpoll.documents.staging.cleanup-bytes=52428800",
    "deedpoll.documents.staging.cleanup-time=PT1S",
    "notify.templates.forms-for-completion-adult=11111111-1111-4111-8111-111111111111",
    "notify.templates.forms-for-completion-minor=22222222-2222-4222-8222-222222222222",
    "notify.email-reply-to-id=33333333-3333-4333-8333-333333333333",
    "notify.document-retention-weeks=17",
    "notify.api-key=local-44444444-4444-4444-8444-444444444444-55555555-5555-4555-8555-555555555555"
})
@ActiveProfiles({"cftlib", "development"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class CallerCdamCftlibTest extends CftlibTest {
    private static final StubDmStore DM = new StubDmStore(4506);
    private static final WireMockServer NOTIFY = new WireMockServer(options().dynamicPort().globalTemplating(true));
    private static final CurrentUser CALLER = CurrentUser.currentUser(List.of(), "Fixture worker", "fixture-worker");
    private static final byte[] STAGED_BYTES = "%PDF-1.4\n% Synthetic staged document\n%%EOF\n"
        .getBytes(StandardCharsets.UTF_8);

    static {
        NOTIFY.start();
        NOTIFY.stubFor(post(urlPathEqualTo("/v2/notifications/email")).willReturn(aResponse()
            .withStatus(201).withHeader("Content-Type", "application/json").withBody("""
                {"id":"66666666-6666-4666-8666-666666666666",
                 "reference":"{{jsonPath request.body '$.reference'}}",
                 "content":{"subject":"Synthetic forms","body":"Synthetic forms","from_email":"local@example.test"},
                 "template":{"id":"{{jsonPath request.body '$.template_id'}}","version":1,
                             "uri":"http://localhost/template"},
                 "uri":"http://localhost/notification"}
                """)));
    }

    @DynamicPropertySource
    static void notifyEndpoint(DynamicPropertyRegistry registry) {
        registry.add("notify.api-url", NOTIFY::baseUrl);
    }

    @Autowired private DeedPollService caseService;
    @Autowired private DeedPollRepository cases;
    @Autowired private DeedPollSupportingDocumentRepository documents;
    @Autowired private DeedPollSupportingDocumentTypeRepository types;
    @Autowired private NotificationRepository notifications;
    @Autowired private CaseEmailService emails;
    @Autowired private NotificationOutbox outbox;
    @Autowired private CorrespondenceDocumentReader reader;
    @Autowired private OwnedStaging staging;
    @Autowired private CcdCreationAttempts attempts;
    @Autowired private CcdLinkingSweep sweep;
    @Autowired private CoreCaseDataApi ccd;
    @Autowired private SystemUserTokens systemUser;
    @Autowired private AuthTokenGenerator serviceTokens;
    @Autowired private ApplicationContext context;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper json;

    private final List<FrozenDeed> deeds = new ArrayList<>();
    private ApplicationContext originalContext;
    private StageOwner owner;
    private OwnedStaging.Receipt stage;
    private UUID stageOperation;
    private String typeId;

    private record FrozenDeed(String caseId, String reference, String documentId, String remoteId,
                              UUID notificationId, UUID requestId, EmailTemplate template,
                              String filename, byte[] bytes, Map<String, Object> personalisation) { }

    @AfterAll
    void stopStubs() {
        NOTIFY.stop();
        DM.close();
    }

    @Test
    @Order(1)
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
    void preparesBothDeedsAndOwnedStageBeforeReplacingApiContext() throws Exception {
        for (int run = 0; run < 20 && !attempts.dueForLinking(1).isEmpty(); run++) {
            sweep.sweep();
        }
        assertThat(attempts.dueForLinking(1)).isEmpty();
        for (EmailTemplate template : List.of(EmailTemplate.FORMS_FOR_COMPLETION_ADULT,
            EmailTemplate.FORMS_FOR_COMPLETION_MINOR)) {
            prepareDeed(template);
        }
        assertThat(NOTIFY.getAllServeEvents()).isEmpty();
        owner = new StageOwner(StageOwner.Kind.RECORD, createCase("Adult"), "verified-domain-owner-fixture");
        stageOperation = UUID.randomUUID();
        stage = staging.admit(owner, stageOperation, stagedFile());
        var type = new DeedPollSupportingDocumentType();
        type.setName("cftlib staged supporting document");
        typeId = types.save(type).getId();
        DM.server().resetRequests();
        assertThatThrownBy(() -> staging.promote(owner, stage.id(), owner.id(), typeId))
            .isInstanceOf(ConflictError.class);
        assertThat(cases.findCcdCaseReference(owner.id())).isEmpty();
        assertThat(documents.findByDeedPollIdOrderByCreatedOnAsc(owner.id())).isEmpty();
        assertThat(DM.server().getAllServeEvents()).isEmpty();
        originalContext = context;
    }

    private void prepareDeed(EmailTemplate template) throws Exception {
        String id = createCase(template == EmailTemplate.FORMS_FOR_COMPLETION_ADULT ? "Adult" : "Minor");
        sweep.sweep();
        final String reference = cases.findCcdCaseReference(id).orElseThrow();
        DM.server().resetRequests();
        UUID request = UUID.randomUUID();
        final long started = System.nanoTime();
        // Use the production coordinator directly to defer the controller's immediate drain until after restart.
        var receipt = emails.send(id, template, Map.of(), request, CALLER);
        assertThat(receipt.enqueued()).isTrue();
        var rows = documents.findByDeedPollIdOrderByCreatedOnAsc(id);
        assertThat(rows).hasSize(1);
        var document = rows.getFirst();
        assertThat(document.getStorageState()).isEqualTo(DocumentStorageState.Attached);
        assertThat(document.getDirection()).isEqualTo(DocumentDirection.Outgoing);
        assertThat(document.getCdamHashToken()).isNull();
        String remote = document.getCdamDocumentId().toString();
        assertAttachment(reference, remote);
        byte[] original = storedBytes(remote);
        assertThat(original).isNotEmpty();
        assertThat(document.getOriginalName()).contains(template == EmailTemplate.FORMS_FOR_COMPLETION_ADULT
            ? "LOC020" : "LOC022");
        assertThat(jdbc.queryForObject("""
            SELECT checksum FROM deedpoll_deedpreparation WHERE "deedPollId" = ?
            """, String.class, id)).isEqualTo(StagedPayloads.checksum(original));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM deedpoll_stagedpayload WHERE id = ?",
            Integer.class, document.getId())).isZero();
        var frozen = notifications.findById(receipt.id()).orElseThrow();
        assertThat(frozen.getPersonalisation()).containsEntry("deed_document", document.getId());
        assertThat(emails.send(id, template, Map.of(), request, CALLER).id()).isEqualTo(receipt.id());
        assertThat(emails.send(id, template, Map.of(), UUID.randomUUID(), CALLER).enqueued()).isFalse();
        DM.server().verify(1, postRequestedFor(urlPathEqualTo("/documents")));
        deeds.add(new FrozenDeed(id, reference, document.getId(), remote, receipt.id(), request, template,
            document.getOriginalName(), original, Map.copyOf(frozen.getPersonalisation())));
        System.out.printf("cftlib: %s preparation/attachment/repeated send took %d ms%n",
            template.name(), (System.nanoTime() - started) / 1000000);
    }

    @Test
    @Order(2)
    void systemCorrespondenceReadHonoursGenuineCcdReadRefusalAfterRestart() throws Exception {
        assertThat(context).isNotSameAs(originalContext);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        Path definition = Path.of("build/cftlib/definitions/DEED_POLL_PLACEHOLDER");
        Path grants = definition.resolve("AuthorisationCaseType.json");
        String original = Files.readString(grants);
        JsonNode withoutRead = json.readTree(original);
        for (JsonNode grant : withoutRead) {
            if (grant.path("UserRole").asText().equals("DEED_POLL_SYSTEM")) {
                ((ObjectNode) grant).put("CRUD", grant.path("CRUD").asText().replace("R", ""));
            }
        }
        try {
            Files.writeString(grants, withoutRead.toString());
            ControlPlane.getApi().importJsonDefinition(definition.toFile());
            Thread.sleep(1200);
            DM.server().resetRequests();
            // Pin an actual CDAM 403/404, not the upstream unmapped-user 502.
            FrozenDeed deed = deeds.getFirst();
            var denied = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(
                "http://localhost:4455/cases/documents/" + deed.remoteId() + "/binary"))
                .header("Authorization", systemUser.accessToken())
                .header("ServiceAuthorization", serviceTokens.generate()).GET().build(),
                HttpResponse.BodyHandlers.ofString());
            assertThat(denied.statusCode()).as(denied.body()).isIn(403, 404);
            assertThat(outbox.drain()).isZero();
            assertThat(NOTIFY.getAllServeEvents()).isEmpty();
            for (FrozenDeed frozen : deeds) {
                var row = notifications.findById(frozen.notificationId()).orElseThrow();
                assertThat(row.getAttempts()).isEqualTo(1);
                assertThat(row.getSendStartedAt()).isNull();
                DM.server().verify(getRequestedFor(urlPathEqualTo("/documents/" + frozen.remoteId())));
                DM.server().verify(0, getRequestedFor(urlPathEqualTo("/documents/" + frozen.remoteId() + "/binary")));
            }
        } finally {
            Files.writeString(grants, original);
            ControlPlane.getApi().importJsonDefinition(definition.toFile());
            Thread.sleep(1200);
        }
        for (FrozenDeed deed : deeds) {
            jdbc.update("UPDATE deedpoll_notification SET \"nextAttemptAt\" = now() WHERE id = ?",
                deed.notificationId());
        }
    }

    @Test
    @Order(3)
    void laterDrainSendsOriginalBytesAndFilenameWithoutStaffContextOrAnotherUpload() throws Exception {
        assertThat(context).isNotSameAs(originalContext);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        DM.server().resetRequests();
        for (FrozenDeed deed : deeds) {
            caseService.updateDeedPoll(deed.caseId(), DeedPollPatch.of(Map.of("newForename", "Later edit")));
            var repeated = emails.send(deed.caseId(), deed.template(), Map.of(), deed.requestId(), CALLER);
            assertThat(repeated.enqueued()).isFalse();
            assertThat(repeated.id()).isEqualTo(deed.notificationId());
            assertThat(documents.findByDeedPollIdOrderByCreatedOnAsc(deed.caseId()))
                .singleElement().satisfies(row -> assertThat(row.getId()).isEqualTo(deed.documentId()));
        }
        final long started = System.nanoTime();
        assertThat(outbox.drain()).isEqualTo(2);
        var requests = NOTIFY.findAll(postRequestedFor(urlPathEqualTo("/v2/notifications/email")));
        assertThat(requests).hasSize(2);
        for (FrozenDeed deed : deeds) {
            JsonNode sent = requests.stream().map(request -> {
                try {
                    return json.readTree(request.getBodyAsString());
                } catch (java.io.IOException failure) {
                    throw new AssertionError(failure);
                }
            }).filter(body -> body.path("reference").asText().equals(deed.notificationId().toString()))
                .findFirst().orElseThrow();
            JsonNode upload = sent.path("personalisation").path("deed_document");
            assertThat(Base64.getDecoder().decode(upload.path("file").asText())).isEqualTo(deed.bytes());
            assertThat(upload.path("filename").asText()).isEqualTo(deed.filename());
            assertThat(upload.path("confirm_email_before_download").asBoolean()).isTrue();
            assertThat(upload.path("retention_period").asText()).isEqualTo("17 weeks");
            var row = notifications.findById(deed.notificationId()).orElseThrow();
            assertThat(row.getState().name()).isEqualTo("Sent");
            assertThat(row.getPersonalisation()).isEqualTo(deed.personalisation());
            DM.server().verify(1, getRequestedFor(urlPathEqualTo("/documents/" + deed.remoteId() + "/binary")));
            assertAttachment(deed.reference(), deed.remoteId());
        }
        assertThat(outbox.drain()).isZero();
        DM.server().verify(0, postRequestedFor(urlPathEqualTo("/documents")));
        assertThat(NOTIFY.findAllUnmatchedRequests()).isEmpty();
        System.out.printf("cftlib: restarted API correspondence drain took %d ms%n",
            (System.nanoTime() - started) / 1000000);
    }

    @Test
    @Order(4)
    void stagedBytesSurviveRestartAndPromoteExactlyOnceAfterNormalLinking() throws Exception {
        assertThat(context).isNotSameAs(originalContext);
        assertThat(cases.findCcdCaseReference(owner.id())).isEmpty();
        assertThat(staging.admit(owner, stageOperation, stagedFile())).isEqualTo(stage);
        DM.server().resetRequests();
        assertThatThrownBy(() -> staging.promote(owner, stage.id(), owner.id(), typeId))
            .isInstanceOf(ConflictError.class);
        assertThat(DM.server().getAllServeEvents()).isEmpty();
        sweep.sweep();
        final String reference = cases.findCcdCaseReference(owner.id()).orElseThrow();
        final long started = System.nanoTime();
        String documentId = staging.promote(owner, stage.id(), owner.id(), typeId);
        assertThat(staging.promote(owner, stage.id(), owner.id(), typeId)).isEqualTo(documentId);
        var document = documents.findById(documentId).orElseThrow();
        assertThat(document.getStorageState()).isEqualTo(DocumentStorageState.Attached);
        assertThat(document.getDirection()).isEqualTo(DocumentDirection.Incoming);
        assertThat(documents.findByDeedPollIdOrderByCreatedOnAsc(owner.id())).hasSize(1);
        assertAttachment(reference, document.getCdamDocumentId().toString());
        try (var content = reader.downloadDocument(owner.id(), documentId).stream()) {
            assertThat(content.readAllBytes()).isEqualTo(STAGED_BYTES);
        }
        assertThat(jdbc.queryForObject("SELECT state FROM deedpoll_ownedstage WHERE id = ?",
            String.class, stage.id())).isEqualTo("Consumed");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM deedpoll_stagedpayload WHERE id = ?",
            Integer.class, stage.id())).isZero();
        DM.server().verify(1, postRequestedFor(urlPathEqualTo("/documents")));
        assertThat(DM.server().findAllUnmatchedRequests()).isEmpty();
        System.out.printf("cftlib: staged promotion/read-back/retry took %d ms%n",
            (System.nanoTime() - started) / 1000000);
    }

    private String createCase(String type) {
        return caseService.createDeedPoll(DeedPollPatch.of(Map.of(
            "dateReceived", "2026-07-01T00:00:00.000Z", "type", type,
            "applicantEmail", "synthetic@example.test", "newForename", "Original"))).deedPoll().getId();
    }

    private UploadedFile stagedFile() {
        return UploadedFile.of(STAGED_BYTES, "original-staged.pdf", "application/pdf");
    }

    private byte[] storedBytes(String remote) throws Exception {
        var response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
            URI.create(DM.server().baseUrl() + "/documents/" + remote + "/binary"))
            .header("ServiceAuthorization", "fixture-inspection").header("user-id", "fixture-inspection")
            .GET().build(), HttpResponse.BodyHandlers.ofByteArray());
        assertThat(response.statusCode()).isEqualTo(200);
        return response.body();
    }

    private void assertAttachment(String reference, String remote) {
        String projection = json.valueToTree(ccd.getCase(systemUser.accessToken(), serviceTokens.generate(), reference)
            .getData()).toString();
        assertThat(projection).contains(remote).doesNotContain("document_hash", "hashToken");
        String snapshot = jdbc.queryForObject("""
            SELECT e.data::text FROM ccd.case_event e JOIN ccd.case_data c ON e.case_data_id = c.id
             WHERE c.reference = ? ORDER BY e.id DESC LIMIT 1
            """, String.class, Long.valueOf(reference));
        assertThat(snapshot).contains(remote).doesNotContain("document_hash", "hashToken");
    }
}
