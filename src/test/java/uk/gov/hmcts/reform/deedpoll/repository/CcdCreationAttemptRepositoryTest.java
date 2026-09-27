package uk.gov.hmcts.reform.deedpoll.repository;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import uk.gov.hmcts.reform.deedpoll.ccd.CcdCreationAttempts;
import uk.gov.hmcts.reform.deedpoll.config.AbstractPostgresContainerTest;
import uk.gov.hmcts.reform.deedpoll.entity.CcdCreationAttempt;
import uk.gov.hmcts.reform.deedpoll.entity.DeedPoll;

import java.sql.Timestamp;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static uk.gov.hmcts.reform.deedpoll.entity.enums.CcdCreationAttemptStatus.AwaitingConfirmation;
import static uk.gov.hmcts.reform.deedpoll.entity.enums.CcdCreationAttemptStatus.Claimed;
import static uk.gov.hmcts.reform.deedpoll.entity.enums.CcdCreationAttemptStatus.Linked;
import static uk.gov.hmcts.reform.deedpoll.entity.enums.CcdCreationAttemptStatus.NeedsRepair;
import static uk.gov.hmcts.reform.deedpoll.entity.enums.CcdCreationAttemptStatus.Pending;

/**
 * V22's creation-attempt ledger against real Postgres: one live attempt per record, and one winner per race.
 *
 * <p>Not {@code @Transactional}: what is being asserted is what separately committed transactions leave behind,
 * and a race inside one test transaction is no race. {@code CcdCreationAttemptStatusTest} and
 * {@code CcdCreationAttemptsTest} are the container-free companions over the rule these statements carry out.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class CcdCreationAttemptRepositoryTest extends AbstractPostgresContainerTest {

    private static final int RACERS = 8;

    private static final int TIMEOUT_SECONDS = 60;

    private static final Duration LEASE = Duration.ofMinutes(5);

    @Autowired
    private CcdCreationAttempts attempts;

    @Autowired
    private CcdCreationAttemptRepository repository;

    @Autowired
    private DeedPollRepository deedPolls;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    @AfterEach
    void clearTables() {
        repository.deleteAll();
        deedPolls.deleteAll();
    }

    private String newCase() {
        return deedPolls.save(new DeedPoll()).getId();
    }

    private CcdCreationAttempt opened(String deedPollId) {
        return attempts.open(deedPollId).orElseThrow();
    }

    @Test
    @DisplayName("an attempt opens Pending, unclaimed, never tried, with an operation id of its own")
    void anAttemptOpensPending() {
        String deedPollId = newCase();

        CcdCreationAttempt attempt = opened(deedPollId);

        assertThat(attempt.getStatus()).isEqualTo(Pending);
        assertThat(attempt.getDeedPollId()).isEqualTo(deedPollId);
        assertThat(attempt.getOperationId()).isNotNull().isNotEqualTo(attempt.getId());
        assertThat(attempt.getClaimedBy()).isNull();
        assertThat(attempt.getLeaseExpiresAt()).isNull();
        assertThat(attempt.getAttempts()).isZero();
        assertThat(attempt.getCreatedOn()).isNotNull();
        assertThat(attempt.getModifiedOn()).isNotNull();
        assertThat(repository.findByOperationId(attempt.getOperationId())).map(CcdCreationAttempt::getId)
            .contains(attempt.getId());
        assertThat(attempts.live(deedPollId)).map(CcdCreationAttempt::getId).contains(attempt.getId());
    }

    @Test
    @DisplayName("a second open while one is live opens nothing, and leaves the first alone")
    void secondOpenOpensNothing() {
        String deedPollId = newCase();
        CcdCreationAttempt first = opened(deedPollId);

        assertThat(attempts.open(deedPollId)).isEmpty();

        assertThat(repository.findByDeedPollIdOrderByCreatedOnAsc(deedPollId))
            .extracting(CcdCreationAttempt::getId).containsExactly(first.getId());
    }

    @Test
    @DisplayName("every live status blocks a second attempt, NeedsRepair included")
    void everyLiveStatusBlocksASecondAttempt() {
        String deedPollId = newCase();
        CcdCreationAttempt attempt = opened(deedPollId);
        assertThat(attempts.open(deedPollId)).as("Pending").isEmpty();

        assertThat(attempts.claim(attempt.getId(), "worker-1", LEASE)).isTrue();
        assertThat(attempts.open(deedPollId)).as("Claimed").isEmpty();

        assertThat(attempts.move(attempt.getId(), Claimed, AwaitingConfirmation, null)).isTrue();
        assertThat(attempts.open(deedPollId)).as("AwaitingConfirmation").isEmpty();

        // The one that matters most: a record whose outcome is unknown must not be offered a fresh creation,
        // because the first may already have made a case in CCD.
        assertThat(attempts.move(attempt.getId(), AwaitingConfirmation, NeedsRepair, "no answer")).isTrue();
        assertThat(attempts.open(deedPollId)).as("NeedsRepair").isEmpty();
    }

    @Test
    @DisplayName("a Linked attempt is no longer live, so the record may have another")
    void linkedAttemptIsNotLive() {
        String deedPollId = newCase();
        CcdCreationAttempt first = opened(deedPollId);
        attempts.claim(first.getId(), "worker-1", LEASE);
        attempts.move(first.getId(), Claimed, Linked, null);

        assertThat(attempts.live(deedPollId)).isEmpty();
        CcdCreationAttempt second = opened(deedPollId);

        assertThat(second.getOperationId()).isNotEqualTo(first.getOperationId());
        assertThat(attempts.live(deedPollId)).map(CcdCreationAttempt::getId).contains(second.getId());
    }

    @Test
    @DisplayName("one record's live attempt does not block another record's")
    void liveIsPerRecord() {
        opened(newCase());

        assertThat(attempts.open(newCase())).isPresent();
    }

    @Test
    @DisplayName("a claim takes a Pending attempt once, counts the try and sets the lease")
    void claimIsExclusive() {
        CcdCreationAttempt attempt = opened(newCase());

        assertThat(attempts.claim(attempt.getId(), "worker-1", LEASE)).isTrue();
        assertThat(attempts.claim(attempt.getId(), "worker-2", LEASE)).isFalse();

        CcdCreationAttempt claimed = repository.findById(attempt.getId()).orElseThrow();
        assertThat(claimed.getStatus()).isEqualTo(Claimed);
        assertThat(claimed.getClaimedBy()).isEqualTo("worker-1");
        assertThat(claimed.getLeaseExpiresAt()).isAfter(claimed.getModifiedOn());
        assertThat(claimed.getAttempts()).isEqualTo(1);
    }

    @Test
    @DisplayName("a released claim can be taken again, and the second try is counted too")
    void releasedClaimCountsAgain() {
        CcdCreationAttempt attempt = opened(newCase());
        attempts.claim(attempt.getId(), "worker-1", LEASE);

        assertThat(attempts.move(attempt.getId(), Claimed, Pending, "CCD unavailable")).isTrue();
        CcdCreationAttempt released = repository.findById(attempt.getId()).orElseThrow();
        assertThat(released.getClaimedBy()).isNull();
        assertThat(released.getLeaseExpiresAt()).isNull();
        assertThat(released.getFailureDetail()).isEqualTo("CCD unavailable");

        assertThat(attempts.claim(attempt.getId(), "worker-2", LEASE)).isTrue();
        assertThat(repository.findById(attempt.getId()).orElseThrow().getAttempts()).isEqualTo(2);
    }

    @Test
    @DisplayName("a move is conditional on the status the caller saw, so a stale caller changes nothing")
    void moveIsConditional() {
        CcdCreationAttempt attempt = opened(newCase());
        attempts.claim(attempt.getId(), "worker-1", LEASE);
        attempts.move(attempt.getId(), Claimed, AwaitingConfirmation, null);

        // A second worker that still believes the attempt is Claimed.
        assertThat(attempts.move(attempt.getId(), Claimed, NeedsRepair, "stale")).isFalse();

        CcdCreationAttempt unchanged = repository.findById(attempt.getId()).orElseThrow();
        assertThat(unchanged.getStatus()).isEqualTo(AwaitingConfirmation);
        assertThat(unchanged.getFailureDetail()).isNull();
    }

    @Test
    @DisplayName("concurrent claims on one attempt yield exactly one winner")
    void concurrentClaimsYieldOneWinner() throws Exception {
        CcdCreationAttempt attempt = opened(newCase());

        List<Boolean> results = race(worker -> attempts.claim(attempt.getId(), "worker-" + worker, LEASE));

        assertThat(results).containsOnlyOnce(true);
        CcdCreationAttempt claimed = repository.findById(attempt.getId()).orElseThrow();
        assertThat(claimed.getAttempts()).isEqualTo(1);
        assertThat(claimed.getClaimedBy()).startsWith("worker-");
    }

    @Test
    @DisplayName("concurrent opens on one record yield exactly one live attempt")
    void concurrentOpensYieldOneAttempt() throws Exception {
        String deedPollId = newCase();

        List<Boolean> results = race(worker -> attempts.open(deedPollId).isPresent());

        assertThat(results).containsOnlyOnce(true);
        assertThat(repository.findByDeedPollIdOrderByCreatedOnAsc(deedPollId)).hasSize(1);
    }

    @Test
    @DisplayName("the database itself refuses a second live row, whatever the application thinks")
    void theIndexRefusesASecondLiveRow() {
        String deedPollId = newCase();
        opened(deedPollId);

        assertThatThrownBy(() -> insertRaw(deedPollId, "NeedsRepair", null, null))
            .isInstanceOf(DataIntegrityViolationException.class)
            .hasMessageContaining("deedpoll_ccdcreationattempt_live_key");
    }

    @Test
    @DisplayName("the database refuses a Claimed row without a claimant and a lease")
    void theDatabaseRefusesAClaimWithoutALease() {
        String deedPollId = newCase();

        assertThatThrownBy(() -> insertRaw(deedPollId, "Claimed", null, null))
            .isInstanceOf(DataIntegrityViolationException.class)
            .hasMessageContaining("deedpoll_ccdcreationattempt_claim_has_lease");
        assertThatThrownBy(() -> insertRaw(deedPollId, "Claimed", "worker-1", null))
            .isInstanceOf(DataIntegrityViolationException.class)
            .hasMessageContaining("deedpoll_ccdcreationattempt_claim_has_lease");
    }

    @Test
    @DisplayName("the database refuses a failure detail over its bound, which is why the service cuts it first")
    void theDatabaseBoundsTheFailureDetail() {
        CcdCreationAttempt attempt = opened(newCase());
        String tooLong = "x".repeat(CcdCreationAttempt.MAX_FAILURE_DETAIL + 1);

        assertThatThrownBy(() -> jdbc.update(
            "UPDATE \"deedpoll_ccdcreationattempt\" SET \"failureDetail\" = ? WHERE \"id\" = ?", tooLong,
            attempt.getId()))
            .isInstanceOf(DataIntegrityViolationException.class);

        // Through the service the same text is written, cut to fit.
        attempts.claim(attempt.getId(), "worker-1", LEASE);
        assertThat(attempts.move(attempt.getId(), Claimed, Pending, tooLong)).isTrue();
        assertThat(repository.findById(attempt.getId()).orElseThrow().getFailureDetail())
            .hasSize(CcdCreationAttempt.MAX_FAILURE_DETAIL);
    }

    @Test
    @DisplayName("an attempt for a record that does not exist is refused")
    void anAttemptNeedsARecord() {
        assertThatThrownBy(() -> attempts.open("no-such-case"))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("deleting the record deletes its attempts")
    void deletingTheRecordDeletesItsAttempts() {
        String deedPollId = newCase();
        CcdCreationAttempt attempt = opened(deedPollId);

        jdbc.update("DELETE FROM \"deedpoll_deedpoll\" WHERE \"id\" = ?", deedPollId);

        assertThat(repository.findById(attempt.getId())).isEqualTo(Optional.empty());
    }

    private void insertRaw(String deedPollId, String status, String claimedBy, Timestamp lease) {
        jdbc.update("""
            INSERT INTO "deedpoll_ccdcreationattempt"
                ("id", "deedPollId", "operationId", "status", "claimedBy", "leaseExpiresAt", "modifiedOn")
            VALUES (?, ?, ?, CAST(? AS "CcdCreationAttemptStatus"), ?, ?, CURRENT_TIMESTAMP)
            """, UUID.randomUUID(), deedPollId, UUID.randomUUID(), status, claimedBy, lease);
    }

    /** Runs one call per racer, all released at once, each in its own committed transaction. */
    private List<Boolean> race(Racer racer) throws Exception {
        CountDownLatch ready = new CountDownLatch(RACERS);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(RACERS);
        try {
            List<Future<Boolean>> futures = new ArrayList<>();
            for (int worker = 0; worker < RACERS; worker++) {
                int id = worker;
                Callable<Boolean> call = () -> {
                    ready.countDown();
                    if (!go.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("the race never started");
                    }
                    return racer.run(id);
                };
                futures.add(pool.submit(call));
            }
            assertThat(ready.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            List<Boolean> results = new ArrayList<>();
            for (Future<Boolean> future : futures) {
                // Surfaces any racer's exception here rather than as an unexplained count.
                results.add(future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    @FunctionalInterface
    private interface Racer {
        boolean run(int worker);
    }
}
