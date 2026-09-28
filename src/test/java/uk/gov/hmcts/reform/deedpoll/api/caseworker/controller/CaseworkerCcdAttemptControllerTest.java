package uk.gov.hmcts.reform.deedpoll.api.caseworker.controller;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import uk.gov.hmcts.reform.deedpoll.auth.StubIdam;
import uk.gov.hmcts.reform.deedpoll.ccd.CcdCreationAttempts;
import uk.gov.hmcts.reform.deedpoll.config.AbstractPostgresContainerTest;
import uk.gov.hmcts.reform.deedpoll.domain.auth.Role;
import uk.gov.hmcts.reform.deedpoll.entity.CcdCreationAttempt;
import uk.gov.hmcts.reform.deedpoll.entity.DeedPoll;
import uk.gov.hmcts.reform.deedpoll.repository.CcdCreationAttemptRepository;
import uk.gov.hmcts.reform.deedpoll.repository.DeedPollRepository;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static uk.gov.hmcts.reform.deedpoll.entity.enums.CcdCreationAttemptStatus.AwaitingConfirmation;
import static uk.gov.hmcts.reform.deedpoll.entity.enums.CcdCreationAttemptStatus.Claimed;
import static uk.gov.hmcts.reform.deedpoll.entity.enums.CcdCreationAttemptStatus.NeedsRepair;
import static uk.gov.hmcts.reform.deedpoll.entity.enums.CcdCreationAttemptStatus.Pending;

