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
import uk.gov.hmcts.reform.deedpoll.domain.auth.CurrentUser;
import uk.gov.hmcts.reform.deedpoll.domain.auth.Role;
import uk.gov.hmcts.reform.deedpoll.repository.DeedPollRepository;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Real PostgreSQL, full HTTP security context, real generator and a local Notify HTTP stub. */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(properties = {
    "deedpoll.gazette.enabled=true",
    "notify.email-reply-to-id=10000000-0000-0000-0000-000000000001", "notify.document-retention-weeks=4",
    "notify.templates.gazette-adult=20000000-0000-0000-0000-000000000001",
    "notify.templates.gazette-minor=20000000-0000-0000-0000-000000000002",
    "notify.templates.gazette-mixed=20000000-0000-0000-0000-000000000003"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class GazetteDeliveryApiTest extends AbstractPostgresContainerTest {
    private static final String ROOT = "/caseworker/gazette-notices";
    private static final String PREVIEW = "/caseworker/gazette-notice-previews";
    private static final String REQUESTS = "/caseworker/gazette-notice-requests";
    private static final String SUBJECT = "gazette-delivery-test";
    private static final CurrentUser ADMIN = CurrentUser.currentUser(List.of(Role.DEEDPOLL_ADMIN), "Admin", SUBJECT);
    private static final uk.gov.hmcts.reform.deedpoll.notify.StubNotify NOTIFY =
        new uk.gov.hmcts.reform.deedpoll.notify.StubNotify();
    private static final StubIdam IDAM = new StubIdam();
    private static final ObjectMapper JSON = new ObjectMapper();

    static {
        IDAM.start();
        NOTIFY.start();
    }

    @AfterAll
    static void stopIdam() {
        IDAM.stop();
        NOTIFY.stop();
    }

    @DynamicPropertySource
    static void idamProperties(DynamicPropertyRegistry registry) {
        registry.add("notify.api-url", NOTIFY::baseUrl);
        registry.add("notify.api-key", NOTIFY::apiKey);
        registry.add("idam.api.issuer-url", IDAM::issuerUrl);
        registry.add("idam.api.audiences", () -> StubIdam.AUDIENCE);
    }

    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    private uk.gov.hmcts.reform.deedpoll.notify.GazetteNotify notify;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    private GazetteConfiguration configuration;
    @Autowired
    private MockMvc mvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private GazettePreviews previews;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    private GazetteRequestLedger ledger;
    @Autowired
    private GazetteDelivery delivery;
    @Autowired
    private GazetteRecovery recovery;
    @Autowired
    private uk.gov.hmcts.reform.deedpoll.repository.GazetteRequestRepository requests;
    @Autowired
    private org.springframework.transaction.PlatformTransactionManager transactions;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    private GazetteSpreadsheet generator;
    @Autowired
    private DeedPollRepository cases;

    private final List<String> ownCases = new ArrayList<>();
    private Map<String, Object> oldSettings;

    @BeforeEach
    void configure() {
        IDAM.stubDefaults();
        NOTIFY.stubDefaults();
        accepted("20000000-0000-0000-0000-000000000001");
        IDAM.stubUserInfo(StubIdam.SUBJECT, "Admin", List.of(Role.DEEDPOLL_ADMIN.claimValue()));
        oldSettings = jdbc.queryForMap("SELECT * FROM deedpoll_gazette_settings WHERE id = 1");
        jdbc.update("""
            UPDATE deedpoll_gazette_settings SET recipient_email = 'gazette@example.com', revision = 1,
                updated_at = now(), updated_by_id = 'fixture', updated_by_name = 'Fixture'
            """);
    }

    @AfterEach
    void cleanOwnEvidence() {
        jdbc.execute("DROP TRIGGER IF EXISTS gazette_delivery_note_failure ON deedpoll_notes");
        jdbc.execute("DROP FUNCTION IF EXISTS gazette_delivery_note_failure()");
        for (String id : ownCases) {
            jdbc.update("DELETE FROM deedpoll_gazette_reservation WHERE deed_poll_id = ?", id);
            jdbc.update("DELETE FROM deedpoll_gazette_notice_batch_case WHERE deed_poll_id = ?", id);
            jdbc.update("DELETE FROM deedpoll_gazette_request_case WHERE deed_poll_id = ?", id);
        }
        jdbc.update("DELETE FROM deedpoll_gazette_request_file WHERE batch_id IN "
            + "(SELECT id FROM deedpoll_gazette_request WHERE submitted_by_id IN (?, ?))", SUBJECT, StubIdam.SUBJECT);
        jdbc.update("DELETE FROM deedpoll_gazette_request WHERE submitted_by_id IN (?, ?)", SUBJECT, StubIdam.SUBJECT);
        jdbc.update("DELETE FROM deedpoll_gazette_notice_batch WHERE sent_by_id IN (?, ?)", SUBJECT, StubIdam.SUBJECT);
        for (String id : ownCases) {
            jdbc.update("DELETE FROM deedpoll_deedpoll WHERE id = ?", id);
        }
        jdbc.update("""
            UPDATE deedpoll_gazette_settings SET recipient_email = ?, revision = ?, updated_at = ?,
                updated_by_id = ?, updated_by_name = ?
            """, oldSettings.get("recipient_email"), oldSettings.get("revision"), oldSettings.get("updated_at"),
            oldSettings.get("updated_by_id"), oldSettings.get("updated_by_name"));
    }


    @ParameterizedTest
    @ValueSource(strings = {"Adult", "Minor", "mixed"})
    void sendsOneEmailWithExactlyTheRequiredFilesAndSettlesEveryCase(String group) throws Exception {
        String template = "20000000-0000-0000-0000-00000000000" + (group.equals("mixed") ? "3"
            : group.equals("Minor") ? "2" : "1");
        accepted(template);
        List<String> ids = group.equals("mixed") ? List.of(ready("Adult"), ready("Minor")) : List.of(ready(group));
        GazettePreview preview = previews.preview(ADMIN, ids);
        UUID key = UUID.randomUUID();
        JsonNode result = json(submit(key, preview, ids.toArray(String[]::new))
            .andExpect(status().isOk()).andExpect(jsonPath("$.state").value("Sent")));
        UUID batch = UUID.fromString(result.get("batchId").asText());
        submit(key, preview, ids.toArray(String[]::new)).andExpect(status().isOk())
            .andExpect(jsonPath("$.batchId").value(batch.toString()));
        recovery.recover();
        assertThat(NOTIFY.emailRequests()).hasSize(1);
        JsonNode body = JSON.readTree(NOTIFY.emailRequests().getFirst().getBody());
        assertThat(body.get("template_id").asText()).isEqualTo(template);
        assertThat(body.get("email_address").asText()).isEqualTo("gazette@example.com");
        assertThat(body.get("email_reply_to_id").asText()).isEqualTo("10000000-0000-0000-0000-000000000001");
        assertThat(body.get("reference").asText()).isEqualTo(batch.toString());
        assertThat(body.at("/personalisation/case_count").asInt()).isEqualTo(ids.size());
        assertThat(body.get("personalisation").size()).isEqualTo(ids.size() + 1);
        for (String kind : List.of("adult", "minor")) {
            JsonNode upload = body.at("/personalisation/" + kind + "_spreadsheet");
            if (!upload.isMissingNode()) {
                assertThat(upload.get("confirm_email_before_download").asBoolean()).isTrue();
                assertThat(upload.get("retention_period").asText()).isEqualTo("4 weeks");
                byte[] content = java.util.Base64.getDecoder().decode(upload.get("file").asText());
                try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(content))) {
                    assertThat(workbook.getSheetAt(0).getLastRowNum()).isEqualTo(1);
                }
            }
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM deedpoll_gazette_request_file WHERE batch_id = ?",
            Integer.class, batch)).isEqualTo(ids.size());
        for (String id : ids) {
            assertThat(jdbc.queryForObject("SELECT count(*) FROM deedpoll_notes WHERE \"deedPollId\" = ?",
                Integer.class, id)).isEqualTo(2);
            assertThat(cases.findById(id).orElseThrow().getDateNoticeSentToLondonGazette()).isNotNull();
        }
        assertThat(result.toString()).doesNotContain("providerNotificationId", "owner", "payloadHash", "file");
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 403, 429, 500, 502, 503, 408, 401})
    void providerRefusalsAndAmbiguityAreTerminalAndNeverAutomaticallyRetried(int code) throws Exception {
        NOTIFY.stubSendStatus(code);
        String id = ready("Adult");
        GazettePreview preview = previews.preview(ADMIN, List.of(id));
        UUID key = UUID.randomUUID();
        boolean refused = code == 400 || code == 403 || code == 429;
        String state = refused ? "NotSent" : "Uncertain";
        submit(key, preview, id).andExpect(status().isOk()).andExpect(jsonPath("$.state").value(state))
            .andExpect(jsonPath("$.failureCode").value(refused ? "PROVIDER_REFUSED" : "SEND_OUTCOME_UNKNOWN"));
        submit(key, preview, id).andExpect(status().isOk()).andExpect(jsonPath("$.state").value(state));
        recovery.recover();
        assertThat(NOTIFY.emailRequests()).hasSize(1);
        assertThat(requests.reserved(id)).isEqualTo(!refused);
        assertThat(cases.findById(id).orElseThrow().getDateNoticeSentToLondonGazette()).isNull();
        if (refused) {
            accepted("20000000-0000-0000-0000-000000000001");
            submit(UUID.randomUUID(), previews.preview(ADMIN, List.of(id)), id)
                .andExpect(status().isOk()).andExpect(jsonPath("$.state").value("Sent"));
            assertThat(NOTIFY.emailRequests()).hasSize(2);
        } else {
            assertThatThrownBy(() -> previews.preview(ADMIN, List.of(id))).isInstanceOf(GazetteRefusal.class);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"oversize", "missingMinor", "generation", "prepare"})
    void preparationFailureNeverSendsAPartialBatch(String failure) throws Exception {
        String adult = ready("Adult");
        String minor = ready("Minor");
        GazettePreview preview = previews.preview(ADMIN, List.of(adult, minor));
        switch (failure) {
            case "oversize" -> org.mockito.Mockito.doReturn(List.of(
                new GeneratedGazetteSpreadsheet("gazette-adult.xlsx", 1, new byte[2 * 1024 * 1024 + 1]),
                new GeneratedGazetteSpreadsheet("gazette-minor.xlsx", 1, new byte[1])))
                .when(generator).generateInputs(org.mockito.ArgumentMatchers.anyList());
            case "missingMinor" -> org.mockito.Mockito.doReturn(List.of(
                new GeneratedGazetteSpreadsheet("gazette-adult.xlsx", 1, new byte[1])))
                .when(generator).generateInputs(org.mockito.ArgumentMatchers.anyList());
            case "generation" -> org.mockito.Mockito.doThrow(new IllegalStateException("private fixture"))
                .when(generator).generateInputs(org.mockito.ArgumentMatchers.anyList());
            case "prepare" -> org.mockito.Mockito.doThrow(new IllegalStateException("private fixture"))
                .when(notify).prepare(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyList());
            default -> throw new AssertionError();
        }
        UUID key = UUID.randomUUID();
        submit(key, preview, adult, minor).andExpect(status().isOk())
            .andExpect(jsonPath("$.state").value("NotSent"))
            .andExpect(jsonPath("$.failureCode").value("PREPARATION_FAILED"));
        submit(key, preview, adult, minor).andExpect(status().isOk());
        assertThat(NOTIFY.emailRequests()).isEmpty();
        assertThat(requests.reserved(adult)).isFalse();
        assertThat(requests.reserved(minor)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void unconfirmedSendAuthorityNeverCallsProvider(boolean commitHappened) throws Exception {
        org.mockito.Mockito.doAnswer(call -> {
            if (commitHappened) {
                call.callRealMethod();
            }
            throw new IllegalStateException("lost commit acknowledgement");
        }).when(ledger).startSend(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyList());
        String id = ready("Adult");
        JsonNode result = json(submit(UUID.randomUUID(), previews.preview(ADMIN, List.of(id)), id)
            .andExpect(status().isAccepted()));
        UUID batch = UUID.fromString(result.get("batchId").asText());
        age(batch);
        recovery.recover();
        assertThat(ledger.read(ADMIN, batch).state()).isEqualTo(commitHappened
            ? GazetteRequestState.Uncertain : GazetteRequestState.NotSent);
        assertThat(NOTIFY.emailRequests()).isEmpty();
    }

    @Test
    void acceptedSendWithFailedLocalNoteBecomesUncertainAndRetainsReceipt() throws Exception {
        failNotes("NEW.note = 'Notice of publication sent to the London Gazette.'");
        String id = ready("Adult");
        GazettePreview preview = previews.preview(ADMIN, List.of(id));
        UUID key = UUID.randomUUID();
        JsonNode response = json(submit(key, preview, id).andExpect(status().isOk())
            .andExpect(jsonPath("$.state").value("Uncertain")));
        UUID batch = UUID.fromString(response.get("batchId").asText());
        assertThat(ledger.read(ADMIN, batch).providerNotificationId()).isEqualTo(
            uk.gov.hmcts.reform.deedpoll.notify.StubNotify.NOTIFICATION_ID);
        assertThat(cases.findById(id).orElseThrow().getDateNoticeSentToLondonGazette()).isNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM deedpoll_gazette_notice_batch WHERE id = ?",
            Integer.class, batch)).isZero();
        assertThat(requests.reserved(id)).isTrue();
        submit(key, preview, id).andExpect(status().isOk()).andExpect(jsonPath("$.state").value("Uncertain"));
        recovery.recover();
        assertThat(NOTIFY.emailRequests()).hasSize(1);
    }

    @Test
    void settingsAndConfigurationAreFrozenBeforePreparationAndRemoteWorkIsOutsideTransactions() throws Exception {
        org.mockito.Mockito.doAnswer(call -> {
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager
                .isActualTransactionActive()).isFalse();
            jdbc.update("UPDATE deedpoll_gazette_settings SET recipient_email = 'changed@example.com', revision = 2");
            org.mockito.Mockito.doThrow(new GazetteRefusal(GazetteRefusal.Code.GAZETTE_UNAVAILABLE))
                .when(configuration).forGroups(1, 0);
            return call.callRealMethod();
        }).when(notify).prepare(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyList());
        org.mockito.Mockito.doAnswer(call -> {
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager
                .isActualTransactionActive()).isFalse();
            GazetteRequest row = call.getArgument(0);
            assertThat(ledger.read(ADMIN, row.id()).state()).isEqualTo(GazetteRequestState.Sending);
            return call.callRealMethod();
        }).when(notify).send(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        String id = ready("Adult");
        GazettePreview preview = previews.preview(ADMIN, List.of(id));
        UUID key = UUID.randomUUID();
        submit(key, preview, id).andExpect(status().isOk()).andExpect(jsonPath("$.state").value("Sent"));
        submit(key, preview, id).andExpect(status().isOk());
        assertThat(JSON.readTree(NOTIFY.emailRequests().getFirst().getBody()).get("email_address").asText())
            .isEqualTo("gazette@example.com");
    }

    @Test
    void recoveryIsBoundedIndependentOfDeliveryAndConcurrentReplicasWriteOneOutcome() throws Exception {
        List<UUID> batches = new ArrayList<>();
        for (int index = 0; index < 27; index++) {
            String id = ready("Adult");
            var row = admit(ADMIN, UUID.randomUUID(), previews.preview(ADMIN, List.of(id)), id);
            if (index % 2 == 0) {
                ledger.startSend(row.claim(), List.of(new GazetteFileEvidence("adult", 1, 100, "0".repeat(64))));
            }
            age(row.request().id());
            batches.add(row.request().id());
        }
        org.mockito.Mockito.doThrow(new GazetteRefusal(GazetteRefusal.Code.GAZETTE_UNAVAILABLE))
            .when(configuration).forGroups(org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt());
        org.mockito.Mockito.doThrow(new AssertionError("Recovery must never send"))
            .when(notify).send(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        recovery.recover();
        assertThat(batches.stream().map(id -> ledger.read(ADMIN, id).state())
            .filter(state -> state == GazetteRequestState.Preparing || state == GazetteRequestState.Sending))
            .hasSize(2);
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var one = executor.submit(recovery::recover);
            var two = executor.submit(recovery::recover);
            one.get(10, java.util.concurrent.TimeUnit.SECONDS);
            two.get(10, java.util.concurrent.TimeUnit.SECONDS);
        }
        for (String id : ownCases) {
            assertThat(jdbc.queryForObject("SELECT count(*) FROM deedpoll_notes WHERE \"deedPollId\" = ?",
                Integer.class, id)).isEqualTo(2);
        }
        assertThat(NOTIFY.emailRequests()).isEmpty();
    }

    @Test
    void failedRecoveryTransactionRemainsEligibleForNextScan() {
        String id = ready("Adult");
        var row = admit(ADMIN, UUID.randomUUID(), previews.preview(ADMIN, List.of(id)), id);
        age(row.request().id());
        failNotes("true");
        recovery.recover();
        assertThat(ledger.read(ADMIN, row.request().id()).state()).isEqualTo(GazetteRequestState.Preparing);
        assertThat(requests.reserved(id)).isTrue();
        jdbc.execute("DROP TRIGGER gazette_delivery_note_failure ON deedpoll_notes");
        recovery.recover();
        assertThat(ledger.read(ADMIN, row.request().id()).state()).isEqualTo(GazetteRequestState.NotSent);
        assertThat(requests.reserved(id)).isFalse();
    }

    @Test
    void deliveryRefusesAnEnclosingTransactionBeforePreparingOrSending() {
        String id = ready("Adult");
        GazettePreview preview = previews.preview(ADMIN, List.of(id));
        assertThatThrownBy(() -> new org.springframework.transaction.support.TransactionTemplate(transactions)
            .execute(status -> delivery.submit(ADMIN, UUID.randomUUID(), List.of(id),
                preview.selectionFingerprint(), 1))).isInstanceOf(IllegalStateException.class);
        assertThat(NOTIFY.emailRequests()).isEmpty();
    }


    @Test
    void simultaneousSameKeyAndOverlappingRequestsCannotSendTwice() throws Exception {
        String id = ready("Adult");
        GazettePreview preview = previews.preview(ADMIN, List.of(id));
        UUID key = UUID.randomUUID();
        var sending = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        org.mockito.Mockito.doAnswer(call -> {
            sending.countDown();
            assertThat(release.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            return call.callRealMethod();
        }).when(notify).send(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> delivery.submit(ADMIN, key, List.of(id),
                preview.selectionFingerprint(), 1));
            try {
                assertThat(sending.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                UUID repeated = delivery.submit(ADMIN, key, List.of(id), preview.selectionFingerprint(), 1);
                assertThat(ledger.read(ADMIN, repeated).state()).isEqualTo(GazetteRequestState.Sending);
                assertThatThrownBy(() -> delivery.submit(ADMIN, UUID.randomUUID(), List.of(id),
                    preview.selectionFingerprint(), 1)).isInstanceOf(GazetteRefusal.class);
                release.countDown();
                assertThat(first.get(10, java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(repeated);
            } finally {
                release.countDown();
            }
        }
        assertThat(NOTIFY.emailRequests()).hasSize(1);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void ownerExpiryBeforeOrAfterSendCannotClaimSent(boolean afterSendStart) throws Exception {
        if (afterSendStart) {
            org.mockito.Mockito.doAnswer(call -> {
                GazetteRequest row = call.getArgument(0);
                age(row.id());
                recovery.recover();
                return call.callRealMethod();
            }).when(notify).send(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        } else {
            org.mockito.Mockito.doAnswer(call -> {
                GazetteRequest row = call.getArgument(0);
                age(row.id());
                return call.callRealMethod();
            }).when(notify).prepare(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyList());
        }
        String id = ready("Adult");
        JsonNode result = json(submit(UUID.randomUUID(), previews.preview(ADMIN, List.of(id)), id)
            .andExpect(status().isOk()).andExpect(jsonPath("$.state").value(afterSendStart ? "Uncertain" : "NotSent")));
        var row = ledger.read(ADMIN, UUID.fromString(result.get("batchId").asText()));
        assertThat(row.providerNotificationId()).isEqualTo(afterSendStart
            ? uk.gov.hmcts.reform.deedpoll.notify.StubNotify.NOTIFICATION_ID : null);
        assertThat(NOTIFY.emailRequests()).hasSize(afterSendStart ? 1 : 0);
        assertThat(requests.reserved(id)).isEqualTo(afterSendStart);
        assertThat(cases.findById(id).orElseThrow().getDateNoticeSentToLondonGazette()).isNull();
    }

    @Test
    void processDeathAfterSendStartIsRecoveredWithoutProviderWork() {
        org.mockito.Mockito.doThrow(new AssertionError("simulated process death"))
            .when(notify).send(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        String id = ready("Adult");
        UUID key = UUID.randomUUID();
        GazettePreview preview = previews.preview(ADMIN, List.of(id));
        assertThatThrownBy(() -> delivery.submit(ADMIN, key, List.of(id), preview.selectionFingerprint(), 1))
            .isInstanceOf(AssertionError.class);
        UUID batch = requests.byKey(key).orElseThrow().id();
        age(batch);
        recovery.recover();
        assertThat(ledger.read(ADMIN, batch).state()).isEqualTo(GazetteRequestState.Uncertain);
        assertThat(delivery.submit(ADMIN, key, List.of(id), preview.selectionFingerprint(), 1)).isEqualTo(batch);
        assertThat(NOTIFY.emailRequests()).isEmpty();
        assertThat(requests.reserved(id)).isTrue();
    }

    @Test
    void lostAcceptanceCommitAcknowledgementPreservesSentWithoutAnotherCallOrNote() throws Exception {
        org.mockito.Mockito.doAnswer(call -> {
            call.callRealMethod();
            throw new IllegalStateException("lost commit acknowledgement");
        }).when(ledger).sent(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any());
        String id = ready("Adult");
        submit(UUID.randomUUID(), previews.preview(ADMIN, List.of(id)), id).andExpect(status().isOk())
            .andExpect(jsonPath("$.state").value("Sent"));
        assertThat(NOTIFY.emailRequests()).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM deedpoll_notes WHERE \"deedPollId\" = ?",
            Integer.class, id)).isEqualTo(2);
    }

    @Test
    void failedUncertaintySettlementLeavesSendMarkerForRecoveryWithoutResend() throws Exception {
        NOTIFY.stubSendStatus(500);
        failNotes("NEW.note = 'London Gazette email outcome is uncertain. Investigate before any resend.'");
        String id = ready("Adult");
        UUID key = UUID.randomUUID();
        GazettePreview preview = previews.preview(ADMIN, List.of(id));
        JsonNode response = json(submit(key, preview, id).andExpect(status().isAccepted())
            .andExpect(jsonPath("$.state").value("Sending")));
        UUID batch = UUID.fromString(response.get("batchId").asText());
        assertThat(requests.reserved(id)).isTrue();
        jdbc.execute("DROP TRIGGER gazette_delivery_note_failure ON deedpoll_notes");
        age(batch);
        recovery.recover();
        submit(key, preview, id).andExpect(status().isOk()).andExpect(jsonPath("$.state").value("Uncertain"));
        assertThat(NOTIFY.emailRequests()).hasSize(1);
    }

    private void age(UUID batch) {
        jdbc.update("UPDATE deedpoll_gazette_request SET submitted_at = now() - interval '10 minutes', "
            + "lease_until = now() - interval '5 minutes' WHERE id = ?", batch);
    }

    private void failNotes(String condition) {
        jdbc.execute("CREATE FUNCTION gazette_delivery_note_failure() RETURNS trigger LANGUAGE plpgsql AS $$ "
            + "BEGIN IF " + condition + " THEN RAISE EXCEPTION 'synthetic note failure'; END IF; RETURN NEW; END $$");
        jdbc.execute("CREATE TRIGGER gazette_delivery_note_failure BEFORE INSERT ON deedpoll_notes "
            + "FOR EACH ROW EXECUTE FUNCTION gazette_delivery_note_failure()");
    }

    private static void accepted(String template) {
        NOTIFY.server().stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(
            com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo("/v2/notifications/email"))
            .willReturn(com.github.tomakehurst.wiremock.client.WireMock.aResponse().withStatus(201)
                .withHeader("Content-Type", "application/json").withBody("""
                    {"id":"%s", "reference":"{{jsonPath request.body '$.reference'}}",
                     "template":{"id":"%s", "version":7, "uri":"/template"},
                     "content":{"subject":"Gazette", "body":"Synthetic", "from_email":"court@example.com"}}
                    """.formatted(uk.gov.hmcts.reform.deedpoll.notify.StubNotify.NOTIFICATION_ID, template))));
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
