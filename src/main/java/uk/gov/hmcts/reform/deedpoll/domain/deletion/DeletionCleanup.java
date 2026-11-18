package uk.gov.hmcts.reform.deedpoll.domain.deletion;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import uk.gov.hmcts.reform.deedpoll.domain.NotFoundError;
import uk.gov.hmcts.reform.deedpoll.entity.AuditClock;
import uk.gov.hmcts.reform.deedpoll.repository.DeletionRequestRepository;
import uk.gov.hmcts.reform.deedpoll.repository.DeletionWorkRepository;
import uk.gov.hmcts.reform.deedpoll.repository.DeletionWorkRepository.CcdCaseState;
import uk.gov.hmcts.reform.deedpoll.storage.LocalFilesystemDocumentStore;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * The application deletion worker's external and binary cleanup (plan 20261118-dm-application-deletion, Task 5): it
 * removes a request's filesystem documents, and works out from the SDK's local copy where a linked case's CCD disposal
 * has got to. It never calls CCD: Retain and Dispose's own task marks, confirms and reads the case
 * ({@code DeedPollRetainAndDisposePolicy} offers it), and only its {@code dispose} records the CCD step done.
 *
 * <p><strong>Claims, leases and short commits, as the outboxes do.</strong> A run claims at most
 * {@code batch-size} due requests one at a time ({@code FOR UPDATE SKIP LOCKED}, so pods never share one), each under
 * a fresh owner and a lease. Every write after that commits on its own, and only while the claim is still this
 * worker's ({@link DeletionWorkRepository#holds}), so a worker whose lease lapsed writes nothing and the pod that took
 * the request over carries on from the inventory. No transaction is open while a file is removed.
 *
 * <p><strong>Nothing counts as removed that was not verified.</strong> A file is done only when the store's root is
 * there and the file is not ({@link LocalFilesystemDocumentStore#remove}); a missing root, an I/O failure or no
 * filesystem store at all leaves the line {@code Pending} with its storage key. A request whose tries run out, or
 * whose inventory cannot be honoured, is {@link DeletionStatus#NeedsAttention} with an allowlisted
 * {@link DeletionFailure}, and the worker stops claiming it. Nothing here completes a request: finalisation (Task 6)
 * does, after {@link DeletionProgress.Decision#externalCleanupDone}.
 *
 * <p>Ordinary document removal is untouched: a caseworker's delete is still a soft delete in CDAM, and this worker
 * sends CDAM nothing.
 */
@Slf4j
@Component
@EnableConfigurationProperties(DeletionCleanupProperties.class)
public class DeletionCleanup {

    private final DeletionWorkRepository work;
    private final DeletionRequestRepository requests;
    private final ObjectProvider<LocalFilesystemDocumentStore> files;
    private final TransactionTemplate transactions;
    private final DeletionCleanupProperties properties;
    private final DeletionProgress progress;
    private final Supplier<LocalDateTime> clock;

    @Autowired
    public DeletionCleanup(DeletionWorkRepository work, DeletionRequestRepository requests,
                           ObjectProvider<LocalFilesystemDocumentStore> files, PlatformTransactionManager transactions,
                           DeletionCleanupProperties properties) {
        this(work, requests, files, transactions, properties, AuditClock::now);
    }

    DeletionCleanup(DeletionWorkRepository work, DeletionRequestRepository requests,
                    ObjectProvider<LocalFilesystemDocumentStore> files, PlatformTransactionManager transactions,
                    DeletionCleanupProperties properties, Supplier<LocalDateTime> clock) {
        this.work = work;
        this.requests = requests;
        this.files = files;
        this.transactions = new TransactionTemplate(transactions);
        this.properties = properties;
        this.progress = new DeletionProgress(properties.maxAttempts(), properties.retryBase(),
                                             properties.pollInterval());
        this.clock = clock;
    }

    /** A claim that is no longer this worker's: it stops, and whoever holds the request now carries on. */
    static final class LeaseLost extends RuntimeException {
        private static final long serialVersionUID = 1L;

        LeaseLost() {
            super("The deletion request's claim is no longer this worker's", null, false, false);
        }
    }

    /**
     * One run: claims and works through up to {@code batch-size} due requests. Never throws: a failure is one WARN
     * naming the exception class only, and the request is due again when its lease lapses.
     *
     * @return how many requests were claimed
     * @throws IllegalStateException if called inside a transaction, where its commits would be the caller's
     */
    public int run() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("The deletion worker must run outside any caller transaction");
        }
        int claimed = 0;
        try {
            while (claimed < properties.batchSize()) {
                UUID owner = UUID.randomUUID();
                Optional<UUID> request = claim(owner);
                if (request.isEmpty()) {
                    break;
                }
                claimed++;
                process(request.get(), owner);
            }
        } catch (RuntimeException failure) {
            log.warn("Application deletion run failed; the request is due again when its lease lapses ({})",
                     failure.getClass().getName());
        }
        return claimed;
    }

    /**
     * Claims the soonest due request for {@code owner}, in a transaction of its own.
     */
    Optional<UUID> claim(UUID owner) {
        LocalDateTime now = clock.get();
        return transactions.execute(status -> work.claimNext(owner, now, now.plus(properties.lease())));
    }

    /**
     * Works one claimed request: its filesystem documents, then its status, then gives the claim up.
     *
     * @return what was decided, or empty if the claim was lost on the way
     */
    Optional<DeletionProgress.Decision> process(UUID requestId, UUID owner) {
        try {
            Optional<DeletionFailure> found = removeLocalDocuments(requestId, owner);
            return Optional.of(settle(requestId, owner, found));
        } catch (LeaseLost lost) {
            log.info("Application deletion request {} was taken over by another worker; stopping", requestId);
            return Optional.empty();
        }
    }

    private Optional<DeletionFailure> removeLocalDocuments(UUID requestId, UUID owner) {
        List<DeletionStep> local = requests.stepsOf(requestId).stream()
            .filter(step -> step.kind() == DeletionStepKind.LOCAL_DOCUMENT
                && step.state() == DeletionStepState.Pending)
            .toList();
        for (DeletionStep step : local) {
            LocalFilesystemDocumentStore store = files.getIfAvailable();
            if (store == null) {
                owned(requestId, owner, () -> work.stepTried(step.id(), DeletionStepOutcome.STORE_UNAVAILABLE));
                return Optional.of(DeletionFailure.LOCAL_STORE_UNAVAILABLE);
            }
            // Asked before each removal, so a worker that has lost the request does not keep deleting for it. The
            // removal itself is idempotent, so the one that lapses between this and the delete costs nothing.
            owned(requestId, owner, () -> 0);
            DeletionStepOutcome outcome;
            try {
                outcome = store.remove(step.locator()) == LocalFilesystemDocumentStore.Removal.DELETED
                    ? DeletionStepOutcome.DELETED : DeletionStepOutcome.ALREADY_ABSENT;
            } catch (NotFoundError invalidKey) {
                owned(requestId, owner, () -> work.stepFailed(step.id(), DeletionStepOutcome.INVALID_STORAGE_KEY));
                return Optional.of(DeletionFailure.LOCAL_DOCUMENT_INVALID_KEY);
            } catch (RuntimeException failed) {
                log.warn("Application deletion request {} could not remove a stored document; it will try again ({})",
                         requestId, failed.getClass().getName());
                owned(requestId, owner, () -> work.stepTried(step.id(), DeletionStepOutcome.REMOVAL_FAILED));
                continue;
            }
            DeletionStepOutcome verified = outcome;
            owned(requestId, owner, () -> work.stepDone(step.id(), verified, clock.get()));
        }
        return Optional.empty();
    }

    private DeletionProgress.Decision settle(UUID requestId, UUID owner, Optional<DeletionFailure> found) {
        return owned(requestId, owner, () -> {
            DeletionRequest request = requests.findById(requestId).orElseThrow(LeaseLost::new);
            Optional<CcdCaseState> ccdCase = request.ccdCaseReference() == null
                ? Optional.empty() : work.ccdCase(request.ccdCaseReference());
            DeletionProgress.Decision decision = progress.decide(requests.stepsOf(requestId), ccdCase, found);
            work.release(requestId, owner, decision.status(),
                         decision.failure() == null ? null : decision.failure().name(),
                         clock.get().plus(decision.delay()));
            if (decision.status() == DeletionStatus.NeedsAttention) {
                log.warn("Application deletion request {} needs attention: {}", requestId, decision.failure());
            }
            return decision;
        });
    }

    /**
     * Runs {@code body} in a transaction of its own while the claim is still {@code owner}'s, locking the request.
     */
    private <T> T owned(UUID requestId, UUID owner, Supplier<T> body) {
        return transactions.execute(status -> {
            if (!work.holds(requestId, owner, clock.get())) {
                throw new LeaseLost();
            }
            return body.get();
        });
    }
}
