package uk.gov.hmcts.reform.deedpoll.domain.deletion;

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
import uk.gov.hmcts.reform.deedpoll.domain.ForbiddenError;
import uk.gov.hmcts.reform.deedpoll.domain.NotFoundError;
import uk.gov.hmcts.reform.deedpoll.domain.UnauthorizedError;
import uk.gov.hmcts.reform.deedpoll.domain.auth.CurrentUser;
import uk.gov.hmcts.reform.deedpoll.domain.auth.Role;
import uk.gov.hmcts.reform.deedpoll.domain.deletion.ApplicationDeletionService.Admission;
import uk.gov.hmcts.reform.deedpoll.domain.deletion.ApplicationDeletionService.DeletionCommand;
import uk.gov.hmcts.reform.deedpoll.entity.AuditClock;
import uk.gov.hmcts.reform.deedpoll.repository.CaseLastChanged;
import uk.gov.hmcts.reform.deedpoll.repository.DeletionRequestRepository;
import uk.gov.hmcts.reform.deedpoll.repository.DeletionWorkRepository;

import java.lang.reflect.Constructor;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A Delivery Manager's restore of an application during its deletion's grace period, against real Postgres and real
 * transactions (addendum 20261119-deletion-grace-period-and-restore, Task 4): it deletes the request and its
 * inventory, records one note-free audit event, refuses once the period has ended or the worker has started, and
 * neither it nor a racing worker or admission deadlocks or leaves both outcomes.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class ApplicationDeletionRestoreTest extends AbstractPostgresContainerTest {

    private static final String SUBJECT = "deletion-restore-dm";
    private static final CurrentUser DELIVERY_MANAGER =
        CurrentUser.currentUser(List.of(Role.DELIVERY_MANAGER), "Dee Manager", SUBJECT);
    private static final CurrentUser SECOND_DELIVERY_MANAGER =
        CurrentUser.currentUser(List.of(Role.DELIVERY_MANAGER), "Second Manager", "deletion-restore-dm-2");
    private static final LocalDateTime MODIFIED = LocalDateTime.of(2026, 11, 18, 9, 30, 15, 250_000_000);
    private static final String NOTE = "Logged twice from one postal application, marker 9RT2K";
    private static final int TIMEOUT_SECONDS = 20;

    @Autowired
    private ApplicationDeletionService deletions;

    @Autowired
    private DeletionRequestRepository requests;

    @Autowired
    private DeletionWorkRepository work;

    @Autowired
    private CaseLastChanged lastChanged;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactions;

    private final List<String> fixtures = new ArrayList<>();

    @BeforeEach
    void nothingElseIsDue() {
        // Nothing another test left behind is due: every claim below must be one of this test's requests.
        jdbc.update("""
            update deedpoll_deletion_request set next_attempt_at = next_attempt_at + interval '100 years'
             where status in ('Pending', 'InProgress', 'AwaitingDisposal')
            """);
    }

    @AfterEach
    void cleanUp() {
        jdbc.execute("DROP TRIGGER IF EXISTS restore_test_audit_failure ON deedpoll_audit_event");
        jdbc.execute("DROP FUNCTION IF EXISTS restore_test_audit_failure()");
        for (String id : fixtures) {
            jdbc.update("DELETE FROM deedpoll_deletion_request WHERE case_id = ?", id);
            jdbc.update("DELETE FROM deedpoll_deedpoll WHERE id = ?", id);
        }
    }

    private String fixture() {
        String id = UUID.randomUUID().toString();
        fixtures.add(id);
        jdbc.update("""
            INSERT INTO deedpoll_deedpoll (id, "modifiedOn", type, state, "displayReference")
            VALUES (?, ?, 'Adult'::"DeedPollType", 'Enroled', ?)
            """, id, Timestamp.valueOf(MODIFIED), "restore-" + id);
        return id;
    }

    private void localDocument(String caseId) {
        jdbc.update("""
            INSERT INTO deedpoll_supportingdocument (id, "deedPollId", direction, name, "documentTypeId",
                "storageKey", "modifiedOn", "storageState", "storageUploadAttempts")
            VALUES (?, ?, 'Incoming', 'Evidence', (SELECT id FROM deedpoll_supportingdocumenttype ORDER BY id LIMIT 1),
                    ?, now(), 'Local', 0)
            """, UUID.randomUUID().toString(), caseId, "files/" + UUID.randomUUID());
    }

    private UUID admit(String caseId) {
        return admit(caseId, MODIFIED).request().id();
    }

    private Admission admit(String caseId, LocalDateTime reviewed) {
        return deletions.request(DELIVERY_MANAGER, caseId,
                                 new DeletionCommand(UUID.randomUUID(), "DUPLICATE", NOTE, reviewed));
    }

    private int count(String sql, Object... arguments) {
        return jdbc.queryForObject(sql, Integer.class, arguments);
    }

    private int requestCount(String caseId) {
        return count("SELECT count(*) FROM deedpoll_deletion_request WHERE case_id = ?", caseId);
    }

    private int stepCount(UUID requestId) {
        return count("SELECT count(*) FROM deedpoll_deletion_step WHERE request_id = ?", requestId);
    }

    private List<Map<String, Object>> restoredEvents(String caseId) {
        return jdbc.queryForList("""
            SELECT actor_id, actor_name, entity_type, entity_id, detail::text AS detail,
                   (SELECT count(*) FROM deedpoll_audit_event_value v WHERE v.event_id = e.id) AS value_rows
              FROM deedpoll_audit_event e WHERE case_id = ? AND action = 'CASE_DELETION_RESTORED'
            """, caseId);
    }

    private static void refused(Runnable restore, DeletionRefusal expected) {
        assertThatThrownBy(restore::run).as(expected.name())
            .isInstanceOfSatisfying(DeletionConflictError.class, error -> {
                assertThat(error.refusal()).isEqualTo(expected);
                assertThat(error.getMessage()).isEqualTo(expected.message());
            });
    }

    @Test
    @DisplayName("a restore inside the window deletes the request and its inventory, and records one note-free event")
    void restoresInsideTheWindow() {
        String id = fixture();
        localDocument(id);
        UUID request = admit(id);
        assertThat(stepCount(request)).isOne();

        String restored = deletions.restore(SECOND_DELIVERY_MANAGER, request);

        assertThat(restored).isEqualTo(id);
        assertThat(requestCount(id)).isZero();
        assertThat(stepCount(request)).as("the inventory goes with its request").isZero();
        assertThat(count("SELECT count(*) FROM deedpoll_supportingdocument WHERE \"deedPollId\" = ?", id))
            .as("nothing on the case was removed").isOne();
        List<Map<String, Object>> events = restoredEvents(id);
        assertThat(events).hasSize(1);
        Map<String, Object> event = events.getFirst();
        assertThat(event.get("actor_id")).as("the restoring Delivery Manager, not the requester")
            .isEqualTo("deletion-restore-dm-2");
        assertThat(event.get("entity_type")).isEqualTo("DeedPoll");
        assertThat(event.get("entity_id")).isEqualTo(id);
        assertThat(event.get("value_rows")).isEqualTo(0L);
        assertThat((String) event.get("detail")).contains(request.toString()).contains("DUPLICATE")
            .doesNotContain("9RT2K");
        assertThat(count("""
            SELECT count(*) FROM deedpoll_audit_event WHERE case_id = ? AND action = 'CASE_DELETION_REQUESTED'
            """, id)).as("the request's own event is kept").isOne();
    }

    @Test
    @DisplayName("every role but Delivery Manager is refused before the id is looked up")
    void forbiddenFirst() {
        String id = fixture();
        UUID request = admit(id);
        for (Role role : Arrays.stream(Role.values()).filter(role -> role != Role.DELIVERY_MANAGER).toList()) {
            CurrentUser caller = CurrentUser.currentUser(List.of(role), "Other", "deletion-restore-other");

            assertThatThrownBy(() -> deletions.restore(caller, request)).as(role.name())
                .isInstanceOf(ForbiddenError.class);
            assertThatThrownBy(() -> deletions.restore(caller, UUID.randomUUID())).as(role.name())
                .isInstanceOf(ForbiddenError.class);
        }
        assertThat(requestCount(id)).isOne();
    }

    @Test
    @DisplayName("a Delivery Manager with no subject is refused, and nothing is restored")
    void noSubjectIsRefused() {
        String id = fixture();
        UUID request = admit(id);
        CurrentUser anonymous = CurrentUser.currentUser(List.of(Role.DELIVERY_MANAGER), "Dee Manager", null);

        assertThatThrownBy(() -> deletions.restore(anonymous, request)).isInstanceOf(UnauthorizedError.class);
        assertThat(requestCount(id)).isOne();
        assertThat(restoredEvents(id)).isEmpty();
    }

    @Test
    @DisplayName("an unknown request is 404, and so is a second restore of the same one")
    void unknownAndRepeatedAreNotFound() {
        assertThatThrownBy(() -> deletions.restore(DELIVERY_MANAGER, UUID.randomUUID()))
            .isInstanceOf(NotFoundError.class).hasMessage("Deletion request not found");
        String id = fixture();
        UUID request = admit(id);
        deletions.restore(DELIVERY_MANAGER, request);

        assertThatThrownBy(() -> deletions.restore(DELIVERY_MANAGER, request))
            .isInstanceOf(NotFoundError.class).hasMessage("Deletion request not found");
        assertThat(restoredEvents(id)).hasSize(1);
    }

    @Test
    @DisplayName("after restorable_until it is RESTORE_WINDOW_CLOSED; once claimed, or attempted, CLEANUP_STARTED")
    void refusedOnceTheWindowHasClosedOrCleanupStarted() {
        String claimed = fixture();
        UUID claimedRequest = admit(claimed);
        DeletionGracePeriods.elapse(jdbc, claimedRequest);
        LocalDateTime now = AuditClock.now();
        assertThat(work.claimNext(UUID.randomUUID(), now, now.plusMinutes(5))).contains(claimedRequest);
        refused(() -> deletions.restore(DELIVERY_MANAGER, claimedRequest), DeletionRefusal.CLEANUP_STARTED);

        // A claim given up and put back to Pending by hand has still been attempted.
        jdbc.update("""
            UPDATE deedpoll_deletion_request SET status = 'Pending', owner = NULL, lease_until = NULL WHERE id = ?
            """, claimedRequest);
        refused(() -> deletions.restore(DELIVERY_MANAGER, claimedRequest), DeletionRefusal.CLEANUP_STARTED);

        String closed = fixture();
        UUID closedRequest = admit(closed);
        DeletionGracePeriods.elapse(jdbc, closedRequest);
        refused(() -> deletions.restore(DELIVERY_MANAGER, closedRequest), DeletionRefusal.RESTORE_WINDOW_CLOSED);
        assertThat(requestCount(closed)).isOne();
        assertThat(stepCount(closedRequest)).isZero();

        assertThat(requestCount(claimed)).isOne();
        assertThat(restoredEvents(closed)).isEmpty();
        assertThat(restoredEvents(claimed)).isEmpty();
    }

    @Test
    @DisplayName("the delete repeats the rule: a request whose window has passed is not deleted, however it was read")
    void theDeleteRepeatsTheRule() {
        String id = fixture();
        UUID request = admit(id);
        LocalDateTime until = requests.findById(request).orElseThrow().restorableUntil();

        LocalDateTime inside = until.minusNanos(1_000_000);
        assertThat(requests.deleteRestorable(request, until)).isFalse();

        // Each of the rule's other conditions, alone, inside the window.
        jdbc.update("UPDATE deedpoll_deletion_request SET status = 'InProgress' WHERE id = ?", request);
        assertThat(requests.deleteRestorable(request, inside)).as("not Pending").isFalse();
        jdbc.update("""
            UPDATE deedpoll_deletion_request SET status = 'Pending', owner = ?, lease_until = ? WHERE id = ?
            """, UUID.randomUUID(), Timestamp.valueOf(until), request);
        assertThat(requests.deleteRestorable(request, inside)).as("claimed").isFalse();
        jdbc.update("""
            UPDATE deedpoll_deletion_request SET owner = NULL, lease_until = NULL, attempts = 1 WHERE id = ?
            """, request);
        assertThat(requests.deleteRestorable(request, inside)).as("attempted").isFalse();
        jdbc.update("UPDATE deedpoll_deletion_request SET attempts = 0 WHERE id = ?", request);

        assertThat(requests.deleteRestorable(request, inside)).isTrue();
        assertThat(requestCount(id)).isZero();
    }

    @Test
    @DisplayName("an audit failure restores nothing: the request and its inventory stay")
    void auditFailureRollsBack() {
        String id = fixture();
        localDocument(id);
        UUID request = admit(id);
        jdbc.execute("""
            CREATE FUNCTION restore_test_audit_failure() RETURNS trigger LANGUAGE plpgsql AS $$
            BEGIN
                IF NEW.action = 'CASE_DELETION_RESTORED' THEN
                    RAISE EXCEPTION 'synthetic audit failure';
                END IF;
                RETURN NEW;
            END;
            $$
            """);
        jdbc.execute("""
            CREATE TRIGGER restore_test_audit_failure BEFORE INSERT ON deedpoll_audit_event
                FOR EACH ROW EXECUTE FUNCTION restore_test_audit_failure()
            """);

        assertThatThrownBy(() -> deletions.restore(DELIVERY_MANAGER, request))
            .isNotInstanceOf(ConflictError.class)
            .rootCause().hasMessageContaining("synthetic audit failure");
        assertThat(requestCount(id)).isOne();
        assertThat(stepCount(request)).isOne();
    }

    @Test
    @DisplayName("deleting the case again after a restore needs a fresh review, and admits a new request")
    void deletingAgainAfterARestore() {
        String id = fixture();
        UUID first = admit(id);
        deletions.restore(DELIVERY_MANAGER, first);

        refused(() -> admit(id, MODIFIED), DeletionRefusal.STALE_CONFIRMATION);
        LocalDateTime reviewed = lastChanged.of(id).orElseThrow();
        Admission again = admit(id, reviewed);

        assertThat(again.admitted()).isTrue();
        assertThat(again.request().id()).isNotEqualTo(first);
        assertThat(requestCount(id)).isOne();
        assertThat(again.request().restorableUntil()).isAfter(AuditClock.now().plusDays(13));
    }

    @Test
    @DisplayName("nothing outside the database is reachable from admission or restore, and neither runs any step")
    void noExternalCallBetweenAdmissionAndRestore() {
        String id = fixture();
        localDocument(id);
        UUID request = admit(id);
        assertThat(count("""
            SELECT count(*) FROM deedpoll_deletion_step WHERE request_id = ? AND (state <> 'Pending' OR attempts > 0)
            """, request)).as("no step was tried between admission and restore").isZero();
        assertThat(count("SELECT attempts FROM deedpoll_deletion_request WHERE id = ?", request)).isZero();
        deletions.restore(DELIVERY_MANAGER, request);

        // The service holds nothing that reaches CCD, CDAM, the filesystem store, Notify or IDAM: only this
        // database's repositories, the audit trail and the process settings.
        Constructor<?>[] constructors = ApplicationDeletionService.class.getConstructors();
        assertThat(constructors).hasSize(1);
        assertThat(constructors[0].getParameterTypes()).allSatisfy(type -> assertThat(type.getPackageName())
            .as(type.getName()).isIn("uk.gov.hmcts.reform.deedpoll.repository",
                                     "uk.gov.hmcts.reform.deedpoll.domain.audit",
                                     "uk.gov.hmcts.reform.deedpoll.domain.settings"));
    }

    @Test
    @DisplayName("a restore racing the worker's claim at the window's edge: exactly one wins, with no deadlock")
    void restoreRacesTheWorker() throws Exception {
        for (int round = 0; round < 5; round++) {
            String id = fixture();
            UUID request = admit(id);
            // The worker is given a clock past the window's end, so both are eligible at once and only the locks
            // decide: the restore's rule reads the real clock, still inside it.
            LocalDateTime pastTheEnd = requests.findById(request).orElseThrow().restorableUntil().plusSeconds(1);
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                CountDownLatch start = new CountDownLatch(1);
                Future<Object> restore = pool.submit(() -> {
                    start.await();
                    try {
                        return deletions.restore(DELIVERY_MANAGER, request);
                    } catch (RuntimeException refused) {
                        return refused;
                    }
                });
                Future<Optional<UUID>> claim = pool.submit(() -> {
                    start.await();
                    return new TransactionTemplate(transactions).execute(status -> work.claimNext(
                        UUID.randomUUID(), pastTheEnd, pastTheEnd.plusMinutes(5)));
                });
                start.countDown();
                Object restored = restore.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                Optional<UUID> claimed = claim.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

                assertThat(claimed).as("round %d: only this test's request is due", round)
                    .satisfiesAnyOf(none -> assertThat(none).isEmpty(), mine -> assertThat(mine).contains(request));
                boolean restoreWon = restored instanceof String;
                boolean claimWon = claimed.isPresent();
                assertThat(restoreWon ^ claimWon).as("round %d: %s / %s", round, restored, claimed).isTrue();
                if (claimWon) {
                    assertThat(restored).isInstanceOfSatisfying(DeletionConflictError.class, error ->
                        assertThat(error.refusal()).isEqualTo(DeletionRefusal.CLEANUP_STARTED));
                    assertThat(requestCount(id)).isOne();
                } else {
                    assertThat(requestCount(id)).isZero();
                }
            } finally {
                pool.shutdownNow();
            }
            // Leave nothing claimable for the next round's claim to take instead.
            jdbc.update("DELETE FROM deedpoll_deletion_request WHERE case_id = ?", id);
        }
    }

    @Test
    @DisplayName("a restore never waits for a request the worker holds, so finalisation's order cannot deadlock it")
    void restoreDoesNotWaitForAHeldRequest() throws Exception {
        String id = fixture();
        UUID request = admit(id);
        ExecutorService pool = Executors.newFixedThreadPool(1);
        try {
            new TransactionTemplate(transactions).executeWithoutResult(status -> {
                // Finalisation's order: the request first, then the case. The restore runs in between; were it to
                // wait for the request while holding the case, the case lock below would deadlock with it.
                jdbc.queryForList("SELECT id FROM deedpoll_deletion_request WHERE id = ? FOR UPDATE", request);
                Future<Object> restore = pool.submit(() -> {
                    try {
                        return deletions.restore(DELIVERY_MANAGER, request);
                    } catch (RuntimeException refused) {
                        return refused;
                    }
                });
                Object restored;
                try {
                    restored = restore.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                } catch (Exception failed) {
                    throw new IllegalStateException(failed);
                }
                assertThat(restored).isInstanceOfSatisfying(DeletionConflictError.class, error ->
                    assertThat(error.refusal()).isEqualTo(DeletionRefusal.CLEANUP_STARTED));
                assertThat(jdbc.queryForList("SELECT id FROM deedpoll_deedpoll WHERE id = ? FOR UPDATE NOWAIT",
                                             String.class, id)).containsExactly(id);
            });
            assertThat(requestCount(id)).isOne();
            assertThat(restoredEvents(id)).isEmpty();
            assertThat(deletions.restore(DELIVERY_MANAGER, request)).as("once released").isEqualTo(id);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("a retry racing a restore waits for the case lock and is stale, never a 202 for a removed request")
    void restoreRacesAnAdmission() throws Exception {
        String id = fixture();
        UUID request = admit(id);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<Object>> futures = new ArrayList<>();
            new TransactionTemplate(transactions).executeWithoutResult(status -> {
                // Hold the case lock, as an admission in progress holds it, until the restore is waiting on it.
                jdbc.queryForList("SELECT id FROM deedpoll_deedpoll WHERE id = ? FOR UPDATE", id);
                futures.add(pool.submit(() -> {
                    try {
                        return deletions.restore(DELIVERY_MANAGER, request);
                    } catch (RuntimeException refused) {
                        return refused;
                    }
                }));
                awaitLockWaiters(1);
                futures.add(pool.submit(() -> {
                    try {
                        return admit(id, MODIFIED);
                    } catch (RuntimeException refused) {
                        return refused;
                    }
                }));
                // The retry takes the case lock before reading the request, so it queues behind the restore.
                awaitLockWaiters(2);
            });
            Object restored = futures.get(0).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            Object admitted = futures.get(1).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

            assertThat(restored).isEqualTo(id);
            assertThat(admitted).as("after the restore, the old review is stale; never a replay of the removed request")
                .isInstanceOfSatisfying(DeletionConflictError.class, error ->
                    assertThat(error.refusal()).isEqualTo(DeletionRefusal.STALE_CONFIRMATION));
            assertThat(requestCount(id)).isZero();
            assertThat(restoredEvents(id)).hasSize(1);
        } finally {
            pool.shutdownNow();
        }
    }

    private void awaitLockWaiters(int count) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
        while (System.nanoTime() < deadline) {
            if (waiters() >= count) {
                return;
            }
            pause();
        }
        throw new IllegalStateException("the restore never waited on the case lock");
    }

    private int waiters() {
        Integer waiting = jdbc.queryForObject("""
            SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock' AND datname = current_database()
            """, Integer.class);
        return waiting == null ? 0 : waiting;
    }

    private static void pause() {
        try {
            Thread.sleep(Duration.ofMillis(20));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }
}
