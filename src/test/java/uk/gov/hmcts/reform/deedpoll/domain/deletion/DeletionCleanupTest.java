package uk.gov.hmcts.reform.deedpoll.domain.deletion;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import uk.gov.hmcts.reform.deedpoll.ccd.CcdProperties;
import uk.gov.hmcts.reform.deedpoll.ccd.DeedPollRetainAndDisposePolicy;
import uk.gov.hmcts.reform.deedpoll.config.AbstractPostgresContainerTest;
import uk.gov.hmcts.reform.deedpoll.domain.auth.CurrentUser;
import uk.gov.hmcts.reform.deedpoll.domain.auth.Role;
import uk.gov.hmcts.reform.deedpoll.domain.deletion.ApplicationDeletionService.DeletionCommand;
import uk.gov.hmcts.reform.deedpoll.entity.AuditClock;
import uk.gov.hmcts.reform.deedpoll.repository.DeletionRequestRepository;
import uk.gov.hmcts.reform.deedpoll.repository.DeletionWorkRepository;
import uk.gov.hmcts.reform.deedpoll.storage.LocalFilesystemDocumentStore;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
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
 * {@link DeletionCleanup} against real Postgres, a real filesystem store and real transactions: what it removes,
 * what it refuses to count as removed, and how claims, leases, restarts and a second worker interleave (plan
 * 20261118-dm-application-deletion, Task 5). Each request is admitted through {@link ApplicationDeletionService},
 * so the inventory is the one admission really writes.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class DeletionCleanupTest extends AbstractPostgresContainerTest {

    private static final CurrentUser DELIVERY_MANAGER =
        CurrentUser.currentUser(List.of(Role.DELIVERY_MANAGER), "Dee Manager", "deletion-cleanup-dm");
    private static final LocalDateTime MODIFIED = LocalDateTime.of(2026, 11, 18, 9, 30, 15, 250_000_000);
    private static final Duration POLL = Duration.ofHours(1);
    private static final int MAX_ATTEMPTS = 2;

    @Autowired
    private ApplicationDeletionService deletions;

    @Autowired
    private DeletionRequestRepository requests;

    @Autowired
    private DeletionWorkRepository work;

    @Autowired
    private DeedPollRetainAndDisposePolicy policy;

    @Autowired
    private CcdProperties ccd;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactions;

    @Autowired
    private ApplicationContext context;

    @TempDir
    private Path root;

    private Path documents;
    private DeletionCleanup cleanup;
    private final List<String> fixtures = new ArrayList<>();
    private final List<Long> ccdCases = new ArrayList<>();

    @BeforeEach
    void setUp() throws IOException {
        documents = Files.createDirectories(root.resolve("documents"));
        cleanup = worker(store(documents));
        // Nothing another test left behind is due: every claim below must be one of this test's requests.
        jdbc.update("""
            update deedpoll_deletion_request set next_attempt_at = next_attempt_at + interval '100 years'
             where status in ('Pending', 'InProgress', 'AwaitingDisposal')
            """);
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
    }

    private ObjectProvider<LocalFilesystemDocumentStore> store(Path at) {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        if (at != null) {
            beans.addBean("store", new LocalFilesystemDocumentStore(at.toString()));
        }
        return beans.getBeanProvider(LocalFilesystemDocumentStore.class);
    }

    private DeletionCleanup worker(ObjectProvider<LocalFilesystemDocumentStore> files) {
        return new DeletionCleanup(work, requests, files, transactions,
            new DeletionCleanupProperties(false, Duration.ofMinutes(5), 10, Duration.ofMinutes(5), MAX_ATTEMPTS,
                                          Duration.ofMinutes(1), POLL), AuditClock::now);
    }

    private String fixture() {
        String id = UUID.randomUUID().toString();
        fixtures.add(id);
        jdbc.update("""
            INSERT INTO deedpoll_deedpoll (id, "modifiedOn", type, state, "displayReference")
            VALUES (?, ?, 'Adult'::"DeedPollType", 'Enroled', ?)
            """, id, Timestamp.valueOf(MODIFIED), "cleanup-" + id);
        return id;
    }

    /**
     * A filesystem document: its row, and its bytes under the key unless {@code stored} is false.
     */
    private String localDocument(String caseId, String key, boolean stored) throws IOException {
        jdbc.update("""
            INSERT INTO deedpoll_supportingdocument (id, "deedPollId", direction, name, "documentTypeId",
                "storageKey", "modifiedOn", "storageState")
            VALUES (?, ?, 'Incoming', 'Evidence', (SELECT id FROM deedpoll_supportingdocumenttype ORDER BY id LIMIT 1),
                    ?, now(), 'Local')
            """, UUID.randomUUID().toString(), caseId, key);
        if (stored) {
            Files.writeString(documents.resolve(key), "applicant evidence");
        }
        return key;
    }

    private long link(String caseId) {
        long reference = 1_000_000_000_000_000L + Math.floorMod(UUID.randomUUID().getMostSignificantBits(),
                                                                  8_999_999_999_999_999L);
        ccdCases.add(reference);
        jdbc.update("UPDATE deedpoll_deedpoll SET \"ccdCaseReference\" = ? WHERE id = ?", String.valueOf(reference),
                    caseId);
        jdbc.update("""
            INSERT INTO ccd.case_data (reference, id, security_classification, jurisdiction, case_type_id, state, data)
            VALUES (?, ?, 'PUBLIC', ?, ?, 'Registered', '{}')
            """, reference, reference, ccd.jurisdictionId(), ccd.caseTypeId());
        return reference;
    }

    private DeletionRequest admit(String caseId) {
        return deletions.request(DELIVERY_MANAGER, caseId, new DeletionCommand(UUID.randomUUID(), "CREATED_IN_ERROR",
            "Opened against the wrong application by mistake", MODIFIED)).request();
    }

    private DeletionRequest reread(DeletionRequest request) {
        return requests.findById(request.id()).orElseThrow();
    }

    private Map<String, Object> step(DeletionRequest request, DeletionStepKind kind) {
        return jdbc.queryForMap("""
            SELECT state, outcome, attempts, locator FROM deedpoll_deletion_step WHERE request_id = ? AND kind = ?
            """, request.id(), kind.name());
    }

    private void due(DeletionRequest request) {
        jdbc.update("UPDATE deedpoll_deletion_request SET next_attempt_at = ? WHERE id = ?",
                    Timestamp.valueOf(AuditClock.now().minusSeconds(1)), request.id());
    }

    private void leaseLapsed(DeletionRequest request) {
        jdbc.update("UPDATE deedpoll_deletion_request SET lease_until = ? WHERE id = ?",
                    Timestamp.valueOf(AuditClock.now().minusSeconds(1)), request.id());
    }

    private Optional<DeletionProgress.Decision> claimAndProcess(DeletionCleanup worker) {
        UUID owner = UUID.randomUUID();
        return worker.claim(owner).flatMap(id -> worker.process(id, owner));
    }

    @Test
    @DisplayName("an unlinked case's files are removed and verified, and only finalisation is left: never Completed")
    void removesUnlinkedFiles() throws IOException {
        String id = fixture();
        String present = localDocument(id, UUID.randomUUID().toString(), true);
        localDocument(id, UUID.randomUUID().toString(), false);
        DeletionRequest request = admit(id);

        Optional<DeletionProgress.Decision> decision = claimAndProcess(cleanup);

        assertThat(decision).hasValueSatisfying(made -> assertThat(made.externalCleanupDone()).isTrue());
        assertThat(documents.resolve(present)).doesNotExist();
        assertThat(jdbc.queryForList("""
            SELECT outcome FROM deedpoll_deletion_step WHERE request_id = ? AND state = 'Done'
            """, String.class, request.id())).containsExactlyInAnyOrder("DELETED", "ALREADY_ABSENT");
        DeletionRequest after = reread(request);
        assertThat(after.status()).isEqualTo(DeletionStatus.InProgress);
        assertThat(after.owner()).isNull();
        assertThat(after.leaseUntil()).isNull();
        assertThat(after.attempts()).isEqualTo(1);
        assertThat(after.nextAttemptAt()).isAfter(AuditClock.now().plus(POLL).minusMinutes(1));
        assertThat(after.completedAt()).isNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM deedpoll_deedpoll WHERE id = ?", Integer.class, id))
            .as("the local record is finalisation's to remove").isOne();
        assertThat(claimAndProcess(cleanup)).as("not due again until the poll interval").isEmpty();
    }

    @Test
    @DisplayName("a store that is not mounted is a failed try, retried, then NeedsAttention with the key still held")
    void unmountedStoreIsNotAbsence() throws IOException {
        String id = fixture();
        String key = localDocument(id, UUID.randomUUID().toString(), true);
        DeletionRequest request = admit(id);
        DeletionCleanup unmounted = worker(store(root.resolve("not-mounted")));

        assertThat(claimAndProcess(unmounted)).hasValueSatisfying(made -> {
            assertThat(made.status()).isEqualTo(DeletionStatus.InProgress);
            assertThat(made.externalCleanupDone()).isFalse();
        });
        assertThat(step(request, DeletionStepKind.LOCAL_DOCUMENT)).containsEntry("state", "Pending")
            .containsEntry("outcome", "REMOVAL_FAILED").containsEntry("attempts", 1).containsEntry("locator", key);
        assertThat(claimAndProcess(unmounted)).as("backed off, not due at once").isEmpty();

        due(request);
        assertThat(claimAndProcess(unmounted)).hasValueSatisfying(made ->
            assertThat(made.failure()).isEqualTo(DeletionFailure.LOCAL_DOCUMENT_UNREMOVED));
        DeletionRequest after = reread(request);
        assertThat(after.status()).isEqualTo(DeletionStatus.NeedsAttention);
        assertThat(after.failure()).isEqualTo("LOCAL_DOCUMENT_UNREMOVED");
        assertThat(step(request, DeletionStepKind.LOCAL_DOCUMENT)).containsEntry("locator", key);
        assertThat(documents.resolve(key)).as("nothing was removed").exists();

        due(request);
        assertThat(claimAndProcess(cleanup)).as("NeedsAttention is out of the worker's hands").isEmpty();
    }

    @Test
    @DisplayName("no filesystem store in this deployment needs attention and touches nothing")
    void noFilesystemStore() throws IOException {
        String id = fixture();
        String key = localDocument(id, UUID.randomUUID().toString(), true);
        DeletionRequest request = admit(id);

        assertThat(claimAndProcess(worker(store(null)))).hasValueSatisfying(made ->
            assertThat(made.failure()).isEqualTo(DeletionFailure.LOCAL_STORE_UNAVAILABLE));
        assertThat(reread(request).status()).isEqualTo(DeletionStatus.NeedsAttention);
        assertThat(step(request, DeletionStepKind.LOCAL_DOCUMENT)).containsEntry("state", "Pending")
            .containsEntry("outcome", "STORE_UNAVAILABLE");
        assertThat(documents.resolve(key)).exists();
    }

    @Test
    @DisplayName("a key that escapes the store's root fails its line and removes nothing outside it")
    void invalidKey() throws IOException {
        String id = fixture();
        final Path outside = Files.writeString(root.resolve("outside.txt"), "not the service's");
        localDocument(id, "../outside.txt", false);
        DeletionRequest request = admit(id);

        assertThat(claimAndProcess(cleanup)).hasValueSatisfying(made ->
            assertThat(made.failure()).isEqualTo(DeletionFailure.LOCAL_DOCUMENT_INVALID_KEY));
        assertThat(step(request, DeletionStepKind.LOCAL_DOCUMENT)).containsEntry("state", "Failed")
            .containsEntry("outcome", "INVALID_STORAGE_KEY");
        assertThat(outside).exists();
    }

    @Test
    @DisplayName("two workers claiming at once: exactly one holds the request")
    void twoWorkers() throws Exception {
        String id = fixture();
        DeletionRequest request = admit(id);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<Optional<UUID>>> claims = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                claims.add(pool.submit(() -> {
                    start.await();
                    return cleanup.claim(UUID.randomUUID());
                }));
            }
            start.countDown();
            List<Optional<UUID>> results = new ArrayList<>();
            for (Future<Optional<UUID>> claim : claims) {
                results.add(claim.get(20, TimeUnit.SECONDS));
            }
            assertThat(results).filteredOn(Optional::isPresent).singleElement()
                .isEqualTo(Optional.of(request.id()));
        } finally {
            pool.shutdownNow();
        }
        assertThat(reread(request).attempts()).isEqualTo(1);
    }

    @Test
    @DisplayName("a lapsed lease is taken over; the first worker then writes and removes nothing")
    void leaseLostAndTakenOver() throws IOException {
        String id = fixture();
        final String key = localDocument(id, UUID.randomUUID().toString(), true);
        DeletionRequest request = admit(id);
        UUID first = UUID.randomUUID();
        assertThat(cleanup.claim(first)).contains(request.id());
        assertThat(cleanup.claim(UUID.randomUUID())).as("held while the lease runs").isEmpty();

        leaseLapsed(request);
        UUID second = UUID.randomUUID();
        assertThat(cleanup.claim(second)).contains(request.id());

        assertThat(cleanup.process(request.id(), first)).isEmpty();
        assertThat(documents.resolve(key)).exists();
        assertThat(step(request, DeletionStepKind.LOCAL_DOCUMENT)).containsEntry("state", "Pending")
            .containsEntry("attempts", 0);
        assertThat(reread(request).owner()).isEqualTo(second);

        assertThat(cleanup.process(request.id(), second)).hasValueSatisfying(made ->
            assertThat(made.externalCleanupDone()).isTrue());
        assertThat(documents.resolve(key)).doesNotExist();
        assertThat(reread(request).attempts()).isEqualTo(2);
    }

    @Test
    @DisplayName("a worker that died after removing a file but before recording it: the next one verifies it absent")
    void restartAfterRemoval() throws IOException {
        String id = fixture();
        String key = localDocument(id, UUID.randomUUID().toString(), true);
        DeletionRequest request = admit(id);
        assertThat(cleanup.claim(UUID.randomUUID())).contains(request.id());
        Files.delete(documents.resolve(key));
        leaseLapsed(request);

        assertThat(claimAndProcess(cleanup)).hasValueSatisfying(made ->
            assertThat(made.externalCleanupDone()).isTrue());
        assertThat(step(request, DeletionStepKind.LOCAL_DOCUMENT)).containsEntry("state", "Done")
            .containsEntry("outcome", "ALREADY_ABSENT");
    }

    @Test
    @DisplayName("a line already done is never redone: a later file under the same key is left alone")
    void doneIsDone() throws IOException {
        String id = fixture();
        String key = localDocument(id, UUID.randomUUID().toString(), true);
        DeletionRequest request = admit(id);
        claimAndProcess(cleanup);
        Files.writeString(documents.resolve(key), "written later by something else");

        due(request);
        assertThat(claimAndProcess(cleanup)).isPresent();
        assertThat(documents.resolve(key)).exists();
        assertThat(step(request, DeletionStepKind.LOCAL_DOCUMENT)).containsEntry("outcome", "DELETED");
    }

    @Test
    @DisplayName("a linked case: files first, then offered to Retain and Dispose, AwaitingDisposal, then disposed")
    void linkedCase() throws IOException {
        String id = fixture();
        long reference = link(id);
        String key = localDocument(id, UUID.randomUUID().toString(), true);
        final DeletionRequest request = admit(id);
        assertThat(policy.findCandidatesForDisposal()).as("not before its files are removed")
            .doesNotContain(reference);

        assertThat(claimAndProcess(cleanup)).hasValueSatisfying(made -> {
            assertThat(made.status()).isEqualTo(DeletionStatus.InProgress);
            assertThat(made.externalCleanupDone()).isFalse();
        });
        assertThat(documents.resolve(key)).doesNotExist();
        assertThat(policy.findCandidatesForDisposal()).contains(reference);

        jdbc.update("""
            UPDATE ccd.case_data SET state = 'PendingDisposal', resolved_ttl = current_date WHERE reference = ?
            """, reference);
        due(request);
        assertThat(claimAndProcess(cleanup)).hasValueSatisfying(made ->
            assertThat(made.status()).isEqualTo(DeletionStatus.AwaitingDisposal));
        assertThat(reread(request).status()).isEqualTo(DeletionStatus.AwaitingDisposal);

        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            policy.dispose(reference);
            jdbc.update("DELETE FROM ccd.case_data WHERE reference = ?", reference);
        });
        assertThat(step(request, DeletionStepKind.CCD_DISPOSAL)).containsEntry("state", "Done")
            .containsEntry("outcome", "DISPOSED_BY_CCD");
        assertThat(reread(request).status()).isEqualTo(DeletionStatus.InProgress);
        assertThat(policy.findCandidatesForDisposal()).doesNotContain(reference);
        assertThat(claimAndProcess(cleanup)).as("due at once for finalisation").hasValueSatisfying(made ->
            assertThat(made.externalCleanupDone()).isTrue());
    }

    @Test
    @DisplayName("a linked case with a file it could not remove is not offered to CCD, and a disposal event is refused")
    void linkedFileStuck() throws IOException {
        String id = fixture();
        final long reference = link(id);
        localDocument(id, UUID.randomUUID().toString(), true);
        DeletionRequest request = admit(id);

        assertThat(claimAndProcess(worker(store(root.resolve("not-mounted"))))).isPresent();

        assertThat(reread(request).status()).isEqualTo(DeletionStatus.InProgress);
        assertThat(policy.findCandidatesForDisposal()).doesNotContain(reference);
        Boolean ready = new TransactionTemplate(transactions).execute(status ->
            work.lockReadyForCcdDisposal(String.valueOf(reference)));
        Integer recorded = new TransactionTemplate(transactions).execute(status ->
            work.recordDisposed(String.valueOf(reference), AuditClock.now()));
        assertThat(ready).isFalse();
        assertThat(recorded).isZero();
    }

    @Test
    @DisplayName("dispose for a case no request is waiting on rolls the SDK's transaction back")
    void disposeRefusesAStranger() {
        String id = fixture();
        long reference = link(id);

        assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
            jdbc.update("DELETE FROM ccd.case_data WHERE reference = ?", reference);
            policy.dispose(reference);
        })).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ccd.case_data WHERE reference = ?", Integer.class,
                                       reference)).as("the SDK's local copy is kept").isOne();
        assertThatThrownBy(() -> policy.dispose(reference)).as("only inside the SDK's transaction")
            .isInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("a linked case whose local CCD copy vanished without a disposal is never read as disposed")
    void ccdCopyMissing() {
        String id = fixture();
        long reference = link(id);
        DeletionRequest request = admit(id);
        jdbc.update("DELETE FROM ccd.case_data WHERE reference = ?", reference);

        assertThat(claimAndProcess(cleanup)).hasValueSatisfying(made ->
            assertThat(made.failure()).isEqualTo(DeletionFailure.CCD_CASE_MISSING));
        assertThat(step(request, DeletionStepKind.CCD_DISPOSAL)).containsEntry("state", "Pending");
    }

    @Test
    @DisplayName("a run is bounded by its batch, refuses a caller transaction, and the timer is off by default")
    void runBoundsAndSwitch() {
        String first = fixture();
        String second = fixture();
        admit(first);
        admit(second);
        DeletionCleanup one = new DeletionCleanup(work, requests, store(documents), transactions,
            new DeletionCleanupProperties(false, Duration.ofMinutes(5), 1, Duration.ofMinutes(5), MAX_ATTEMPTS,
                                          Duration.ofMinutes(1), POLL), AuditClock::now);

        assertThat(one.run()).isOne();
        assertThat(one.run()).isOne();
        assertThat(one.run()).isZero();
        assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> one.run()))
            .isInstanceOf(IllegalStateException.class);
        assertThat(context.getBeanNamesForType(DeletionCleanupTimer.class)).isEmpty();
    }

    @Test
    @DisplayName("with the worker off a request stays Pending, fenced and stored")
    void disabledLeavesItPending() throws IOException {
        String id = fixture();
        String key = localDocument(id, UUID.randomUUID().toString(), true);
        DeletionRequest request = admit(id);

        assertThat(reread(request).status()).isEqualTo(DeletionStatus.Pending);
        assertThat(documents.resolve(key)).exists();
        assertThat(step(request, DeletionStepKind.LOCAL_DOCUMENT)).containsEntry("locator", key);
    }
}
