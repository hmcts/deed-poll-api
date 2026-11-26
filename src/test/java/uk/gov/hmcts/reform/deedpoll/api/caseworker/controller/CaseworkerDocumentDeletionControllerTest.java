package uk.gov.hmcts.reform.deedpoll.api.caseworker.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import uk.gov.hmcts.reform.deedpoll.auth.StubIdam;
import uk.gov.hmcts.reform.deedpoll.config.AbstractPostgresContainerTest;
import uk.gov.hmcts.reform.deedpoll.domain.auth.Role;
import uk.gov.hmcts.reform.deedpoll.domain.deletion.DeletionTestDocumentType;
import uk.gov.hmcts.reform.deedpoll.domain.documents.deletion.DocumentDeletionFailure;
import uk.gov.hmcts.reform.deedpoll.domain.documents.deletion.DocumentDeletionRefusal;
import uk.gov.hmcts.reform.deedpoll.domain.documents.deletion.DocumentDeletionRemote;
import uk.gov.hmcts.reform.deedpoll.domain.documents.deletion.DocumentDeletionRemote.StepOutcome;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The document deletion contract over real HTTP: the full security context, the real filter chain and Postgres (plan
 * 20261125-permanent-document-deletion, Task 6). The admission and work rules themselves are {@code
 * DocumentDeletionServiceTest}'s and {@code DocumentDeletionWorkTest}'s; this proves who reaches them, what reaches
 * the wire, and that nothing private does. The remote steps are a mock, so only a document with a file reaches it;
 * a metadata-only document completes without it.
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@ExtendWith(OutputCaptureExtension.class)
class CaseworkerDocumentDeletionControllerTest extends AbstractPostgresContainerTest {

    private static final StubIdam IDAM = new StubIdam();
    private static final String REASON = "Uploaded in error, marker 9K2WQ";
    private static final String MARKER = "9K2WQ";