/**
 * The CCD attempt repair endpoints over HTTP, against a real database and a stub IDAM.
 *
 * <p>The whole context, for the reason every controller test here gives: a slice does not get
 * {@code SecurityConfiguration}. The rules are pinned container-free in {@code CcdAttemptRepairsTest}; what this
 * class adds is which status code each refusal becomes, that the admin gate is a 403 on the wire, that two admins
 * repairing at once make one move, and that a repair writes its attribution and no binding.
 *
 * <p>No CCD stub is started, deliberately: neither POST calls CCD, so a request that tried to would fail here.
 *
 * <p><strong>{@code @DirtiesContext}, so this class leaves no context cached behind it.</strong> Its own IDAM stub
 * makes its context unique, and the unit JVM runs at Gradle's default 512 MB with every earlier controller
 * context still cached: adding this one as well exhausted the heap in a later class's ArchUnit import.
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CaseworkerCcdAttemptControllerTest extends AbstractPostgresContainerTest {

    private static final String ATTEMPTS = "/caseworker/ccd-attempts";

    private static final String UNKNOWN_ID = "11111111-1111-1111-1111-111111111111";

    private static final Duration LEASE = Duration.ofMinutes(5);

    private static final int TIMEOUT_SECONDS = 60;

    private static final StubIdam IDAM = new StubIdam();

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
    private MockMvc mockMvc;

    @Autowired
    private CcdCreationAttempts attempts;

    @Autowired
    private CcdCreationAttemptRepository attemptRows;

    @Autowired
    private DeedPollRepository deedPolls;

    @BeforeEach
    void setUp() {
        clearTables();
        IDAM.stubDefaults();
        asAdmin();
    }

    @AfterEach
    void tearDown() {
        clearTables();
    }

    private void clearTables() {
        attemptRows.deleteAll();
        deedPolls.deleteAll();
    }

    @Nested
    @DisplayName("the admin gate")
    class Gate {

        @Test
        @DisplayName("a token is required for all three operations")
        void unauthenticated() throws Exception {
            String id = needingRepair(false).getId().toString();

            mockMvc.perform(get(ATTEMPTS)).andExpect(status().isUnauthorized());
            mockMvc.perform(post(ATTEMPTS + "/" + id + "/reconciliation")).andExpect(status().isUnauthorized());
            mockMvc.perform(post(ATTEMPTS + "/" + id + "/retry")).andExpect(status().isUnauthorized());
            assertThat(stored(id).getStatus()).isEqualTo(NeedsRepair);
        }

        @Test
        @DisplayName("a plain caseworker is refused all three with a 403 naming the role, and nothing moves")
        void caseworkerRefused() throws Exception {
            String id = needingRepair(false).getId().toString();
            IDAM.stubUserInfo(StubIdam.SUBJECT, "Ada Caseworker", List.of("caseworker-civil"));

            refusedAllThree(id);
        }

        @Test
        @DisplayName("a judge is refused all three with a 403, and nothing moves")
        void judgeRefused() throws Exception {
            String id = needingRepair(false).getId().toString();
            IDAM.stubUserInfo(StubIdam.SUBJECT, "District Judge Bell", List.of(Role.JUDICIAL.claimValue()));

            refusedAllThree(id);
        }

        private void refusedAllThree(String id) throws Exception {
            authorised(get(ATTEMPTS))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value(containsString(Role.DEEDPOLL_ADMIN.claimValue())));
            authorised(post(ATTEMPTS + "/" + id + "/reconciliation")).andExpect(status().isForbidden());
            authorised(post(ATTEMPTS + "/" + id + "/retry")).andExpect(status().isForbidden());

            CcdCreationAttempt untouched = stored(id);
            assertThat(untouched.getStatus()).isEqualTo(NeedsRepair);
            assertThat(untouched.getRepairedById()).isNull();
        }
    }

    @Nested
    @DisplayName("the list")
    class TheList {

        @Test
        @DisplayName("answers the counts per status and the unlinked attempts, publishing none of the hidden fields")
        void listsUnlinked() throws Exception {
            CcdCreationAttempt repair = needingRepair(true);
            opened();

            authorised(get(ATTEMPTS))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.counts.length()").value(5))
                .andExpect(jsonPath("$.counts[?(@.status == 'NeedsRepair')].count").value(1))
                .andExpect(jsonPath("$.counts[?(@.status == 'Pending')].count").value(1))
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.size").value(25))
                .andExpect(jsonPath("$.attempts.length()").value(2))
                .andExpect(jsonPath("$.attempts[?(@.id == '" + repair.getId() + "')].canRetryFromScratch")
                               .value(false))
                .andExpect(jsonPath("$.attempts[0]", not(hasKey("submissionMayHaveReached"))))
                .andExpect(jsonPath("$.attempts[0]", not(hasKey("claimedBy"))))
                .andExpect(jsonPath("$.attempts[0]", not(hasKey("operationId"))))
                .andExpect(jsonPath("$.attempts[0]", not(hasKey("leaseExpiresAt"))))
                .andExpect(jsonPath("$.attempts[0]", not(hasKey("repairedById"))));
        }

        @Test
        @DisplayName("narrows to one status")
        void narrows() throws Exception {
            CcdCreationAttempt repair = needingRepair(false);
            opened();

            authorised(get(ATTEMPTS).param("status", "NeedsRepair"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.attempts[0].id").value(repair.getId().toString()))
                .andExpect(jsonPath("$.attempts[0].canRetryFromScratch").value(true));
        }

        @Test
        @DisplayName("caps the page size at the case list's maximum")
        void capsThePage() throws Exception {
            needingRepair(false);

            authorised(get(ATTEMPTS).param("size", "1000"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(100));
        }

        @Test
        @DisplayName("an unknown status, or Linked, is a 400 naming the parameter")
        void refusesAnUnknownStatus() throws Exception {
            for (String status : List.of("Nonsense", "Linked")) {
                authorised(get(ATTEMPTS).param("status", status))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors[0].field").value("status"));
            }
        }
    }

    @Nested
    @DisplayName("the repairs")
    class Repairs {

        @Test
        @DisplayName("reconciliation moves NeedsRepair to AwaitingConfirmation, attributed, binding nothing")
        void reconciles() throws Exception {
            CcdCreationAttempt attempt = needingRepair(true);

            authorised(post(ATTEMPTS + "/" + attempt.getId() + "/reconciliation"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("AwaitingConfirmation"))
                .andExpect(jsonPath("$", not(hasKey("repairedById"))));

            CcdCreationAttempt repaired = stored(attempt.getId().toString());
            assertThat(repaired.getStatus()).isEqualTo(AwaitingConfirmation);
            assertThat(repaired.getRepairedById()).isEqualTo(StubIdam.SUBJECT);
            assertThat(repaired.getRepairedOn()).isNotNull();
            assertThat(deedPolls.findById(attempt.getDeedPollId()).orElseThrow().getCcdCaseReference()).isNull();
        }

        @Test
        @DisplayName("a retry from scratch moves NeedsRepair to Pending, attributed, binding nothing")
        void retries() throws Exception {
            CcdCreationAttempt attempt = needingRepair(false);

            authorised(post(ATTEMPTS + "/" + attempt.getId() + "/retry"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("Pending"))
                .andExpect(jsonPath("$.tries").value(0));

            CcdCreationAttempt retried = stored(attempt.getId().toString());
            assertThat(retried.getStatus()).isEqualTo(Pending);
            assertThat(retried.getRepairedById()).isEqualTo(StubIdam.SUBJECT);
            assertThat(deedPolls.findById(attempt.getDeedPollId()).orElseThrow().getCcdCaseReference()).isNull();
        }

        @Test
        @DisplayName("a retry from scratch of an attempt that may have reached CCD is a 409 saying what to do instead")
        void retryRefusedAfterSubmission() throws Exception {
            CcdCreationAttempt attempt = needingRepair(true);

            authorised(post(ATTEMPTS + "/" + attempt.getId() + "/retry"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value(containsString("reconciliation instead")));

            CcdCreationAttempt untouched = stored(attempt.getId().toString());
            assertThat(untouched.getStatus()).isEqualTo(NeedsRepair);
            assertThat(untouched.getRepairedById()).isNull();
        }

        @Test
        @DisplayName("either repair of an attempt that is not NeedsRepair is a 409")
        void notNeedingRepair() throws Exception {
            CcdCreationAttempt attempt = opened();

            for (String move : List.of("/reconciliation", "/retry")) {
                authorised(post(ATTEMPTS + "/" + attempt.getId() + move))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error").value(containsString("Pending rather than NeedsRepair")));
            }
            assertThat(stored(attempt.getId().toString()).getRepairedById()).isNull();
        }

        @Test
        @DisplayName("either repair of an unknown id, or one that is not an id, is a 404")
        void unknown() throws Exception {
            for (String id : List.of(UNKNOWN_ID, "not-an-id")) {
                for (String move : List.of("/reconciliation", "/retry")) {
                    authorised(post(ATTEMPTS + "/" + id + move)).andExpect(status().isNotFound());
                }
            }
        }

        @Test
        @DisplayName("two admins repairing at once make one move: one 200, one 409")
        void concurrentRepairsMakeOneMove() throws Exception {
            CcdCreationAttempt attempt = needingRepair(false);
            String path = ATTEMPTS + "/" + attempt.getId();

            List<Integer> statuses = race(List.of(
                () -> authorised(post(path + "/reconciliation")).andReturn().getResponse().getStatus(),
                () -> authorised(post(path + "/retry")).andReturn().getResponse().getStatus()));

            assertThat(statuses).containsExactlyInAnyOrder(200, 409);
            assertThat(stored(attempt.getId().toString()).getStatus()).isIn(AwaitingConfirmation, Pending);
        }
    }

    private CcdCreationAttempt opened() {
        String deedPollId = deedPolls.save(new DeedPoll()).getId();
        return attempts.open(deedPollId).orElseThrow();
    }

    private CcdCreationAttempt needingRepair(boolean submitted) {
        CcdCreationAttempt attempt = opened();
        assertThat(attempts.claim(attempt.getId(), "worker-1", LEASE)).isPresent();
        if (submitted) {
            assertThat(attempts.recordSubmission(attempt.getId(), "worker-1", 1)).isTrue();
        }
        assertThat(attempts.move(attempt.getId(), Claimed, NeedsRepair, "no answer")).isTrue();
        return attempt;
    }

    private CcdCreationAttempt stored(String id) {
        return attemptRows.findById(UUID.fromString(id)).orElseThrow();
    }

    private List<Integer> race(List<Callable<Integer>> calls) throws Exception {
        CountDownLatch ready = new CountDownLatch(calls.size());
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(calls.size());
        try {
            List<Future<Integer>> futures = new ArrayList<>();
            for (Callable<Integer> call : calls) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    if (!go.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("the race never started");
                    }
                    return call.call();
                }));
            }
            assertThat(ready.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            List<Integer> results = new ArrayList<>();
            for (Future<Integer> future : futures) {
                results.add(future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    private static void asAdmin() {
        IDAM.stubUserInfo(StubIdam.SUBJECT, "An Admin", List.of(Role.DEEDPOLL_ADMIN.claimValue()));
    }

    private ResultActions authorised(MockHttpServletRequestBuilder request) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(IDAM.accessToken(claims -> claims.subject(StubIdam.SUBJECT)));
        return mockMvc.perform(request.headers(headers));
    }
}
