package uk.gov.hmcts.reform.deedpoll.openapi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AssignableTypeFilter;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.web.servlet.MockMvc;
import uk.gov.hmcts.reform.deedpoll.api.KeyedRequestBody;
import uk.gov.hmcts.reform.deedpoll.api.caseworker.controller.CaseworkerEmailController;
import uk.gov.hmcts.reform.deedpoll.config.AbstractPostgresContainerTest;
import uk.gov.hmcts.reform.deedpoll.config.StaffArea;
import uk.gov.hmcts.reform.deedpoll.domain.deedpoll.CasePaging;
import uk.gov.hmcts.reform.deedpoll.domain.deedpoll.CaseSearch;
import uk.gov.hmcts.reform.deedpoll.domain.deedpoll.CaseSort;
import uk.gov.hmcts.reform.deedpoll.domain.deedpoll.DeedPollSchemas;
import uk.gov.hmcts.reform.deedpoll.domain.deedpoll.ListFilters;
import uk.gov.hmcts.reform.deedpoll.domain.deedpoll.QueryOperator;
import uk.gov.hmcts.reform.deedpoll.domain.documents.UploadPolicy;
import uk.gov.hmcts.reform.deedpoll.domain.documents.preview.PreviewFailure;
import uk.gov.hmcts.reform.deedpoll.domain.documents.preview.PreviewFormat;
import uk.gov.hmcts.reform.deedpoll.domain.documents.preview.PreviewProperties;
import uk.gov.hmcts.reform.deedpoll.domain.referrals.ReferralReason;
import uk.gov.hmcts.reform.deedpoll.domain.referrals.ReferralSchemas;
import uk.gov.hmcts.reform.deedpoll.domain.witnesses.WitnessSchemas;
import uk.gov.hmcts.reform.deedpoll.entity.enums.ConfirmationOutcome;
import uk.gov.hmcts.reform.deedpoll.entity.enums.MasterDecision;
import uk.gov.hmcts.reform.deedpoll.notify.ApplicationRejectedPersonalisation;
import uk.gov.hmcts.reform.deedpoll.notify.EmailSlot;
import uk.gov.hmcts.reform.deedpoll.notify.EmailTemplate;

import java.io.OutputStream;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Built-in feature which saves service's swagger specs in temporary directory.
 * Each CI run on master should automatically save and upload (if updated) documentation.
 *
 * <p>The default path below is the contract with the pipeline: the HMCTS Jenkins library reads
 * {@code /tmp/openapi-specs.json} after the integration stage and publishes it to {@code hmcts/cnp-api-docs}.
 * It is the template's own wiring, kept verbatim -- {@code pcs-api} keeps the same file -- so that it works when
 * the pipeline is onboarded rather than needing to be discovered then. Local concurrent runs may set
 * {@code OPENAPI_OUTPUT_PATH} to an isolated artifact in an existing directory.
 *
 * <p><strong>What the spec covers is asserted, not assumed.</strong> The template's version only checked that
 * the document was served, which stays green while it describes nothing: {@code springdoc.packagesToScan} named
 * the template's own controller package for the whole of tasks 13 to 15, so a spec claiming to describe this
 * service omitted all twelve caseworker routes and said so in valid JSON. A published document that is silently
 * empty is worse than no document, because a consumer trusts it.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
class OpenAPIPublisherTest extends AbstractPostgresContainerTest {

    /** Pipeline default, with an optional isolated local output path. */
    private static final Path PUBLISHED_SPEC = Paths.get(
        System.getenv().getOrDefault("OPENAPI_OUTPUT_PATH", "/tmp/openapi-specs.json"));

    /**
     * Every route the caseworker surface has, as plan 01's tasks 13 to 15 enumerate them, plus the witness routes
     * added afterwards, the saved-query routes after those, the referral routes after those, the closure the
     * referral state machine added, the three email routes the Notify send path added, and the referral's
     * confirmation sub-resource that records the applicant's answer to the email ④ sends, and plan D2's assignment
     * sub-resource, through which a caseworker assigns a referral to a Master. Listed rather than
     * counted, because a count agrees with a spec that documents the right number of the wrong things.
     */
    private static final List<String> CASEWORKER_PATHS = List.of(
        "/caseworker/cases",
        "/caseworker/cases/{id}",
        "/caseworker/cases/field-rules",
        "/caseworker/document-types",
        "/caseworker/cases/{id}/notes",
        "/caseworker/cases/{id}/notes/{noteId}",
        "/caseworker/cases/{id}/witnesses",
        "/caseworker/cases/{id}/witnesses/{witnessId}",
        "/caseworker/cases/{id}/documents",
        "/caseworker/cases/{id}/documents/{documentId}/file",
        "/caseworker/saved-queries",
        "/caseworker/saved-queries/{id}",
        "/caseworker/saved-queries/{id}/selection",
        "/caseworker/cases/{id}/referrals",
        "/caseworker/cases/{id}/referrals/{referralId}",
        "/caseworker/cases/{id}/referrals/{referralId}/withdrawal",
        "/caseworker/cases/{id}/referrals/{referralId}/closure",
        "/caseworker/cases/{id}/referrals/{referralId}/confirmation",
        "/caseworker/cases/{id}/referrals/{referralId}/assignment",
        "/caseworker/cases/{id}/emails",
        "/caseworker/cases/{id}/emails/{template}",
        "/caseworker/cases/{id}/emails/{template}/preview"
    );

    /**
     * Every route the judicial surface has, and deliberately <strong>not</strong> folded into
     * {@link #CASEWORKER_PATHS}.
     *
     * <p>Asserted the same way and counted separately, because they are a different surface for a different
     * audience: adding them to the caseworker list would make the caseworker count -- a number the README, the
     * resume notes and a package comment all state -- misreport what a caseworker can do. Listed rather than
     * counted for the reason the caseworker paths are, and listed <em>at all</em> so that a judicial route which
     * silently vanished from the spec fails the build exactly as a caseworker one would. {@code deed-poll-admin}
     * generates its client from this document, so an unpublished route is an unbuildable judicial screen.
     */
    private static final List<String> JUDICIAL_PATHS = List.of(
        "/judicial/referrals",
        "/judicial/referrals/mine",
        "/judicial/referrals/{referralId}",
        "/judicial/referrals/{referralId}/claim",
        "/judicial/referrals/{referralId}/direction",
        "/judicial/referrals/{referralId}/direction/withdraw",
        "/judicial/referrals/{referralId}/documents/{documentId}/file"
    );

    /**
     * The privileged view of the CCD creation attempts and its two repairs (plan 02b, task 4), counted apart from
     * {@link #CASEWORKER_PATHS} for the reason the judicial routes are: the caseworker count is a number the README
     * and the resume notes state, and these three operations require {@code REPAIR_CCD}.
     */
    private static final List<String> CCD_ATTEMPT_PATHS = List.of(
        "/caseworker/ccd-attempts",
        "/caseworker/ccd-attempts/{attemptId}/reconciliation",
        "/caseworker/ccd-attempts/{attemptId}/retry"
    );

    /**
     * The two document previews (2026-10-23), one per surface, counted apart from both surfaces' lists for the reason
     * the CCD attempt operations are: the caseworker and judicial counts are numbers the README and the resume notes
     * state, and these are a read-only rendition beside a download each surface already had.
     */
    private static final List<String> PREVIEW_PATHS = List.of(
        "/caseworker/cases/{id}/documents/{documentId}/preview",
        "/judicial/referrals/{referralId}/documents/{documentId}/preview"
    );

    /** Delivery Manager-only publication history, whose single GET is asserted separately. */
    private static final String GAZETTE_HISTORY_PATH = "/caseworker/gazette-notices";

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    private MockMvc mvc;

    @Test
    void publishesSameOriginServersForHttpAndHttpsDocuments() throws Exception {
        for (boolean secure : List.of(false, true)) {
            JsonNode spec = JSON.readTree(mvc.perform(get("/v3/api-docs").secure(secure))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());

            assertSameOriginServers(spec.path("servers"));
            // Check only server declarations, not schema properties that happen to be named "servers".
            for (JsonNode path : spec.path("paths")) {
                if (path.has("servers")) {
                    assertSameOriginServers(path.get("servers"));
                }
                for (String method : List.of("get", "put", "post", "delete", "options", "head", "patch", "trace")) {
                    JsonNode operation = path.path(method);
                    if (operation.has("servers")) {
                        assertSameOriginServers(operation.get("servers"));
                    }
                }
            }
            assertThat(spec.at("/components/securitySchemes/bearerAuth/scheme").asText()).isEqualTo("bearer");
            assertThat(spec.at("/security/0/bearerAuth").isArray()).isTrue();
        }
    }

    private static void assertSameOriginServers(JsonNode servers) {
        assertThat(servers.isArray()).isTrue();
        assertThat(servers.size()).isPositive();
        for (JsonNode server : servers) {
            assertThat(server.path("url").asText()).isEqualTo("/");
            assertThat(server.has("variables")).isFalse();
        }
    }

