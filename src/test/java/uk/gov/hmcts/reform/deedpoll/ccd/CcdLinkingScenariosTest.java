package uk.gov.hmcts.reform.deedpoll.ccd;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.extension.ResponseDefinitionTransformerV2;
import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.http.Request;
import com.github.tomakehurst.wiremock.http.ResponseDefinition;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import uk.gov.hmcts.reform.authorisation.filters.ServiceAuthFilter;
import uk.gov.hmcts.reform.deedpoll.auth.StubIdam;
import uk.gov.hmcts.reform.deedpoll.auth.StubS2s;
import uk.gov.hmcts.reform.deedpoll.ccd.CcdLinkingService.Result;
import uk.gov.hmcts.reform.deedpoll.config.AbstractPostgresContainerTest;
import uk.gov.hmcts.reform.deedpoll.domain.auth.CurrentUser;
import uk.gov.hmcts.reform.deedpoll.domain.auth.Role;
import uk.gov.hmcts.reform.deedpoll.entity.CcdCreationAttempt;
import uk.gov.hmcts.reform.deedpoll.entity.DeedPoll;
import uk.gov.hmcts.reform.deedpoll.entity.enums.CcdCreationAttemptStatus;
import uk.gov.hmcts.reform.deedpoll.repository.CcdCreationAttemptRepository;
import uk.gov.hmcts.reform.deedpoll.repository.DeedPollRepository;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Plan 02, Task 7: the linking operation against a fake CCD data-store, in every scenario the plan names -- success;
 * a lost response after CCD committed; a handler rollback; a conflicting existing link; a token refused between
 * start and submit; two workers racing on one record -- plus an expired claim and a case CCD shows under the wrong
 * case type. Each ends in one confirmed link or a visible {@code NeedsRepair}, and never a second case. Plan 02b,
 * Task 2 adds the submission flag and the wait: a failure before submission leaves it false, anything submitted
 * leaves it true, a claim lost before it is written submits nothing, and a released attempt waits for its
 * {@code notBefore}. Task 3 adds the sweep, switched on here: the case list request links at most
 * {@code max-records}, is not blocked by a stuck record, stops after one failure in an outage, and two at once make
 * one case per record.
 *
 * <p>The fake is WireMock on the outside, so the operation talks to it over HTTP through the real
 * {@code CoreCaseDataApi}, the real system-user password grant and the real S2S generator. On the inside it does
 * what data-store does for a decentralised case: it calls back into this service's own
 * {@code /ccd-persistence/cases}, as {@code ccd_data}, with the user's token and an {@code Idempotency-Key} derived
 * from the event token. So a case "created" here is a real binding and a real {@code ccd.case_data} row, committed
 * or rolled back by the SDK's own transaction, and "never a second case" is counted from the table.
 *
 * <p>Not {@code @Transactional}, for {@code CcdPersistenceSubmissionTest}'s reason. The decisions themselves are
 * pinned container-free in {@code CcdLinkingRuleTest} and {@code CcdLinkingServiceTest}; Docker-free CI runs those.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CcdLinkingScenariosTest extends AbstractPostgresContainerTest {

    private static final String SYSTEM_ROLE = "deed-poll-system-under-test";
    private static final String SYSTEM_USERNAME = "deed-poll-system-user";
    private static final String CCD_DATA = "ccd-data-s2s-token";
    private static final long REFERENCES_BASE = 1_700_000_000_000_000L;
    private static final AtomicLong DISPLAY_SERIALS = new AtomicLong(1);
    private static final CurrentUser ADMIN =
        CurrentUser.currentUser(List.of(Role.DEEDPOLL_ADMIN), "An Admin", "idam-admin");

    private static final StubIdam IDAM = new StubIdam();
    private static final StubS2s S2S = new StubS2s();
    private static final FakeDataStore FAKE = new FakeDataStore();
    private static final WireMockServer DATA_STORE = new WireMockServer(options().dynamicPort().extensions(FAKE));

    /** The S2S token this service leases, which the fake sees on every call. */
    private static final String LEASED;

    static {
        IDAM.start();
        S2S.start();
        DATA_STORE.start();
        DATA_STORE.stubFor(any(anyUrl()).willReturn(aResponse()));
        LEASED = IDAM.accessToken();
    }

    @AfterAll
    static void stopStubs() {
        IDAM.stop();
        S2S.stop();
        DATA_STORE.stop();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("idam.api.issuer-url", IDAM::issuerUrl);
        registry.add("idam.api.audiences", () -> StubIdam.AUDIENCE);
        registry.add("idam.api.url", IDAM::baseUrl);
        registry.add("idam.s2s-auth.url", S2S::baseUrl);
        // Base32 the TOTP library accepts, and a secret for nothing: the stub leases to anyone.
        registry.add("idam.s2s-auth.totp-secret", () -> "AAAAAAAAAAAAAAAA");
        registry.add("idam.client.id", () -> "deed-poll-test-client");
        registry.add("idam.client.secret", () -> "not-a-real-secret");
        registry.add("idam.client.redirect_uri", () -> "http://localhost/oauth2/callback");
        registry.add("idam.system-user.username", () -> SYSTEM_USERNAME);
        registry.add("idam.system-user.password", () -> "not-a-real-password");
        registry.add("core_case_data.api.url", DATA_STORE::baseUrl);
        registry.add("deedpoll.ccd.system-role", () -> SYSTEM_ROLE);
        // On here, and only in tests: the sweep scenarios drive linking through the case list request.
        registry.add("deedpoll.ccd.linking.sweep.enabled", () -> "true");
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private CcdProperties ccd;

    @Autowired
    private CcdLinkingService linking;

    @Autowired
    private CcdCreationAttempts attempts;

    @Autowired
    private CcdAttemptRepairs repairs;

    @Autowired
    private CcdCreationAttemptRepository attemptRepository;

    @Autowired
    private DeedPollRepository deedPolls;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void stubs() {
        IDAM.stubDefaults();
        IDAM.stubUserInfoBody("""
            {
              "sub": "%s",
              "uid": "deed-poll-system-uid",
              "name": "Deed Poll System",
              "given_name": "Deed Poll",
              "family_name": "System",
              "roles": ["caseworker", "%s"]
            }
            """.formatted(StubIdam.SUBJECT, SYSTEM_ROLE));
        IDAM.stubPasswordGrant();
        S2S.reset();
        S2S.token(CCD_DATA, "ccd_data");
        S2S.lease(LEASED);
        FAKE.reset(mockMvc, ccd);
    }

    @BeforeEach
    @AfterEach
    void clearTables() {
        jdbc.execute("TRUNCATE ccd.case_data CASCADE");
        attemptRepository.deleteAll();
        deedPolls.deleteAll();
    }

    @Test
    @DisplayName("success: one start, one submit, one case, and the attempt Linked -- as the system user, with S2S")
    void success() {
        String record = record();

        assertThat(linking.link(record)).isEqualTo(Result.LINKED);

        assertThat(status(record)).isEqualTo(CcdCreationAttemptStatus.Linked);
        assertThat(boundReference(record)).isNotNull();
        assertThat(submissionMayHaveReached(record)).isTrue();
        assertThat(cases()).isOne();
        assertThat(FAKE.starts.get()).isOne();
        assertThat(FAKE.submits.get()).isOne();
        assertThat(FAKE.serviceAuthorisation.get()).isEqualTo("Bearer " + LEASED);
        assertThat(FAKE.authorisation.get()).startsWith("Bearer ");
        IDAM.server().verify(postRequestedFor(urlPathEqualTo("/o/token"))
            .withRequestBody(containing("username=" + SYSTEM_USERNAME)));

        // And again: nothing is sent for a record already linked.
        assertThat(linking.link(record)).isEqualTo(Result.ALREADY_LINKED);
        assertThat(FAKE.starts.get()).isOne();
    }

    @Test
    @DisplayName("a lost response after CCD committed is read, not retried: AwaitingConfirmation until CCD answers")
    void lostResponseAfterCommit() {
        String record = record();
        FAKE.loseSubmitResponse = true;
        FAKE.unreadable.set(1);

        assertThat(linking.link(record)).isEqualTo(Result.CCD_UNANSWERED);
        assertThat(status(record)).isEqualTo(CcdCreationAttemptStatus.AwaitingConfirmation);
        assertThat(boundReference(record)).isNotNull();
        assertThat(submissionMayHaveReached(record)).isTrue();

        // CCD can be read again; reconciliation confirms what the binding said, and nothing was started twice.
        assertThat(linking.reconcileOutstanding()).isOne();
        assertThat(status(record)).isEqualTo(CcdCreationAttemptStatus.Linked);
        assertThat(cases()).isOne();
        assertThat(FAKE.starts.get()).isOne();
        assertThat(FAKE.submits.get()).isOne();
        assertThat(FAKE.reads.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("a handler rollback leaves nothing bound; it waits out the settle window, then is a repair item")
    void handlerRollback() {
        String record = record();
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
            assertThat(linking.link(record)).isEqualTo(Result.CCD_UNANSWERED);
        } finally {
            jdbc.execute("DROP TRIGGER refuse_case_event ON ccd.case_event");
            jdbc.execute("DROP FUNCTION refuse_case_event_for_test()");
        }
        assertThat(boundReference(record)).isNull();
        assertThat(cases()).isZero();

        // Inside the window a reconciliation only reads again.
        assertThat(linking.reconcileOutstanding()).isOne();
        assertThat(status(record)).isEqualTo(CcdCreationAttemptStatus.AwaitingConfirmation);

        ageAttempt(record);
        assertThat(linking.reconcileOutstanding()).isOne();
        assertThat(status(record)).isEqualTo(CcdCreationAttemptStatus.NeedsRepair);
        assertThat(detail(record)).isEqualTo(
            "No CCD case reference was bound to the record within the settle window");
        assertThat(linking.link(record)).isEqualTo(Result.NEEDS_REPAIR);
        assertThat(cases()).isZero();
        assertThat(FAKE.starts.get()).isOne();
        assertThat(FAKE.submits.get()).isOne();
    }

    @Test
    @DisplayName("a conflicting existing link is confirmed, not replaced: the record keeps its first case")
    void conflictingExistingLink() {
        String record = record();
        long earlier = FAKE.references.incrementAndGet();
        // An earlier submission for the same record lands just before this one: CCD refuses the second with 422.
        FAKE.beforeSubmit = deedPollId -> FAKE.persist(earlier, deedPollId, "earlier-token", null);

        assertThat(linking.link(record)).isEqualTo(Result.LINKED);

        assertThat(boundReference(record)).isEqualTo(String.valueOf(earlier));
        assertThat(cases()).isOne();
        assertThat(status(record)).isEqualTo(CcdCreationAttemptStatus.Linked);
        assertThat(FAKE.refusals.get()).isOne();
    }

    @Test
    @DisplayName("a token refused between start and submit created nothing, so the next due claim starts again, once")
    void tokenExpiryBetweenStartAndSubmit() {
        String record = record();
        FAKE.expired.add("token-1");

        assertThat(linking.link(record)).isEqualTo(Result.RETRY_LATER);
        assertThat(status(record)).isEqualTo(CcdCreationAttemptStatus.Pending);
        assertThat(detail(record)).isEqualTo("Submission refused: CCD answered HTTP 403");
        assertThat(boundReference(record)).isNull();
        assertThat(cases()).isZero();
        assertThat(submissionMayHaveReached(record)).isTrue();
        assertThat(jdbc.queryForObject("""
            SELECT "notBefore" > now() AT TIME ZONE 'UTC' + interval '4 minutes'
              FROM "deedpoll_ccdcreationattempt" WHERE "deedPollId" = ?
            """, Boolean.class, record)).isTrue();

        // Released with notBefore set: not claimed again, and nothing sent, until it is due.
        assertThat(linking.link(record)).isEqualTo(Result.RETRY_LATER);
        assertThat(FAKE.starts.get()).isOne();

        makeDue(record);
        assertThat(linking.link(record)).isEqualTo(Result.LINKED);
        assertThat(FAKE.starts.get()).isEqualTo(2);
        assertThat(cases()).isOne();
        assertThat(attempts.live(record)).isEmpty();
        assertThat(jdbc.queryForObject("""
            SELECT attempts FROM "deedpoll_ccdcreationattempt" WHERE "deedPollId" = ?
            """, Integer.class, record)).isEqualTo(2);
    }

    @Test
    @DisplayName("an IDAM failure before submission is released, and never marks the attempt as possibly submitted")
    void idamFailureLeavesTheFlagFalse() {
        String record = record();
        IDAM.server().stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(urlPathEqualTo("/o/token"))
            .willReturn(aResponse().withStatus(500)));

        assertThat(linking.link(record)).isEqualTo(Result.RETRY_LATER);

        assertThat(status(record)).isEqualTo(CcdCreationAttemptStatus.Pending);
        assertThat(detail(record)).startsWith("Failed before submission: ");
        assertThat(submissionMayHaveReached(record)).isFalse();
        assertThat(FAKE.starts.get()).isZero();
        assertThat(FAKE.submits.get()).isZero();
    }

    @Test
    @DisplayName("a refused start is released, and never marks the attempt as possibly submitted")
    void startCaseFailureLeavesTheFlagFalse() {
        String record = record();
        FAKE.startStatus = 503;

        assertThat(linking.link(record)).isEqualTo(Result.RETRY_LATER);

        assertThat(status(record)).isEqualTo(CcdCreationAttemptStatus.Pending);
        assertThat(detail(record)).isEqualTo("Failed before submission: CCD answered HTTP 503");
        assertThat(submissionMayHaveReached(record)).isFalse();
        assertThat(FAKE.starts.get()).isOne();
        assertThat(FAKE.submits.get()).isZero();
    }

    @Test
    @DisplayName("a claim lost between the start and the flag write submits nothing, and leaves the flag false")
    void claimLostBeforeTheFlagSubmitsNothing() {
        String record = record();
        // What reconciliation does to a claim it reads as expired, done while the worker waits on startCase.
        FAKE.duringStart = () -> jdbc.update("""
            UPDATE "deedpoll_ccdcreationattempt"
               SET status = 'AwaitingConfirmation', "claimedBy" = NULL, "leaseExpiresAt" = NULL
             WHERE "deedPollId" = ?
            """, record);

        assertThat(linking.link(record)).isEqualTo(Result.AWAITING_CONFIRMATION);

        assertThat(FAKE.starts.get()).isOne();
        assertThat(FAKE.submits.get()).isZero();
        assertThat(submissionMayHaveReached(record)).isFalse();
        assertThat(boundReference(record)).isNull();
        assertThat(cases()).isZero();
    }

    @Test
    @DisplayName("two workers racing on one record: one start, one case, one of them LINKED")
    void twoWorkersRace() throws Exception {
        String record = record();
        FAKE.startDelay = Duration.ofMillis(500);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            List<Future<Result>> results = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                results.add(workers.submit(() -> {
                    go.await();
                    return linking.link(record);
                }));
            }
            go.countDown();
            List<Result> seen = new ArrayList<>();
            for (Future<Result> result : results) {
                seen.add(result.get(30, TimeUnit.SECONDS));
            }
            assertThat(seen).containsOnlyOnce(Result.LINKED);
            assertThat(seen).allMatch(result -> result == Result.LINKED || result == Result.IN_PROGRESS
                || result == Result.ALREADY_LINKED);
        } finally {
            workers.shutdownNow();
        }
        assertThat(FAKE.starts.get()).isOne();
        assertThat(cases()).isOne();
        assertThat(status(record)).isEqualTo(CcdCreationAttemptStatus.Linked);
    }

    @Test
    @DisplayName("an expired claim goes to reconciliation, which confirms a submission that did land and starts none")
    void expiredClaimIsReconciled() {
        String record = record();
        CcdCreationAttempt attempt = attempts.live(record).orElseThrow();
        assertThat(attempts.claim(attempt.getId(), "a-worker-that-died", Duration.ofMinutes(5))).isPresent();
        jdbc.update("""
            UPDATE "deedpoll_ccdcreationattempt" SET "leaseExpiresAt" = "leaseExpiresAt" - interval '1 day'
             WHERE id = ?
            """, attempt.getId());

        assertThat(linking.reconcileOutstanding()).isOne();
        assertThat(status(record)).isEqualTo(CcdCreationAttemptStatus.AwaitingConfirmation);

        // The dead worker's submission reaches this service after all.
        FAKE.persist(FAKE.references.incrementAndGet(), record, "the-dead-workers-token", null);
        assertThat(linking.reconcileOutstanding()).isOne();
        assertThat(status(record)).isEqualTo(CcdCreationAttemptStatus.Linked);
        assertThat(FAKE.starts.get()).isZero();
        assertThat(cases()).isOne();
    }

    @Test
    @DisplayName("a case CCD shows under another case type contradicts the binding: repair at once, no second case")
    void contradictedCaseTypeNeedsRepair() {
        String record = record();
        FAKE.loseSubmitResponse = true;
        FAKE.caseTypeShown = "SOME_OTHER_CASE_TYPE";

        assertThat(linking.link(record)).isEqualTo(Result.NEEDS_REPAIR);

        assertThat(detail(record)).isEqualTo("CCD's case does not match the record's binding");
        assertThat(boundReference(record)).isNotNull();
        assertThat(cases()).isOne();
        assertThat(FAKE.starts.get()).isOne();
    }

    @Test
    @DisplayName("sweep: one list request links at most max-records records, oldest first")
    void sweepLinksAtMostMaxRecords() throws Exception {
        String oldest = unlinkedRecord(3);
        String middle = unlinkedRecord(2);
        final String newest = unlinkedRecord(1);

        listCases();

        assertThat(status(oldest)).isEqualTo(CcdCreationAttemptStatus.Linked);
        assertThat(status(middle)).isEqualTo(CcdCreationAttemptStatus.Linked);
        assertThat(attemptRepository.findByDeedPollIdOrderByCreatedOnAsc(newest)).isEmpty();
        assertThat(boundReference(newest)).isNull();
        assertThat(cases()).isEqualTo(2);

        listCases();
        assertThat(status(newest)).isEqualTo(CcdCreationAttemptStatus.Linked);
        assertThat(cases()).isEqualTo(3);
    }

    @Test
    @DisplayName("sweep: a NeedsRepair record at the head of the queue does not block the one behind it")
    void sweepIsNotBlockedByAStuckHead() throws Exception {
        String stuck = unlinkedRecord(2);
        assertThat(attempts.open(stuck)).isPresent();
        jdbc.update("""
            UPDATE "deedpoll_ccdcreationattempt" SET "status" = 'NeedsRepair' WHERE "deedPollId" = ?
            """, stuck);
        String next = unlinkedRecord(1);

        listCases();

        assertThat(status(next)).isEqualTo(CcdCreationAttemptStatus.Linked);
        assertThat(status(stuck)).isEqualTo(CcdCreationAttemptStatus.NeedsRepair);
        assertThat(cases()).isOne();
    }

    @Test
    @DisplayName("sweep: a CCD outage stops it after one failed call, and the list still answers 200 with its body")
    void sweepStopsOnAnOutage() throws Exception {
        String first = unlinkedRecord(2);
        final String second = unlinkedRecord(1);
        FAKE.startStatus = 503;

        String answered = listCases();

        assertThat(FAKE.starts.get()).isOne();
        assertThat(status(first)).isEqualTo(CcdCreationAttemptStatus.Pending);
        assertThat(detail(first)).isEqualTo("Failed before submission: CCD answered HTTP 503");
        assertThat(attemptRepository.findByDeedPollIdOrderByCreatedOnAsc(second)).isEmpty();
        assertThat(cases()).isZero();

        // The first is waiting out retry-after, so the second is the head now -- and CCD is still down.
        assertThat(listCases()).isEqualTo(answered);
        assertThat(FAKE.starts.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("sweep: a bound case CCD cannot read stops reconciliation after one read, and settles nothing")
    void sweepReconciliationStopsOnAnUnreadableCase() throws Exception {
        String first = record();
        final String second = record();
        FAKE.loseSubmitResponse = true;
        FAKE.unreadable.set(Integer.MAX_VALUE);
        assertThat(linking.link(first)).isEqualTo(Result.CCD_UNANSWERED);
        assertThat(linking.link(second)).isEqualTo(Result.CCD_UNANSWERED);
        FAKE.reads.set(0);

        listCases();

        assertThat(FAKE.reads.get()).isOne();
        assertThat(status(first)).isEqualTo(CcdCreationAttemptStatus.AwaitingConfirmation);
        assertThat(status(second)).isEqualTo(CcdCreationAttemptStatus.AwaitingConfirmation);
    }

    @Test
    @DisplayName("an admin's reconciliation of a bound record is linked by the next list request, starting nothing")
    void reconcileAgainIsLinkedByTheNextListRequest() throws Exception {
        String record = record();
        FAKE.loseSubmitResponse = true;
        FAKE.unreadable.set(Integer.MAX_VALUE);
        assertThat(linking.link(record)).isEqualTo(Result.CCD_UNANSWERED);
        ageAttempt(record);
        assertThat(linking.reconcileOutstanding()).isOne();
        assertThat(status(record)).isEqualTo(CcdCreationAttemptStatus.NeedsRepair);

        FAKE.unreadable.set(0);
        repairs.reconcileAgain(attempts.live(record).orElseThrow().getId().toString(), ADMIN);
        listCases();

        assertThat(status(record)).isEqualTo(CcdCreationAttemptStatus.Linked);
        assertThat(FAKE.starts.get()).isOne();
        assertThat(cases()).isOne();
    }

    @Test
    @DisplayName("an admin's reconciliation restarts the settle window: an unbound record waits, not straight back")
    void reconcileAgainRestartsTheSettleWindow() {
        String record = record();
        jdbc.update("""
            UPDATE "deedpoll_ccdcreationattempt" SET "status" = 'NeedsRepair' WHERE "deedPollId" = ?
            """, record);
        ageAttempt(record);

        repairs.reconcileAgain(attempts.live(record).orElseThrow().getId().toString(), ADMIN);
        assertThat(linking.reconcileOutstanding()).isOne();

        assertThat(status(record)).isEqualTo(CcdCreationAttemptStatus.AwaitingConfirmation);
        assertThat(FAKE.starts.get()).isZero();
    }

    @Test
    @DisplayName("sweep: two list requests at once make one case per record")
    void concurrentSweepsMakeOneCasePerRecord() throws Exception {
        String first = unlinkedRecord(2);
        final String second = unlinkedRecord(1);
        // Long enough that both requests select both records before either has claimed anything.
        FAKE.startDelay = Duration.ofMillis(300);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch go = new CountDownLatch(1);
            List<Future<String>> lists = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                lists.add(pool.submit(() -> {
                    go.await();
                    return listCases();
                }));
            }
            go.countDown();
            for (Future<String> list : lists) {
                list.get(60, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(status(first)).isEqualTo(CcdCreationAttemptStatus.Linked);
        assertThat(status(second)).isEqualTo(CcdCreationAttemptStatus.Linked);
        assertThat(attemptRepository.findByDeedPollIdOrderByCreatedOnAsc(first)).hasSize(1);
        assertThat(attemptRepository.findByDeedPollIdOrderByCreatedOnAsc(second)).hasSize(1);
        assertThat(cases()).isEqualTo(2);
        assertThat(FAKE.submits.get()).isEqualTo(2);
    }

    /**
     * GET /caseworker/cases as a caseworker, which must answer 200; its body.
     */
    private String listCases() throws Exception {
        MvcResult result = mockMvc.perform(get("/caseworker/cases")
                .header("Authorization", "Bearer " + IDAM.accessToken()))
            .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return result.getResponse().getContentAsString();
    }

    /**
     * A record with no attempt and no binding, created {@code days} ago, which the sweep has to find itself.
     */
    private String unlinkedRecord(int days) {
        String id = deedPolls.save(new DeedPoll()).getId();
        jdbc.update("UPDATE deedpoll_deedpoll SET \"displayReference\" = ?, \"createdOn\" = now() - make_interval("
            + "days => ?) WHERE id = ?", "KBDP2026-%06d".formatted(DISPLAY_SERIALS.getAndIncrement()), days, id);
        return id;
    }

    private String record() {
        String id = deedPolls.save(new DeedPoll()).getId();
        jdbc.update("UPDATE deedpoll_deedpoll SET \"displayReference\" = ? WHERE id = ?",
            "KBDP2026-%06d".formatted(DISPLAY_SERIALS.getAndIncrement()), id);
        assertThat(attempts.open(id)).isPresent();
        return id;
    }

    /** The status of the record's latest attempt, live or not. */
    private CcdCreationAttemptStatus status(String record) {
        return CcdCreationAttemptStatus.valueOf(jdbc.queryForObject("""
            SELECT status::text FROM "deedpoll_ccdcreationattempt" WHERE "deedPollId" = ?
             ORDER BY "createdOn" DESC LIMIT 1
            """, String.class, record));
    }

    private String detail(String record) {
        return jdbc.queryForObject("""
            SELECT "failureDetail" FROM "deedpoll_ccdcreationattempt" WHERE "deedPollId" = ?
             ORDER BY "createdOn" DESC LIMIT 1
            """, String.class, record);
    }

    private boolean submissionMayHaveReached(String record) {
        return jdbc.queryForObject("""
            SELECT "submissionMayHaveReached" FROM "deedpoll_ccdcreationattempt" WHERE "deedPollId" = ?
             ORDER BY "createdOn" DESC LIMIT 1
            """, Boolean.class, record);
    }

    /** Puts a released attempt's notBefore behind it, as waiting out retry-after would. */
    private void makeDue(String record) {
        jdbc.update("""
            UPDATE "deedpoll_ccdcreationattempt" SET "notBefore" = "notBefore" - interval '1 day'
             WHERE "deedPollId" = ?
            """, record);
    }

    /** Puts the record's attempt a day past its last move, and so past the settle window. */
    private void ageAttempt(String record) {
        jdbc.update("""
            UPDATE "deedpoll_ccdcreationattempt" SET "modifiedOn" = "modifiedOn" - interval '1 day'
             WHERE "deedPollId" = ?
            """, record);
    }

    private String boundReference(String record) {
        return jdbc.queryForObject("SELECT \"ccdCaseReference\" FROM deedpoll_deedpoll WHERE id = ?", String.class,
            record);
    }

    private Integer cases() {
        return jdbc.queryForObject("SELECT count(*) FROM ccd.case_data", Integer.class);
    }

    /**
     * Data-store, as far as a decentralised case type sees it: the three case API calls the operation makes, each
     * answered by calling back into this service's persistence API the way data-store does.
     *
     * <p>The event token is {@code token-N}; the {@code Idempotency-Key} data-store sends is derived from it, as
     * data-store's {@code IdempotencyKeyHolder} derives its own, so a fresh start is a fresh key.
     */
    static final class FakeDataStore implements ResponseDefinitionTransformerV2 {

        private static final Pattern START = Pattern.compile("/case-types/([^/]+)/event-triggers/([^/?]+)");
        private static final Pattern SUBMIT = Pattern.compile("/case-types/([^/]+)/cases");
        private static final Pattern READ = Pattern.compile("/cases/(\\d+)");

        private final ObjectMapper json = new ObjectMapper();

        final AtomicLong references = new AtomicLong(REFERENCES_BASE);
        final AtomicInteger starts = new AtomicInteger();
        final AtomicInteger submits = new AtomicInteger();
        final AtomicInteger refusals = new AtomicInteger();
        final AtomicInteger reads = new AtomicInteger();
        /** How many reads to answer 503 before answering. */
        final AtomicInteger unreadable = new AtomicInteger();
        final Set<String> expired = ConcurrentHashMap.newKeySet();
        final AtomicReference<String> authorisation = new AtomicReference<>();
        final AtomicReference<String> serviceAuthorisation = new AtomicReference<>();

        volatile boolean loseSubmitResponse;
        volatile Duration startDelay = Duration.ZERO;
        /** A status to answer every start with instead of a token, or 0 to answer normally. */
        volatile int startStatus;
        /** Runs as each start is answered: whatever happens to the attempt while its worker waits on CCD. */
        volatile Runnable duringStart = () -> {
        };
        volatile String caseTypeShown;
        volatile Consumer<String> beforeSubmit = deedPollId -> {
        };

        private volatile MockMvc mockMvc;
        private volatile CcdProperties ccd;

        void reset(MockMvc mockMvc, CcdProperties ccd) {
            this.mockMvc = mockMvc;
            this.ccd = ccd;
            starts.set(0);
            submits.set(0);
            refusals.set(0);
            reads.set(0);
            unreadable.set(0);
            expired.clear();
            authorisation.set(null);
            serviceAuthorisation.set(null);
            loseSubmitResponse = false;
            startDelay = Duration.ZERO;
            startStatus = 0;
            duringStart = () -> {
            };
            caseTypeShown = null;
            beforeSubmit = deedPollId -> {
            };
        }

        @Override
        public String getName() {
            return "fake-ccd-data-store";
        }

        @Override
        public ResponseDefinition transform(ServeEvent serveEvent) {
            Request request = serveEvent.getRequest();
            String path = request.getUrl().split("\\?")[0];
            try {
                Matcher start = START.matcher(path);
                if ("GET".equals(request.getMethod().getName()) && start.matches()) {
                    return start(request);
                }
                if ("POST".equals(request.getMethod().getName()) && SUBMIT.matcher(path).matches()) {
                    return submit(request);
                }
                Matcher read = READ.matcher(path);
                if ("GET".equals(request.getMethod().getName()) && read.matches()) {
                    return read(request, read.group(1));
                }
                return answer(404, "{}");
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }

        private ResponseDefinition start(Request request) throws InterruptedException {
            authorisation.set(request.getHeader("Authorization"));
            serviceAuthorisation.set(request.getHeader(ServiceAuthFilter.AUTHORISATION));
            final int n = starts.incrementAndGet();
            Thread.sleep(startDelay.toMillis());
            duringStart.run();
            if (startStatus != 0) {
                return answer(startStatus, "{}");
            }
            return answer(200, """
                {"token": "token-%d", "event_id": "%s", "case_details": null}
                """.formatted(n, DeedPollCaseType.LINK_DEED_POLL));
        }

        private ResponseDefinition submit(Request request) throws Exception {
            submits.incrementAndGet();
            JsonNode content = json.readTree(request.getBodyAsString());
            String token = content.path("event_token").asText();
            if (expired.contains(token)) {
                return answer(403, "{\"message\": \"Cannot validate provided event token\"}");
            }
            String deedPollId = content.path("data").path("deedPollId").asText();
            beforeSubmit.accept(deedPollId);

            long reference = references.incrementAndGet();
            MvcResult result;
            try {
                result = persist(reference, deedPollId, token, request.getHeader("Authorization"));
            } catch (RuntimeException failed) {
                // The persistence API threw: data-store answers 5xx, and keeps its own pointer.
                return answer(500, "{\"message\": \"callback failed\"}");
            }
            JsonNode persisted = json.readTree(result.getResponse().getContentAsString());
            if (result.getResponse().getStatus() != 200) {
                return answer(500, "{}");
            }
            if (persisted.path("errors").size() > 0) {
                refusals.incrementAndGet();
                return answer(422, "{\"message\": \"Case data validation failed\"}");
            }
            if (loseSubmitResponse) {
                return aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER).build();
            }
            return answer(201, caseDetails(reference, persisted.path("case_details")));
        }

        private ResponseDefinition read(Request request, String reference) throws Exception {
            reads.incrementAndGet();
            if (unreadable.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
                return answer(503, "{}");
            }
            MvcResult result = mockMvc.perform(get("/ccd-persistence/cases").param("case-refs", reference)
                    .header(ServiceAuthFilter.AUTHORISATION, "Bearer " + CCD_DATA)
                    .header("Authorization", request.getHeader("Authorization")))
                .andReturn();
            JsonNode found = json.readTree(result.getResponse().getContentAsString());
            if (!found.isArray() || found.isEmpty()) {
                return answer(404, "{}");
            }
            return answer(200, caseDetails(Long.parseLong(reference), found.get(0).path("case_details")));
        }

        /**
         * Sends a creation event for {@code deedPollId} to the persistence API, as data-store does.
         *
         * @param authorisation the user's bearer token to forward, or null for a fresh one
         */
        MvcResult persist(long reference, String deedPollId, String token, String authorisation) {
            String body = """
                {
                  "case_details": {
                    "id": %d,
                    "jurisdiction": "%s",
                    "case_type_id": "%s",
                    "state": "Registered",
                    "security_classification": "PUBLIC",
                    "case_data": {"deedPollId": "%s"}
                  },
                  "event_details": {"case_type": "%s", "event_id": "%s", "event_name": "Link deed poll"},
                  "internal_case_id": %d
                }
                """.formatted(reference, ccd.jurisdictionId(), ccd.caseTypeId(), deedPollId, ccd.caseTypeId(),
                DeedPollCaseType.LINK_DEED_POLL, reference - REFERENCES_BASE);
            try {
                return mockMvc.perform(post("/ccd-persistence/cases")
                        .header(ServiceAuthFilter.AUTHORISATION, "Bearer " + CCD_DATA)
                        .header("Authorization", authorisation != null ? authorisation : "Bearer " + IDAM.accessToken())
                        .header("Idempotency-Key",
                            UUID.nameUUIDFromBytes(token.getBytes(StandardCharsets.UTF_8)).toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                    .andReturn();
            } catch (Exception e) {
                throw new IllegalStateException("the persistence API failed", e);
            }
        }

        /** A case as CCD's case API shows one, composed from what the persistence API answered. */
        private String caseDetails(long reference, JsonNode persisted) throws Exception {
            ObjectNode shown = json.createObjectNode();
            shown.put("id", reference);
            shown.put("jurisdiction", ccd.jurisdictionId());
            shown.put("case_type_id", caseTypeShown != null ? caseTypeShown : persisted.path("case_type_id").asText());
            shown.put("state", persisted.path("state").asText());
            shown.set("case_data", persisted.path("case_data"));
            return json.writeValueAsString(shown);
        }

        private static ResponseDefinition answer(int status, String body) {
            return aResponse().withStatus(status).withHeader("Content-Type", "application/json").withBody(body)
                .build();
        }
    }
}
