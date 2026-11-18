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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import uk.gov.hmcts.reform.deedpoll.auth.StubIdam;
import uk.gov.hmcts.reform.deedpoll.config.AbstractPostgresContainerTest;
import uk.gov.hmcts.reform.deedpoll.domain.auth.Role;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The deletion contract over real HTTP: the full security context, the real filter chain and Postgres (plan
 * 20261118-dm-application-deletion, Task 7). The admission rules themselves are
 * {@code ApplicationDeletionServiceTest}'s; this proves what reaches the wire, in which order, and that nothing private
 * does.
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@ExtendWith(OutputCaptureExtension.class)
class CaseworkerApplicationDeletionControllerTest extends AbstractPostgresContainerTest {

    private static final StubIdam IDAM = new StubIdam();
    private static final LocalDateTime MODIFIED = LocalDateTime.of(2026, 11, 18, 9, 30, 15, 250_000_000);
    private static final String NOTE = "Keyed twice from one postal application, marker 7Q4ZX";
    private static final String SECOND_SUBJECT = "deletion-http-second-dm";

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

    private final List<String> fixtures = new ArrayList<>();

    @BeforeEach
    void setUp() {
        IDAM.stubDefaults();
        asStaff(Role.DELIVERY_MANAGER.claimValue());
    }

    @AfterEach
    void cleanUp() {
        for (String id : fixtures) {
            jdbc.update("DELETE FROM deedpoll_deletion_request WHERE case_id = ?", id);
            jdbc.update("DELETE FROM deedpoll_deedpoll WHERE id = ?", id);
        }
    }

