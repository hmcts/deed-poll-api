package uk.gov.hmcts.reform.deedpoll.ccd;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import uk.gov.hmcts.ccd.sdk.api.callback.SubmitResponse;
import uk.gov.hmcts.reform.authorisation.filters.ServiceAuthFilter;
import uk.gov.hmcts.reform.deedpoll.auth.StubIdam;
import uk.gov.hmcts.reform.deedpoll.auth.StubS2s;
import uk.gov.hmcts.reform.deedpoll.ccd.CcdLinkRule.Outcome;
import uk.gov.hmcts.reform.deedpoll.config.AbstractPostgresContainerTest;
import uk.gov.hmcts.reform.deedpoll.entity.CcdCreationAttempt;
import uk.gov.hmcts.reform.deedpoll.entity.DeedPoll;
import uk.gov.hmcts.reform.deedpoll.entity.enums.CcdCreationAttemptStatus;
import uk.gov.hmcts.reform.deedpoll.repository.CcdCreationAttemptRepository;
import uk.gov.hmcts.reform.deedpoll.repository.DeedPollRepository;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code POST /ccd-persistence/cases} for {@code linkDeedPoll}, end to end through the SDK's own controller and
 * transaction against real Postgres: data-store's S2S token, the system user, the {@code Idempotency-Key}, the
 * handler, the {@code ccd}-schema writes and the view (plan 02, Task 6).
 *
 * <p>Not {@code @Transactional}: what is asserted is what the SDK's transaction commits or rolls back, and a test
 * transaction wrapped round it would hide both. The rule the handler carries out is stated container-free in
 * {@code CcdLinkRuleTest}, and its wiring in {@code LinkDeedPollHandlerTest}; Docker-free CI runs those and not this.
 *
 * <p>Rollback is proved two ways, because either half of the shared transaction can be the one that fails. The
 * handler is made to throw after its real bind has been observed on the transaction's own connection, and,
 * separately, a trigger makes the SDK's {@code ccd.case_event} insert fail after both the bind and
 * {@code ccd.case_data} have been written. Either way nothing survives.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CcdPersistenceSubmissionTest extends AbstractPostgresContainerTest {

    private static final String SYSTEM_ROLE = "deed-poll-system-under-test";
    private static final String SYSTEM_UID = "deed-poll-system-uid";
    private static final String CCD_DATA = "ccd-data-s2s-token";

    private static final Duration LEASE = Duration.ofMinutes(5);

    /** Sixteen digits, so every reference minted from it is one the service can store. */
    private static final long REFERENCES_BASE = 1_700_000_000_000_000L;
    private static final AtomicLong REFERENCES = new AtomicLong(REFERENCES_BASE);
    private static final AtomicLong DISPLAY_SERIALS = new AtomicLong(1);

    private static final StubIdam IDAM = new StubIdam();
    private static final StubS2s S2S = new StubS2s();

    static {
        // Started before the context reads their URLs, as SecurityConfigurationTest explains.
        IDAM.start();
        S2S.start();
    }

    @AfterAll
    static void stopStubs() {
        IDAM.stop();
        S2S.stop();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("idam.api.issuer-url", IDAM::issuerUrl);
        registry.add("idam.api.audiences", () -> StubIdam.AUDIENCE);
        // Where the SDK's own IdamClient resolves the user for the event's audit columns.
        registry.add("idam.api.url", IDAM::baseUrl);
        registry.add("idam.s2s-auth.url", S2S::baseUrl);
        registry.add("deedpoll.ccd.system-role", () -> SYSTEM_ROLE);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private CcdProperties ccd;

    @Autowired
    private DeedPollRepository deedPolls;

    @Autowired
    private CcdCreationAttempts attempts;

    @Autowired
    private CcdCreationAttemptRepository attemptRepository;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoSpyBean
    private LinkDeedPollHandler handler;

    @BeforeEach
    void stubs() {
        IDAM.stubDefaults();
        // The SDK writes the uid and the two names onto ccd.case_event, so the system user needs all three.
        IDAM.stubUserInfoBody("""
            {
              "sub": "%s",
              "uid": "%s",
              "name": "Deed Poll System",
              "given_name": "Deed Poll",
              "family_name": "System",
              "roles": ["caseworker", "%s"]
            }
            """.formatted(StubIdam.SUBJECT, SYSTEM_UID, SYSTEM_ROLE));
        S2S.reset();
        S2S.token(CCD_DATA, "ccd_data");
    }

    @BeforeEach
    @AfterEach
    void clearTables() {
        jdbc.execute("TRUNCATE ccd.case_data CASCADE");
        attemptRepository.deleteAll();
        deedPolls.deleteAll();
    }

    @Test
    @DisplayName("binds a claimed attempt's record once: the reference, one case row, one event, and the view")
    void bindsAClaimedAttempt() throws Exception {
        String record = record(CcdCreationAttemptStatus.Claimed);
        long reference = REFERENCES.incrementAndGet();

        submit(reference, record, UUID.randomUUID())
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.errors").isEmpty())
            .andExpect(jsonPath("$.case_details.case_data.deedPollId").value(record))
            .andExpect(jsonPath("$.case_details.case_data.deedPollReference").value(displayOf(record)));

        assertThat(boundReference(record)).isEqualTo(String.valueOf(reference));
        assertThat(caseRows(reference)).isOne();
        assertThat(events(reference)).isOne();
        assertThat(jdbc.queryForObject("""
            SELECT ce.user_id FROM ccd.case_event ce JOIN ccd.case_data cd ON cd.id = ce.case_data_id
             WHERE cd.reference = ?
            """, String.class, reference)).isEqualTo(SYSTEM_UID);
        // CCD's own data column holds nothing: the record is the truth, and the view composes the case from it.
        assertThat(jdbc.queryForObject("SELECT data::text FROM ccd.case_data WHERE reference = ?", String.class,
            reference)).isEqualTo("{}");
    }

    @Test
    @DisplayName("binds an attempt awaiting confirmation, which is a submission already expected")
    void bindsAnAttemptAwaitingConfirmation() throws Exception {
        String record = record(CcdCreationAttemptStatus.AwaitingConfirmation);
        long reference = REFERENCES.incrementAndGet();

        submit(reference, record, UUID.randomUUID())
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.errors").isEmpty());

        assertThat(boundReference(record)).isEqualTo(String.valueOf(reference));
    }

    @Test
    @DisplayName("a repeat with the same Idempotency-Key replays without running the handler; a new key is a no-op")
    void repeatsBindOnce() throws Exception {
        String record = record(CcdCreationAttemptStatus.Claimed);
        long reference = REFERENCES.incrementAndGet();
        UUID key = UUID.randomUUID();

        submit(reference, record, key).andExpect(status().isOk());
        submit(reference, record, key)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.case_details.case_data.deedPollId").value(record));
        assertThat(events(reference)).isOne();
        verify(spied(), times(1)).submit(any());

        // A second journey for the same case: the rule's ALREADY_BOUND, so another event and still one binding.
        submit(reference, record, UUID.randomUUID())
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.errors").isEmpty());
        assertThat(events(reference)).isEqualTo(2);
        assertThat(boundReference(record)).isEqualTo(String.valueOf(reference));
        verify(spied(), times(2)).submit(any());
    }

    @Test
    @DisplayName("a replay answers what was stored, even after the record has changed; a read answers the record")
    void replayAnswersTheStoredSnapshot() throws Exception {
        String record = record(CcdCreationAttemptStatus.Claimed);
        String before = displayOf(record);
        long reference = REFERENCES.incrementAndGet();
        UUID key = UUID.randomUUID();
        submit(reference, record, key).andExpect(status().isOk());

        jdbc.update("UPDATE deedpoll_deedpoll SET \"displayReference\" = 'KBDP2026-CHANGED' WHERE id = ?", record);

        submit(reference, record, key)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.case_details.case_data.deedPollReference").value(before));
        mockMvc.perform(get("/ccd-persistence/cases").param("case-refs", String.valueOf(reference))
                .headers(headers()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].case_details.case_data.deedPollReference").value("KBDP2026-CHANGED"))
            .andExpect(jsonPath("$[0].case_details.case_data.deedPollId").value(record));
    }

    @Test
    @DisplayName("refuses a second reference for a linked record, and writes nothing for it")
    void refusesAConflictingReference() throws Exception {
        String record = record(CcdCreationAttemptStatus.Claimed);
        long first = REFERENCES.incrementAndGet();
        long second = REFERENCES.incrementAndGet();
        submit(first, record, UUID.randomUUID()).andExpect(status().isOk());

        submit(second, record, UUID.randomUUID())
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.errors[0]").value(Outcome.RECORD_LINKED_ELSEWHERE.refusal()));

        assertThat(boundReference(record)).isEqualTo(String.valueOf(first));
        assertThat(caseRows(second)).isZero();
    }

    @Test
    @DisplayName("refuses a reference another record already holds, and leaves both as they were")
    void refusesATakenReference() throws Exception {
        String holder = record(CcdCreationAttemptStatus.Claimed);
        String other = record(CcdCreationAttemptStatus.Claimed);
        long reference = REFERENCES.incrementAndGet();
        submit(reference, holder, UUID.randomUUID()).andExpect(status().isOk());

        submit(reference, other, UUID.randomUUID())
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.errors[0]").value(Outcome.REFERENCE_TAKEN.refusal()));

        assertThat(boundReference(holder)).isEqualTo(String.valueOf(reference));
        assertThat(boundReference(other)).isNull();
        assertThat(events(reference)).isOne();
    }

    @Test
    @DisplayName("refuses a forged UUID, and creates no case")
    void refusesAForgedUuid() throws Exception {
        long reference = REFERENCES.incrementAndGet();

        submit(reference, UUID.randomUUID().toString(), UUID.randomUUID())
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.errors[0]").value(Outcome.NO_SUCH_RECORD.refusal()));

        assertThat(caseRows(reference)).isZero();
    }

    @Test
    @DisplayName("refuses a real record nothing has claimed: a UUID in a payload is not permission to bind a case")
    void refusesAnUnclaimedRecord() throws Exception {
        String pending = record(CcdCreationAttemptStatus.Pending);
        String withoutAttempt = deedPolls.save(new DeedPoll()).getId();
        String repair = record(CcdCreationAttemptStatus.NeedsRepair);

        for (String record : List.of(pending, withoutAttempt, repair)) {
            long reference = REFERENCES.incrementAndGet();
            submit(reference, record, UUID.randomUUID())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errors[0]").value(Outcome.NO_ATTEMPT_IN_PROGRESS.refusal()));
            assertThat(boundReference(record)).isNull();
            assertThat(caseRows(reference)).isZero();
        }
    }

    @Test
    @DisplayName("a handler that throws after binding rolls the bind back with the SDK's writes")
    void throwAfterBindingRollsBackTogether() throws Exception {
        String record = record(CcdCreationAttemptStatus.Claimed);
        long reference = REFERENCES.incrementAndGet();
        List<String> seenInsideTheTransaction = new ArrayList<>();
        doAnswer(invocation -> {
            SubmitResponse<?> response = (SubmitResponse<?>) invocation.callRealMethod();
            assertThat(response.getErrors()).isNullOrEmpty();
            // The JdbcTemplate joins the SDK's transaction, so this is the bind as that transaction sees it.
            seenInsideTheTransaction.add(boundReference(record));
            throw new IllegalStateException("thrown after binding");
        }).when(spied()).submit(any());

        assertThatThrownBy(() -> submit(reference, record, UUID.randomUUID()))
            .hasRootCauseMessage("thrown after binding");

        assertThat(seenInsideTheTransaction).containsExactly(String.valueOf(reference));
        assertThat(boundReference(record)).isNull();
        assertThat(caseRows(reference)).isZero();
    }

    @Test
    @DisplayName("an SDK write that fails after the bind and the case row rolls all three back together")
    void failingSdkWriteRollsBackTheBind() throws Exception {
        String record = record(CcdCreationAttemptStatus.Claimed);
        long reference = REFERENCES.incrementAndGet();
        jdbc.execute("""
            CREATE FUNCTION refuse_case_event_for_test() RETURNS trigger LANGUAGE plpgsql AS $$
            BEGIN
              RAISE EXCEPTION 'case_event refused by the test';
            END $$
            """);
        jdbc.execute("""
            CREATE TRIGGER refuse_case_event BEFORE INSERT ON ccd.case_event
              FOR EACH ROW EXECUTE FUNCTION refuse_case_event_for_test()
            """);
        try {
            assertThatThrownBy(() -> submit(reference, record, UUID.randomUUID()))
                .hasStackTraceContaining("case_event refused by the test");
        } finally {
            jdbc.execute("DROP TRIGGER refuse_case_event ON ccd.case_event");
            jdbc.execute("DROP FUNCTION refuse_case_event_for_test()");
        }

        assertThat(boundReference(record)).isNull();
        assertThat(caseRows(reference)).isZero();
        assertThat(attempts.live(record)).map(CcdCreationAttempt::getStatus).contains(CcdCreationAttemptStatus.Claimed);
    }

    /**
     * The spy itself, for Mockito. Through the bean's transactional proxy a stubbing or a verification is a call to
     * {@code submit} with no transaction, which {@code MANDATORY} refuses before Mockito sees it.
     */
    private LinkDeedPollHandler spied() {
        return AopTestUtils.getUltimateTargetObject(handler);
    }

    /**
     * A record with a display reference and a live attempt in {@code status}.
     */
    private String record(CcdCreationAttemptStatus status) {
        String id = deedPolls.save(new DeedPoll()).getId();
        jdbc.update("UPDATE deedpoll_deedpoll SET \"displayReference\" = ? WHERE id = ?",
            "KBDP2026-%06d".formatted(DISPLAY_SERIALS.getAndIncrement()), id);
        CcdCreationAttempt attempt = attempts.open(id).orElseThrow();
        if (status != CcdCreationAttemptStatus.Pending) {
            assertThat(attempts.claim(attempt.getId(), "worker-1", LEASE)).isTrue();
        }
        switch (status) {
            case AwaitingConfirmation ->
                assertThat(attempts.move(attempt.getId(), CcdCreationAttemptStatus.Claimed, status, null)).isTrue();
            case NeedsRepair ->
                assertThat(attempts.move(attempt.getId(), CcdCreationAttemptStatus.Claimed, status, "no answer"))
                    .isTrue();
            default -> {
            }
        }
        return id;
    }

    private ResultActions submit(long reference, String deedPollId, UUID idempotencyKey) throws Exception {
        HttpHeaders headers = headers();
        headers.set("Idempotency-Key", idempotencyKey.toString());
        return mockMvc.perform(post("/ccd-persistence/cases")
            .headers(headers)
            .contentType(MediaType.APPLICATION_JSON)
            .content(event(reference, deedPollId)));
    }

    /** A creation event as data-store sends it: no case before, and the two definition fields. */
    private String event(long reference, String deedPollId) {
        return """
            {
              "case_details": {
                "id": %d,
                "jurisdiction": "%s",
                "case_type_id": "%s",
                "state": "Registered",
                "security_classification": "PUBLIC",
                "case_data": {"deedPollId": "%s", "deedPollReference": "not trusted"}
              },
              "event_details": {"case_type": "%s", "event_id": "%s", "event_name": "Link deed poll"},
              "internal_case_id": %d
            }
            """.formatted(reference, ccd.jurisdictionId(), ccd.caseTypeId(), deedPollId, ccd.caseTypeId(),
            DeedPollCaseType.LINK_DEED_POLL, internalIdOf(reference));
    }

    /**
     * The id data-store gives the case's {@code ccd.case_data} row. One per reference, as data-store's own is: the
     * SDK writes the event against the id it is sent, not the id the upserted row already has.
     */
    private static long internalIdOf(long reference) {
        return reference - REFERENCES_BASE;
    }

    private static HttpHeaders headers() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(ServiceAuthFilter.AUTHORISATION, "Bearer " + CCD_DATA);
        headers.setBearerAuth(IDAM.accessToken());
        return headers;
    }

    private String displayOf(String record) {
        return jdbc.queryForObject("SELECT \"displayReference\" FROM deedpoll_deedpoll WHERE id = ?", String.class,
            record);
    }

    private String boundReference(String record) {
        return jdbc.queryForObject("SELECT \"ccdCaseReference\" FROM deedpoll_deedpoll WHERE id = ?", String.class,
            record);
    }

    private Integer caseRows(long reference) {
        return jdbc.queryForObject("SELECT count(*) FROM ccd.case_data WHERE reference = ?", Integer.class, reference);
    }

    private Integer events(long reference) {
        return jdbc.queryForObject("""
            SELECT count(*) FROM ccd.case_event ce JOIN ccd.case_data cd ON cd.id = ce.case_data_id
             WHERE cd.reference = ?
            """, Integer.class, reference);
    }
}