    @Test
    void documentsDeliveryManagerGazetteHistoryWithoutPrivateIdentities() throws Exception {
        JsonNode spec = JSON.readTree(mvc.perform(get("/v3/api-docs"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
        JsonNode path = spec.get("paths").get(GAZETTE_HISTORY_PATH);
        assertThat(path.fieldNames()).toIterable().containsExactlyInAnyOrder("get", "post");
        assertThat(path.at("/get/description").asText()).contains("caseworker-deedpoll-delivery-manager");
        assertThat(path.at("/get/responses/200/content/*~1*/schema/$ref").asText())
            .isEqualTo("#/components/schemas/GazetteNoticeHistoryResponse");
        assertThat(spec.at("/components/schemas/GazetteNoticeHistoryResponse/properties").fieldNames())
            .toIterable().containsExactlyInAnyOrder("batches", "page", "size", "total", "totalPages");
        assertThat(spec.at("/components/schemas/GazetteNoticeBatchResponse/properties").fieldNames())
            .toIterable().containsExactlyInAnyOrder(
                "batchId", "sentAt", "sentBy", "caseCount", "referenceNumbers", "referencesTruncated");
    }

    @Test
    void documentsGazettePreviewAndStatusWithoutPublishingInternalClaims() throws Exception {
        JsonNode spec = JSON.readTree(mvc.perform(get("/v3/api-docs"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
        for (String name : List.of("PreviewGazetteRequest", "SubmitGazetteRequest")) {
            JsonNode body = spec.at("/components/schemas/" + name);
            assertThat(body.get("additionalProperties").asBoolean()).isFalse();
            assertThat(body.at("/properties/caseIds/minItems").asInt()).isOne();
            assertThat(body.at("/properties/caseIds/maxItems").asInt()).isEqualTo(100);
            assertThat(body.at("/properties/caseIds/uniqueItems").asBoolean()).isTrue();
        }
        assertThat(spec.at("/components/schemas/SubmitGazetteRequest/properties").fieldNames()).toIterable()
            .containsExactlyInAnyOrder("caseIds", "selectionFingerprint", "recipientRevision");
        assertThat(spec.at("/components/schemas/GazetteStatusResponse/properties").fieldNames()).toIterable()
            .containsExactlyInAnyOrder("batchId", "state", "submittedAt", "submittedBy", "recipientEmail",
                "recipientRevision", "caseCount", "adultCount", "minorCount", "sentAt", "failureCode");
        assertThat(spec.at("/components/schemas/GazettePreviewResponse/properties").fieldNames()).toIterable()
            .containsExactlyInAnyOrder("recipientEmail", "recipientRevision", "caseCount", "adultCount", "minorCount",
                "cases", "selectionFingerprint", "expiresAt");
        assertThat(spec.at("/components/schemas/GazetteError/properties").fieldNames()).toIterable()
            .containsExactlyInAnyOrder("error", "code", "conflicts");
        JsonNode send = spec.get("paths").get(GAZETTE_HISTORY_PATH).get("post");
        assertThat(send.at("/description").asText()).contains("Sending is disabled by default");
        for (String status : List.of("200", "202", "409", "503")) {
            assertThat(send.at("/responses/" + status + "/content").isMissingNode()).isFalse();
        }
        assertThat(send.get("parameters").toString()).contains("Idempotency-Key", "required");
        for (String privateType : List.of("GazetteInput", "GazetteRequest", "Claim", "Reservation")) {
            assertThat(spec.at("/components/schemas/" + privateType).isMissingNode()).isTrue();
        }
    }

    @Test
    void documentsGazetteSettingsAndTheStaleEditContract() throws Exception {
        JsonNode spec = JSON.readTree(mvc.perform(get("/v3/api-docs"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
        JsonNode path = spec.get("paths").get("/caseworker/gazette-settings");
        assertThat(path.fieldNames()).toIterable().containsExactlyInAnyOrder("get", "put");
        assertThat(path.at("/put/description").asText())
            .contains("caseworker-deedpoll-delivery-manager", "409", "No email");
        assertThat(spec.at("/components/schemas/GazetteSettingsResponse/properties").fieldNames()).toIterable()
            .containsExactlyInAnyOrder("recipientEmail", "revision", "updatedAt", "updatedBy");
        JsonNode body = spec.at("/components/schemas/UpdateGazetteSettingsRequest");
        assertThat(body.get("required").toString()).contains("recipientEmail", "revision");
        assertThat(body.get("additionalProperties").asBoolean()).isFalse();
        assertThat(body.at("/properties/recipientEmail/maxLength").asInt()).isEqualTo(254);
    }

    @Test
    void documentsProcessSettingsTheirBoundsAndTheirRefusal() throws Exception {
        JsonNode spec = JSON.readTree(mvc.perform(get("/v3/api-docs"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
        JsonNode path = spec.get("paths").get("/caseworker/process-settings");
        assertThat(path.fieldNames()).toIterable().containsExactlyInAnyOrder("get", "put");
        assertThat(path.at("/get/operationId").asText()).isEqualTo("getProcessSettings");
        assertThat(path.at("/put/operationId").asText()).isEqualTo("updateProcessSettings");
        for (String method : List.of("get", "put")) {
            assertThat(path.at("/" + method + "/description").asText())
                .contains("caseworker-deedpoll-delivery-manager", "MANAGE_PROCESS_CONFIG");
            assertThat(path.at("/" + method + "/responses/403").isMissingNode()).as(method).isFalse();
        }
        assertThat(path.at("/put/description").asText())
            .contains("409", "processing window", "optional for one release");
        assertThat(spec.at("/components/schemas/ProcessSettingsResponse/properties").fieldNames()).toIterable()
            .containsExactlyInAnyOrder("referralClaimExpiryMinutes", "reminderIntervalHours", "windowCap",
                "morningWindowStart", "morningWindowEnd", "afternoonWindowStart", "afternoonWindowEnd", "revision",
                "updatedAt", "updatedBy");
        JsonNode body = spec.at("/components/schemas/UpdateProcessSettingsRequest");
        assertThat(body.get("required")).extracting(JsonNode::asText)
            .containsExactlyInAnyOrder("referralClaimExpiryMinutes", "revision");
        assertThat(body.get("required").toString()).contains("referralClaimExpiryMinutes", "revision");
        assertThat(body.get("additionalProperties").asBoolean()).isFalse();
        JsonNode minutes = body.at("/properties/referralClaimExpiryMinutes");
        assertThat(minutes.get("minimum").asInt()).isEqualTo(1440);
        assertThat(minutes.get("maximum").asInt()).isEqualTo(43200);
        assertThat(minutes.get("format").asText()).isEqualTo("int32");
        assertThat(body.at("/properties/reminderIntervalHours/minimum").asInt()).isEqualTo(1);
        assertThat(body.at("/properties/reminderIntervalHours/maximum").asInt()).isEqualTo(168);
        assertThat(body.at("/properties/windowCap/minimum").asInt()).isEqualTo(1);
        assertThat(body.at("/properties/windowCap/maximum").asInt()).isEqualTo(100);
        for (String time : List.of("morningWindowStart", "morningWindowEnd", "afternoonWindowStart",
                                   "afternoonWindowEnd")) {
            assertThat(body.at("/properties/" + time + "/pattern").asText()).as(time)
                .isEqualTo("^([01][0-9]|2[0-3]):(00|15|30|45)$");
        }
        assertThat(body.at("/properties/revision/minimum").asInt()).isZero();
    }

    @Test
    void documentsTheMastersListItsBoundsAndNoSubject() throws Exception {
        JsonNode spec = JSON.readTree(mvc.perform(get("/v3/api-docs"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
        JsonNode list = spec.get("paths").get("/caseworker/masters");
        assertThat(list.fieldNames()).toIterable().containsExactlyInAnyOrder("get", "post");
        assertThat(list.at("/get/operationId").asText()).isEqualTo("listMasters");
        assertThat(list.at("/post/operationId").asText()).isEqualTo("createMaster");
        assertThat(list.at("/post/responses/201").isMissingNode()).isFalse();
        JsonNode one = spec.get("paths").get("/caseworker/masters/{id}");
        assertThat(one.fieldNames()).toIterable().containsExactly("patch");
        assertThat(one.at("/patch/operationId").asText()).isEqualTo("updateMaster");
        assertThat(one.at("/patch/description").asText()).contains("409", "unlinks");
        for (JsonNode operation : List.of(list.get("get"), list.get("post"), one.get("patch"))) {
            assertThat(operation.at("/responses/403").isMissingNode()).isFalse();
        }
        for (String write : List.of("/post/description", "/patch/description")) {
            assertThat((write.startsWith("/post") ? list : one).at(write).asText())
                .contains("caseworker-deedpoll-delivery-manager", "MANAGE_PROCESS_CONFIG");
        }
        assertThat(spec.at("/components/schemas/MasterResponse/properties").fieldNames()).toIterable()
            .containsExactlyInAnyOrder("id", "name", "email", "available", "linked", "revision");
        JsonNode create = spec.at("/components/schemas/CreateMasterRequest");
        assertThat(create.get("required")).extracting(JsonNode::asText).containsExactlyInAnyOrder("name", "email");
        assertThat(create.get("additionalProperties").asBoolean()).isFalse();
        assertThat(create.at("/properties/name/maxLength").asInt()).isEqualTo(200);
        assertThat(create.at("/properties/name/minLength").asInt()).isOne();
        assertThat(create.at("/properties/email/maxLength").asInt()).isEqualTo(320);
        assertThat(create.at("/properties/available/type").asText()).isEqualTo("boolean");
        JsonNode update = spec.at("/components/schemas/UpdateMasterRequest");
        assertThat(update.get("required")).extracting(JsonNode::asText).containsExactly("revision");
        assertThat(update.at("/properties/revision/minimum").asInt()).isZero();
        assertThat(update.get("additionalProperties").asBoolean()).isFalse();
        assertThat(update.get("minProperties").asInt()).isEqualTo(2);
        assertThat(create.get("properties").fieldNames()).toIterable()
            .containsExactlyInAnyOrder("name", "email", "available");
        assertThat(update.get("properties").fieldNames()).toIterable()
            .containsExactlyInAnyOrder("name", "email", "available", "revision");
    }

    @Test
    void documentsTheBankHolidaysAndTheirBody() throws Exception {
        JsonNode spec = JSON.readTree(mvc.perform(get("/v3/api-docs"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
        JsonNode list = spec.get("paths").get("/caseworker/process-settings/bank-holidays");
        assertThat(list.fieldNames()).toIterable().containsExactlyInAnyOrder("get", "post");
        assertThat(list.at("/get/operationId").asText()).isEqualTo("listBankHolidays");
        assertThat(list.at("/post/operationId").asText()).isEqualTo("addBankHoliday");
        assertThat(list.at("/post/responses/201").isMissingNode()).isFalse();
        assertThat(list.at("/post/description").asText())
            .contains("caseworker-deedpoll-delivery-manager", "MANAGE_PROCESS_CONFIG", "409");
        JsonNode one = spec.get("paths").get("/caseworker/process-settings/bank-holidays/{date}");
        assertThat(one.fieldNames()).toIterable().containsExactly("delete");
        assertThat(one.at("/delete/operationId").asText()).isEqualTo("removeBankHoliday");
        assertThat(one.at("/delete/responses/204").isMissingNode()).isFalse();
        assertThat(one.at("/delete/description").asText()).contains("MANAGE_PROCESS_CONFIG", "404");
        for (JsonNode operation : List.of(list.get("get"), list.get("post"), one.get("delete"))) {
            assertThat(operation.at("/responses/403").isMissingNode()).isFalse();
        }
        assertThat(spec.at("/components/schemas/BankHolidayResponse/properties").fieldNames()).toIterable()
            .containsExactlyInAnyOrder("date", "name");
        JsonNode create = spec.at("/components/schemas/CreateBankHolidayRequest");
        assertThat(create.get("required")).extracting(JsonNode::asText).containsExactlyInAnyOrder("date", "name");
        assertThat(create.get("additionalProperties").asBoolean()).isFalse();
        assertThat(create.at("/properties/date/format").asText()).isEqualTo("date");
        assertThat(create.at("/properties/name/minLength").asInt()).isOne();
        assertThat(create.at("/properties/name/maxLength").asInt()).isEqualTo(200);
    }

    @Test
    void documentsEveryGazetteGateAndNamedWorklistRefusal() throws Exception {
        JsonNode spec = JSON.readTree(mvc.perform(get("/v3/api-docs"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
        int operations = 0;
        for (String path : List.of("/caseworker/gazette-notices", "/caseworker/gazette-settings",
            "/caseworker/gazette-notice-previews", "/caseworker/gazette-notice-requests",
            "/caseworker/gazette-notices/{batchId}", "/caseworker/gazette-notices/{batchId}/cases")) {
            for (JsonNode operation : spec.get("paths").get(path)) {
                assertThat(operation.path("description").asText()).contains("caseworker-deedpoll-delivery-manager")
                    .doesNotContain("caseworker-deedpoll-admin");
                operations++;
            }
        }
        assertThat(operations).isEqualTo(8);
        JsonNode cases = spec.get("paths").get("/caseworker/cases").get("get");
        assertThat(cases.path("description").asText())
            .contains("readyForPublication", "caseworker-deedpoll-delivery-manager", "403", "saved-query", "count");
        JsonNode filter = StreamSupport.stream(cases.get("parameters").spliterator(), false)
            .filter(parameter -> "filter".equals(parameter.path("name").asText())).findFirst().orElseThrow();
        assertThat(filter.path("description").asText())
            .contains("caseworker-deedpoll-delivery-manager", "403");
    }

    @Test
    void documentsPublicationFilterInBothSavedQueryWriteContracts() throws Exception {
        JsonNode spec = JSON.readTree(mvc.perform(get("/v3/api-docs"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
        for (String schema : List.of("CreateSavedQueryRequest", "UpdateSavedQueryRequest")) {
            List<String> filters = StreamSupport.stream(
                spec.at("/components/schemas/" + schema + "/properties/filter/enum").spliterator(), false)
                .map(JsonNode::asText).toList();
            assertThat(filters).as("%s retains existing filters and appends publication readiness", schema)
                .containsExactly("all", "open", "received", "enroled", "returned",
                                 "awaitingConfirmation", "readyForPublication");
        }
    }

    @DisplayName("Generate swagger documentation")
    @Test
    void generateDocs() throws Exception {
        byte[] specs = mvc.perform(get("/v3/api-docs"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsByteArray();

        try (OutputStream outputStream = Files.newOutputStream(PUBLISHED_SPEC)) {
            outputStream.write(specs);
        }

        assertThat(PUBLISHED_SPEC).exists();

        JsonNode paths = JSON.readTree(specs).get("paths");
        assertThat(paths).isNotNull();
        assertThat(paths.fieldNames()).toIterable().containsAll(CASEWORKER_PATHS);
        assertThat(paths.fieldNames()).toIterable().containsAll(JUDICIAL_PATHS);
        assertThat(paths.fieldNames()).toIterable().containsAll(PREVIEW_PATHS);
    }

    /**
     * The two document previews' whole contract, which {@code deed-poll-admin}'s viewer adapter is generated from.
     *
     * <p>The handlers write their own response, so springdoc on its own would publish a bodiless 200; everything
     * below is {@code OpenAPIDocumentPreviews}'s, and asserted here so that a refusal the service sends cannot be
     * missing from the document, nor a code listed that it never sends. The limits are the defaults, which is what
     * the producer artifact is generated under.
     */
    @DisplayName("Documents both document previews: the PDF, its headers, the four fixed refusals and the limits")
    @Test
    void documentsTheDocumentPreviews() throws Exception {
        JsonNode spec = JSON.readTree(mvc.perform(get("/v3/api-docs"))
                                          .andExpect(status().isOk())
                                          .andReturn()
                                          .getResponse()
                                          .getContentAsByteArray());
        JsonNode paths = spec.get("paths");

        assertThat(CASEWORKER_PATHS).doesNotContainAnyElementsOf(PREVIEW_PATHS);
        assertThat(JUDICIAL_PATHS).doesNotContainAnyElementsOf(PREVIEW_PATHS);
        Map<String, String> operationIds = Map.of(
            PREVIEW_PATHS.get(0), "previewCaseDocument",
            PREVIEW_PATHS.get(1), "previewReferralDocument");

        List<String> codes = Arrays.stream(PreviewFailure.values()).map(PreviewFailure::code).toList();
        for (String path : PREVIEW_PATHS) {
            assertThat(paths.get(path).fieldNames()).toIterable().as(path).containsExactly("get");
            JsonNode operation = paths.get(path).get("get");
            assertThat(operation.get("operationId").asText()).isEqualTo(operationIds.get(path));
            assertThat(operation.get("description").asText()).as(path)
                .contains("read-only rendition", "/file", "DOCUMENT_PREVIEWED", "404", "Range", "TIFF", "DOCX");

            JsonNode responses = operation.get("responses");
            assertThat(responses.fieldNames()).toIterable().as(path)
                .containsExactlyInAnyOrder("200", "403", "404", "422", "429", "502", "503");

            JsonNode ok = responses.get("200");
            assertThat(ok.get("content").fieldNames()).toIterable().containsExactly("application/pdf");
            assertThat(ok.at("/content/application~1pdf/schema/format").asText()).isEqualTo("binary");
            assertThat(ok.get("headers").fieldNames()).toIterable().containsExactlyInAnyOrder(
                "Content-Disposition", "Content-Length", "Cache-Control", "X-Content-Type-Options",
                "Content-Security-Policy", "Accept-Ranges");
            assertThat(ok.at("/headers/Content-Disposition/description").asText()).contains("document-preview.pdf");
            assertThat(ok.at("/headers/Accept-Ranges/description").asText()).contains("none");

            JsonNode notFound = resolve(spec, responses.at("/404/content/application~1json/schema"));
            assertThat(notFound.path("properties").fieldNames()).toIterable()
                .containsExactlyInAnyOrder("error", "errors");
            JsonNode badGateway = resolve(spec, responses.at("/502/content/application~1json/schema"));
            assertThat(badGateway.path("properties").fieldNames()).toIterable()
                .containsExactlyInAnyOrder("error", "errors");

            List<String> published = new ArrayList<>();
            for (String status : List.of("422", "429", "503")) {
                JsonNode refusal = responses.get(status);
                assertThat(refusal.at("/headers/Cache-Control").isMissingNode()).as(status).isFalse();
                JsonNode error = resolve(spec, refusal.at("/content/application~1json/schema"));
                assertThat(error.path("properties").fieldNames()).toIterable().as(status)
                    .containsExactlyInAnyOrder("error", "code");
                assertThat(error.at("/properties/code/enum")).map(JsonNode::asText)
                    .containsExactlyElementsOf(codes);
                Arrays.stream(PreviewFailure.values())
                    .filter(failure -> Integer.toString(failure.status()).equals(status))
                    .forEach(failure -> {
                        assertThat(refusal.get("description").asText()).as(status)
                            .contains(failure.code(), failure.message());
                        published.add(failure.code());
                    });
            }
            assertThat(published).as("every code under its own status").containsExactlyInAnyOrderElementsOf(codes);
            assertThat(responses.at("/429/headers/Retry-After/schema/type").asText()).isEqualTo("integer");
            assertThat(responses.at("/422/headers/Retry-After").isMissingNode()).isTrue();

            JsonNode limits = operation.get("x-document-preview-limits");
            assertThat(limits.get("requestBudgetSeconds").asLong())
                .isEqualTo(PreviewProperties.REQUEST_BUDGET.toSeconds());
            assertThat(limits.get("maxInputBytes").asLong()).isEqualTo(UploadPolicy.MAX_UPLOAD_BYTES);
            assertThat(limits.get("maxOutputBytes").asLong()).isEqualTo(PreviewProperties.MAX_OUTPUT_BYTES);
            assertThat(limits.get("maxPages").asInt()).isEqualTo(PreviewProperties.MAX_PAGES);
            assertThat(limits.get("maxPagesEnforcedFor")).map(JsonNode::asText)
                .containsExactly("image/jpeg", "image/png", "image/tiff");
            assertThat(limits.get("maxPagesEnforcedByViewerFor")).map(JsonNode::asText)
                .containsExactly("application/pdf", PreviewFormat.DOCX.mimeType());
            assertThat(limits.get("description").asText()).contains("unparsed", "viewer");
            assertThat(limits.get("maxFramePixels").asLong()).isEqualTo(PreviewProperties.MAX_FRAME_PIXELS);
            assertThat(limits.get("maxConcurrentConversionsPerInstance").asInt())
                .isEqualTo(PreviewProperties.MAX_CONCURRENT_CONVERSIONS);
            assertThat(limits.get("maxConcurrentPreviewsPerInstance").asInt())
                .isEqualTo(PreviewProperties.MAX_CONCURRENT_PREVIEWS);
            assertThat(limits.get("busyRetryAfterSeconds").asLong())
                .isEqualTo(PreviewProperties.BUSY_RETRY_AFTER.toSeconds());
            assertThat(limits.get("rangeRequests").asText()).isEqualTo("unsupported");

            // Nothing names the converter or a stored identifier: the admin's client is generated from this text.
            assertThat(operation.toString().toLowerCase(Locale.ROOT))
                .doesNotContain("docmosis", "tornado", "rs/convert", "cdam", "hashtoken", "storagekey");
        }
    }

    /**
     * The eight judicial operations, published and counted apart from the caseworker surface's thirty-four.
     *
     * <p>Both halves for the reason the caseworker assertion gives: a count cannot tell a new verb on a known
     * path from a new path, and a path list cannot tell a route that was removed from one that never existed.
     *
     * <p><strong>Eight operations across seven paths</strong> -- the shared pool, this judge's own work, one referral
     * and one of its documents' files each carry a {@code GET}, a referral's claim, its direction and the direction's
     * withdrawal each carry a {@code POST}, and the direction a {@code PUT} that revises it.
     * There is no third write and no judicial closure: closing a referral is the office's acknowledgement that
     * the direction has been actioned, so it lives on the caseworker surface and its absence here is the decision
     * rather than an omission.
     *
     * <p><strong>Four until {@code /judicial/referrals/mine} was published</strong>, and this is the one referral
     * follow-up that moves the contract. The number is asserted here rather than only listed because
     * {@code deed-poll-admin} generates its client from this document: an unpublished route is an unbuildable
     * screen, and the personal list exists precisely because a judge who claimed a referral could not find it
     * again. The document download (2026-10-19) moved it to six, reached through the referral and never the case,
     * revising a direction to seven, and withdrawing one to eight.
     */
    @DisplayName("Documents the eight judicial operations, separately from the caseworker count")
    @Test
    void documentsTheJudicialOperations() throws Exception {
        JsonNode paths = JSON.readTree(mvc.perform(get("/v3/api-docs"))
                                           .andExpect(status().isOk())
                                           .andReturn()
                                           .getResponse()
                                           .getContentAsByteArray())
            .get("paths");

        assertThat(paths.fieldNames()).toIterable().containsAll(JUDICIAL_PATHS);

        long operations = JUDICIAL_PATHS.stream()
            .mapToLong(path -> paths.get(path).size())
            .sum();
        assertThat(operations).isEqualTo(8);

        // The personal list is a path of its own and not a verb on the pool's, which is what a reader of the
        // count above cannot see: `/judicial/referrals` still carries exactly one operation, so `mine` cannot
        // have been documented by widening the pool's entry.
        assertThat(paths.get("/judicial/referrals").size()).isEqualTo(1);
        assertThat(paths.get("/judicial/referrals/mine").fieldNames()).toIterable().containsExactly("get");

        // And the caseworker count is untouched by their arrival, asserted here as well as above: the judicial
        // routes are a new surface, so the number that describes the old one must not have moved.
        assertThat(CASEWORKER_PATHS).doesNotContainAnyElementsOf(JUDICIAL_PATHS);
    }

    /**
     * What the 2026-10-19 judicial changes publish: the wider application, the document route, revising and
     * withdrawing a direction, and the {@code directionChangedAt} flag on both referral responses.
     *
     * <p>Asserted on the descriptions rather than only on the paths because {@code deed-poll-admin} writes its
     * screens from this document, and two of the rules are not visible in a schema: a revision keeps the first
     * signature, and <strong>withdrawing restarts the processing window</strong>, at whose end the referral is
     * returned to the office (design decision 6, as plan D2 changed it). A judge who withdraws and walks away loses
     * the referral, and the admin has to be able to say so.
     */
    @DisplayName("Documents the Master's application view, its documents, and revising or withdrawing a direction")
    @Test
    void documentsTheJudicialApplicationViewAndRevisedDirections() throws Exception {
        JsonNode spec = JSON.readTree(mvc.perform(get("/v3/api-docs"))
                                          .andExpect(status().isOk())
                                          .andReturn()
                                          .getResponse()
                                          .getContentAsByteArray());

        String referral = "/paths/~1judicial~1referrals~1{referralId}";
        assertThat(spec.at(referral + "/get/description").asText())
            .contains("as a Master sees it", "No case id, contact details, witnesses or case notes", "CASE_VIEWED",
                      "404");

        JsonNode file = spec.at("/paths/~1judicial~1referrals~1{referralId}~1documents~1{documentId}~1file/get");
        assertThat(file.at("/description").asText())
            .contains("application.documents", "DOCUMENT_DOWNLOADED", "404");

        JsonNode direction = spec.at(referral.replace("}", "}~1direction"));
        assertThat(direction.at("/post/requestBody/content/application~1json/schema/$ref").asText())
            .isEqualTo("#/components/schemas/JudicialDirectionRequest");
        assertThat(direction.at("/put/requestBody/content/application~1json/schema/$ref").asText())
            .as("the revision takes the direction's own body")
            .isEqualTo("#/components/schemas/JudicialDirectionRequest");
        assertThat(direction.at("/put/description").asText())
            .contains("Decided", "409", "directionChangedAt", "audit");

        JsonNode withdraw = spec.at(referral.replace("}", "}~1direction~1withdraw") + "/post");
        assertThat(withdraw.has("requestBody")).as("withdrawing takes no body").isFalse();
        assertThat(withdraw.at("/description").asText())
            .contains("Claimed", "directionChangedAt", "restarts the processing window", "returned to the office");

        assertThat(spec.at("/components/schemas/JudicialDirectionRequest/description").asText())
            .contains("PUT");

        for (String schema : List.of("ReferralResponse", "JudicialReferralDetail")) {
            JsonNode changed = spec.at("/components/schemas/" + schema + "/properties/directionChangedAt");
            assertThat(changed.at("/format").asText()).as("%s publishes the flag", schema).isEqualTo("date-time");
            assertThat(changed.at("/description").asText()).as("%s describes the flag", schema)
                .contains("revised or withdrew", "never cleared");
        }

        assertThat(spec.at("/components/schemas/JudicialReferralDetail/properties/application/description")
                       .asText())
            .contains("Never a case id, contact details or case notes");
        assertThat(spec.at("/components/schemas/JudicialReferralDetail/properties").has("caseNotes"))
            .as("the Master's referral carries no timeline").isFalse();
    }

    /**
     * The three CCD attempt operations -- the list and the two repairs -- and what their schemas never carry.
     *
     * <p>The schema half is the disclosure rule on the published contract: {@code deed-poll-admin} generates its
     * client from this document, so a field named here would reach every admin's browser. Whether a submission may
     * have reached CCD is published only as its consequence, {@code canRetryFromScratch}.
     */
    @DisplayName("Documents the three CCD attempt operations, and no field an attempt keeps to itself")
    @Test
    void documentsTheCcdAttemptOperations() throws Exception {
        JsonNode document = JSON.readTree(mvc.perform(get("/v3/api-docs"))
                                              .andExpect(status().isOk())
                                              .andReturn()
                                              .getResponse()
                                              .getContentAsByteArray());
        JsonNode paths = document.get("paths");

        assertThat(paths.fieldNames()).toIterable().containsAll(CCD_ATTEMPT_PATHS);
        assertThat(paths.get("/caseworker/ccd-attempts").fieldNames()).toIterable().containsExactly("get");
        assertThat(paths.get("/caseworker/ccd-attempts/{attemptId}/reconciliation").fieldNames()).toIterable()
            .containsExactly("post");
        assertThat(paths.get("/caseworker/ccd-attempts/{attemptId}/retry").fieldNames()).toIterable()
            .containsExactly("post");
        assertThat(CASEWORKER_PATHS).doesNotContainAnyElementsOf(CCD_ATTEMPT_PATHS);

        JsonNode schemas = document.get("components").get("schemas");
        for (String schema : List.of("CcdAttemptListResponse", "CcdAttemptResponse", "CcdAttemptCountResponse")) {
            assertThat(schemas.has(schema)).as(schema).isTrue();
            List<String> properties = new ArrayList<>();
            schemas.get(schema).get("properties").fieldNames().forEachRemaining(properties::add);
            assertThat(properties).as(schema).doesNotContainAnyElementsOf(List.of(
                "claimedBy", "leaseExpiresAt", "operationId", "submissionMayHaveReached", "repairedById",
                "provenNothingCreated", "repairedOn", "notBefore"));
        }
        List<String> row = new ArrayList<>();
        schemas.get("CcdAttemptResponse").get("properties").fieldNames().forEachRemaining(row::add);
        assertThat(row).containsExactlyInAnyOrder("id", "deedPollId", "displayReference", "status", "tries",
            "failureDetail", "modifiedOn", "canRetryFromScratch");
    }

    /**
     * Every documented path belongs to a list this class names -- so a route added outside both fails here.
     *
     * <p><strong>Added because the two counts above cannot see an addition.</strong> Each sums the operations of
     * the paths its own list names, so a brand-new path is simply not counted: the caseworker total stays 33, the
     * judicial total stays 8, both assertions pass, and an undocumented route reaches
     * {@code deed-poll-admin}'s generated client without anything failing. Found while adding {@code mine},
     * which had to be written into {@link #JUDICIAL_PATHS} by hand for the count to move at all.
     *
     * <p>{@code "/"} is Spring Boot's welcome route, and {@code "/me"} is the caller's own access answer, which any
     * authenticated caller may read: {@code SecurityConfiguration.ANY_AUTHENTICATED_PATHS}. They are the only
     * legitimate paths outside both surfaces. Adding a name to this set is a deliberate act: a new
     * {@code /caseworker} or {@code /judicial} route belongs in the list for its own namespace, where the count then
     * forces the number to be restated.
     */
    @DisplayName("Documents no path outside the caseworker and judicial surfaces but the root and /me")
    @Test
    void documentsNoUnlistedPath() throws Exception {
        JsonNode paths = JSON.readTree(mvc.perform(get("/v3/api-docs"))
                                           .andExpect(status().isOk())
                                           .andReturn()
                                           .getResponse()
                                           .getContentAsByteArray())
            .get("paths");

        List<String> documented = new ArrayList<>();
        paths.fieldNames().forEachRemaining(documented::add);

        List<String> known = new ArrayList<>(CASEWORKER_PATHS);
        known.addAll(JUDICIAL_PATHS);
        known.addAll(CCD_ATTEMPT_PATHS);
        known.addAll(PREVIEW_PATHS);
        known.add(GAZETTE_HISTORY_PATH);
        known.add("/caseworker/gazette-settings");
        known.add("/caseworker/process-settings");
        known.add("/caseworker/masters");
        known.add("/caseworker/masters/{id}");
        known.add("/caseworker/process-settings/bank-holidays");
        known.add("/caseworker/process-settings/bank-holidays/{date}");
        known.add("/caseworker/gazette-notice-previews");
        known.add("/caseworker/gazette-notice-requests");
        known.add("/caseworker/gazette-notices/{batchId}");
        known.add("/caseworker/gazette-notices/{batchId}/cases");
        known.add("/");
        known.add("/me");

        assertThat(documented)
            .describedAs("a documented path in neither surface -- add it to the list for its namespace, so that "
                         + "namespace's operation count has to be restated with it")
            .containsExactlyInAnyOrderElementsOf(known);
    }

    /**
     * Every staff operation documents the 403 its area can answer, and no other operation does.
     *
     * <p>Selected by {@link StaffArea}, the same table the filter chain builds its rules from, so a new staff route
     * is covered without being listed here. Every operation in an area can be refused: the chain refuses a caller
     * the area does not admit, and the domain refuses a member who lacks a capability, both with {@code ApiError}.
     * The two exceptions are deliberate. {@code GET /} is public, so nothing refuses it, and {@code GET /me} admits
     * any authenticated caller precisely so that one with no role is told so rather than refused.
     *
     * <p>The schema is resolved, not merely referenced: a {@code 403} key, or a {@code $ref} to a component that
     * is not there, would satisfy a scanner and give a generated client nothing to parse.
     */
    @DisplayName("Documents a 403 with the error body on every staff operation, and on neither / nor /me")
    @Test
    void documentsTheForbiddenResponseOnEveryStaffOperation() throws Exception {
        JsonNode spec = JSON.readTree(mvc.perform(get("/v3/api-docs"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());

        List<String> refusable = new ArrayList<>();
        List<String> exempt = new ArrayList<>();
        for (Map.Entry<String, JsonNode> path : spec.get("paths").properties()) {
            for (String method : List.of("get", "put", "post", "delete", "patch")) {
                JsonNode operation = path.getValue().get(method);
                if (operation == null) {
                    continue;
                }
                String name = method.toUpperCase(Locale.ROOT) + " " + path.getKey();
                if (StaffArea.of(path.getKey()).isEmpty()) {
                    assertThat(operation.at("/responses/403").isMissingNode()).as(name).isTrue();
                    exempt.add(name);
                    continue;
                }
                assertThat(operation.has("security")).as("%s inherits the global bearer requirement", name)
                    .isFalse();
                assertForbiddenResponse(spec, name, operation.at("/responses/403"));
                refusable.add(name);
            }
        }

        assertThat(refusable).as("staff operations").isNotEmpty();
        assertThat(exempt).containsExactlyInAnyOrder("GET /", "GET /me");
        assertThat(spec.at("/paths/~1/get/security").isArray()).isTrue();
        assertThat(spec.at("/paths/~1/get/security").isEmpty()).as("GET / stays public").isTrue();
        assertThat(spec.at("/paths/~1me/get").has("security")).as("GET /me inherits the bearer requirement")
            .isFalse();
    }

    private static void assertForbiddenResponse(JsonNode spec, String name, JsonNode declared) {
        assertThat(declared.isMissingNode()).as("%s documents 403", name).isFalse();
        JsonNode response = resolve(spec, declared);
        assertThat(response.path("description").asText()).as("%s 403 description", name).isNotBlank();
        assertThat(response.path("content").fieldNames()).toIterable().as("%s 403 content", name)
            .containsExactly("application/json");

        JsonNode error = resolve(spec, response.at("/content/application~1json/schema"));
        assertThat(error.path("properties").fieldNames()).toIterable().as("%s 403 schema", name)
            .containsExactlyInAnyOrder("error", "errors");
        assertThat(error.path("type").asText()).as("%s 403 schema type", name).isEqualTo("object");
        assertThat(error.at("/properties/error/type").asText()).isEqualTo("string");
        assertThat(error.at("/properties/errors/type").asText()).isEqualTo("array");
        JsonNode fieldError = resolve(spec, error.at("/properties/errors/items"));
        assertThat(fieldError.path("properties").fieldNames()).toIterable()
            .containsExactlyInAnyOrder("field", "message");
        assertThat(fieldError.path("type").asText()).isEqualTo("object");
        assertThat(fieldError.at("/properties/field/type").asText()).isEqualTo("string");
        assertThat(fieldError.at("/properties/message/type").asText()).isEqualTo("string");
    }

    /**
     * Follows {@code $ref}s within the document, failing on one that points at nothing.
     */
    private static JsonNode resolve(JsonNode spec, JsonNode node) {
        JsonNode current = node;
        while (current.has("$ref")) {
            String ref = current.get("$ref").asText();
            assertThat(ref).startsWith("#/");
            current = spec.at(ref.substring(1));
            assertThat(current.isMissingNode()).as("%s resolves", ref).isFalse();
        }
        assertThat(current.isMissingNode()).isFalse();
        return current;
    }

    /**
     * The personal list's envelope, and the row type it deliberately reuses.
     *
     * <p>Asserted rather than left to the operation count, because the count cannot tell a published route from a
     * published <em>contract</em>: {@code deed-poll-admin} generates its client from this document, so the shape
     * of {@code JudicialMyReferralsResponse} is the shape the judicial dashboard is written against.
     *
     * <p><strong>The row assertion is the security one.</strong> Ownership moved to the token's IDAM subject in
     * this plan's first task, and {@code claimedById} / {@code decidedById} must not be published anywhere: a
     * document that offered a judge's subject would put one in every generated client and every browser network
     * log. {@code JudicialReferralSummary} is asserted to carry exactly the referral fields it carried when it was
     * the pool's row type and nothing else -- no owner id, no case id, no applicant name and no direction text --
     * so a field added to it in either direction fails here.
     */
    @DisplayName("Documents the personal list's envelope, with the pool's row type unchanged")
    @Test
    void documentsThePersonalReferralList() throws Exception {
        JsonNode spec = JSON.readTree(mvc.perform(get("/v3/api-docs"))
                                          .andExpect(status().isOk())
                                          .andReturn()
                                          .getResponse()
                                          .getContentAsByteArray());

        // The operation answers with the new envelope, named: a client generated against `JudicialPoolResponse`
        // here would compile and then describe the pool's complement as the pool. Reached under `*/*`, which is
        // what springdoc publishes for a controller that declares no `produces` -- asserted as the document spells
        // it rather than as the media type the service actually sends.
        assertThat(spec.at("/paths/~1judicial~1referrals~1mine/get/responses/200/content/*~1*/schema/$ref")
                       .asText())
            .isEqualTo("#/components/schemas/JudicialMyReferralsResponse");

        JsonNode envelope = spec.at("/components/schemas/JudicialMyReferralsResponse/properties");
        assertThat(envelope.fieldNames()).toIterable()
            .containsExactlyInAnyOrder("referrals", "page", "size", "total", "totalPages");
        assertThat(envelope.at("/referrals/items/$ref").asText())
            .isEqualTo("#/components/schemas/JudicialReferralSummary");

        // Paging is described the same way the pool's is, from the same constant, so the two judicial lists
        // cannot drift into disagreeing about what a page is.
        Map<String, JsonNode> byName = new LinkedHashMap<>();
        spec.at("/paths/~1judicial~1referrals~1mine/get/parameters")
            .forEach(parameter -> byName.put(parameter.at("/name").asText(), parameter));
        assertThat(byName.keySet()).containsExactlyInAnyOrder("page", "size");
        byName.forEach((name, parameter) ->
                           assertThat(parameter.at("/required").asBoolean())
                               .as("%s is optional, as it is on the pool", name)
                               .isFalse());

        // The row type, shared with the former pool -- the referral speaking about itself, plus plan D2's
        // processing window, and no ownership, case or application field however this document might have acquired
        // one.
        JsonNode row = spec.at("/components/schemas/JudicialReferralSummary/properties");
        assertThat(row.fieldNames()).toIterable()
            .containsExactlyInAnyOrder("id", "state", "reasons", "notes", "urgency", "urgencyReason", "createdOn",
                                       "assignedAt", "directionDueBy");
        assertThat(spec.at("/paths/~1judicial~1referrals/get/responses/200/content/*~1*/schema/$ref").asText())
            .as("the pool still answers with its own envelope")
            .isEqualTo("#/components/schemas/JudicialPoolResponse");

        // The application a Master reads (narrowed 2026-10-27): the names and the documents, and none of the
        // case's identity, payment data, contact details or witnesses. The full list is
        // JudicialApplicationViewTest's; this is that it is published.
        JsonNode application = spec.at("/components/schemas/JudicialApplicationView/properties");
        assertThat(application.fieldNames()).toIterable()
            .contains("newForename", "oldSurname", "parents", "documents")
            .doesNotContain("id", "referencenumber", "feeReference", "refundAmount", "refundReference",
                            "privacy", "applicantEmail", "witnesses");
        assertThat(spec.at("/components/schemas").has("JudicialWitness"))
            .as("no witness schema is published to Masters").isFalse();
        assertThat(spec.at("/components/schemas/JudicialDocument/properties").fieldNames()).toIterable()
            .contains("id", "documentType", "uploadedOn")
            .doesNotContain("storageKey", "hasFile");

        // And the subject columns are nowhere in the document at all, asserted over the whole of it rather than
        // field by field: the two names are searched as text, so a schema this test does not know about cannot
        // publish them either.
        String published = spec.toString();
        assertThat(published)
            .as("no schema publishes a judge's IDAM subject")
            .doesNotContain("claimedById")
            .doesNotContain("decidedById");
    }

    @DisplayName("Documents the thirty-four caseworker operations and the token they need")
    @Test
    void documentsTheCaseworkerOperations() throws Exception {
        JsonNode spec = JSON.readTree(mvc.perform(get("/v3/api-docs"))
                                          .andExpect(status().isOk())
                                          .andReturn()
                                          .getResponse()
                                          .getContentAsByteArray());

        JsonNode paths = spec.get("paths");
        // Thirty-five operations across twenty-two paths (plan D2 added the referral's assignment PUT):
        // `/caseworker/cases`, the notes and witnesses collections,
        // the saved-query collection and the referrals collection each carry a GET and a POST, the case carries a
        // GET and a PATCH, the note, the witness and the saved query each carry a GET, a PATCH and a DELETE, a
        // saved query's selection carries a PUT and a DELETE, one referral carries a GET, and its withdrawal, its
        // closure and its confirmation each carry a POST -- the note's delete since 2026-09-08, the witness's since
        // the Administrator gate landed, the saved query's since the library did and the selection's with it, the
        // four referral routes with the referrals feature, the closure with the referral state machine, and the
        // confirmation with the tracking of the reply the ④ email asks for: recording an answer is its own
        // operation rather than a case edit, and it is idempotent by refusal rather than by PATCH.
        // Three of them are the Email tab's: the case's emails collection carries a GET saying which emails
        // apply, and one email type carries a POST to send it and a POST to preview it. Preview is a POST despite
        // being a read, because the caseworker's slot text is the input and a URL would log a judge's reason.
        // The judge's own operations -- the pool, their own outstanding work, one referral, claiming it and
        // recording a direction -- are deliberately not here: they are routed on the judicial surface rather than
        // this one, so this count stays the caseworker count however that surface grows.
        // Both the paths and the count are asserted because neither alone is enough: a count could not tell a new
        // verb on a known path from a new path, and the path list could not tell a route that was removed from one
        // that never existed.
        long operations = CASEWORKER_PATHS.stream()
            .mapToLong(path -> paths.get(path).size())
            .sum();
        assertThat(operations).isEqualTo(35);

        JsonNode emailResponse = spec.at("/components/schemas/CaseEmailsResponse/properties");
        assertThat(emailResponse.fieldNames()).toIterable()
            .containsExactlyInAnyOrder("availability", "history", "slotDefaults");
        JsonNode history = spec.at("/components/schemas/EmailHistoryResponse/properties");
        assertThat(history.has("state")).isFalse();
        assertThat(history.path("notifyState").path("description").asText())
            .contains("accepted by GOV.UK Notify")
            .contains("does not mean delivered");
        assertThat(history.path("body").path("description").asText())
            .contains("plain text")
            .contains("branded HTML");

        // Every one of them needs a forwarded IDAM token, which the document has to say or the client generated
        // from it cannot make a call. Declared once, globally, because the service authenticates by default.
        assertThat(spec.at("/components/securitySchemes/bearerAuth/scheme").asText()).isEqualTo("bearer");
        assertThat(spec.at("/security/0/bearerAuth").isArray()).isTrue();

        // The exception, and the only one: Azure's Always-On probe has no token.
        assertThat(spec.at("/paths/~1/get/security").isArray()).isTrue();
        assertThat(spec.at("/paths/~1/get/security").isEmpty()).isTrue();
    }

    /**
     * Both emails, named on the token that names one -- and no new operation for the second of them.
     *
     * <p><strong>This is what a second template had to prove, and the two halves are opposite failures.</strong>
     * {@code deed-poll-admin}'s Email tab was written to need no change when a template is added, which is only
     * true if the document names the new template without moving the contract: a template that the document does
     * not mention is one a client can only discover by being 404'd, and a template that arrived as a new path or a
     * new verb would mean the tab had a route to add after all. So the keys are asserted to be there and the
     * caseworker operation count is asserted to be exactly where notify-1 left it.
     *
     * <p>Read out of {@link EmailTemplate} rather than written here, for the reason the operator table and the
     * sortable fields are: a third template appears in the document -- and in this assertion -- without anyone
     * editing either file. {@link uk.gov.hmcts.reform.deedpoll.config.OpenAPIEmailTemplates} is what puts it there.
     */
    @DisplayName("Names both email templates on the token, and adds no operation for the second")
    @Test
    void documentsBothEmailTemplates() throws Exception {
        JsonNode spec = JSON.readTree(mvc.perform(get("/v3/api-docs"))
                                          .andExpect(status().isOk())
                                          .andReturn()
                                          .getResponse()
                                          .getContentAsByteArray());

        String send = "/paths/~1caseworker~1cases~1{id}~1emails~1{template}/post/parameters";
        String preview = "/paths/~1caseworker~1cases~1{id}~1emails~1{template}~1preview/post/parameters";
        for (String operation : List.of(send, preview)) {
            JsonNode token = parameterNamed(spec.at(operation), "template");
            assertThat(token.isMissingNode()).as("%s declares the template token", operation).isFalse();
            String description = token.at("/description").asText();
            for (EmailTemplate template : EmailTemplate.values()) {
                assertThat(description)
                    .as("%s names the %s email", operation, template.key())
                    .contains(template.key())
                    .contains(template.displayName());
            }
            // A description and deliberately not an `enum`: an enumerated path token becomes a closed union in a
            // generated client, so a fifth template would stop an old client calling the route it already had --
            // and the GET that publishes availability is where a tab is meant to learn what it may send.
            assertThat(token.at("/schema/enum").isMissingNode())
                .as("%s leaves the token an open string, so a later template breaks no generated client",
                    operation)
                .isTrue();
            assertThat(typesOf(token.at("/schema"))).contains("string");
        }

        // And the email surface is unmoved: both emails travel through the operations that were already there, so
        // neither of them added one. The count is the whole caseworker surface, which the confirmation
        // sub-resource did move -- that route is not an email route, and the assertions below are what say the two
        // email paths still carry one operation each.
        JsonNode paths = spec.get("paths");
        assertThat(CASEWORKER_PATHS.stream().mapToLong(path -> paths.get(path).size()).sum()).isEqualTo(35);
        assertThat(paths.get("/caseworker/cases/{id}/emails/{template}").fieldNames()).toIterable()
            .containsExactly("post");
        assertThat(paths.get("/caseworker/cases/{id}/emails").fieldNames()).toIterable().containsExactly("get");

        // The availability list is what carries the second entry at run time, and its row type is unchanged by the
        // arrival of one: the template is a key on the row rather than a field per template, which is the property
        // that let a second email ship with no change to the response shape.
        assertThat(spec.at("/components/schemas/EmailTypeResponse/properties").fieldNames()).toIterable()
            .containsExactlyInAnyOrder("template", "applicable", "reason", "provisioned", "slots");
        assertThat(typesOf(spec.at("/components/schemas/EmailTypeResponse/properties/template")))
            .contains("string");
        assertThat(spec.at("/components/schemas/CaseEmailsResponse/properties/availability/type").asText())
            .isEqualTo("array");
    }

    /**
     * The send's required {@code Idempotency-Key} header, published without moving the caseworker contract.
     *
     * <p><strong>Both halves matter and they are opposite failures.</strong> {@code deed-poll-admin} generates its
     * client from this document, so a required header the document does not mention is a client whose every send is
     * a 400 -- and a header that arrived as a new operation or a new path would mean the Email tab had a route to
     * add after all, which notify-2 was written not to need. So the parameter is asserted to be there, required and
     * typed, and the caseworker operation count is asserted not to have moved with it.
     *
     * <p><strong>The description is asserted because the schema cannot carry the rule.</strong>
     * {@code string/uuid, required} is satisfied exactly as well by a client that mints a fresh UUID per request --
     * which sends a second email on every double-click, the behaviour the header exists to prevent. The one
     * property that makes it work is that a compose journey reuses its key, and prose is the only place in an
     * OpenAPI document that can say so. {@link uk.gov.hmcts.reform.deedpoll.config.OpenAPIEmailTemplates} puts it
     * there.
     *
     * <p>And it is scoped to the send: a preview writes no row, so a key required there would be a refusal a
     * client could not act on, and the availability {@code GET} takes no header at all.
     */
    @DisplayName("Documents the send's required Idempotency-Key header, and adds no operation for it")
    @Test
    void documentsTheIdempotencyKeyHeader() throws Exception {
        JsonNode spec = JSON.readTree(mvc.perform(get("/v3/api-docs"))
                                          .andExpect(status().isOk())
                                          .andReturn()
                                          .getResponse()
                                          .getContentAsByteArray());

        JsonNode send = spec.at("/paths/~1caseworker~1cases~1{id}~1emails~1{template}/post/parameters");
        JsonNode key = parameterNamed(send, CaseworkerEmailController.IDEMPOTENCY_KEY);
        assertThat(key.isMissingNode())
            .as("the send declares the header a client must send, under the conventional spelling")
            .isFalse();
        assertThat(key.at("/in").asText()).isEqualTo("header");
        assertThat(key.at("/required").asBoolean())
            .as("required, so a client that has not been updated is told rather than silently losing the rule")
            .isTrue();
        assertThat(typesOf(key.at("/schema"))).contains("string");
        assertThat(key.at("/schema/format").asText())
            .as("a UUID, which is what the route binds and refuses anything else as a 400")
            .isEqualTo("uuid");
        String described = key.at("/description").asText();
        assertThat(described)
            .as("the document carries the one rule the schema cannot")
            .contains("compose journey")
            .contains("retry")
            .contains("409")
            .contains("enqueued=false");

        // Neither read takes one. The preview's parameters are its two path tokens and nothing else; the
        // availability GET's is the case id.
        assertThat(parameterNamed(
            spec.at("/paths/~1caseworker~1cases~1{id}~1emails~1{template}~1preview/post/parameters"),
            CaseworkerEmailController.IDEMPOTENCY_KEY).isMissingNode())
            .as("a preview owes nothing, so it requires no journey to be named")
            .isTrue();
        assertThat(parameterNamed(spec.at("/paths/~1caseworker~1cases~1{id}~1emails/get/parameters"),
                                  CaseworkerEmailController.IDEMPOTENCY_KEY).isMissingNode())
            .isTrue();

        // And the surface is unmoved: a header is part of an operation rather than one of its own.
        JsonNode paths = spec.get("paths");
        assertThat(CASEWORKER_PATHS.stream().mapToLong(path -> paths.get(path).size()).sum()).isEqualTo(35);
        assertThat(paths.get("/caseworker/cases/{id}/emails/{template}").fieldNames()).toIterable()
            .containsExactly("post");
    }

    /**
     * The parameter an operation declares under {@code name}, or a missing node.
     */
    private static JsonNode parameterNamed(JsonNode parameters, String name) {
        return StreamSupport.stream(parameters.spliterator(), false)
            .filter(parameter -> name.equals(parameter.at("/name").asText()))
            .findFirst()
            .orElse(JSON.missingNode());
    }

    /**
     * A property's declared types, however the document spells them.
     *
     * <p>OpenAPI 3.1 allows {@code "type": "string"} and {@code "type": ["string", "null"]} for the same field
     * depending on whether it accepts a null, so a caller asserting a type has to accept both forms or it is
     * really asserting nullability by accident.
     */
    private static List<String> typesOf(JsonNode property) {
        JsonNode type = property.get("type");
        if (type == null) {
            return List.of();
        }
        return type.isArray()
            ? StreamSupport.stream(type.spliterator(), false).map(JsonNode::asText).toList()
            : List.of(type.asText());
    }

    /** Whether an enum list offers a JSON null, which is what permits a patch to clear the field. */
    private static boolean offersNullMember(JsonNode enumList) {
        return StreamSupport.stream(enumList.spliterator(), false).anyMatch(JsonNode::isNull);
    }

    /**
     * Every operation that takes a body, asserted to actually describe one -- the judicial direction included.
     *
     * <p>The same argument as the path assertions above, one level in. Every write body in this service binds as a
     * keyed map rather than as declared components -- see {@code KeyedRequestBody} for why it has to -- so
     * springdoc has no fields to reflect over and published {@code CreateCaseRequest: {}} for each of the JSON
     * writes, and no request body at all for the multipart upload. Valid JSON describing nothing, which a
     * consumer generating a client cannot distinguish from a body that genuinely takes no fields.
     * {@link uk.gov.hmcts.reform.deedpoll.config.OpenAPIWriteBodies} fills them in from the domain's own tables;
     * this pins that they stay filled in.
     */
    @DisplayName("Describes the body of every operation that takes one")
    @Test
    void documentsTheWriteBodies() throws Exception {
        JsonNode spec = JSON.readTree(mvc.perform(get("/v3/api-docs"))
                                          .andExpect(status().isOk())
                                          .andReturn()
                                          .getResponse()
                                          .getContentAsByteArray());

        // The six JSON bodies, each named by the operation that takes it.
        for (String body : List.of("CreateCaseRequest", "UpdateCaseRequest", "CreateNoteRequest",
                                   "UpdateNoteRequest", "CreateWitnessRequest", "UpdateWitnessRequest")) {
            JsonNode properties = spec.at("/components/schemas/" + body + "/properties");
            assertThat(properties.isObject())
                .as("%s declares its properties rather than publishing an empty schema", body)
                .isTrue();
            assertThat(properties.size()).as("%s has at least one property", body).isPositive();
        }

        // A case body describes every writable field, so the count tracks the schema rather than a number here.
        int writableFields = DeedPollSchemas.writeBodyFields().size();
        assertThat(spec.at("/components/schemas/CreateCaseRequest/properties").size())
            .isEqualTo(writableFields);
        assertThat(spec.at("/components/schemas/UpdateCaseRequest/properties").size())
            .isEqualTo(writableFields);

        // Spot-checks that the types travelled, one of each kind rather than all sixty-seven: a text field with
        // its cap, an enum with its members, a date, a boolean, a decimal and a multiselect.
        String create = "/components/schemas/CreateCaseRequest/properties/";
        assertThat(typesOf(spec.at(create + "newForename"))).contains("string");
        assertThat(spec.at(create + "newForename/maxLength").asInt()).isEqualTo(1000);
        // The narrative cap, api's own rule matching admin's edit form, on both bodies.
        for (String narrative : List.of("returnReason", "referralNotes", "masterDirections")) {
            assertThat(spec.at(create + narrative + "/maxLength").asInt()).isEqualTo(2000);
            assertThat(spec.at("/components/schemas/UpdateCaseRequest/properties/" + narrative + "/maxLength")
                .asInt()).isEqualTo(2000);
        }
        assertThat(spec.at(create + "type/enum").isArray()).isTrue();
        assertThat(spec.at(create + "dateReceived/format").asText()).isEqualTo("date-time");
        assertThat(typesOf(spec.at(create + "migratedRecord"))).contains("boolean");
        assertThat(typesOf(spec.at(create + "refundAmount"))).contains("number");
        assertThat(typesOf(spec.at(create + "iShall"))).contains("array");

        // Which fields a patch can clear, which is the distinction the keyed body exists to carry. This document
        // is OpenAPI 3.1, where nullability is a type union and the 3.0 `nullable: true` flag is dropped on
        // serialisation -- so asserting the union is also what stops that flag being used by mistake and
        // published as nothing at all.
        assertThat(typesOf(spec.at(create + "masterName")))
            .as("a nullable field offers null in its type union")
            .contains("string", "null");
        // And the other side of it: a column that refuses a null does not offer one, or a client would be told it
        // could clear a field the service would then 400 on.
        assertThat(typesOf(spec.at(create + "migratedRecord")))
            .as("a non-nullable field offers no null")
            .doesNotContain("null");

        // A nullable ENUM has to say it twice, and this is the assertion that was missing. `enum` constrains the
        // value to its members independently of the type union, so a list of members without null describes a
        // field a validating client cannot clear even though the union next to it says it can -- a document that
        // contradicts itself, and refuses the clear the service accepts. Asserted on a nullable enum rather than
        // on masterName, because the non-enum case above cannot fail this way.
        // Asserted as a JSON null rather than as the text "null", which is a member name a client would send as
        // a string and would not clear anything.
        assertThat(spec.at(create + "type/enum").isArray()).isTrue();
        assertThat(offersNullMember(spec.at(create + "type/enum")))
            .as("a nullable enum lists null among its members, not only in its type union")
            .isTrue();
        assertThat(typesOf(spec.at(create + "type"))).contains("string", "null");
        // The mirror: an enum whose column refuses a null lists only real members.
        assertThat(offersNullMember(spec.at(create + "privacy/enum")))
            .as("a non-nullable enum offers no null member")
            .isFalse();

        // The note body's one field, required on create and optional on patch -- the distinction a client needs.
        assertThat(typesOf(spec.at("/components/schemas/CreateNoteRequest/properties/note"))).contains("string");
        assertThat(spec.at("/components/schemas/CreateNoteRequest/required/0").asText()).isEqualTo("note");
        assertThat(spec.at("/components/schemas/UpdateNoteRequest/required").isMissingNode()).isTrue();

        // The witness body's four fields, with the caps the schema enforces rather than numbers a client would
        // discover by being refused. All four required on create -- every column is NOT NULL -- and none on the
        // patch, which is the whole difference between the two bodies.
        String witness = "/components/schemas/CreateWitnessRequest/properties/";
        assertThat(spec.at(witness + "witnessName/maxLength").asInt())
            .isEqualTo(WitnessSchemas.MAX_WITNESS_NAME_LENGTH);
        assertThat(spec.at(witness + "witnessAddress/maxLength").asInt())
            .isEqualTo(WitnessSchemas.MAX_WITNESS_ADDRESS_LENGTH);
        assertThat(spec.at(witness + "witnessOccupation/maxLength").asInt())
            .isEqualTo(WitnessSchemas.MAX_WITNESS_OCCUPATION_LENGTH);
        // Trimmed and refused if blank (review finding R15), so each text field publishes a floor on both bodies.
        for (String body : List.of("CreateWitnessRequest", "UpdateWitnessRequest")) {
            for (String text : List.of("witnessName", "witnessAddress", "witnessOccupation")) {
                assertThat(spec.at("/components/schemas/" + body + "/properties/" + text + "/minLength").asInt())
                    .as("%s.%s", body, text)
                    .isEqualTo(1);
            }
        }
        // A boolean and not a string: the schema refuses "true" rather than coercing it, and a document that
        // called this a string would have clients sending the one value that coerces wrongly.
        assertThat(typesOf(spec.at(witness + "declarant"))).contains("boolean");
        assertThat(spec.at("/components/schemas/CreateWitnessRequest/required").size()).isEqualTo(4);
        assertThat(spec.at("/components/schemas/UpdateWitnessRequest/required").isMissingNode()).isTrue();
        // And no null offered anywhere on the patch, because an explicit null is a 400 rather than a clear.
        assertThat(typesOf(spec.at("/components/schemas/UpdateWitnessRequest/properties/witnessName")))
            .as("a witness field cannot be cleared, so the document does not offer a null")
            .doesNotContain("null");

        // The referral body, whose two caps are the ones that keep a referral from being stored and then refused
        // by the case note it writes -- so they are asserted to be ReferralSchemas' own numbers rather than
        // whatever the document happens to say. reasons is the only outright requirement; notes and urgencyReason
        // are required conditionally, which OpenAPI cannot express and the description carries in prose.
        String referral = "/components/schemas/CreateReferralRequest/properties/";
        assertThat(spec.at(referral + "notes/maxLength").asInt()).isEqualTo(ReferralSchemas.MAX_NOTES_LENGTH);
        assertThat(spec.at(referral + "urgencyReason/maxLength").asInt())
            .isEqualTo(ReferralSchemas.MAX_URGENCY_REASON_LENGTH);
        assertThat(typesOf(spec.at(referral + "reasons"))).contains("array");
        assertThat(spec.at(referral + "reasons/minItems").asInt()).isEqualTo(1);
        // The vocabulary reaches the document from the enum that validates it, so the two cannot drift.
        List<String> publishedReasons = StreamSupport
            .stream(spec.at(referral + "reasons/items/enum").spliterator(), false)
            .map(JsonNode::asText)
            .toList();
        assertThat(publishedReasons)
            .containsExactlyElementsOf(Arrays.stream(ReferralReason.values()).map(Enum::name).toList());
        assertThat(spec.at("/components/schemas/CreateReferralRequest/required").size()).isEqualTo(1);
        assertThat(spec.at("/components/schemas/CreateReferralRequest/required/0").asText()).isEqualTo("reasons");
        // And no update body, because a referral is not edited in place: amending one is withdrawing it and
        // raising another. Asserted rather than left implicit, since the tidy-minded addition would be a PATCH.
        assertThat(spec.at("/components/schemas/UpdateReferralRequest").isMissingNode())
            .as("there is no referral patch body, because there is no referral patch")
            .isTrue();

        // The judicial direction: two required fields and one optional, with the cap and the vocabulary taken from
        // what enforces them rather than from whatever the document happens to say.
        String direction = "/components/schemas/JudicialDirectionRequest/properties/";
        assertThat(spec.at(direction + "directionsText/maxLength").asInt())
            .isEqualTo(ReferralSchemas.MAX_DIRECTIONS_LENGTH);
        List<String> publishedDecisions = StreamSupport
            .stream(spec.at(direction + "decision/enum").spliterator(), false)
            .map(JsonNode::asText)
            .toList();
        assertThat(publishedDecisions)
            .containsExactlyElementsOf(Arrays.stream(MasterDecision.values()).map(Enum::name).toList());
        // A boolean and required, which is the pair that matters: publication puts the applicant's former and new
        // names in a public notice, so a document calling this a string would have clients sending "false" -- the
        // one value a truthy read gets wrong, and a disclosure that cannot be taken back.
        assertThat(typesOf(spec.at(direction + "changesToStandardPublication"))).contains("boolean");
        // The pre-2026-10-27 name's one release is over: not on the body, nor on either referral response.
        assertThat(spec.at(direction + "publishToGazette").isMissingNode()).isTrue();
        assertThat(spec.at("/components/schemas/ReferralResponse/properties/publishToGazette").isMissingNode())
            .isTrue();
        assertThat(spec.at("/components/schemas/JudicialReferralDetail/properties/publishToGazette").isMissingNode())
            .isTrue();
        assertThat(spec.at("/components/schemas/JudicialDirectionRequest/required").size()).isEqualTo(2);
        assertThat(StreamSupport
                       .stream(spec.at("/components/schemas/JudicialDirectionRequest/required").spliterator(),
                               false)
                       .map(JsonNode::asText)
                       .toList())
            .containsExactlyInAnyOrder("decision", "changesToStandardPublication");
        // Nothing nullable anywhere on it: a direction is recorded once and complete, so there is no field to
        // clear and no patch that could clear one.
        assertThat(typesOf(spec.at(direction + "decision")))
            .as("a direction cannot be cleared, so the document offers no null")
            .doesNotContain("null");

        // The two email bodies, which carry the slots and nothing else. Both are asserted rather than one, because
        // they are separate types for separate operations and the customiser could describe either alone.
        for (String email : List.of("PreviewEmailRequest", "SendEmailRequest")) {
            String slots = "/components/schemas/" + email + "/properties/";
            assertThat(spec.at(slots + EmailSlot.JUDGES_REASON.placeholder() + "/maxLength").asInt())
                .isEqualTo(ReferralSchemas.MAX_DIRECTIONS_LENGTH);
            assertThat(spec.at(slots + EmailSlot.ADDITIONAL_NOTES.placeholder() + "/maxLength").asInt())
                .isEqualTo(ReferralSchemas.MAX_NOTES_LENGTH);
            // ⑤'s slot, from the email package's own constant rather than ReferralSchemas': the two numbers are
            // equal today and are two independent decisions, so a change to what a judge may record must not move
            // what a caseworker may write to an applicant -- and a form renders the counter from this number.
            assertThat(spec.at(slots + EmailSlot.DOCUMENTS_REQUIRED.placeholder() + "/maxLength").asInt())
                .isEqualTo(EmailSlot.MAX_EMAIL_PROSE_LENGTH);
            // And what omitting each of them means, which is three answers and not two: describing every required
            // slot as pre-populated told a consumer that documents_required would be filled in for them, which is
            // the opposite of the rule -- so the sentence carries the slot's own reason for being required.
            assertThat(spec.at(slots + EmailSlot.DOCUMENTS_REQUIRED.placeholder() + "/description").asText())
                .as("%s says documents_required is required of the request", email)
                .contains("Required of the request")
                .contains(EmailSlot.DOCUMENTS_REQUIRED.requiredBecause())
                .doesNotContain("pre-populated from the referral");
            assertThat(spec.at(slots + EmailSlot.JUDGES_REASON.placeholder() + "/description").asText())
                .as("%s says judges_reason is required of the email and not of the request", email)
                .contains("pre-populated from the referral's direction");
            assertThat(spec.at(slots + EmailSlot.ADDITIONAL_NOTES.placeholder() + "/description").asText())
                .as("%s says additional_notes is optional", email)
                .startsWith("Optional");
            // The slots are the whole body: a client must not be told it may send reference_number, which is the
            // one thing this payload exists to refuse.
            assertThat(spec.at("/components/schemas/" + email + "/properties").size())
                .isEqualTo(EmailSlot.values().length);
            assertThat(spec.at(slots + ApplicationRejectedPersonalisation.REFERENCE_NUMBER).isMissingNode())
                .as("%s does not offer a resolved value as a field", email)
                .isTrue();
            // The only body in the document that closes itself, and the only one where the service refuses an
            // unknown key rather than stripping it. Publishing true here would describe an API that quietly
            // ignored an attempt to set the reference an applicant is told.
            assertThat(spec.at("/components/schemas/" + email + "/additionalProperties").asBoolean(true))
                .as("%s refuses unknown keys rather than ignoring them", email)
                .isFalse();
            // Nothing declared required: judges_reason is required of the email and not of the request, because it
            // is pre-populated from the direction -- a client declaring it mandatory would refuse the ordinary
            // case, which is a caseworker sending the judge's words unaltered.
            assertThat(spec.at("/components/schemas/" + email + "/required").isMissingNode())
                .as("%s declares no required field", email)
                .isTrue();
        }

        // The upload, which had no body at all: the media type is as load-bearing as the fields, because a client
        // that posts JSON to it fails whatever field names it uses.
        JsonNode uploadForm = spec.at(
            "/paths/~1caseworker~1cases~1{id}~1documents/post/requestBody/content/multipart~1form-data/schema");
        assertThat(uploadForm.isObject()).as("the upload declares a multipart/form-data body").isTrue();
        assertThat(uploadForm.at("/properties/file/format").asText()).isEqualTo("binary");
        assertThat(uploadForm.at("/properties/documentTypeId").isObject()).isTrue();
        assertThat(uploadForm.at("/properties/name").isObject()).isTrue();
        assertThat(uploadForm.at("/properties/direction/enum").isArray()).isTrue();
        assertThat(uploadForm.at("/required/0").asText()).isEqualTo("documentTypeId");
    }

    /**
     * The case list's four query parameters, asserted to be described rather than reflected.
     *
     * <p>The same argument as the write bodies, on the way in instead of the way out. The list binds its
     * parameters from a {@code MultiValueMap} because a repeated {@code where} cannot arrive any other way, and
     * springdoc reflects that into one required parameter literally named {@code parameters} -- a document that
     * says a bare list request is a 400 and never mentions {@code where}, {@code q}, {@code sort}, {@code page} or
     * {@code size}. {@link uk.gov.hmcts.reform.deedpoll.config.OpenAPIQueryParameters} replaces it; this pins
     * that it stays replaced, because {@code deed-poll-admin} generates its client from this document and the
     * reflected form is one a client cannot call the operation with at all.
     */
    @DisplayName("Describes the case list's query parameters rather than the map they bind from")
    @Test
    void documentsTheCaseListQueryParameters() throws Exception {
        JsonNode spec = JSON.readTree(mvc.perform(get("/v3/api-docs"))
                                          .andExpect(status().isOk())
                                          .andReturn()
                                          .getResponse()
                                          .getContentAsByteArray());

        JsonNode parameters = spec.at("/paths/~1caseworker~1cases/get/parameters");
        assertThat(parameters.isArray()).isTrue();

        Map<String, JsonNode> byName = new LinkedHashMap<>();
        parameters.forEach(parameter -> byName.put(parameter.at("/name").asText(), parameter));

        // The binding mechanism is not part of the surface, and neither is the component it was typed with.
        assertThat(byName).as("the reflected map parameter is gone").doesNotContainKey("parameters");
        assertThat(spec.at("/components/schemas/MultiValueMapStringString").isMissingNode())
            .as("and so is the schema springdoc generated for it")
            .isTrue();

        assertThat(byName.keySet()).containsExactlyInAnyOrder("filter", "where", "q", "sort", "page", "size");

        // Not one of them is required: a bare list is the service's front page, and the reflected document said
        // otherwise. This is the assertion that would have caught the published form.
        byName.forEach((name, parameter) ->
                           assertThat(parameter.at("/required").asBoolean())
                               .as("%s is optional", name)
                               .isFalse());

        // `where` repeats rather than taking one comma-separated value, and `explode` on an array is what says
        // so. A client that joined conditions with commas would send one unparseable entry, and a comma is
        // already meaningful inside a `between` or `isOneOf` value.
        assertThat(byName.get("where").at("/schema/type").asText()).isEqualTo("array");
        assertThat(byName.get("where").at("/explode").asBoolean()).isTrue();

        // The paging defaults are the domain's constants, so the document tracks CasePaging rather than a number
        // written here -- and no `maximum`, because an over-cap size is clamped and answered, not refused.
        assertThat(byName.get("page").at("/schema/default").asInt()).isEqualTo(CasePaging.FIRST_PAGE);
        assertThat(byName.get("size").at("/schema/default").asInt()).isEqualTo(CasePaging.DEFAULT_SIZE);
        assertThat(byName.get("size").at("/schema/maximum").isMissingNode())
            .as("a clamped cap is described in prose, not as a constraint a client would enforce")
            .isTrue();
        assertThat(byName.get("size").at("/description").asText())
            .contains(String.valueOf(CasePaging.MAX_SIZE));

        // The operator table reaches the document from the enum that validates it, so the two cannot drift.
        String where = byName.get("where").at("/description").asText();
        for (QueryOperator operator : QueryOperator.values()) {
            assertThat(where).as("the document lists the %s operator", operator.wire()).contains(operator.wire());
        }

        // And the sortable fields likewise, from CaseSort rather than restated.
        String sort = byName.get("sort").at("/description").asText();
        CaseSort.sortableFields()
            .forEach(field -> assertThat(sort).as("the document lists %s as sortable", field).contains(field));

        // The searched columns come from the matcher for the same reason, and the description has to say the
        // matching is not fuzzy: a consumer that promised typo tolerance would be promising for the service.
        String search = byName.get("q").at("/description").asText();
        CaseSearch.NAME_FIELDS
            .forEach(field -> assertThat(search).as("the document lists %s as searched", field).contains(field));
        assertThat(search).contains("not fuzzy");

        // And the tab keys, which springdoc reflects as a bare optional string: the keys are a list in the domain
        // rather than an enum, so a consumer had no way to learn them from this document at all. deed-poll-admin
        // renders the tab bar from here, which is what makes the waiting list's own key discoverable rather than
        // something to be read out of this repository. Keys and labels both, from ListFilters rather than
        // restated, so a sixth tab is published without this assertion or the customiser being edited.
        String filter = byName.get("filter").at("/description").asText();
        ListFilters.filterTabs().forEach(tab -> assertThat(filter)
            .as("the document lists the %s tab", tab.key())
            .contains(tab.key())
            .contains(tab.label()));
        assertThat(filter)
            .as("and says an unrecognised key falls back rather than being refused")
            .contains(ListFilters.LIST_FILTERS.get(0).key())
            .contains("publishToLondonGazette=true", "dateNoticeSentToLondonGazette", "state=Enroled",
                      "dateEnroled:asc");
    }

    /**
     * The confirmation sub-resource's whole contract: its body, its answer, and the two fields the wait adds to
     * the case list and the case read.
     *
     * <p><strong>Published because a screen is built against it.</strong> Recording the applicant's answer to the
     * ④ email and showing which cases are still waiting is a {@code deed-poll-admin} screen, and that repository
     * generates its client from this document -- so an unpublished field is a screen that cannot be written, and a
     * field published under the wrong name is one written against a call that 400s.
     *
     * <p><strong>The negative halves are the security ones.</strong> {@code confirmationRecordedBy} and
     * {@code confirmationReceivedAt} come from the token and the clock, so the <em>request</em> body must not
     * offer them -- a document that did would have clients sending an author the service strips, which is an
     * attribution a caller believes it set. And the wait is a caseworker field: no judicial schema may carry it,
     * asserted over the whole document as text rather than schema by schema, so a type this test does not know
     * about cannot publish one either.
     */
    @DisplayName("Documents the confirmation body, its answer, and the wait on the list and the case")
    @Test
    void documentsTheConfirmationContract() throws Exception {
        JsonNode spec = JSON.readTree(mvc.perform(get("/v3/api-docs"))
                                          .andExpect(status().isOk())
                                          .andReturn()
                                          .getResponse()
                                          .getContentAsByteArray());

        // The route answers with the referral, which is what lets a client re-render the tab from the response
        // rather than reading the referral again.
        assertThat(spec.at("/paths/~1caseworker~1cases~1{id}~1referrals~1{referralId}~1confirmation"
                               + "/post/responses/200/content/*~1*/schema/$ref").asText())
            .isEqualTo("#/components/schemas/ReferralResponse");

        // The body: the outcome vocabulary from the enum that validates it and the column it is stored in, so the
        // document cannot offer a label Postgres lacks; the note's cap from the schema that enforces it.
        String confirmation = "/components/schemas/RecordConfirmationRequest/properties/";
        List<String> publishedOutcomes = StreamSupport
            .stream(spec.at(confirmation + "outcome/enum").spliterator(), false)
            .map(JsonNode::asText)
            .toList();
        assertThat(publishedOutcomes)
            .containsExactlyElementsOf(Arrays.stream(ConfirmationOutcome.values()).map(Enum::name).toList());
        assertThat(spec.at(confirmation + "note/maxLength").asInt())
            .isEqualTo(ReferralSchemas.MAX_CONFIRMATION_NOTE_LENGTH);

        // The outcome is required and the note is not: the answer is the fact, and a required field whose usual
        // value is empty is one every caller fills in without reading.
        assertThat(StreamSupport
                       .stream(spec.at("/components/schemas/RecordConfirmationRequest/required").spliterator(),
                               false)
                       .map(JsonNode::asText)
                       .toList())
            .containsExactly("outcome");

        // Two fields the body does not offer, and one it never could. The author and the received date are the
        // token's and the clock's, and the document says they are ignored rather than refused -- which is what
        // `additionalProperties: true` next to it means, and why publishing `false` here would describe an API
        // that 400s the body a tidy-minded client sends.
        assertThat(spec.at(confirmation + "confirmationRecordedBy").isMissingNode())
            .as("the body cannot name an author")
            .isTrue();
        assertThat(spec.at(confirmation + "confirmationReceivedAt").isMissingNode())
            .as("the body cannot backdate a reply")
            .isTrue();
        assertThat(spec.at("/components/schemas/RecordConfirmationRequest/additionalProperties").asBoolean(false))
            .as("an unknown key is stripped, as it is on every other keyed body")
            .isTrue();
        String bodyDescription = spec.at("/components/schemas/RecordConfirmationRequest/description").asText();
        assertThat(bodyDescription).contains("confirmationRecordedBy").contains("token").contains("409");

        // The answer: all four columns, because they are what the referrals tab renders -- the date it was asked,
        // what came back, when it was recorded and by whom.
        JsonNode referral = spec.at("/components/schemas/ReferralResponse/properties");
        assertThat(referral.fieldNames()).toIterable()
            .contains("confirmationRequestedAt", "confirmationOutcome", "confirmationReceivedAt",
                      "confirmationRecordedBy");

        // The row says how long and the case says since when, which is the split the two DTOs keep: a day count
        // on the case read would be a number that was true when it was serialised.
        assertThat(spec.at("/components/schemas/CaseSummaryResponse/properties/daysAwaitingConfirmation")
                       .isObject())
            .as("the list row carries the days waited")
            .isTrue();
        assertThat(spec.at("/components/schemas/CaseSummaryResponse/properties/awaitingConfirmationSince")
                       .isMissingNode())
            .as("and not the date, which is the case read's")
            .isTrue();
        // A day count on the row, a date on the case, and both typed: a client that read "43" as a date, or the
        // date as a count, would render a waiting list nobody could act on.
        assertThat(typesOf(spec.at("/components/schemas/CaseSummaryResponse/properties/daysAwaitingConfirmation")))
            .contains("integer");
        for (String caseSchema : List.of("CaseDetailResponse", "SavedCaseResponse")) {
            JsonNode since = spec.at("/components/schemas/" + caseSchema + "/properties/awaitingConfirmationSince");
            assertThat(since.isObject())
                .as("%s publishes the wait beside the state", caseSchema)
                .isTrue();
            assertThat(since.at("/format").asText())
                .as("%s publishes it as a date-time, as it does every other case date", caseSchema)
                .isEqualTo("date-time");
        }

        // And no judicial schema publishes any of it, asserted over the whole document: a judge decided the
        // non-publication request and has no part in what the applicant then chose.
        List<String> judicialSchemas =
            List.of("JudicialReferralSummary", "JudicialReferralDetail", "JudicialApplicationView");
        for (String judicial : judicialSchemas) {
            JsonNode properties = spec.at("/components/schemas/" + judicial + "/properties");
            assertThat(properties.isObject()).as("%s is published at all", judicial).isTrue();
            List<String> names = new ArrayList<>();
            properties.fieldNames().forEachRemaining(names::add);
            assertThat(names)
                .as("%s publishes nothing about the applicant's confirmation", judicial)
                .noneMatch(name -> name.toLowerCase(Locale.ROOT).contains("confirmation"));
        }
    }

    /**
     * Every keyed write body publishes its fields, rather than the empty schema springdoc would reflect.
     *
     * <p><strong>This exists because the saved-query bodies shipped empty.</strong> Both are
     * {@code KeyedRequestBody} subclasses -- classes with no fields, deliberately, because a patch turns on the
     * difference between an omitted field and an explicit null -- so springdoc published
     * {@code CreateSavedQueryRequest: {}}. {@code OpenAPIWriteBodies} is what compensates and it was not extended
     * when the resource was added, which nothing noticed: the assertions above check paths, operation counts and
     * parameter descriptions, and none of them looks at a request body's properties.
     *
     * <p><strong>Derived from the classpath rather than a list, which is the point.</strong> A list here would
     * need editing for the tenth keyed body exactly as {@code OpenAPIWriteBodies} did for the ninth, and would be
     * forgotten the same way. Scanning for {@code KeyedRequestBody} subclasses means a new one fails this test on
     * the commit that introduces it, before it can reach a consumer -- and an empty published schema is worse than
     * a missing one, because a generated client compiles and then sends nothing.
     */
    @DisplayName("Publishes the fields of every keyed write body, so no consumer sees an empty schema")
    @Test
    void documentsEveryKeyedWriteBody() throws Exception {
        JsonNode schemas = JSON.readTree(mvc.perform(get("/v3/api-docs"))
                                             .andExpect(status().isOk())
                                             .andReturn()
                                             .getResponse()
                                             .getContentAsByteArray())
            .at("/components/schemas");

        List<Class<?>> keyedBodies = keyedRequestBodies();
        assertThat(keyedBodies)
            .as("the scan found the keyed bodies at all -- an empty list would make this test vacuous")
            .isNotEmpty();

        for (Class<?> body : keyedBodies) {
            JsonNode schema = schemas.get(body.getSimpleName());
            assertThat(schema)
                .as("%s is published at all", body.getSimpleName())
                .isNotNull();
            assertThat(schema.at("/properties").isMissingNode())
                .as("%s publishes its fields rather than an empty schema -- add it to OpenAPIWriteBodies",
                    body.getSimpleName())
                .isFalse();
            assertThat(schema.at("/properties").size())
                .as("%s publishes at least one property", body.getSimpleName())
                .isPositive();
        }
    }

    /**
     * Every concrete {@code KeyedRequestBody} subclass under the DTO packages.
     *
     * <p>Spring's own scanner, so this needs no extra dependency. Abstract classes are excluded --
     * {@code CaseWriteRequest} is a shared base and is not itself a body a route binds -- and springdoc names a
     * schema after the concrete class in any case.
     */
    private static List<Class<?>> keyedRequestBodies() {
        ClassPathScanningCandidateComponentProvider scanner =
            new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AssignableTypeFilter(KeyedRequestBody.class));
        return scanner.findCandidateComponents("uk.gov.hmcts.reform.deedpoll.api").stream()
            .map(BeanDefinition::getBeanClassName)
            .map(OpenAPIPublisherTest::loaded)
            .filter(type -> !Modifier.isAbstract(type.getModifiers()))
            .toList();
    }

    private static Class<?> loaded(String name) {
        try {
            return Class.forName(name);
        } catch (ClassNotFoundException impossible) {
            // The scanner found it on this classpath, so it loads.
            throw new IllegalStateException(name, impossible);
        }
    }
}