    @Test
    @DisplayName("no token is 401 on both routes, and writes nothing")
    void unauthenticated() throws Exception {
        String id = fixture();

        mvc.perform(post(admissionPath(id)).header(CaseworkerApplicationDeletionController.IDEMPOTENCY_KEY,
                                                   UUID.randomUUID().toString())
                        .contentType("application/json").content(body("DUPLICATE", NOTE, MODIFIED.toString())))
            .andExpect(status().isUnauthorized());
        mvc.perform(get(statusPath(UUID.randomUUID().toString()))).andExpect(status().isUnauthorized());
        assertThat(requestCount(id)).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"caseworker-deedpoll", "caseworker-deedpoll-team-leader", "caseworker-deedpoll-admin",
        "caseworker-deedpoll-judicial", "caseworker-civil"})
    @DisplayName("every role but Delivery Manager is 403 on both routes, before the id, header or body is parsed")
    void refusedBeforeAnythingIsRead(String role) throws Exception {
        String id = fixture();
        final String admitted = admit(id, UUID.randomUUID(), body("DUPLICATE", NOTE, MODIFIED.toString()))
            .get("requestId").asText();
        asStaff(role);

        keyed(post(admissionPath(id)), UUID.randomUUID().toString(), body("DUPLICATE", NOTE, MODIFIED.toString()))
            .andExpect(status().isForbidden());
        keyed(post(admissionPath(UUID.randomUUID().toString())), null, "{\"forged\":true}")
            .andExpect(status().isForbidden());
        keyed(post(admissionPath(id)), "not-a-uuid", null).andExpect(status().isForbidden());
        keyed(post(admissionPath(id)), UUID.randomUUID().toString(), "{not json").andExpect(status().isForbidden());
        keyed(post(admissionPath(id)), UUID.randomUUID().toString(), "[]").andExpect(status().isForbidden());
        authorised(post(admissionPath(id)).header(CaseworkerApplicationDeletionController.IDEMPOTENCY_KEY,
                                                  UUID.randomUUID().toString())
                       .contentType("text/plain").content("not json"))
            .andExpect(status().isForbidden());
        keyed(post(admissionPath(id)), UUID.randomUUID().toString(),
              "x".repeat(CaseworkerApplicationDeletionController.MAX_BODY_BYTES * 4))
            .andExpect(status().isForbidden());
        authorised(get(statusPath(admitted))).andExpect(status().isForbidden());
        authorised(get(statusPath("not-a-uuid"))).andExpect(status().isForbidden());
        authorised(get(statusPath(UUID.randomUUID().toString()))).andExpect(status().isForbidden());

        assertThat(requestCount(id)).isOne();
    }

    @Test
    @DisplayName("a Delivery Manager's admission is 202 with a Location, no-store, the id and Pending only")
    void admits() throws Exception {
        String id = fixture();
        String modifiedOn = publishedModifiedOn(id);
        UUID key = UUID.randomUUID();

        MvcResult result = keyed(post(admissionPath(id)), key.toString(), body("DUPLICATE", NOTE, modifiedOn))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.status").value("Pending"))
            .andReturn();

        JsonNode answer = json.readTree(result.getResponse().getContentAsString());
        assertThat(answer.fieldNames()).toIterable().containsExactlyInAnyOrder("requestId", "status");
        String requestId = answer.get("requestId").asText();
        assertThat(result.getResponse().getHeader(HttpHeaders.LOCATION))
            .isEqualTo("/caseworker/application-deletions/" + requestId);
        assertThat(result.getResponse().getHeader(HttpHeaders.CACHE_CONTROL)).contains("no-store");
        assertThat(result.getResponse().getContentAsString()).doesNotContain("7Q4ZX");
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM deedpoll_audit_event
             WHERE case_id = ? AND action = 'CASE_DELETION_REQUESTED' AND actor_id = ?
            """, Integer.class, id, StubIdam.SUBJECT)).isOne();

        MvcResult read = authorised(get(statusPath(requestId))).andExpect(status().isOk()).andReturn();
        JsonNode detail = json.readTree(read.getResponse().getContentAsString());
        assertThat(detail.fieldNames()).toIterable().containsExactlyInAnyOrder(
            "requestId", "caseId", "reason", "status", "requestedAt", "completedAt", "failure");
        assertThat(detail.get("requestId").asText()).isEqualTo(requestId);
        assertThat(detail.get("caseId").asText()).isEqualTo(id);
        assertThat(detail.get("reason").asText()).isEqualTo("DUPLICATE");
        assertThat(detail.get("status").asText()).isEqualTo("Pending");
        assertThat(detail.get("completedAt").isNull()).isTrue();
        assertThat(detail.get("failure").isNull()).isTrue();
        assertThat(read.getResponse().getHeader(HttpHeaders.CACHE_CONTROL)).contains("no-store");
        assertThat(read.getResponse().getContentAsString())
            .doesNotContain("7Q4ZX").doesNotContain(StubIdam.SUBJECT).doesNotContain(key.toString());
    }

    @Test
    @DisplayName("a missing or malformed key, an unknown key and each refused field are 400, writing nothing")
    void validation() throws Exception {
        String id = fixture();
        String valid = body("DUPLICATE", NOTE, MODIFIED.toString());

        keyed(post(admissionPath(id)), null, valid).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.errors[0].field").value("Idempotency-Key"));
        keyed(post(admissionPath(id)), "not-a-uuid", valid).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.errors[0].field").value("Idempotency-Key"));
        String forged = "{\"reason\":\"DUPLICATE\",\"note\":\"" + NOTE + "\",\"expectedLastChangedOn\":\"" + MODIFIED
            + "\",\"permanent\":false}";
        keyed(post(admissionPath(id)), UUID.randomUUID().toString(), forged).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error")
                           .value("A deletion request takes only reason, note and expectedLastChangedOn"));
        keyed(post(admissionPath(id)), UUID.randomUUID().toString(), body("VOID", NOTE, MODIFIED.toString()))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("reason"));
        keyed(post(admissionPath(id)), UUID.randomUUID().toString(),
              body("DUPLICATE", "too short", MODIFIED.toString()))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("note"));
        keyed(post(admissionPath(id)), UUID.randomUUID().toString(), body("DUPLICATE", NOTE, "yesterday"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.errors[0].field").value("expectedLastChangedOn"));
        keyed(post(admissionPath(id)), UUID.randomUUID().toString(),
              "{\"reason\":1,\"note\":[],\"expectedLastChangedOn\":5}")
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.length()").value(3));
        keyed(post(admissionPath(id)), UUID.randomUUID().toString(), null).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.errors.length()").value(3));
        keyed(post(admissionPath(id)), UUID.randomUUID().toString(), "{not json").andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("Request body is missing or is not valid JSON"));
        keyed(post(admissionPath(id)), UUID.randomUUID().toString(), "[]").andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("Request body is missing or is not valid JSON"));
        authorised(post(admissionPath(id)).header(CaseworkerApplicationDeletionController.IDEMPOTENCY_KEY,
                                                  UUID.randomUUID().toString())
                       .contentType("text/plain").content(body("DUPLICATE", NOTE, MODIFIED.toString())))
            .andExpect(status().isUnsupportedMediaType());
        authorised(post(admissionPath(id)).header(CaseworkerApplicationDeletionController.IDEMPOTENCY_KEY,
                                                  UUID.randomUUID().toString())
                       .content(body("DUPLICATE", NOTE, MODIFIED.toString())))
            .andExpect(status().isUnsupportedMediaType());
        authorised(multipart(admissionPath(id)).param("reason", "DUPLICATE").param("note", NOTE)
                       .header(CaseworkerApplicationDeletionController.IDEMPOTENCY_KEY, UUID.randomUUID().toString()))
            .andExpect(status().isUnsupportedMediaType());
        authorised(post(admissionPath(id)).header(CaseworkerApplicationDeletionController.IDEMPOTENCY_KEY,
                                                  UUID.randomUUID().toString())
                       .contentType("application/x-www-form-urlencoded").content("reason=DUPLICATE"))
            .andExpect(status().isUnsupportedMediaType());
        keyed(post(admissionPath(id)), UUID.randomUUID().toString(),
              "{\"note\":\"" + "x".repeat(CaseworkerApplicationDeletionController.MAX_BODY_BYTES) + "\"}")
            .andExpect(status().isPayloadTooLarge());

        assertThat(requestCount(id)).isZero();
    }

    @Test
    @DisplayName("an unknown application, an unknown request and a malformed request id are 404")
    void notFound() throws Exception {
        keyed(post(admissionPath(UUID.randomUUID().toString())), UUID.randomUUID().toString(),
              body("DUPLICATE", NOTE, MODIFIED.toString())).andExpect(status().isNotFound());
        authorised(get(statusPath(UUID.randomUUID().toString()))).andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error").value("Deletion request not found"));
        authorised(get(statusPath("not-a-uuid"))).andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error").value("Deletion request not found"));
    }

    @Test
    @DisplayName("a stale confirmation is 409 with its code and words, and writes nothing")
    void stale() throws Exception {
        String id = fixture();

        MvcResult result = keyed(post(admissionPath(id)), UUID.randomUUID().toString(),
                                 body("DUPLICATE", NOTE, MODIFIED.plusSeconds(1).toString()))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("STALE_CONFIRMATION"))
            .andExpect(jsonPath("$.error").value(
                "This application has changed since you reviewed it. Review it again before deleting it."))
            .andReturn();
        assertThat(result.getResponse().getHeader(HttpHeaders.CACHE_CONTROL)).contains("no-store");
        assertThat(requestCount(id)).isZero();
    }

    @Test
    @DisplayName("the same key and body replays 202, also after the case is gone; another body or reason is 409")
    void replays() throws Exception {
        String id = fixture();
        UUID key = UUID.randomUUID();
        String original = body("DUPLICATE", NOTE, MODIFIED.toString());
        String requestId = admit(id, key, original).get("requestId").asText();

        assertThat(admit(id, key, original).get("requestId").asText()).isEqualTo(requestId);
        keyed(post(admissionPath(id)), key.toString(), body("DUPLICATE", NOTE + " again", MODIFIED.toString()))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("KEY_REUSED"));
        keyed(post(admissionPath(id)), UUID.randomUUID().toString(),
              body("CREATED_IN_ERROR", NOTE, MODIFIED.toString()))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CONFLICTING_REASON"));
        assertThat(admit(id, UUID.randomUUID(), original).get("requestId").asText())
            .as("a second key for the same reason answers the first request").isEqualTo(requestId);

        // The case is removed as finalisation removes it: DELETE only, which V49 allows. The tombstone outlives it.
        jdbc.update("DELETE FROM deedpoll_deedpoll WHERE id = ?", id);
        MvcResult replay = keyed(post(admissionPath(id)), key.toString(), original)
            .andExpect(status().isAccepted()).andReturn();
        assertThat(json.readTree(replay.getResponse().getContentAsString()).get("requestId").asText())
            .isEqualTo(requestId);
        assertThat(replay.getResponse().getHeader(HttpHeaders.LOCATION)).endsWith(requestId);
        authorised(get(statusPath(requestId))).andExpect(status().isOk())
            .andExpect(jsonPath("$.caseId").value(id));
        assertThat(requestCount(id)).isOne();
    }

    @Test
    @DisplayName("any Delivery Manager may read another's request, which still names the first")
    void anotherDeliveryManagerReads() throws Exception {
        String id = fixture();
        String requestId = admit(id, UUID.randomUUID(), body("CREATED_IN_ERROR", NOTE, MODIFIED.toString()))
            .get("requestId").asText();

        IDAM.stubUserInfo(SECOND_SUBJECT, "Second Manager", List.of(Role.DELIVERY_MANAGER.claimValue()));
        String token = IDAM.accessToken(claims -> claims.subject(SECOND_SUBJECT));
        mvc.perform(get(statusPath(requestId)).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.reason").value("CREATED_IN_ERROR"))
            .andExpect(jsonPath("$.status").value("Pending"));
        assertThat(jdbc.queryForObject("SELECT requested_by_subject FROM deedpoll_deletion_request WHERE id = ?::uuid",
                                       String.class, requestId)).isEqualTo(StubIdam.SUBJECT);
    }

    @Test
    @DisplayName("the note reaches no log line, admitted, replayed or refused")
    void noteNeverLogged(CapturedOutput output) throws Exception {
        String id = fixture();
        UUID key = UUID.randomUUID();
        admit(id, key, body("DUPLICATE", NOTE, MODIFIED.toString()));
        keyed(post(admissionPath(id)), key.toString(), body("DUPLICATE", NOTE + " changed", MODIFIED.toString()))
            .andExpect(status().isConflict());
        keyed(post(admissionPath(id)), UUID.randomUUID().toString(), body("DUPLICATE", "short 7Q4ZX", "x"))
            .andExpect(status().isBadRequest());

        assertThat(output.getAll()).doesNotContain("7Q4ZX");
    }

    private String fixture() {
        String id = UUID.randomUUID().toString();
        fixtures.add(id);
        jdbc.update("""
            INSERT INTO deedpoll_deedpoll (id, "modifiedOn", type, state, "displayReference")
            VALUES (?, ?, 'Adult'::"DeedPollType", 'Enroled', ?)
            """, id, Timestamp.valueOf(MODIFIED), "deletion-http-" + id);
        return id;
    }

    /**
     * {@code modifiedOn} exactly as the case detail publishes it, which is what Admin sends back.
     */
    private String publishedModifiedOn(String id) throws Exception {
        String detail = authorised(get("/caseworker/cases/" + id)).andExpect(status().isOk()).andReturn()
            .getResponse().getContentAsString();
        return json.readTree(detail).get("modifiedOn").asText();
    }

    private JsonNode admit(String id, UUID key, String body) throws Exception {
        return json.readTree(keyed(post(admissionPath(id)), key.toString(), body)
                                 .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString());
    }

    private String body(String reason, String note, String expectedLastChangedOn) throws Exception {
        return json.writeValueAsString(java.util.Map.of(
            "reason", reason, "note", note, "expectedLastChangedOn", expectedLastChangedOn));
    }

    private int requestCount(String caseId) {
        return jdbc.queryForObject("SELECT count(*) FROM deedpoll_deletion_request WHERE case_id = ?",
                                   Integer.class, caseId);
    }

    private static String admissionPath(String id) {
        return "/caseworker/cases/" + id + "/deletion-requests";
    }

    private static String statusPath(String requestId) {
        return CaseworkerApplicationDeletionController.STATUS_PATH + requestId;
    }

    private void asStaff(String role) {
        IDAM.stubUserInfo(StubIdam.SUBJECT, "Staff", List.of(role));
    }

    private ResultActions keyed(MockHttpServletRequestBuilder request, String key, String body) throws Exception {
        if (key != null) {
            request.header(CaseworkerApplicationDeletionController.IDEMPOTENCY_KEY, key);
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
