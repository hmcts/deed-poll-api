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
import uk.gov.hmcts.reform.deedpoll.domain.FieldError;
import uk.gov.hmcts.reform.deedpoll.domain.auth.CurrentUser;
import uk.gov.hmcts.reform.deedpoll.domain.auth.Role;
import uk.gov.hmcts.reform.deedpoll.entity.DeedPoll;
import uk.gov.hmcts.reform.deedpoll.entity.Referral;
import uk.gov.hmcts.reform.deedpoll.entity.enums.ReferralState;
import uk.gov.hmcts.reform.deedpoll.repository.DeedPollNoteRepository;
import uk.gov.hmcts.reform.deedpoll.repository.DeedPollRepository;
import uk.gov.hmcts.reform.deedpoll.repository.ReferralRepository;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The batch send under concurrency (plan F1, step 6): the window lock makes the cap hold across two senders, and the
 * one lock order (window, referrals by id, Masters by id) lets two sends naming the same referrals in opposite orders
 * finish rather than deadlock.
 *
 * <p>On {@code DirectionRevisionConcurrencyTest}'s pattern: the first transaction holds its locks until PostgreSQL
 * reports the second waiting on it, so the schedule is observed rather than guessed. Not {@code @Transactional}: the
 * point is what two committed transactions leave behind.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class ReferralSendConcurrencyTest extends AbstractPostgresContainerTest {

    private static final int TIMEOUT_SECONDS = 60;

    /** Wednesday morning's window, for both sends, however long the lock wait takes. */
    private static final LocalDateTime AT = LocalDateTime.of(2026, 10, 28, 9, 0);

    private static final CurrentUser TIA = CurrentUser.currentUser(List.of(Role.TEAM_LEADER), "Tia Leader", "tl-1");
    private static final CurrentUser DEE =
        CurrentUser.currentUser(List.of(Role.DELIVERY_MANAGER), "Dee Manager", "dm-1");

    @Autowired
    private ReferralQueue queue;
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
    void setUp() {
        clearTables();
    }

    @AfterEach
    void restore() {
        clearTables();
        jdbc.update("UPDATE deedpoll_process_settings SET window_cap = 10");
    }

    private void clearTables() {
        notes.deleteAll();
        referrals.deleteAll();
        deedPolls.deleteAll();
        jdbc.update("DELETE FROM deedpoll_master_email");
        jdbc.update("DELETE FROM deedpoll_master");
    }

    @Test
    @DisplayName("with the cap at 2, two sends of 2 to different Masters: one succeeds and the waiting one is a 409")
    void capHolds() throws Exception {
        jdbc.update("UPDATE deedpoll_process_settings SET window_cap = 2");
        UUID sullivan = master("Master Sullivan");
        UUID okafor = master("Master Okafor");
        List<UUID> first = List.of(openReferral(), openReferral());
        List<UUID> second = List.of(openReferral(), openReferral());

        List<Throwable> outcomes = race(
            () -> queue.send(TIA, allocations(first, sullivan), AT),
            () -> queue.send(DEE, allocations(second, okafor), AT));

        assertThat(outcomes.get(0)).isNull();
        assertThat(outcomes.get(1)).isInstanceOf(ConflictError.class)
            .hasMessage("No more referrals can be sent this morning.");
        first.forEach(id -> assertThat(stored(id).getState()).isEqualTo(ReferralState.Claimed));
        second.forEach(id -> {
            assertThat(stored(id).getState()).isEqualTo(ReferralState.Open);
            assertThat(sentEvents(id)).isZero();
        });
    }

    @Test
    @DisplayName("two sends naming the same referrals in opposite orders finish: one sends, the other is a 409")
    void sameReferralsInOppositeOrders() throws Exception {
        // Both are in one window, so the window lock serialises them before either takes a row lock. This proves the
        // outcome, not the id ordering of the row locks, which guards a send against a reassignment (no window lock).
        UUID sullivan = master("Master Sullivan");
        UUID okafor = master("Master Okafor");
        UUID one = openReferral();
        UUID two = openReferral();

        List<Throwable> outcomes = race(
            () -> queue.send(TIA, List.of(new ReferralAllocation(one, sullivan),
                                          new ReferralAllocation(two, okafor)), AT),
            () -> queue.send(DEE, List.of(new ReferralAllocation(two, sullivan),
                                          new ReferralAllocation(one, okafor)), AT));

        assertThat(outcomes.get(0)).isNull();
        assertThat(outcomes.get(1)).isInstanceOfSatisfying(ConflictError.class, error ->
            assertThat(error.errors()).extracting(FieldError::field)
                .containsExactly("allocations[0].referralId", "allocations[1].referralId"));
        assertThat(stored(one).getAssignedMasterId()).isEqualTo(sullivan);
        assertThat(stored(two).getAssignedMasterId()).isEqualTo(okafor);
        assertThat(sentEvents(one)).isOne();
        assertThat(sentEvents(two)).isOne();
    }

    /**
     * Runs the first send in a transaction that holds its locks until the second is seen waiting on it, then lets
     * both commit or refuse.
     *
     * @return each send's refusal, or null where it succeeded
     */
    private List<Throwable> race(Supplier<ReferralSend> first, Supplier<ReferralSend> second) throws Exception {
        CountDownLatch firstWrote = new CountDownLatch(1);
        CountDownLatch secondStarting = new CountDownLatch(1);
        AtomicBoolean secondWaited = new AtomicBoolean();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Throwable> firstSend = pool.submit(() ->
                attempt(() -> new TransactionTemplate(transactionManager).execute(status -> {
                    final int firstBackend = jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class);
                    first.get();
                    firstWrote.countDown();
                    await(secondStarting);
                    awaitBlockedBy(firstBackend);
                    secondWaited.set(true);
                    return null;
                })));
            Future<Throwable> secondSend = pool.submit(() -> {
                await(firstWrote);
                secondStarting.countDown();
                return attempt(() -> new TransactionTemplate(transactionManager).execute(status -> second.get()));
            });

            Throwable firstOutcome = firstSend.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            Throwable secondOutcome = secondSend.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            assertThat(secondWaited).isTrue();
            return Arrays.asList(firstOutcome, secondOutcome);
        } finally {
            pool.shutdownNow();
        }
    }

    private static List<ReferralAllocation> allocations(List<UUID> ids, UUID master) {
        return ids.stream().map(id -> new ReferralAllocation(id, master)).toList();
    }

    private UUID master(String name) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO deedpoll_master (id, name, email, created_at, created_by_id, created_by_name)
            VALUES (?, ?, ?, now(), 'subject-dm', 'Dee Manager')
            """, id, name, id + "@example.test");
        return id;
    }

    private UUID openReferral() {
        DeedPoll deedPoll = new DeedPoll();
        deedPoll.setDateReceived(LocalDateTime.of(2026, 1, 2, 10, 0));
        Referral referral = new Referral();
        referral.setDeedPoll(deedPolls.save(deedPoll));
        referral.setState(ReferralState.Open);
        referral.setReasons(List.of(ReferralReason.DomesticAbuse.name()));
        return referrals.save(referral).getId();
    }

    private Referral stored(UUID id) {
        return referrals.findById(id).orElseThrow();
    }

    private int sentEvents(UUID referral) {
        return jdbc.queryForObject(
            "SELECT count(*) FROM deedpoll_audit_event WHERE action = 'REFERRAL_SENT' AND entity_id = ?",
            Integer.class, referral.toString());
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
