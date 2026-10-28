package uk.gov.hmcts.reform.deedpoll.domain.settings;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;
import uk.gov.hmcts.reform.deedpoll.config.AbstractPostgresContainerTest;
import uk.gov.hmcts.reform.deedpoll.domain.ConflictError;
import uk.gov.hmcts.reform.deedpoll.domain.InvalidInputError;
import uk.gov.hmcts.reform.deedpoll.domain.audit.AuditTrail;
import uk.gov.hmcts.reform.deedpoll.domain.auth.CurrentUser;
import uk.gov.hmcts.reform.deedpoll.domain.auth.Role;

import java.time.Duration;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;

/**
 * V40, V42 and the service against a real Postgres, each update in its own committed transaction.
 *
 * <p>Not rolled back, because the concurrency and rollback cases are only meaningful across real commits. The
 * singleton and its history are reset around every test; the audit rows cannot be (V36 refuses the delete), so each
 * test acts as a fresh subject and counts only its own events.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class ProcessSettingsPersistenceTest extends AbstractPostgresContainerTest {

    private static final int TIMEOUT_SECONDS = 60;

    @Autowired
    private ProcessSettingsService service;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionTemplate transactions;

    @MockitoSpyBean
    private AuditTrail audit;

    private String subject;

    @BeforeEach
    @AfterEach
    void reseed() {
        jdbc.update("DELETE FROM deedpoll_process_settings_change");
        jdbc.update("""
            INSERT INTO deedpoll_process_settings (id, referral_claim_expiry_minutes, revision) VALUES (1, 10080, 0)
            ON CONFLICT (id) DO UPDATE SET referral_claim_expiry_minutes = 10080, reminder_interval_hours = 48,
                master_window_cap = 10, morning_window_start = '07:00', morning_window_end = '10:30',
                afternoon_window_start = '14:00', afternoon_window_end = '14:30', revision = 0, updated_at = NULL,
                updated_by_id = NULL, updated_by_name = NULL
            """);
        subject = "process-settings-" + UUID.randomUUID();
    }

    @Test
    @DisplayName("the seed: seven days, 48 hours, a cap of 10 and the two windows, at revision 0 with no attribution")
    void seeded() {
        ProcessSettings settings = service.get(deliveryManager(subject));

        assertThat(settings).isEqualTo(new ProcessSettings(Duration.ofDays(7), Duration.ofHours(48), 10,
            LocalTime.of(7, 0), LocalTime.of(10, 30), LocalTime.of(14, 0), LocalTime.of(14, 30), 0, null, null));
        assertThat(service.processingWindow()).isEqualTo(Duration.ofMinutes(10080));
        assertThat(service.reminderInterval()).isEqualTo(Duration.ofHours(48));
        assertThat(service.masterWindowCap()).isEqualTo(10);
        assertThat(service.referralWindows()).isEqualTo(settings.referralWindows());
    }

    @Test
    @DisplayName("the database refuses every value outside its bounds, misordered windows, and unrevised attribution")
    void databaseChecks() {
        for (String assignment : List.of("referral_claim_expiry_minutes = 1439",
                                         "referral_claim_expiry_minutes = 43201",
                                         "reminder_interval_hours = 0", "reminder_interval_hours = 169",
                                         "master_window_cap = 0", "master_window_cap = 101",
                                         "morning_window_start = '07:10'", "afternoon_window_end = '14:30:30'",
                                         "morning_window_end = '07:00'", "morning_window_end = '14:00'",
                                         "afternoon_window_end = '14:00'")) {
            assertThatThrownBy(() -> jdbc.update("UPDATE deedpoll_process_settings SET " + assignment
                + " WHERE id = 1")).as(assignment).isInstanceOf(DataIntegrityViolationException.class);
        }
        assertThat(jdbc.update("UPDATE deedpoll_process_settings SET referral_claim_expiry_minutes = 43200, "
            + "reminder_interval_hours = 1, master_window_cap = 100, morning_window_end = '13:45' WHERE id = 1"))
            .isOne();
        assertThatThrownBy(() -> jdbc.update("UPDATE deedpoll_process_settings SET revision = 1 WHERE id = 1"))
            .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(
            "INSERT INTO deedpoll_process_settings (id, referral_claim_expiry_minutes, revision) VALUES (2, 10080, 0)"))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("a change commits the row, one history row with both values, and one audit event with no values")
    void change() {
        ProcessSettings next = service.update(deliveryManager(subject), body(1445, 0));

        assertThat(next.revision()).isOne();
        assertThat(row()).containsEntry("referral_claim_expiry_minutes", 1445).containsEntry("revision", 1)
            .containsEntry("updated_by_id", subject).containsEntry("updated_by_name", "A Delivery Manager");
        assertThat(row().get("updated_at")).isNotNull();
        assertThat(history()).singleElement().satisfies(change -> assertThat(change)
            .containsEntry("revision", 1)
            .containsEntry("field", "referralClaimExpiryMinutes")
            .containsEntry("previous_value", "10080")
            .containsEntry("new_value", "1445")
            .containsEntry("changed_by_id", subject)
            .containsEntry("changed_by_name", "A Delivery Manager"));
        assertThat(events()).singleElement().satisfies(event -> assertThat(event)
            .containsEntry("action", "PROCESS_SETTINGS_CHANGED")
            .containsEntry("entity_type", "ProcessSettings")
            .containsEntry("entity_id", "1")
            .containsEntry("case_id", null)
            .containsEntry("fields", "referralClaimExpiryMinutes")
            .containsEntry("value_rows", 0L));
        assertThat(service.processingWindow()).isEqualTo(Duration.ofMinutes(1445));
    }

    @Test
    @DisplayName("an unchanged value, a stale revision and a refused body each leave everything as it was")
    void nothingWritten() {
        CurrentUser caller = deliveryManager(subject);

        assertThat(service.update(caller, body(10080, 0)).revision()).isZero();
        assertThatThrownBy(() -> service.update(caller, body(2880, 1))).isInstanceOf(ConflictError.class);
        assertThatThrownBy(() -> service.update(caller, body(1439, 0))).isInstanceOf(InvalidInputError.class);

        assertUnchanged();
    }

    @Test
    @DisplayName("a stale revision carrying the value the row now holds is still a conflict, not a save")
    void staleBeatsNoOp() {
        service.update(deliveryManager("first-" + subject), body(2880, 0));

        assertThatThrownBy(() -> service.update(deliveryManager(subject), body(2880, 0)))
            .isInstanceOf(ConflictError.class);
        assertThat(row()).containsEntry("revision", 1);
        assertThat(history()).hasSize(1);
        assertThat(events()).isEmpty();
    }

    @Test
    @DisplayName("two updates from the same revision, overlapping on the lock, give one success and one conflict")
    void concurrentUpdates() throws Exception {
        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean first = new AtomicBoolean(true);
        stub(() -> doAnswer(call -> {
            if (first.getAndSet(false)) {
                holding.countDown();
                await(release);
            }
            return call.callRealMethod();
        }).when(audit).record(any(), any()));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            final Future<ProcessSettings> winner =
                pool.submit(() -> service.update(deliveryManager(subject), body(2880, 0)));
            await(holding);
            Future<ProcessSettings> loser = pool.submit(() -> service.update(deliveryManager(subject), body(4320, 0)));
            awaitLockWaiter();
            release.countDown();

            List<Object> outcomes = new ArrayList<>();
            for (Future<ProcessSettings> update : List.of(winner, loser)) {
                try {
                    outcomes.add(update.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
                } catch (ExecutionException failure) {
                    outcomes.add(failure.getCause());
                }
            }
            assertThat(outcomes).hasOnlyElementsOfTypes(ProcessSettings.class, ConflictError.class);
            assertThat(outcomes).filteredOn(ConflictError.class::isInstance).hasSize(1);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }

        assertThat(row()).containsEntry("revision", 1).containsEntry("referral_claim_expiry_minutes", 2880);
        assertThat(history()).singleElement()
            .satisfies(change -> assertThat(change).containsEntry("new_value", "2880"));
        assertThat(events()).hasSize(1);
    }

    @Test
    @DisplayName("an audit insert that fails rolls back the row and the history")
    void auditFailureRollsBack() {
        stub(() -> doThrow(new IllegalStateException("audit refused")).when(audit).record(any(), any()));

        assertThatThrownBy(() -> service.update(deliveryManager(subject), body(2880, 0)))
            .isInstanceOf(IllegalStateException.class);

        assertUnchanged();
    }

    @Test
    @DisplayName("a history insert that fails leaves the row unchanged and commits no audit event")
    void historyFailureRollsBack() {
        jdbc.update("""
            INSERT INTO deedpoll_process_settings_change
                (revision, field, previous_value, new_value, changed_at, changed_by_id, changed_by_name)
            VALUES (1, 'referralClaimExpiryMinutes', '10080', '1440', now(), 'someone', 'Someone')
            """);

        assertThatThrownBy(() -> service.update(deliveryManager(subject), body(2880, 0)))
            .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(row()).containsEntry("referral_claim_expiry_minutes", 10080).containsEntry("revision", 0);
        assertThat(history()).singleElement().satisfies(change -> assertThat(change)
            .containsEntry("changed_by_id", "someone"));
        assertThat(events()).isEmpty();
    }

    @Test
    @DisplayName("a missing singleton is an error for the sweep's read, never a default")
    void missingRow() {
        jdbc.update("DELETE FROM deedpoll_process_settings");

        assertThatThrownBy(service::processingWindow).isInstanceOf(IllegalStateException.class);
    }

    /**
     * Stubs the spy inside a transaction, because the spy is of {@code AuditTrail}'s transactional proxy and
     * {@code record} is {@code MANDATORY}: the stubbing call itself passes through the interceptor.
     */
    private void stub(Runnable stubbing) {
        transactions.executeWithoutResult(status -> stubbing.run());
    }

    private void assertUnchanged() {
        assertThat(row()).containsEntry("referral_claim_expiry_minutes", 10080).containsEntry("revision", 0)
            .containsEntry("updated_at", null).containsEntry("updated_by_id", null);
        assertThat(history()).isEmpty();
        assertThat(events()).isEmpty();
    }

    /** Waits until some backend is blocked on a lock, which here can only be the second update's FOR UPDATE. */
    private void awaitLockWaiter() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
        while (System.nanoTime() < deadline) {
            Integer waiting = jdbc.queryForObject("""
                SELECT count(*) FROM pg_stat_activity
                WHERE wait_event_type = 'Lock' AND query LIKE '%deedpoll_process_settings%'
                """, Integer.class);
            if (waiting != null && waiting > 0) {
                return;
            }
            Thread.sleep(20);
        }
        throw new IllegalStateException("the second update never waited on the singleton lock");
    }

    private Map<String, Object> row() {
        return jdbc.queryForMap("SELECT * FROM deedpoll_process_settings WHERE id = 1");
    }

    private List<Map<String, Object>> history() {
        return jdbc.queryForList("SELECT * FROM deedpoll_process_settings_change ORDER BY revision, field");
    }

    private List<Map<String, Object>> events() {
        return jdbc.queryForList("""
            SELECT e.action, e.entity_type, e.entity_id, e.case_id, array_to_string(e.fields, ',') AS fields,
                   (SELECT count(*) FROM deedpoll_audit_event_value v WHERE v.event_id = e.id) AS value_rows
            FROM deedpoll_audit_event e WHERE e.actor_id = ? ORDER BY e.id
            """, subject);
    }

    private static CurrentUser deliveryManager(String subject) {
        return CurrentUser.currentUser(List.of(Role.DELIVERY_MANAGER), "A Delivery Manager", subject);
    }

    private static Map<String, Object> body(int minutes, int revision) {
        Map<String, Object> body = new HashMap<>();
        body.put("referralClaimExpiryMinutes", minutes);
        body.put("revision", revision);
        return body;
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new IllegalStateException("the other update never reached its signal");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }
}
