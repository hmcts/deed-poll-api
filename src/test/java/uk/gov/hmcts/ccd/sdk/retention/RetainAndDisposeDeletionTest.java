package uk.gov.hmcts.ccd.sdk.retention;

import feign.FeignException;
import feign.Request;
import feign.Response;
import feign.RetryableException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.invocation.InvocationOnMock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.LinkedMultiValueMap;
import uk.gov.hmcts.ccd.sdk.RetainAndDisposePolicy;
import uk.gov.hmcts.ccd.sdk.api.EventPayload;
import uk.gov.hmcts.ccd.sdk.api.callback.SubmitResponse;
import uk.gov.hmcts.reform.authorisation.generators.AuthTokenGenerator;
import uk.gov.hmcts.reform.ccd.client.CoreCaseDataApi;
import uk.gov.hmcts.reform.ccd.client.model.CaseDataContent;
import uk.gov.hmcts.reform.ccd.client.model.CaseDetails;
import uk.gov.hmcts.reform.ccd.client.model.StartEventResponse;
import uk.gov.hmcts.reform.deedpoll.Application;
import uk.gov.hmcts.reform.deedpoll.ccd.CcdProperties;
import uk.gov.hmcts.reform.deedpoll.ccd.DeedPollCaseData;
import uk.gov.hmcts.reform.deedpoll.ccd.DeedPollCaseState;
import uk.gov.hmcts.reform.deedpoll.ccd.DisposalEventsHandler;
import uk.gov.hmcts.reform.deedpoll.config.AbstractPostgresContainerTest;
import uk.gov.hmcts.reform.deedpoll.domain.auth.CurrentUser;
import uk.gov.hmcts.reform.deedpoll.domain.auth.Role;
import uk.gov.hmcts.reform.deedpoll.domain.deletion.ApplicationDeletionService;
import uk.gov.hmcts.reform.deedpoll.domain.deletion.ApplicationDeletionService.DeletionCommand;
import uk.gov.hmcts.reform.deedpoll.domain.deletion.DeletionCleanup;
import uk.gov.hmcts.reform.deedpoll.domain.deletion.DeletionCleanupProperties;
import uk.gov.hmcts.reform.deedpoll.domain.deletion.DeletionFinalisation;
import uk.gov.hmcts.reform.deedpoll.domain.deletion.DeletionGracePeriods;
import uk.gov.hmcts.reform.deedpoll.domain.deletion.DeletionRequest;
import uk.gov.hmcts.reform.deedpoll.domain.deletion.DeletionStatus;
import uk.gov.hmcts.reform.deedpoll.domain.deletion.DeletionTestDocumentType;
import uk.gov.hmcts.reform.deedpoll.repository.DeletionRequestRepository;
import uk.gov.hmcts.reform.deedpoll.repository.DeletionWorkRepository;
import uk.gov.hmcts.reform.deedpoll.storage.LocalFilesystemDocumentStore;
import uk.gov.hmcts.reform.idam.client.IdamClient;
import uk.gov.hmcts.reform.idam.client.models.UserInfo;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Application deletion plan (20261118), Task 5: the SDK's own {@code RetainAndDisposeTask}, built by its own
 * auto-configuration over this application's beans (the policy, {@code JdbcClient}, {@code TransactionOperations},
 * the data source), against real Postgres, with data-store scripted.
 *
 * <p>The auto-configuration runs in a child of the application context, with {@code mode=live}, so its condition and
 * its bean inputs are proven to resolve here; the child has no scheduler, so the task runs only when called. Data-store
 * is a Mockito {@link CoreCaseDataApi}: {@code RetainAndDisposeContractTest} already pins the HTTP contract of every
 * status over a real Feign client, so this pins what the task then does with each answer. A submitted event runs this
 * service's real {@link DisposalEventsHandler} in a transaction, as {@code /ccd-persistence} would, and only then
 * moves the local copy as the SDK's persistence would. ConfirmDisposal's simulated {@code resolved_ttl} is yesterday,
 * standing for "the next day's run": data-store sets today, and the task disposes only of a TTL before today.
 */
