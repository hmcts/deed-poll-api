package uk.gov.hmcts.reform.deedpoll.domain.gazette;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import uk.gov.hmcts.reform.deedpoll.auth.StubIdam;
import uk.gov.hmcts.reform.deedpoll.config.AbstractPostgresContainerTest;
import uk.gov.hmcts.reform.deedpoll.domain.InvalidInputError;
import uk.gov.hmcts.reform.deedpoll.domain.auth.CurrentUser;
import uk.gov.hmcts.reform.deedpoll.domain.auth.Role;
import uk.gov.hmcts.reform.deedpoll.entity.AuditClock;
import uk.gov.hmcts.reform.deedpoll.repository.DeedPollRepository;

import java.io.ByteArrayInputStream;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Full security context, real PostgreSQL and real mapped spreadsheet values; no provider calls. */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(properties = {
    "deedpoll.gazette.enabled=true", "notify.api-key=synthetic-not-used",
    "notify.email-reply-to-id=10000000-0000-0000-0000-000000000001", "notify.document-retention-weeks=4",
    "notify.templates.gazette-adult=20000000-0000-0000-0000-000000000001",
    "notify.templates.gazette-minor=20000000-0000-0000-0000-000000000002",
    "notify.templates.gazette-mixed=20000000-0000-0000-0000-000000000003"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class GazettePreviewApiTest extends AbstractPostgresContainerTest {
    private static final String ROOT = "/caseworker/gazette-notices";
    private static final String PREVIEW = "/caseworker/gazette-notice-previews";
    private static final String REQUESTS = "/caseworker/gazette-notice-requests";
    private static final String SUBJECT = "gazette-preview-test";
    private static final CurrentUser DELIVERY_MANAGER =
        CurrentUser.currentUser(List.of(Role.DELIVERY_MANAGER), "Delivery Manager", SUBJECT);
    private static final StubIdam IDAM = new StubIdam();
    private static final ObjectMapper JSON = new ObjectMapper();

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

    @org.springframework.test.context.bean.override.mockito.MockitoBean
    private uk.gov.hmcts.reform.deedpoll.notify.GazetteNotify notify;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    private GazetteConfiguration configuration;
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    private uk.gov.hmcts.reform.deedpoll.notify.NotificationOutbox outbox;
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    private uk.gov.hmcts.reform.deedpoll.ccd.CcdLinkingSweep sweep;
    @Autowired
    private MockMvc mvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private GazettePreviews previews;
    @Autowired
    private GazetteRequestLedger ledger;
    @Autowired
    private GazetteSpreadsheet generator;
    @Autowired
    private DeedPollRepository cases;

    private final List<String> ownCases = new ArrayList<>();
    private Map<String, Object> oldSettings;

    @BeforeEach
    void configure() {
        IDAM.stubDefaults();
        IDAM.stubUserInfo(StubIdam.SUBJECT, "Delivery Manager", List.of(Role.DELIVERY_MANAGER.claimValue()));
        oldSettings = jdbc.queryForMap("SELECT * FROM deedpoll_gazette_settings WHERE id = 1");
        jdbc.update("""
            UPDATE deedpoll_gazette_settings SET recipient_email = 'gazette@example.com', revision = 1,
                updated_at = now(), updated_by_id = 'fixture', updated_by_name = 'Fixture'
            """);
    }

    @AfterEach
    void cleanOwnEvidence() {
        org.mockito.Mockito.verifyNoInteractions(notify);
        for (String id : ownCases) {
            jdbc.update("DELETE FROM deedpoll_gazette_reservation WHERE deed_poll_id = ?", id);
            jdbc.update("DELETE FROM deedpoll_gazette_notice_batch_case WHERE deed_poll_id = ?", id);
            jdbc.update("DELETE FROM deedpoll_gazette_request_case WHERE deed_poll_id = ?", id);
        }
        jdbc.update("DELETE FROM deedpoll_gazette_request_file WHERE batch_id IN "
            + "(SELECT id FROM deedpoll_gazette_request WHERE submitted_by_id IN (?, ?))", SUBJECT, StubIdam.SUBJECT);
        jdbc.update("DELETE FROM deedpoll_gazette_request WHERE submitted_by_id IN (?, ?)", SUBJECT, StubIdam.SUBJECT);
        jdbc.update("DELETE FROM deedpoll_gazette_notice_batch WHERE sent_by_id = ?", SUBJECT);
        for (String id : ownCases) {
            jdbc.update("DELETE FROM deedpoll_deedpoll WHERE id = ?", id);
        }
        jdbc.update("""
            UPDATE deedpoll_gazette_settings SET recipient_email = ?, revision = ?, updated_at = ?,
                updated_by_id = ?, updated_by_name = ?
            """, oldSettings.get("recipient_email"), oldSettings.get("revision"), oldSettings.get("updated_at"),
            oldSettings.get("updated_by_id"), oldSettings.get("updated_by_name"));
    }

    static java.util.stream.Stream<List<String>> refusedClaims() {
        return java.util.stream.Stream.of(List.of(), List.of("caseworker-civil"),
            List.of(Role.CASEWORKER.claimValue()), List.of(Role.JUDICIAL.claimValue()),
            List.of(Role.DEEDPOLL_ADMIN.claimValue()), List.of(Role.TEAM_LEADER.claimValue()),
            List.of(Role.TEAM_LEADER.claimValue(), Role.JUDICIAL.claimValue()));
    }

    @ParameterizedTest
    @MethodSource("refusedClaims")
    void everyOperationRefusesValidRequestsWithoutReadingOrChangingEvidence(List<String> claims) throws Exception {
        String id = ready("Adult");
        GazettePreview preview = previews.preview(DELIVERY_MANAGER, List.of(id));
        UUID key = UUID.randomUUID();
        CurrentUser verified =
            CurrentUser.currentUser(List.of(Role.DELIVERY_MANAGER), "Delivery Manager", StubIdam.SUBJECT);
        var admitted = admit(verified, key, preview, id);
        String fresh = ready("Minor");
        GazettePreview freshPreview = previews.preview(DELIVERY_MANAGER, List.of(fresh));
        final var before = evidence();
        IDAM.stubUserInfo(StubIdam.SUBJECT, "Delivery Manager", claims);
        for (MockHttpServletRequestBuilder request : routes(admitted.request().id())) {
            authorised(request).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.recipientEmail").doesNotExist())
                .andExpect(jsonPath("$.cases").doesNotExist())
                .andExpect(jsonPath("$.total").doesNotExist());
        }
        // Both a valid fresh submission and the original subject's known-key retry must be refused.
        submit(UUID.randomUUID(), freshPreview, fresh).andExpect(status().isForbidden());
        submit(key, preview, id).andExpect(status().isForbidden());
        previewHttp(fresh).andExpect(status().isForbidden());
        assertThat(evidence()).isEqualTo(before);
        org.mockito.Mockito.verifyNoInteractions(notify, outbox, sweep);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void deliveryManagerAloneOrWithJudicialPassesEveryGate(boolean judicial) throws Exception {
        String id = ready("Adult");
        GazettePreview preview = previews.preview(DELIVERY_MANAGER, List.of(id));
        UUID key = UUID.randomUUID();
        final var admitted = admit(
            CurrentUser.currentUser(List.of(Role.DELIVERY_MANAGER), "Delivery Manager", StubIdam.SUBJECT),
            key, preview, id);
        IDAM.stubUserInfo(StubIdam.SUBJECT, "Manager", judicial
            ? List.of(Role.DELIVERY_MANAGER.claimValue(), Role.JUDICIAL.claimValue())
            : List.of(Role.DELIVERY_MANAGER.claimValue()));
        authorised(get(ROOT)).andExpect(status().isOk());
        authorised(get("/caseworker/gazette-settings")).andExpect(status().isOk());
        authorised(put("/caseworker/gazette-settings").contentType("application/json")
            .content("{\"recipientEmail\":\"gazette@example.com\",\"revision\":1}"))
            .andExpect(status().isOk());
        previewHttp(ready("Minor")).andExpect(status().isOk());
        submit(key, preview, id).andExpect(status().isAccepted());
        authorised(get(REQUESTS)).andExpect(status().isOk());
        authorised(get(ROOT + "/" + admitted.request().id())).andExpect(status().isOk());
        authorised(get(ROOT + "/" + admitted.request().id() + "/cases")).andExpect(status().isOk());
        authorised(get("/caseworker/cases").param("filter", "readyForPublication").param("size", "1"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.total").isNumber());
    }

    @ParameterizedTest
    @MethodSource("refusedClaims")
    void savedReadyQueryCannotBypassTheGateButOrdinaryCaseQueriesRemainAvailable(List<String> claims)
        throws Exception {
        String id = ready("Adult");
        JsonNode saved = json(authorised(post("/caseworker/saved-queries").contentType("application/json")
            .content("{\"name\":\"Saved Gazette worklist\",\"filter\":\"readyForPublication\"}"))
            .andExpect(status().isCreated()));
        String queryId = saved.get("id").asText();
        try {
            IDAM.stubUserInfo(StubIdam.SUBJECT, "Staff", claims);
            JsonNode read = json(authorised(get("/caseworker/saved-queries/" + queryId))
                .andExpect(status().isOk()));
            authorised(get("/caseworker/cases").param("filter", read.get("filter").asText()).param("size", "1"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.total").doesNotExist());
            org.mockito.Mockito.verifyNoInteractions(notify, outbox, sweep);
            authorised(get("/caseworker/cases").param("filter", "all").param("where", "state:is:Enroled"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.cases[?(@.id == '" + id + "')]").exists());
        } finally {
            jdbc.update("DELETE FROM deedpoll_savedquery WHERE id = ?", UUID.fromString(queryId));
        }
    }

    private Map<String, List<String>> evidence() {
        Map<String, List<String>> snapshot = new java.util.LinkedHashMap<>();
        for (String table : List.of("deedpoll_gazette_settings", "deedpoll_gazette_request",
            "deedpoll_gazette_request_case", "deedpoll_gazette_request_file", "deedpoll_gazette_reservation",
            "deedpoll_gazette_notice_batch", "deedpoll_gazette_notice_batch_case", "deedpoll_notes",
            "deedpoll_supportingdocument", "deedpoll_notification", "deedpoll_deedpoll")) {
            snapshot.put(table,
                jdbc.queryForList("SELECT row_to_json(t)::text FROM " + table + " t ORDER BY 1", String.class));
        }
        return snapshot;
    }

    @Test
    void everyOperationRequiresAuthentication() throws Exception {
        for (MockHttpServletRequestBuilder request : routes(UUID.randomUUID())) {
            mvc.perform(request).andExpect(status().isUnauthorized());
        }
    }

    @Test
    void previewShowsCountsRecipientAndOldestFirstWithoutWritingEvidence() throws Exception {
        String adult = ready("AdultTransgender");
        String minor = ready("MinorTransgender");
        jdbc.update("UPDATE deedpoll_deedpoll SET \"dateEnroled\" = '2025-01-01' WHERE id = ?", minor);
        JsonNode response = json(authorised(post(PREVIEW).contentType("application/json")
            .content(JSON.writeValueAsString(Map.of("caseIds", List.of(adult, minor)))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.cases[0].caseId").value(minor))
            .andExpect(jsonPath("$.adultCount").value(1)).andExpect(jsonPath("$.minorCount").value(1)));
        assertThat(response.get("recipientEmail").asText()).isEqualTo("gazette@example.com");
        assertThat(response.get("recipientRevision").asInt()).isOne();
        assertThat(LocalDateTime.parse(response.get("expiresAt").asText()))
            .isBetween(AuditClock.now().plusMinutes(29), AuditClock.now().plusMinutes(30));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM deedpoll_notes WHERE \"deedPollId\" IN (?, ?)",
            Integer.class, adult, minor)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM deedpoll_gazette_reservation WHERE deed_poll_id IN (?, ?)",
            Integer.class, adult, minor)).isZero();
        assertThat(response.fieldNames()).toIterable().containsExactlyInAnyOrder("recipientEmail", "recipientRevision",
            "caseCount", "adultCount", "minorCount", "cases", "selectionFingerprint", "expiresAt");
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"caseIds\":[]}", "{\"caseIds\":[null]}", "{\"caseIds\":[1]}",
        "{\"caseIds\":[\"same\",\"same\"]}", "{\"caseIds\":[\"case\"],\"recipientEmail\":\"wrong@example.com\"}"})
    void previewRefusesMalformedSelections(String body) throws Exception {
        authorised(post(PREVIEW).contentType("application/json").content(body)).andExpect(status().isBadRequest());
    }

    @Test
    void selectionCapAndUnknownOrUnsupportedCasesAreWholeRequestRefusals() throws Exception {
        List<String> tooMany = java.util.stream.IntStream.range(0, 101).mapToObj(Integer::toString).toList();
        authorised(post(PREVIEW).contentType("application/json")
            .content(JSON.writeValueAsString(Map.of("caseIds", tooMany)))).andExpect(status().isBadRequest());
        previewHttp("missing").andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("CASE_NOT_READY"))
            .andExpect(jsonPath("$.conflicts[0].reason").value("NOT_FOUND"));
        for (String type : List.of("Unknown", "DeedOfRelinquishment")) {
            previewHttp(ready(type)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CASE_TYPE_UNSUPPORTED"));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"newForename", "newMiddleName", "newSurname", "oldForename", "oldMiddleName", "oldSurname",
        "nationalitySectionOther", "applicantBuildingNoName", "applicantAddress1", "applicantAddress2",
        "applicantTownCity", "applicantPostcode", "representativeName", "representativeCompany",
        "representativeBuildingNoName", "representativeAddress1", "representativeAddress2", "representativeTownCity",
        "representativePostcode", "applicantEmail", "displayReference"})
    void changingAnyMappedTextAfterPreviewRefusesAdmission(String field) {
        String id = ready("Adult");
        GazettePreview preview = previews.preview(DELIVERY_MANAGER, List.of(id));
        jdbc.update("UPDATE deedpoll_deedpoll SET \"" + field + "\" = 'changed' WHERE id = ?", id);
        assertThatThrownBy(() -> admit(DELIVERY_MANAGER, UUID.randomUUID(), preview, id))
            .isInstanceOfSatisfying(GazetteRefusal.class,
                error -> assertThat(error.code()).isEqualTo(GazetteRefusal.Code.SELECTION_CHANGED));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM deedpoll_gazette_reservation WHERE deed_poll_id = ?",
            Integer.class, id)).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"dateOfApplication\" = '2025-02-03'", "\"dateEnroled\" = '2025-02-03'",
        "type = 'Minor'", "\"maritalStatus\" = 'Single'", "nationality = 'BritishCitizen'",
        "\"nationalitySection\" = 'Section1'"})
    void changingMappedDatesAndEnumsRefusesAdmission(String assignment) {
        String id = ready("Adult");
        GazettePreview preview = previews.preview(DELIVERY_MANAGER, List.of(id));
        jdbc.update("UPDATE deedpoll_deedpoll SET " + assignment + " WHERE id = ?", id);
        assertThatThrownBy(() -> admit(DELIVERY_MANAGER, UUID.randomUUID(), preview, id))
            .isInstanceOfSatisfying(GazetteRefusal.class,
                error -> assertThat(error.code()).isEqualTo(GazetteRefusal.Code.SELECTION_CHANGED));
    }

    @Test
    void canonicalSelectionAndUnmappedChangesPreserveConfirmation() throws Exception {
        String adult = ready("Adult");
        String minor = ready("Minor");
        GazettePreview preview = previews.preview(DELIVERY_MANAGER, List.of(adult, minor));
        jdbc.update("UPDATE deedpoll_deedpoll SET \"modifiedBy\" = 'Another editor' WHERE id = ?", adult);
        // Minor applicant address is not among the supplied minor template's visible columns.
        jdbc.update("UPDATE deedpoll_deedpoll SET \"applicantAddress1\" = 'Not exported' WHERE id = ?", minor);
        var admitted = admit(DELIVERY_MANAGER, UUID.randomUUID(), preview, minor, adult);
        assertThat(admitted.request().configuration().templateId().toString())
            .isEqualTo("20000000-0000-0000-0000-000000000003");
        assertThat(admitted.inputs()).hasSize(2);
        jdbc.update("UPDATE deedpoll_deedpoll SET \"displayReference\" = 'Changed later' WHERE id = ?", adult);
        authorised(get(ROOT + "/" + admitted.request().id() + "/cases"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(2));
        assertThat(json(authorised(get(ROOT + "/" + admitted.request().id() + "/cases"))).toString())
            .contains("reference-" + adult).doesNotContain("Changed later");
    }

    @Test
    void requestPagingHasStableOrderStateFilterAndBounds() throws Exception {
        String first = ready("Adult");
        String second = ready("Minor");
        var one = admit(DELIVERY_MANAGER, UUID.randomUUID(), previews.preview(DELIVERY_MANAGER, List.of(first)), first);
        var two = admit(DELIVERY_MANAGER, UUID.randomUUID(),
            previews.preview(DELIVERY_MANAGER, List.of(second)), second);
        jdbc.update("UPDATE deedpoll_gazette_request SET submitted_at = '2026-01-01' WHERE id IN (?, ?)",
            one.request().id(), two.request().id());
        String higher = java.util.stream.Stream.of(one.request().id().toString(), two.request().id().toString())
            .sorted().toList().getLast();
        authorised(get(REQUESTS).param("size", "1")).andExpect(status().isOk())
            .andExpect(jsonPath("$.requests[0].batchId").value(higher)).andExpect(jsonPath("$.totalPages").value(2));
        authorised(get(REQUESTS).param("page", "3").param("size", "1")).andExpect(status().isOk())
            .andExpect(jsonPath("$.requests.length()").value(0)).andExpect(jsonPath("$.total").value(2));
        ledger.preparationFailed(one.claim());
        authorised(get(REQUESTS).param("state", "Preparing")).andExpect(status().isOk())
            .andExpect(jsonPath("$.total").value(1))
            .andExpect(jsonPath("$.requests[0].batchId").value(two.request().id().toString()));
    }

    @Test
    void unconfiguredRecipientIsADeclaredNoAdmissionRefusal() throws Exception {
        String id = ready("Adult");
        jdbc.update("""
            UPDATE deedpoll_gazette_settings SET recipient_email = null, revision = 0,
                updated_at = null, updated_by_id = null, updated_by_name = null
            """);
        previewHttp(id).andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("RECIPIENT_NOT_CONFIGURED"));
    }

    @Test
    void expiryReadinessAndRecipientRevisionAreRecheckedBeforeAdmission() {
        String id = ready("Adult");
        GazettePreview preview = previews.preview(DELIVERY_MANAGER, List.of(id));
        var input = GazetteInput.from(cases.findById(id).orElseThrow());
        String expired = GazetteConfirmation.issue(List.of(input), AuditClock.now().minusSeconds(1));
        assertThatThrownBy(() -> ledger.admit(DELIVERY_MANAGER, UUID.randomUUID(), List.of(id), expired, 1))
            .isInstanceOfSatisfying(GazetteRefusal.class,
                error -> assertThat(error.code()).isEqualTo(GazetteRefusal.Code.CONFIRMATION_EXPIRED));
        jdbc.update("UPDATE deedpoll_deedpoll SET \"publishToLondonGazette\" = false WHERE id = ?", id);
        assertThatThrownBy(() -> admit(DELIVERY_MANAGER, UUID.randomUUID(), preview, id))
            .isInstanceOfSatisfying(GazetteRefusal.class,
                error -> assertThat(error.code()).isEqualTo(GazetteRefusal.Code.CASE_NOT_READY));
        jdbc.update("UPDATE deedpoll_deedpoll SET \"publishToLondonGazette\" = true WHERE id = ?", id);
        jdbc.update("UPDATE deedpoll_gazette_settings SET revision = 3");
        assertThatThrownBy(() -> admit(DELIVERY_MANAGER, UUID.randomUUID(), preview, id))
            .isInstanceOfSatisfying(GazetteRefusal.class,
                error -> assertThat(error.code()).isEqualTo(GazetteRefusal.Code.RECIPIENT_CHANGED));
    }

    @Test
    void detachedInputsGenerateOriginalValuesAndRepeatedKeysNeverReturnInputs() throws Exception {
        String id = ready("Adult");
        jdbc.update("UPDATE deedpoll_deedpoll SET \"newForename\" = 'Original' WHERE id = ?", id);
        GazettePreview preview = previews.preview(DELIVERY_MANAGER, List.of(id));
        UUID key = UUID.randomUUID();
        var admitted = admit(DELIVERY_MANAGER, key, preview, id);
        // Direct fixture SQL emulates a later change after the short lock; ordinary PATCH is already guarded.
        jdbc.update("UPDATE deedpoll_deedpoll SET \"newForename\" = 'Changed' WHERE id = ?", id);
        assertThatThrownBy(() -> admitted.inputs().getFirst().values().set(4, "Mutated"))
            .isInstanceOf(UnsupportedOperationException.class);
        var generated = generator.generateInputs(admitted.inputs()).getFirst();
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(generated.content()))) {
            assertThat(workbook.getSheetAt(0).getRow(1).getCell(4).getStringCellValue()).isEqualTo("Original");
        }
        var repeat = ledger.admit(DELIVERY_MANAGER, key, List.of(id), preview.selectionFingerprint(), 1);
        assertThat(repeat.claim()).isNull();
        assertThat(repeat.inputs()).isEmpty();
        previewHttp(id).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CASE_RESERVED"));
    }

    @Test
    void disabledDeliveryCannotAdmitAFreshRequest() throws Exception {
        String id = ready("Adult");
        GazettePreview preview = previews.preview(DELIVERY_MANAGER, List.of(id));
        org.mockito.Mockito.doThrow(new GazetteRefusal(GazetteRefusal.Code.GAZETTE_UNAVAILABLE))
            .when(configuration).forGroups(1, 0);
        submit(UUID.randomUUID(), preview, id).andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.code").value("GAZETTE_UNAVAILABLE"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM deedpoll_gazette_request_case WHERE deed_poll_id = ?",
            Integer.class, id)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM deedpoll_notes WHERE \"deedPollId\" = ?",
            Integer.class, id)).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"revisionText", "revisionDecimal", "tokenNumber", "missingRevision",
        "unknown", "duplicates", "tokenMalformed"})
    void submissionStrictlyValidatesItsOwnBody(String mutation) throws Exception {
        String id = ready("Adult");
        GazettePreview preview = previews.preview(DELIVERY_MANAGER, List.of(id));
        Map<String, Object> body = new java.util.HashMap<>(Map.of("caseIds", List.of(id),
            "selectionFingerprint", preview.selectionFingerprint(), "recipientRevision", 1));
        switch (mutation) {
            case "revisionText" -> body.put("recipientRevision", "1");
            case "revisionDecimal" -> body.put("recipientRevision", 1.5);
            case "tokenNumber" -> body.put("selectionFingerprint", 123);
            case "missingRevision" -> body.remove("recipientRevision");
            case "unknown" -> body.put("recipientEmail", "untrusted@example.com");
            case "duplicates" -> body.put("caseIds", List.of(id, id));
            case "tokenMalformed" -> body.put("selectionFingerprint", "invalid");
            default -> throw new AssertionError("Missing mutation");
        }
        authorised(post(ROOT).header("Idempotency-Key", UUID.randomUUID()).contentType("application/json")
            .content(JSON.writeValueAsString(body))).andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM deedpoll_gazette_request_case WHERE deed_poll_id = ?",
            Integer.class, id)).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"1-1-1-1-1", "invalid", "", "missing"})
    void submissionRequiresACanonicalUuidHeader(String header) throws Exception {
        String id = ready("Adult");
        GazettePreview preview = previews.preview(DELIVERY_MANAGER, List.of(id));
        var request = post(ROOT).contentType("application/json").content(JSON.writeValueAsString(Map.of(
            "caseIds", List.of(id), "selectionFingerprint", preview.selectionFingerprint(), "recipientRevision", 1)));
        if (!header.equals("missing")) {
            request.header("Idempotency-Key", header);
        }
        authorised(request).andExpect(status().isBadRequest());
    }

    @Test
    void sameKeyHttpRetryReturnsStatusAndLocationBeforeCurrentConfigurationChecks() throws Exception {
        String id = ready("Minor");
        UUID key = UUID.randomUUID();
        GazettePreview preview = previews.preview(DELIVERY_MANAGER, List.of(id));
        CurrentUser verified =
            CurrentUser.currentUser(List.of(Role.DELIVERY_MANAGER), "Delivery Manager", StubIdam.SUBJECT);
        var admitted = admit(verified, key, preview, id);
        jdbc.update("UPDATE deedpoll_gazette_settings SET recipient_email = 'new@example.com', revision = 2");
        JsonNode pending = json(submit(key, preview, id).andExpect(status().isAccepted())
            .andExpect(jsonPath("$.recipientEmail").value("gazette@example.com")));
        assertThat(pending.get("batchId").asText()).isEqualTo(admitted.request().id().toString());
        LocalDateTime afterExpiry = AuditClock.now().plusMinutes(31);
        try (var clock = org.mockito.Mockito.mockStatic(AuditClock.class)) {
            clock.when(AuditClock::now).thenReturn(afterExpiry);
            submit(key, preview, id).andExpect(status().isAccepted());
            assertThat(ledger.admit(verified, key, List.of(id), preview.selectionFingerprint(), 1).claim()).isNull();
        }
        ledger.preparationFailed(admitted.claim());
        var terminal = submit(key, preview, id).andExpect(status().isOk())
            .andExpect(jsonPath("$.failureCode").value("PREPARATION_FAILED"));
        assertThat(terminal.andReturn().getResponse().getHeader("Location"))
            .isEqualTo(ROOT + "/" + admitted.request().id());
        String body = terminal.andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain(StubIdam.SUBJECT, key.toString(), admitted.claim().owner().toString(),
            "providerNotificationId", "templateId", "payloadHash", "selectionFingerprint");
        assertThat(json(terminal).fieldNames()).toIterable().containsExactlyInAnyOrder(
            "batchId", "state", "submittedAt",
            "submittedBy", "recipientEmail", "recipientRevision", "caseCount", "adultCount", "minorCount", "sentAt",
            "failureCode");
        authorised(get(REQUESTS).param("state", "NotSent")).andExpect(status().isOk())
            .andExpect(jsonPath("$.requests[0].batchId").value(admitted.request().id().toString()));
    }

    @Test
    void changedSubjectAndPayloadAreTypedConflicts() throws Exception {
        String id = ready("Adult");
        GazettePreview preview = previews.preview(DELIVERY_MANAGER, List.of(id));
        UUID key = UUID.randomUUID();
        admit(DELIVERY_MANAGER, key, preview, id);
        submit(key, preview, id).andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("REQUEST_KEY_REUSED"));
        assertThatThrownBy(() -> ledger.repeat(DELIVERY_MANAGER, key, List.of(id), "different", 1))
            .isInstanceOfSatisfying(GazetteRefusal.class,
                error -> assertThat(error.code()).isEqualTo(GazetteRefusal.Code.REQUEST_KEY_REUSED));
    }

    @Test
    void legacyDetailsKeepUnknownFactsNullAndMembershipPagesAreCompleteAndFrozen() throws Exception {
        UUID batch = UUID.randomUUID();
        jdbc.update("INSERT INTO deedpoll_gazette_notice_batch VALUES (?, now(), ?, 'Legacy Sender', ?, 103)",
            batch, SUBJECT, UUID.randomUUID());
        for (int index = 0; index < 103; index++) {
            String id = ready("Adult");
            jdbc.update("INSERT INTO deedpoll_gazette_notice_batch_case VALUES (?, ?, ?)",
                batch, id, String.format("frozen-%03d", index));
        }
        authorised(get(ROOT + "/" + batch)).andExpect(status().isOk())
            .andExpect(jsonPath("$.state").value("Sent")).andExpect(jsonPath("$.submittedAt").isEmpty())
            .andExpect(jsonPath("$.submittedBy").isEmpty()).andExpect(jsonPath("$.recipientEmail").isEmpty())
            .andExpect(jsonPath("$.adultCount").isEmpty()).andExpect(jsonPath("$.caseCount").value(103));
        authorised(get(ROOT + "/" + batch + "/cases").param("size", "1000"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.size").value(100))
            .andExpect(jsonPath("$.cases.length()").value(100)).andExpect(jsonPath("$.totalPages").value(2));
        authorised(get(ROOT + "/" + batch + "/cases").param("size", "100").param("page", "2"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.cases.length()").value(3))
            .andExpect(jsonPath("$.cases[0].referenceNumber").value("frozen-100"));
        authorised(get(REQUESTS)).andExpect(status().isOk()).andExpect(jsonPath("$.total").value(0));
    }

    @Test
    void missingBatchesAndInvalidPagingOrStateAreRefused() throws Exception {
        authorised(get(ROOT + "/" + UUID.randomUUID())).andExpect(status().isNotFound());
        authorised(get(ROOT + "/" + UUID.randomUUID() + "/cases")).andExpect(status().isNotFound());
        authorised(get(REQUESTS).param("state", "unknown")).andExpect(status().isBadRequest());
        authorised(get(REQUESTS).param("state", "")).andExpect(status().isBadRequest());
        authorised(get(REQUESTS).param("page", "0")).andExpect(status().isBadRequest());
        authorised(get(REQUESTS).param("size", "many")).andExpect(status().isBadRequest());
    }

    @Test
    void malformedAndFutureTokensAreBadInput() {
        assertThatThrownBy(() -> GazetteConfirmation.expiry("invalid")).isInstanceOf(InvalidInputError.class);
        String future = GazetteConfirmation.issue(List.of(), AuditClock.now().plusMinutes(31));
        assertThatThrownBy(() -> GazetteConfirmation.expiry(future)).isInstanceOf(InvalidInputError.class);
    }

    private List<MockHttpServletRequestBuilder> routes(UUID id) {
        return List.of(post(PREVIEW).contentType("application/json").content("{\"caseIds\":[\"case\"]}"),
            post(ROOT).header("Idempotency-Key", UUID.randomUUID()).contentType("application/json")
                .content("{\"caseIds\":[\"case\"],\"selectionFingerprint\":\"token\",\"recipientRevision\":1}"),
            get(REQUESTS), get(ROOT + "/" + id), get(ROOT + "/" + id + "/cases"),
            get(ROOT), get("/caseworker/gazette-settings"),
            put("/caseworker/gazette-settings").contentType("application/json")
                .content("{\"recipientEmail\":\"changed@example.com\",\"revision\":1}"),
            get("/caseworker/cases").param("filter", "readyForPublication").param("size", "1"));
    }

    private GazetteRequestLedger.Reservation admit(CurrentUser caller, UUID key,
                                                  GazettePreview preview, String... ids) {
        return ledger.admit(caller, key, List.of(ids), preview.selectionFingerprint(), preview.recipientRevision());
    }

    private ResultActions submit(UUID key, GazettePreview preview, String... ids) throws Exception {
        return authorised(post(ROOT).header("Idempotency-Key", key).contentType("application/json")
            .content(JSON.writeValueAsString(Map.of("caseIds", List.of(ids), "selectionFingerprint",
                preview.selectionFingerprint(), "recipientRevision", preview.recipientRevision()))));
    }

    private ResultActions previewHttp(String id) throws Exception {
        return authorised(post(PREVIEW).contentType("application/json")
            .content(JSON.writeValueAsString(Map.of("caseIds", List.of(id)))));
    }

    private ResultActions authorised(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request.header(HttpHeaders.AUTHORIZATION, "Bearer " + IDAM.accessToken()));
    }

    private static JsonNode json(ResultActions result) throws Exception {
        return JSON.readTree(result.andReturn().getResponse().getContentAsByteArray());
    }

    private String ready(String type) {
        String id = UUID.randomUUID().toString();
        ownCases.add(id);
        jdbc.update("""
            INSERT INTO deedpoll_deedpoll (id, "modifiedOn", type, state, "dateEnroled", "displayReference")
            VALUES (?, now(), ?::"DeedPollType", 'Enroled', '2026-01-01', ?)
            """, id, type, "reference-" + id);
        return id;
    }
}