    static {
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
    private MockMvc mvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ObjectMapper json;

    @MockitoBean
    private DocumentDeletionRemote remote;

    private final List<String> fixtures = new ArrayList<>();

    @BeforeEach
    void setUp() {
        IDAM.stubDefaults();
        asStaff(Role.CASEWORKER.claimValue());
    }

    @AfterEach
    void cleanUp() {
        for (String id : fixtures) {
            jdbc.update("DELETE FROM deedpoll_deedpoll WHERE id = ?", id);
        }
        DeletionTestDocumentType.removeIfUnused(jdbc);
    }

    // --- who --------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("no token, and an S2S token alone (a citizen or service caller), are 401 on both routes")
    void unauthenticated() throws Exception {
        String caseId = fixture();
        String documentId = document(caseId, null);

        mvc.perform(post(admissionPath(caseId, documentId))
                        .header(CaseworkerDocumentDeletionController.IDEMPOTENCY_KEY, UUID.randomUUID().toString())
                        .contentType("application/json").content(body(REASON, 1)))
            .andExpect(status().isUnauthorized());
        mvc.perform(get(statusPath(caseId, UUID.randomUUID().toString()))).andExpect(status().isUnauthorized());
        mvc.perform(post(admissionPath(caseId, documentId)).header("ServiceAuthorization", "Bearer s2s-token")
                        .header(CaseworkerDocumentDeletionController.IDEMPOTENCY_KEY, UUID.randomUUID().toString())
                        .contentType("application/json").content(body(REASON, 1)))
            .andExpect(status().isUnauthorized());
        mvc.perform(get(statusPath(caseId, UUID.randomUUID().toString()))
                        .header("ServiceAuthorization", "Bearer s2s-token"))
            .andExpect(status().isUnauthorized());
        assertThat(requestCount(caseId)).isZero();
        assertThat(documentExists(documentId)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"caseworker-deedpoll-judicial", "caseworker-civil", "citizen"})
    @DisplayName("a judge, another service's staff and a citizen are 403 on both routes, before anything is read")
    void refused(String role) throws Exception {
        String caseId = fixture();
        String documentId = document(caseId, null);
        final String admitted = admitAs(caseId, document(caseId, "held-file"), UUID.randomUUID(), "InProgress");
        asStaff(role);

        keyed(post(admissionPath(caseId, documentId)), UUID.randomUUID().toString(), body(REASON, 1))
            .andExpect(status().isForbidden());
        keyed(post(admissionPath(caseId, documentId)), "not-a-uuid", "{not json").andExpect(status().isForbidden());
        keyed(post(admissionPath(caseId, documentId)), null,
              "x".repeat(CaseworkerDocumentDeletionController.MAX_BODY_BYTES * 4))
            .andExpect(status().isForbidden());
        authorised(get(statusPath(caseId, admitted))).andExpect(status().isForbidden());
        authorised(get(statusPath(caseId, "not-a-uuid"))).andExpect(status().isForbidden());

        assertThat(requestCount(caseId)).isOne();
        assertThat(documentExists(documentId)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"caseworker-deedpoll", "caseworker-deedpoll-team-leader",
        "caseworker-deedpoll-delivery-manager", "caseworker-deedpoll-admin"})
    @DisplayName("every staff role deletes with the published documentVersion: 200 Completed, no-store, no Location,"
        + " one DOCUMENT_DELETED and one note under the caller")
    void everyStaffRoleDeletes(String role) throws Exception {
        asStaff(role);
        String caseId = fixture();
        String documentId = document(caseId, null);
        long version = publishedVersion(caseId, documentId);
        assertThat(version).isOne();

        UUID key = UUID.randomUUID();
        MvcResult result = keyed(post(admissionPath(caseId, documentId)), key.toString(), body(REASON, version))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("Completed"))
            .andReturn();

        JsonNode answer = json.readTree(result.getResponse().getContentAsString());
        assertThat(answer.fieldNames()).toIterable().containsExactlyInAnyOrder("requestId", "status");
        assertThat(result.getResponse().getHeader(HttpHeaders.CACHE_CONTROL)).contains("no-store");
        assertThat(result.getResponse().getHeader(HttpHeaders.LOCATION)).isNull();
        assertThat(result.getResponse().getContentAsString()).doesNotContain(MARKER).doesNotContain(key.toString());
        assertThat(documentExists(documentId)).isFalse();
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM deedpoll_audit_event
             WHERE case_id = ? AND action = 'DOCUMENT_DELETED' AND actor_id = ? AND entity_id = ?
            """, Integer.class, caseId, StubIdam.SUBJECT, documentId)).isOne();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM deedpoll_notes WHERE \"deedPollId\" = ? AND note LIKE ?",
                                       Integer.class, caseId, "%" + MARKER + "%")).isOne();
        verify(remote, never()).removeLocalFile(anyString());

        String requestId = answer.get("requestId").asText();
        MvcResult read = authorised(get(statusPath(caseId, requestId))).andExpect(status().isOk()).andReturn();
        JsonNode detail = json.readTree(read.getResponse().getContentAsString());
        assertThat(detail.fieldNames()).toIterable().containsExactlyInAnyOrder(
            "requestId", "documentId", "status", "requestedAt", "completedAt", "failure");
        assertThat(detail.get("documentId").asText()).isEqualTo(documentId);
        assertThat(detail.get("status").asText()).isEqualTo("Completed");
        assertThat(detail.get("completedAt").isNull()).isFalse();
        assertThat(detail.get("failure").isNull()).isTrue();
        assertThat(read.getResponse().getHeader(HttpHeaders.CACHE_CONTROL)).contains("no-store");
        assertThat(read.getResponse().getContentAsString()).doesNotContain(MARKER).doesNotContain(StubIdam.SUBJECT)
            .doesNotContain("Evidence").doesNotContain(key.toString());
    }

    @Test
    @DisplayName("an unconfirmed step is 202 InProgress with the status Location; a failed one 202 NeedsAttention")
    void unfinishedIsAccepted() throws Exception {
        String caseId = fixture();
        String retried = document(caseId, "retried-file");
        String failed = document(caseId, "failed-file");
        when(remote.removeLocalFile("retried-file")).thenReturn(StepOutcome.retry("TIMEOUT"));
        when(remote.removeLocalFile("failed-file"))
            .thenReturn(StepOutcome.failed("UNREMOVED", DocumentDeletionFailure.LOCAL_FILE_UNREMOVED));

        MvcResult inProgress = keyed(post(admissionPath(caseId, retried)), UUID.randomUUID().toString(),
                                     body(REASON, 1))
            .andExpect(status().isAccepted()).andExpect(jsonPath("$.status").value("InProgress")).andReturn();
        String requestId = json.readTree(inProgress.getResponse().getContentAsString()).get("requestId").asText();
        assertThat(inProgress.getResponse().getHeader(HttpHeaders.LOCATION))
            .isEqualTo("/caseworker/cases/" + caseId + "/document-deletions/" + requestId);
        assertThat(inProgress.getResponse().getHeader(HttpHeaders.CACHE_CONTROL)).contains("no-store");
        authorised(get(statusPath(caseId, requestId))).andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("InProgress"))
            .andExpect(jsonPath("$.failure").doesNotExist());

        MvcResult attention = keyed(post(admissionPath(caseId, failed)), UUID.randomUUID().toString(),
                                    body(REASON, 1))
            .andExpect(status().isAccepted()).andExpect(jsonPath("$.status").value("NeedsAttention")).andReturn();
        String attentionId = json.readTree(attention.getResponse().getContentAsString()).get("requestId").asText();
        MvcResult read = authorised(get(statusPath(caseId, attentionId))).andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("NeedsAttention"))
            .andExpect(jsonPath("$.failure").value("LOCAL_FILE_UNREMOVED"))
            .andReturn();
        assertThat(read.getResponse().getContentAsString()).doesNotContain("failed-file").doesNotContain(MARKER);
        authorised(get("/caseworker/cases/" + caseId)).andExpect(status().isOk())
            .andExpect(jsonPath("$.documents.length()").value(0));
    }

    // --- validation and refusals ------------------------------------------------------------------------------

    @Test
    @DisplayName("a missing or malformed key, an unknown key and each refused field are 400; 413 and 415 too;"
        + " nothing is written")
    void validation() throws Exception {
        String caseId = fixture();
        String documentId = document(caseId, null);
        String path = admissionPath(caseId, documentId);
        String valid = body(REASON, 1);

        keyed(post(path), null, valid).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.errors[0].field").value("Idempotency-Key"));
        keyed(post(path), "not-a-uuid", valid).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.errors[0].field").value("Idempotency-Key"));
        keyed(post(path), UUID.randomUUID().toString(),
              "{\"reason\":\"Duplicate\",\"expectedDocumentVersion\":1,\"author\":\"Somebody else\"}")
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("A document deletion request takes only reason and "
                                                 + "expectedDocumentVersion"));
        keyed(post(path), UUID.randomUUID().toString(), body("   ", 1)).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.errors[0].field").value("reason"));
        keyed(post(path), UUID.randomUUID().toString(), body("Line one\nline two " + MARKER, 1))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("reason"));
        keyed(post(path), UUID.randomUUID().toString(), body("x".repeat(501), 1))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("reason"));
        for (String version : List.of("\"1\"", "1.0", "0", "-1", "null", "99999999999999999999")) {
            keyed(post(path), UUID.randomUUID().toString(),
                  "{\"reason\":\"Duplicate\",\"expectedDocumentVersion\":" + version + "}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("expectedDocumentVersion"));
        }
        keyed(post(path), UUID.randomUUID().toString(), null).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.errors.length()").value(2));
        keyed(post(path), UUID.randomUUID().toString(), "{not json").andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("Request body is missing or is not valid JSON"));
        authorised(post(path).header(CaseworkerDocumentDeletionController.IDEMPOTENCY_KEY,
                                     UUID.randomUUID().toString())
                       .contentType("text/plain").content(valid))
            .andExpect(status().isUnsupportedMediaType());
        authorised(multipart(path).param("reason", "Duplicate")
                       .header(CaseworkerDocumentDeletionController.IDEMPOTENCY_KEY, UUID.randomUUID().toString()))
            .andExpect(status().isUnsupportedMediaType());
        keyed(post(path), UUID.randomUUID().toString(),
              "{\"reason\":\"" + "x".repeat(CaseworkerDocumentDeletionController.MAX_BODY_BYTES) + "\"}")
            .andExpect(status().isPayloadTooLarge());

        assertThat(requestCount(caseId)).isZero();
        assertThat(documentExists(documentId)).isTrue();
    }

    @Test
    @DisplayName("an older documentVersion is 409 DOCUMENT_CHANGED with its code and words, and writes nothing")
    void changed() throws Exception {
        String caseId = fixture();
        String documentId = document(caseId, null);
        jdbc.update("UPDATE deedpoll_supportingdocument SET name = 'Renamed' WHERE id = ?", documentId);
        assertThat(publishedVersion(caseId, documentId)).isEqualTo(2);

        MvcResult result = keyed(post(admissionPath(caseId, documentId)), UUID.randomUUID().toString(),
                                 body(REASON, 1))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("DOCUMENT_CHANGED"))
            .andExpect(jsonPath("$.error").value(DocumentDeletionRefusal.DOCUMENT_CHANGED.message()))
            .andReturn();
        assertThat(json.readTree(result.getResponse().getContentAsString()).fieldNames()).toIterable()
            .containsExactlyInAnyOrder("error", "code");
        assertThat(result.getResponse().getHeader(HttpHeaders.CACHE_CONTROL)).contains("no-store");
        assertThat(result.getResponse().getContentAsString()).doesNotContain(MARKER);
        assertThat(requestCount(caseId)).isZero();
        assertThat(documentExists(documentId)).isTrue();
    }

    @Test
    @DisplayName("an unknown case, an unknown document and another case's document or request are the same 404s")
    void crossCase() throws Exception {
        String caseId = fixture();
        String other = fixture();
        String otherDocument = document(other, null);
        String otherRequest = admitAs(other, document(other, "other-file"), UUID.randomUUID(), "InProgress");

        keyed(post(admissionPath(caseId, otherDocument)), UUID.randomUUID().toString(), body(REASON, 1))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error").value("Document " + otherDocument + " not found"));
        keyed(post(admissionPath(caseId, UUID.randomUUID().toString())), UUID.randomUUID().toString(),
              body(REASON, 1))
            .andExpect(status().isNotFound());
        keyed(post(admissionPath(UUID.randomUUID().toString(), otherDocument)), UUID.randomUUID().toString(),
              body(REASON, 1))
            .andExpect(status().isNotFound()).andExpect(jsonPath("$.error").value("DeedPoll not found"));
        authorised(get(statusPath(caseId, otherRequest))).andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error").value("Document deletion request not found"));
        authorised(get(statusPath(caseId, "not-a-uuid"))).andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error").value("Document deletion request not found"));
        authorised(get(statusPath(UUID.randomUUID().toString(), otherRequest))).andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error").value("DeedPoll not found"));

        assertThat(requestCount(caseId)).isZero();
        assertThat(documentExists(otherDocument)).isTrue();
    }

    // --- replay -----------------------------------------------------------------------------------------------

    @Test
    @DisplayName("a lost answer replays: the same key and body is the same Completed request after the row is gone;"
        + " another body is 409 KEY_REUSED; the requester is kept")
    void replaysAfterTheRowIsGone() throws Exception {
        String caseId = fixture();
        String documentId = document(caseId, null);
        UUID key = UUID.randomUUID();
        String original = body(REASON, 1);
        String requestId = json.readTree(keyed(post(admissionPath(caseId, documentId)), key.toString(), original)
                                             .andExpect(status().isOk()).andReturn().getResponse()
                                             .getContentAsString()).get("requestId").asText();
        assertThat(documentExists(documentId)).isFalse();

        IDAM.stubUserInfo("document-deletion-second", "Second Worker", List.of(Role.TEAM_LEADER.claimValue()));
        String second = IDAM.accessToken(claims -> claims.subject("document-deletion-second"));
        MvcResult replay = mvc.perform(post(admissionPath(caseId, documentId))
                                           .header(HttpHeaders.AUTHORIZATION, "Bearer " + second)
                                           .header(CaseworkerDocumentDeletionController.IDEMPOTENCY_KEY,
                                                   key.toString())
                                           .contentType("application/json").content(original))
            .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("Completed")).andReturn();
        assertThat(json.readTree(replay.getResponse().getContentAsString()).get("requestId").asText())
            .isEqualTo(requestId);
        asStaff(Role.CASEWORKER.claimValue());
        keyed(post(admissionPath(caseId, documentId)), key.toString(), body("Duplicate", 1))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("KEY_REUSED"));
        keyed(post(admissionPath(caseId, documentId)), UUID.randomUUID().toString(), original)
            .andExpect(status().isOk()).andExpect(jsonPath("$.requestId").value(requestId));

        assertThat(requestCount(caseId)).isOne();
        assertThat(jdbc.queryForObject(
            "SELECT requested_by_subject FROM deedpoll_document_deletion_request WHERE id = ?::uuid",
            String.class, requestId)).isEqualTo(StubIdam.SUBJECT);
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM deedpoll_audit_event WHERE case_id = ? AND action = 'DOCUMENT_DELETED'
            """, Integer.class, caseId)).isOne();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM deedpoll_notes WHERE \"deedPollId\" = ?",
                                       Integer.class, caseId)).isOne();
    }

    @Test
    @DisplayName("access is asked again: a caller whose staff role is removed is 403 on replay and status")
    void accessRevoked() throws Exception {
        String caseId = fixture();
        String documentId = document(caseId, "revoked-file");
        when(remote.removeLocalFile("revoked-file")).thenReturn(StepOutcome.retry("TIMEOUT"));
        UUID key = UUID.randomUUID();
        String requestId = admitAs(caseId, documentId, key, "InProgress");

        asStaff("caseworker-deedpoll-judicial");
        keyed(post(admissionPath(caseId, documentId)), key.toString(), body(REASON, 1))
            .andExpect(status().isForbidden());
        authorised(get(statusPath(caseId, requestId))).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("the reason reaches no log line, admitted, replayed or refused")
    void reasonNeverLogged(CapturedOutput output) throws Exception {
        String caseId = fixture();
        String documentId = document(caseId, null);
        UUID key = UUID.randomUUID();
        keyed(post(admissionPath(caseId, documentId)), key.toString(), body(REASON, 1)).andExpect(status().isOk());
        keyed(post(admissionPath(caseId, documentId)), key.toString(), body(REASON + " changed", 1))
            .andExpect(status().isConflict());
        keyed(post(admissionPath(caseId, documentId)), UUID.randomUUID().toString(), body("Tab\t" + MARKER, 1))
            .andExpect(status().isBadRequest());

        assertThat(output.getAll()).doesNotContain(MARKER);
    }

    // --- fixtures ---------------------------------------------------------------------------------------------

    private String fixture() {
        String id = UUID.randomUUID().toString();
        fixtures.add(id);
        jdbc.update("""
            INSERT INTO deedpoll_deedpoll (id, "modifiedOn", type, state, "displayReference")
            VALUES (?, now(), 'Adult'::"DeedPollType", 'Enroled', ?)
            """, id, "document-deletion-http-" + id);
        return id;
    }

    /** A filesystem document with this storage key, or a metadata-only one for null. */
    private String document(String caseId, String storageKey) {
        DeletionTestDocumentType.ensure(jdbc);
        String id = UUID.randomUUID().toString();
        jdbc.update("""
            INSERT INTO deedpoll_supportingdocument (id, "deedPollId", direction, name, "documentTypeId",
                "storageKey", "modifiedOn", "storageState", "originalName", "createdOn")
            VALUES (?, ?, 'Incoming', 'Evidence', ?, ?, now(), 'Local', 'evidence.pdf', clock_timestamp())
            """, id, caseId, DeletionTestDocumentType.ID, storageKey);
        return id;
    }

    /** Admits a deletion of a document with a file, whose remote step the mock leaves unconfirmed. */
    private String admitAs(String caseId, String documentId, UUID key, String expectedStatus) throws Exception {
        when(remote.removeLocalFile(anyString())).thenReturn(StepOutcome.retry("TIMEOUT"));
        MvcResult result = keyed(post(admissionPath(caseId, documentId)), key.toString(), body(REASON, 1))
            .andExpect(status().isAccepted()).andExpect(jsonPath("$.status").value(expectedStatus)).andReturn();
        return json.readTree(result.getResponse().getContentAsString()).get("requestId").asText();
    }

    /** The document's documentVersion exactly as the case detail publishes it. */
    private long publishedVersion(String caseId, String documentId) throws Exception {
        JsonNode detail = json.readTree(authorised(get("/caseworker/cases/" + caseId)).andExpect(status().isOk())
                                            .andReturn().getResponse().getContentAsString());
        for (JsonNode document : detail.get("documents")) {
            if (document.get("id").asText().equals(documentId)) {
                return document.get("documentVersion").asLong();
            }
        }
        throw new AssertionError("the document is not published");
    }

    private String body(String reason, long version) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("reason", reason);
        body.put("expectedDocumentVersion", version);
        return json.writeValueAsString(body);
    }

    private int requestCount(String caseId) {
        return jdbc.queryForObject("SELECT count(*) FROM deedpoll_document_deletion_request WHERE case_id = ?",
                                   Integer.class, caseId);
    }

    private boolean documentExists(String documentId) {
        return jdbc.queryForObject("SELECT count(*) FROM deedpoll_supportingdocument WHERE id = ?", Integer.class,
                                   documentId) == 1;
    }

    private static String admissionPath(String caseId, String documentId) {
        return "/caseworker/cases/" + caseId + "/documents/" + documentId + "/deletion-requests";
    }

    private static String statusPath(String caseId, String requestId) {
        return "/caseworker/cases/" + caseId + "/document-deletions/" + requestId;
    }

    private void asStaff(String role) {
        IDAM.stubUserInfo(StubIdam.SUBJECT, "Staff", List.of(role));
    }

    private ResultActions keyed(MockHttpServletRequestBuilder request, String key, String body) throws Exception {
        if (key != null) {
            request.header(CaseworkerDocumentDeletionController.IDEMPOTENCY_KEY, key);
        }
        if (body != null) {
            request.contentType("application/json").content(body);
        }
        return authorised(request);
    }

    private ResultActions authorised(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request.header(HttpHeaders.AUTHORIZATION, "Bearer " + IDAM.accessToken()));
    }
}