@SpringBootTest(classes = Application.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
class RetainAndDisposeDeletionTest extends AbstractPostgresContainerTest {

    private static final CurrentUser DELIVERY_MANAGER =
        CurrentUser.currentUser(List.of(Role.DELIVERY_MANAGER), "Dee Manager", "retain-and-dispose-dm");
    private static final LocalDateTime MODIFIED = LocalDateTime.of(2026, 11, 18, 9, 30, 15, 250_000_000);
    private static final String SYSTEM_TOKEN = "Bearer system-user-token";
    private static final String SYSTEM_UID = "system-user-uid";

    @Autowired
    private ConfigurableApplicationContext application;

    @Autowired
    private ApplicationDeletionService deletions;

    @Autowired
    private DeletionRequestRepository requests;

    @Autowired
    private DeletionWorkRepository work;

    @Autowired
    private DisposalEventsHandler handler;

    @Autowired
    private DeletionFinalisation finalisation;

    @Autowired
    private CcdProperties ccd;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactions;

    @TempDir
    private Path documents;

    private final CoreCaseDataApi dataStore = mock(CoreCaseDataApi.class);
    /** What data-store answers a read of the case with: null for the case, else the exception to throw. */
    private final AtomicReference<RuntimeException> readAnswer = new AtomicReference<>();
    /** Whether the next submit commits its change and then loses the answer, or loses it before committing. */
    private final AtomicReference<String> submitFault = new AtomicReference<>();
    private final List<String> fixtures = new ArrayList<>();
    private final List<Long> ccdCases = new ArrayList<>();
    private DeletionCleanup cleanup;
    private TransactionTemplate transaction;

    @BeforeEach
    void setUp() {
        transaction = new TransactionTemplate(transactions);
        StaticListableBeanFactory store = new StaticListableBeanFactory();
        store.addBean("store", new LocalFilesystemDocumentStore(documents.toString()));
        cleanup = new DeletionCleanup(work, requests, store.getBeanProvider(LocalFilesystemDocumentStore.class),
            transactions, new DeletionCleanupProperties(false, Duration.ofMinutes(5), 10, Duration.ofMinutes(5), 8,
                                                        Duration.ofMinutes(1), Duration.ofHours(1)), finalisation);
        // Nothing another test left is due or in disposal.
        jdbc.update("""
            update deedpoll_deletion_request set next_attempt_at = next_attempt_at + interval '100 years'
             where status in ('Pending', 'InProgress', 'AwaitingDisposal')
            """);
        when(dataStore.startEventForCaseWorker(anyString(), anyString(), anyString(), anyString(), anyString(),
                                               anyString(), anyString())).thenAnswer(this::start);
        when(dataStore.submitEventForCaseWorker(anyString(), anyString(), anyString(), anyString(), anyString(),
                                                anyString(), anyBoolean(), any())).thenAnswer(this::submit);
        when(dataStore.getCase(anyString(), anyString(), anyString())).thenAnswer(this::read);
    }

    @AfterEach
    void cleanUp() {
        for (String id : fixtures) {
            jdbc.update("DELETE FROM deedpoll_deletion_request WHERE case_id = ?", id);
            jdbc.update("DELETE FROM deedpoll_deedpoll WHERE id = ?", id);
        }
        for (Long reference : ccdCases) {
            jdbc.update("DELETE FROM ccd.case_data WHERE reference = ?", reference);
        }
        DeletionTestDocumentType.removeIfUnused(jdbc);
    }

    // ---- data-store, scripted --------------------------------------------------------------------------------------

    private StartEventResponse start(InvocationOnMock call) {
        return StartEventResponse.builder()
            .caseDetails(CaseDetails.builder().id(Long.parseLong(call.getArgument(5))).data(Map.of()).build())
            .eventId(call.getArgument(6))
            .token("start-token")
            .build();
    }

    private CaseDetails submit(InvocationOnMock call) {
        long reference = Long.parseLong(call.getArgument(5));
        String eventId = ((CaseDataContent) call.getArgument(7)).getEvent().getId();
        if ("before".equals(submitFault.getAndSet(null))) {
            throw timeout();
        }
        boolean mark = RetainAndDisposePolicy.DISPOSAL_EVENT_ID.equals(eventId);
        String state = jdbc.queryForObject("SELECT state FROM ccd.case_data WHERE reference = ?", String.class,
                                           reference);
        if (mark != "Registered".equals(state)) {
            throw status(422, "submitEvent");
        }
        EventPayload<DeedPollCaseData, DeedPollCaseState> payload =
            new EventPayload<>(reference, new DeedPollCaseData(), new LinkedMultiValueMap<>());
        // As /ccd-persistence runs it: the handler inside the SDK's event transaction, then the SDK's own write.
        SubmitResponse<DeedPollCaseState> answer = transaction.execute(status -> {
            SubmitResponse<DeedPollCaseState> handled = mark
                ? handler.markForDisposal(payload) : handler.confirmDisposal(payload);
            if (handled.getErrors() == null || handled.getErrors().isEmpty()) {
                jdbc.update(mark
                    ? "UPDATE ccd.case_data SET state = 'PendingDisposal' WHERE reference = ?"
                    : "UPDATE ccd.case_data SET resolved_ttl = current_date - 1 WHERE reference = ?", reference);
            }
            return handled;
        });
        if (answer.getErrors() != null && !answer.getErrors().isEmpty()) {
            throw status(422, "submitEvent");
        }
        if ("after".equals(submitFault.getAndSet(null))) {
            throw timeout();
        }
        return CaseDetails.builder().id(reference).state(RetainAndDisposePolicy.DISPOSAL_STATE_ID).build();
    }

    private CaseDetails read(InvocationOnMock call) {
        RuntimeException answer = readAnswer.get();
        if (answer != null) {
            throw answer;
        }
        return CaseDetails.builder().id(Long.parseLong(call.getArgument(2))).build();
    }

    private static Request request() {
        return Request.create(Request.HttpMethod.GET, "/cases/1", Map.of(), null, StandardCharsets.UTF_8, null);
    }

    private static FeignException status(int status, String method) {
        return FeignException.errorStatus(method, Response.builder().status(status).reason("scripted")
            .request(request()).headers(Map.of()).build());
    }

    private static RetryableException timeout() {
        return new RetryableException(-1, "Read timed out", Request.HttpMethod.POST, new SocketTimeoutException(),
                                      (Long) null, request());
    }

    // ---- the task --------------------------------------------------------------------------------------------------

    /**
     * One run of the task the SDK's auto-configuration builds, with {@code mode=live}.
     */
    private Throwable runTask() {
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        IdamClient idam = mock(IdamClient.class);
        when(idam.getAccessToken("system-user", "not-a-real-password")).thenReturn(SYSTEM_TOKEN);
        when(idam.getUserInfo(SYSTEM_TOKEN)).thenReturn(UserInfo.builder().uid(SYSTEM_UID).build());
        new ApplicationContextRunner()
            .withParent(application)
            .withConfiguration(AutoConfigurations.of(RetainAndDisposeAutoConfiguration.class))
            .withPropertyValues("ccd.decentralised-runtime.retain-and-dispose.mode=live",
                                "ccd.decentralised-runtime.retain-and-dispose.system-user.username=system-user",
                                "ccd.decentralised-runtime.retain-and-dispose.system-user.password=not-a-real-password")
            .withBean("scriptedDataStore", CoreCaseDataApi.class, () -> dataStore, bean -> bean.setPrimary(true))
            .withBean("scriptedIdam", IdamClient.class, () -> idam, bean -> bean.setPrimary(true))
            .withBean("scriptedS2s", AuthTokenGenerator.class, () -> () -> "Bearer service-token",
                      bean -> bean.setPrimary(true))
            .run(child -> {
                assertThat(child).hasNotFailed().hasBean("retainAndDisposeTask");
                thrown.set(catchThrowable(child.getBean("retainAndDisposeTask", Runnable.class)::run));
            });
        return thrown.get();
    }

    // ---- fixtures --------------------------------------------------------------------------------------------------

    private String linkedCase() {
        String id = UUID.randomUUID().toString();
        fixtures.add(id);
        long reference = 1_000_000_000_000_000L + Math.floorMod(UUID.randomUUID().getMostSignificantBits(),
                                                                  8_999_999_999_999_999L);
        ccdCases.add(reference);
        jdbc.update("""
            INSERT INTO deedpoll_deedpoll (id, "modifiedOn", type, state, "displayReference", "ccdCaseReference")
            VALUES (?, ?, 'Adult'::"DeedPollType", 'Enroled', ?, ?)
            """, id, Timestamp.valueOf(MODIFIED), "disposal-" + id, String.valueOf(reference));
        jdbc.update("""
            INSERT INTO ccd.case_data (reference, id, security_classification, jurisdiction, case_type_id, state, data)
            VALUES (?, ?, 'PUBLIC', ?, ?, 'Registered', '{}')
            """, reference, reference, ccd.jurisdictionId(), ccd.caseTypeId());
        return id;
    }

    private String fileOn(String caseId) throws IOException {
        String key = UUID.randomUUID().toString();
        DeletionTestDocumentType.ensure(jdbc);
        jdbc.update("""
            INSERT INTO deedpoll_supportingdocument (id, "deedPollId", direction, name, "documentTypeId",
                "storageKey", "modifiedOn", "storageState")
            VALUES (?, ?, 'Incoming', 'Evidence', 'deletion-test-evidence',
                    ?, now(), 'Local')
            """, UUID.randomUUID().toString(), caseId, key);
        Files.writeString(documents.resolve(key), "applicant evidence");
        return key;
    }

    private void cdamDocumentOn(String caseId) {
        DeletionTestDocumentType.ensure(jdbc);
        jdbc.update("""
            INSERT INTO deedpoll_supportingdocument (id, "deedPollId", direction, name, "documentTypeId",
                "modifiedOn", "storageState", "cdamDocumentId", "storageUploadAttempts")
            VALUES (?, ?, 'Incoming', 'Evidence', 'deletion-test-evidence',
                    now(), 'Attached', ?, 1)
            """, UUID.randomUUID().toString(), caseId, UUID.randomUUID());
    }

    /** Admitted, and its grace period already over: the worker may claim it now. */
    private DeletionRequest admit(String caseId) {
        DeletionRequest request = deletions.request(DELIVERY_MANAGER, caseId, new DeletionCommand(UUID.randomUUID(),
            "DUPLICATE", "Second copy of a postal application logged twice", MODIFIED)).request();
        DeletionGracePeriods.elapse(jdbc, request.id());
        return requests.findById(request.id()).orElseThrow();
    }

    private long referenceOf(DeletionRequest request) {
        return Long.parseLong(request.ccdCaseReference());
    }

    private Map<String, Object> ccdCopy(long reference) {
        return jdbc.queryForList("SELECT state, resolved_ttl FROM ccd.case_data WHERE reference = ?", reference)
            .stream().findFirst().orElse(null);
    }

    private List<String> steps(DeletionRequest request) {
        return jdbc.queryForList("""
            SELECT kind || ':' || state || ':' || coalesce(outcome, '-') FROM deedpoll_deletion_step
             WHERE request_id = ? ORDER BY kind
            """, String.class, request.id());
    }

    private DeletionStatus statusOf(DeletionRequest request) {
        return requests.findById(request.id()).orElseThrow().status();
    }

    private void due(DeletionRequest request) {
        jdbc.update("UPDATE deedpoll_deletion_request SET next_attempt_at = now() - interval '1 day' WHERE id = ?",
                    request.id());
    }

    /** Admitted, files removed by the worker: ready for CCD. */
    private DeletionRequest readyForCcd() throws IOException {
        String id = linkedCase();
        String key = fileOn(id);
        cdamDocumentOn(id);
        DeletionRequest request = admit(id);
        assertThat(cleanup.run()).isOne();
        assertThat(documents.resolve(key)).doesNotExist();
        return request;
    }

    // ---- the journeys ----------------------------------------------------------------------------------------------

    @Test
    @DisplayName("marked, confirmed, retained while CCD still has it, then on 404 disposed and recorded")
    void fullJourney() throws IOException {
        DeletionRequest request = readyForCcd();
        long reference = referenceOf(request);

        assertThat(runTask()).isNull();
        assertThat(ccdCopy(reference)).containsEntry("state", "PendingDisposal");
        assertThat(ccdCopy(reference).get("resolved_ttl")).isNotNull();
        assertThat(steps(request)).contains("CCD_DISPOSAL:Pending:-", "CDAM_DOCUMENT:Pending:-");
        due(request);
        cleanup.run();
        assertThat(statusOf(request)).isEqualTo(DeletionStatus.AwaitingDisposal);

        readAnswer.set(status(404, "getCase"));
        assertThat(runTask()).isNull();

        assertThat(ccdCopy(reference)).as("the SDK deleted its local copy").isNull();
        assertThat(steps(request)).containsExactlyInAnyOrder("CCD_DISPOSAL:Done:DISPOSED_BY_CCD",
            "CDAM_DOCUMENT:Done:DISPOSED_BY_CCD", "LOCAL_DOCUMENT:Done:DELETED");
        assertThat(statusOf(request)).as("finalisation's, not yet Completed").isEqualTo(DeletionStatus.InProgress);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM deedpoll_deedpoll WHERE id = ?", Integer.class,
                                       request.caseId())).isOne();

        assertThat(cleanup.run()).as("due at once, and finalised").isOne();
        assertThat(statusOf(request)).isEqualTo(DeletionStatus.Completed);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM deedpoll_deedpoll WHERE id = ?", Integer.class,
                                       request.caseId())).isZero();
        assertThat(jdbc.queryForObject("""
            SELECT actor_id FROM deedpoll_audit_event WHERE case_id = ? AND action = 'CASE_DELETED'
            """, String.class, request.caseId())).isEqualTo(DELIVERY_MANAGER.userId());
    }

    @ParameterizedTest(name = "a {0} reading the disposed case is never absence")
    @ValueSource(ints = {401, 403, 500, 502, 503, -1})
    void failuresAreNotAbsence(int answer) throws IOException {
        DeletionRequest request = readyForCcd();
        final long reference = referenceOf(request);
        assertThat(runTask()).isNull();

        readAnswer.set(answer < 0 ? timeout() : status(answer, "getCase"));
        runTask();

        assertThat(ccdCopy(reference)).as("the local copy is kept").isNotNull();
        assertThat(steps(request)).contains("CCD_DISPOSAL:Pending:-");
        due(request);
        cleanup.run();
        assertThat(statusOf(request)).isEqualTo(DeletionStatus.AwaitingDisposal);
    }

    @Test
    @DisplayName("a 404 on the confirmation's visibility check aborts the run, and nothing is confirmed or disposed")
    void unreadableBeforeConfirmationAborts() throws IOException {
        DeletionRequest request = readyForCcd();
        long reference = referenceOf(request);
        readAnswer.set(status(404, "getCase"));

        assertThat(runTask()).isInstanceOf(IllegalStateException.class);

        assertThat(ccdCopy(reference)).containsEntry("state", "PendingDisposal").containsEntry("resolved_ttl", null);
        assertThat(steps(request)).contains("CCD_DISPOSAL:Pending:-");
    }

    @Test
    @DisplayName("a mark whose answer is lost before data-store commits is marked again on the next run")
    void timeoutBeforeSuccess() throws IOException {
        DeletionRequest request = readyForCcd();
        long reference = referenceOf(request);
        submitFault.set("before");

        runTask();
        assertThat(ccdCopy(reference)).containsEntry("state", "Registered");

        runTask();
        assertThat(ccdCopy(reference)).containsEntry("state", "PendingDisposal");
        assertThat(ccdCopy(reference).get("resolved_ttl")).isNotNull();
    }

    @Test
    @DisplayName("a mark whose answer is lost after data-store commits is confirmed, never marked twice")
    void timeoutAfterSuccess() throws IOException {
        DeletionRequest request = readyForCcd();
        long reference = referenceOf(request);
        submitFault.set("after");

        runTask();

        assertThat(ccdCopy(reference)).containsEntry("state", "PendingDisposal");
        assertThat(ccdCopy(reference).get("resolved_ttl")).as("confirmed in the same run").isNotNull();
        verify(dataStore).startEventForCaseWorker(anyString(), anyString(), anyString(), anyString(), anyString(),
            eq(String.valueOf(reference)), eq(RetainAndDisposePolicy.DISPOSAL_EVENT_ID));
    }

    @Test
    @DisplayName("a linked case whose files are not yet removed is not offered, and a stray event is refused")
    void notReadyIsNotOffered() throws IOException {
        String id = linkedCase();
        fileOn(id);
        DeletionRequest request = admit(id);
        long reference = referenceOf(request);

        assertThat(runTask()).isNull();
        verify(dataStore, never()).startEventForCaseWorker(anyString(), anyString(), anyString(), anyString(),
                                                           anyString(), anyString(), anyString());
        assertThat(ccdCopy(reference)).containsEntry("state", "Registered");

        linkedCase();
        long strangerReference = ccdCases.getLast();
        EventPayload<DeedPollCaseData, DeedPollCaseState> payload =
            new EventPayload<>(strangerReference, new DeedPollCaseData(), new LinkedMultiValueMap<>());
        assertThat(transaction.execute(status -> handler.markForDisposal(payload)).getErrors())
            .as("a case with no deletion request").isNotEmpty();
        assertThat(transaction.execute(status -> handler.markForDisposal(
            new EventPayload<>(reference, new DeedPollCaseData(), new LinkedMultiValueMap<>()))).getErrors())
            .as("a case whose own cleanup is not done").isNotEmpty();
    }
}
