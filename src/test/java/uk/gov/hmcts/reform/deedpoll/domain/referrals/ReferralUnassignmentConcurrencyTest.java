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
import uk.gov.hmcts.reform.deedpoll.entity.AuditClock;
import uk.gov.hmcts.reform.deedpoll.entity.DeedPoll;
import uk.gov.hmcts.reform.deedpoll.entity.Referral;
import uk.gov.hmcts.reform.deedpoll.entity.enums.ReferralState;
import uk.gov.hmcts.reform.deedpoll.entity.enums.WindowSlot;
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
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The processing window's timer racing a request-time sweep (plan D2): both find the same overdue assignment, and it
 * is released once, with one note, because the second release waits on the first's row lock and then finds the row
 * no longer {@code Claimed}.
 *
 * <p>On {@code ReferralAssignmentConcurrencyTest}'s pattern: the request's transaction holds its release until
 * PostgreSQL reports the timer's statement waiting on it, so the schedule is observed rather than guessed. The timer
 * is the real {@link ReferralUnassignment}, constructed here because the Postgres-backed tests switch its bean off.
 * Not {@code @Transactional}: the point is what two committed transactions leave behind.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class ReferralUnassignmentConcurrencyTest extends AbstractPostgresContainerTest {

    private static final int TIMEOUT_SECONDS = 60;

    private static final String RETURNED = ", returned to the office for assignment: no direction from ";

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
    @AfterEach
    void clearTables() {
        notes.deleteAll();
        referrals.deleteAll();
        deedPolls.deleteAll();
        jdbc.update("DELETE FROM deedpoll_master");
        jdbc.update("UPDATE deedpoll_process_settings SET referral_claim_expiry_minutes = 10080");
    }

    @Test
    @DisplayName("the timer and a request racing release an overdue assignment once and write one note")
    void releasesOnce() throws Exception {
        String caseId = storedCase();
        String referralId = overdueAssignment(caseId, master());
        ReferralUnassignment timer = new ReferralUnassignment(service);

        CountDownLatch requestReleased = new CountDownLatch(1);
        CountDownLatch timerStarting = new CountDownLatch(1);
        AtomicBoolean timerWaited = new AtomicBoolean();
        AtomicInteger requestCount = new AtomicInteger(-1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            // The request: its sweep joins the request's transaction, as the judicial reads' does.
            Future<?> request = pool.submit(() -> new TransactionTemplate(transactionManager).execute(status -> {
                final int requestBackend = jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class);
                requestCount.set(service.unassignOverdue());
                requestReleased.countDown();
                await(timerStarting);
                awaitBlockedBy(requestBackend);
                timerWaited.set(true);
                return null;
            }));
            Future<?> scheduled = pool.submit(() -> {
                await(requestReleased);
                timerStarting.countDown();
                timer.run();
            });

            request.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            scheduled.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            assertThat(timerWaited).isTrue();
        } finally {
            pool.shutdownNow();
        }

        assertThat(requestCount).hasValue(1);
        Referral released = referrals.findById(UUID.fromString(referralId)).orElseThrow();
        assertThat(released.getState()).isEqualTo(ReferralState.Open);
        assertThat(released.getAssignedMasterId()).isNull();
        assertThat(notes.findByDeedPollIdOrderByCreatedOnDesc(caseId))
            .singleElement()
            .satisfies(note -> assertThat(note.getNote()).contains(RETURNED + "Master Sullivan within 7 days."));
        assertThat(deedPolls.findById(caseId).orElseThrow().getCurrentReferralState()).isEqualTo(ReferralState.Open);
    }

    @Test
    @DisplayName("the timer on its own returns an overdue assignment, and runs outside any transaction")
    void timerReleases() {
        String caseId = storedCase();
        String referralId = overdueAssignment(caseId, master());

        new ReferralUnassignment(service).run();

        assertThat(referrals.findById(UUID.fromString(referralId)).orElseThrow().getState())
            .isEqualTo(ReferralState.Open);
        assertThat(notes.findByDeedPollIdOrderByCreatedOnDesc(caseId)).hasSize(1);
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

    /** Assigned to an unlinked Master eight days ago, past the seeded seven-day processing window. */
    private String overdueAssignment(String deedPollId, UUID master) {
        LocalDateTime assigned = AuditClock.now().minusDays(8);
        Referral referral = new Referral();
        referral.setDeedPoll(deedPolls.findById(deedPollId).orElseThrow());
        referral.setState(ReferralState.Claimed);
        referral.setReasons(List.of(ReferralReason.DomesticAbuse.name()));
        referral.setClaimedBy("Master Sullivan");
        referral.setClaimedAt(assigned);
        referral.setLastTouchedAt(assigned);
        referral.setAssignedMasterId(master);
        referral.setAssignedAt(assigned);
        referral.setWindowDate(assigned.toLocalDate());
        referral.setWindowSlot(WindowSlot.AM);
        String id = referrals.save(referral).getId().toString();
        jdbc.update("UPDATE deedpoll_deedpoll SET \"currentReferralState\" = 'Claimed' WHERE id = ?", deedPollId);
        return id;
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

    private void awaitBlockedBy(int requestBackend) {
        Awaitility.await().pollInSameThread().atMost(TIMEOUT_SECONDS, TimeUnit.SECONDS).until(() ->
            Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM pg_locks
                    WHERE NOT granted AND ? = ANY(pg_blocking_pids(pid))
                )
                """, Boolean.class, requestBackend)));
    }
}
