package uk.gov.hmcts.reform.deedpoll.domain.referrals;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import uk.gov.hmcts.reform.deedpoll.config.AbstractPostgresContainerTest;
import uk.gov.hmcts.reform.deedpoll.domain.ConflictError;
import uk.gov.hmcts.reform.deedpoll.domain.auth.CurrentUser;
import uk.gov.hmcts.reform.deedpoll.entity.DeedPoll;
import uk.gov.hmcts.reform.deedpoll.entity.Referral;
import uk.gov.hmcts.reform.deedpoll.entity.enums.ReferralState;
import uk.gov.hmcts.reform.deedpoll.repository.DeedPollNoteRepository;
import uk.gov.hmcts.reform.deedpoll.repository.DeedPollRepository;
import uk.gov.hmcts.reform.deedpoll.repository.ReferralRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The per-Master cap under concurrency (plan D2): with the cap at 1, two assignments of two referrals to one Master
 * in one window give exactly one success and one 409, because the second waits on the Master's row lock and then
 * counts what the first committed.
 *
 * <p>Against a real database for {@code DirectionRevisionConcurrencyTest}'s reason, and on its pattern: the first
 * transaction holds its locks until PostgreSQL reports the second waiting on it, so the schedule is observed rather
 * than guessed. Not {@code @Transactional}: the point is what two committed transactions leave behind.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class ReferralAssignmentConcurrencyTest extends AbstractPostgresContainerTest {

    private static final int TIMEOUT_SECONDS = 60;

    private static final CurrentUser ADA = CurrentUser.currentUser(List.of(), "Ada Caseworker", "caseworker-1");
    private static final CurrentUser BEN = CurrentUser.currentUser(List.of(), "Ben Caseworker", "caseworker-2");

    @Autowired
    private ReferralService service;

    @Autowired
    private DeedPollRepository deedPolls;

    @Autowired
    private ReferralRepository referrals;

    @Autowired
    private DeedPollNoteRepository notes;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void capAtOne() {
        clearTables();
        jdbc.update("UPDATE deedpoll_process_settings SET master_window_cap = 1");
    }

    @AfterEach
    void restore() {
        clearTables();
        jdbc.update("UPDATE deedpoll_process_settings SET master_window_cap = 10");
    }

    private void clearTables() {
        notes.deleteAll();
        referrals.deleteAll();
        deedPolls.deleteAll();
        jdbc.update("DELETE FROM deedpoll_master");
    }

    @Test
    @DisplayName("two assignments to one Master at the cap of 1: one succeeds and the waiting one is a 409")
    void capHolds() throws Exception {
        UUID master = master();
        String firstCase = storedCase();
        String secondCase = storedCase();
        String first = openReferral(firstCase);
        String second = openReferral(secondCase);

        CountDownLatch firstWrote = new CountDownLatch(1);
        CountDownLatch secondStarting = new CountDownLatch(1);
        AtomicBoolean secondWaited = new AtomicBoolean();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Throwable> firstAssign = pool.submit(() ->
                attempt(() -> new TransactionTemplate(transactionManager).execute(status -> {
                    final int firstBackend = jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class);
                    service.assign(firstCase, first, master, ADA);
                    firstWrote.countDown();
                    await(secondStarting);
                    awaitBlockedBy(firstBackend);
                    secondWaited.set(true);
                    return null;
                })));
            Future<Throwable> secondAssign = pool.submit(() -> {
                await(firstWrote);
                secondStarting.countDown();
                return attempt(() -> service.assign(secondCase, second, master, BEN));
            });

            assertThat(firstAssign.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isNull();
            assertThat(secondAssign.get(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                .isInstanceOf(ConflictError.class)
                .hasMessageStartingWith("This Master already has 1 referral for the ");
            assertThat(secondWaited).isTrue();
        } finally {
            pool.shutdownNow();
        }

        assertThat(stored(first).getState()).isEqualTo(ReferralState.Claimed);
        assertThat(stored(first).getAssignedMasterId()).isEqualTo(master);
        assertThat(stored(second).getState()).isEqualTo(ReferralState.Open);
        assertThat(stored(second).getAssignedMasterId()).isNull();
        assertThat(notes.findByDeedPollIdOrderByCreatedOnDesc(secondCase)).isEmpty();
        assertThat(jdbc.queryForObject(
            "SELECT count(*) FROM deedpoll_audit_event WHERE action = 'REFERRAL_ASSIGNED' AND entity_id = ?",
            Integer.class, second)).isZero();
    }

    private UUID master() {
        UUID id = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO deedpoll_master (id, name, email, created_at, created_by_id, created_by_name)
            VALUES (?, 'Master Sullivan', ?, now(), 'subject-dm', 'Dee Manager')
            """, id, id + "@example.test");
        return id;
    }

    private String storedCase() {
        DeedPoll deedPoll = new DeedPoll();
        deedPoll.setDateReceived(LocalDateTime.of(2026, 1, 2, 10, 0));
        return deedPolls.save(deedPoll).getId();
    }

    private String openReferral(String deedPollId) {
        Referral referral = new Referral();
        referral.setDeedPoll(deedPolls.findById(deedPollId).orElseThrow());
        referral.setState(ReferralState.Open);
        referral.setReasons(List.of(ReferralReason.DomesticAbuse.name()));
        return referrals.save(referral).getId().toString();
    }

    private Referral stored(String id) {
        return referrals.findById(UUID.fromString(id)).orElseThrow();
    }

    private static Throwable attempt(Runnable work) {
        try {
            work.run();
            return null;
        } catch (RuntimeException refused) {
            return refused;
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new IllegalStateException("the other transaction never got as far as signalling");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }

    private void awaitBlockedBy(int firstBackend) {
        Awaitility.await().pollInSameThread().atMost(TIMEOUT_SECONDS, TimeUnit.SECONDS).until(() ->
            Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM pg_locks
                    WHERE NOT granted AND ? = ANY(pg_blocking_pids(pid))
                )
                """, Boolean.class, firstBackend)));
    }
}
