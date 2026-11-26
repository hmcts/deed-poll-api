package uk.gov.hmcts.reform.deedpoll.domain.documents.deletion;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import uk.gov.hmcts.reform.deedpoll.domain.documents.deletion.DocumentDeletionRemote.StepOutcome;
import uk.gov.hmcts.reform.deedpoll.entity.AuditClock;
import uk.gov.hmcts.reform.deedpoll.repository.DocumentDeletionRequestRepository;
import uk.gov.hmcts.reform.deedpoll.repository.DocumentDeletionRequestRepository.DocumentRecord;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Carries an admitted document deletion request out: its remote steps, then its local completion (plan
 * 20261125-permanent-document-deletion, Task 4). The staff member's own request calls it straight after admission,
 * within the request budget; {@link DocumentDeletionRecovery} calls it for a request whose owner went away.
 *
 * <p><strong>Claims, leases and short commits, as application deletion's worker does.</strong> A pass claims the
 * request under a fresh owner and a lease longer than the budget. Every write after that commits on its own, and a
 * try, a failure or a release is written only while the claim is still this owner's; a worker whose lease lapsed
 * stops, and whoever holds the request now carries on from its steps. The one exception is a step's receipt, which is
 * recorded whoever holds the claim: it is a fact about the far side, and forgetting it would make the next worker
 * re-send a permanent delete whose second answer, a 404, proves nothing. No transaction is open during a remote call.
 *
 * <p><strong>The budget.</strong> A remote call starts only when what is left of the deadline covers its allowance
 * (and a CCD submission is asked again after its start), so the staff member's answer comes well inside the
 * gateway's 30 s. A step not started for want of time leaves the request {@code InProgress}, due at once, for the
 * sweep.
 *
 * <p><strong>Outcomes.</strong> A receipt moves to the next step. An unknown or transient answer is a try, and the
 * request waits {@code InProgress} for the next one, with a doubling delay; when a step's tries reach
 * {@code max-attempts} it is {@code Failed} and the request {@code NeedsAttention}. A refusal another try will not fix
 * is the same at once. Completion runs only once every step is {@code Done}, in one transaction under the case lock,
 * then the document's, then the request's. If it fails it is rolled back whole and retried, and after
 * {@code max-attempts} passes the request needs attention, {@link DocumentDeletionFailure#LOCAL_COMPLETION_REFUSED}.
 * Nothing here ever reports {@code Completed} that {@link DocumentDeletionCompletion} did not write.
 */
@Slf4j
@Component
@EnableConfigurationProperties(DocumentDeletionProperties.class)
public class DocumentDeletionWork {

    private final DocumentDeletionRequestRepository requests;
    private final DocumentDeletionRemote remote;
    private final DocumentDeletionCompletion completion;
    private final TransactionTemplate transactions;
    private final DocumentDeletionProperties properties;
    private final Supplier<LocalDateTime> clock;

    @Autowired
    public DocumentDeletionWork(DocumentDeletionRequestRepository requests, DocumentDeletionRemote remote,
                                DocumentDeletionCompletion completion, PlatformTransactionManager transactions,
                                DocumentDeletionProperties properties) {
        this(requests, remote, completion, transactions, properties, AuditClock::now);
    }

    DocumentDeletionWork(DocumentDeletionRequestRepository requests, DocumentDeletionRemote remote,
                         DocumentDeletionCompletion completion, PlatformTransactionManager transactions,
                         DocumentDeletionProperties properties, Supplier<LocalDateTime> clock) {
        this.requests = requests;
        this.remote = remote;
        this.completion = completion;
        this.transactions = new TransactionTemplate(transactions);
        this.properties = properties;
        this.clock = clock;
    }

    /** The point after which no remote call starts: now plus the request budget. */
    public LocalDateTime deadline() {
        return clock.get().plus(properties.requestBudget());
    }

    /** A claim that is no longer this worker's: it stops, and whoever holds the request now carries on. */
    static final class LeaseLost extends RuntimeException {
        private static final long serialVersionUID = 1L;

        LeaseLost() {
            super("The document deletion request's claim is no longer this worker's", null, false, false);
        }
    }

    /**
     * Claims the request, if it is {@code InProgress} and unclaimed (or its claim lapsed), and carries it out until
     * the deadline. A request somebody else holds is left to them.
     *
     * @return the status the request has afterwards, as read; empty if it is gone
     * @throws IllegalStateException if called inside a transaction, where its commits would be the caller's
     */
    public Optional<DocumentDeletionStatus> carryOut(UUID requestId, LocalDateTime deadline) {
        requireNoTransaction();
        UUID owner = UUID.randomUUID();
        LocalDateTime now = clock.get();
        Boolean claimed = transactions.execute(
            status -> requests.claim(requestId, owner, now, now.plus(properties.recovery().lease())));
        if (Boolean.TRUE.equals(claimed)) {
            process(requestId, owner, deadline);
        }
        return statusOf(requestId);
    }

    /**
     * The sweep's claim: the soonest due request, in a transaction of its own.
     *
     * @return the owner and the request it now holds, or empty when nothing is due
     */
    Optional<UUID> claimNext(UUID owner) {
        requireNoTransaction();
        LocalDateTime now = clock.get();
        return transactions.execute(
            status -> requests.claimNext(owner, now, now.plus(properties.recovery().lease())));
    }

    /**
     * Works one claimed request: each step still {@code Pending}, in order, then its completion; and gives the claim
     * up. Never throws but for a programming fault: a lost lease stops it quietly.
     */
    void process(UUID requestId, UUID owner, LocalDateTime deadline) {
        try {
            for (DocumentDeletionStep step : requests.stepsOf(requestId)) {
                if (step.state() == DocumentDeletionStepState.Done) {
                    continue;
                }
                if (step.state() == DocumentDeletionStepState.Failed) {
                    // Unreachable: a failed step leaves the request NeedsAttention, which no claim holds.
                    needsAttention(requestId, owner, exhausted(step.kind()));
                    return;
                }
                if (!runStep(requestId, owner, step, deadline)) {
                    return;
                }
            }
            complete(requestId, owner);
        } catch (LeaseLost lost) {
            log.info("Document deletion request {} was taken over by another worker; stopping", requestId);
        }
    }

    /**
     * One step: the budget, the claim, the call, and its outcome recorded.
     *
     * @return whether to go on to the next step
     */
    private boolean runStep(UUID requestId, UUID owner, DocumentDeletionStep step, LocalDateTime deadline) {
        Duration allowance = step.kind() == DocumentDeletionStepKind.CCD_REFERENCE
            ? properties.ccdCallAllowance() : properties.cdamCallAllowance();
        if (!covers(deadline, allowance)) {
            release(requestId, owner, DocumentDeletionStatus.InProgress, null, now());
            return false;
        }
        // Asked before each call, so a worker that has lost the request does not keep deleting for it.
        owned(requestId, owner, () -> 0);
        StepOutcome outcome = call(requestId, step, deadline);
        switch (outcome.verdict()) {
            case DONE -> {
                transactions.executeWithoutResult(status -> requests.stepDone(step.id(), outcome.code(), now()));
                return true;
            }
            case NOT_SENT -> {
                release(requestId, owner, DocumentDeletionStatus.InProgress, null, now());
                return false;
            }
            case FAILED -> {
                owned(requestId, owner, () -> requests.stepFailed(step.id(), outcome.code(), true));
                needsAttention(requestId, owner, outcome.failure());
                return false;
            }
            case RETRY -> {
                int tries = step.attempts() + 1;
                owned(requestId, owner, () -> requests.stepTried(step.id(), outcome.code()));
                if (tries >= properties.recovery().maxAttempts()) {
                    owned(requestId, owner, () -> requests.stepFailed(step.id(), outcome.code(), false));
                    needsAttention(requestId, owner, exhausted(step.kind()));
                } else {
                    log.warn("Document deletion request {}: step {} unconfirmed ({}); it will be tried again",
                             requestId, step.kind(), outcome.code());
                    release(requestId, owner, DocumentDeletionStatus.InProgress, null,
                            now().plus(properties.retryDelay(tries)));
                }
                return false;
            }
            default -> throw new IllegalStateException("Unknown verdict " + outcome.verdict());
        }
    }

    private StepOutcome call(UUID requestId, DocumentDeletionStep step, LocalDateTime deadline) {
        try {
            return switch (step.kind()) {
                case CCD_REFERENCE -> {
                    DocumentDeletionRequest request = requests.findById(requestId).orElseThrow(LeaseLost::new);
                    yield remote.removeCcdReference(request.caseId(), request.documentId(), step.locator(),
                                                    () -> covers(deadline, properties.ccdCallAllowance()));
                }
                case CDAM_BINARY -> remote.deleteCdamPermanently(UUID.fromString(step.locator()));
                case LOCAL_FILE -> remote.removeLocalFile(step.locator());
            };
        } catch (LeaseLost lost) {
            throw lost;
        } catch (RuntimeException unexpected) {
            // A fault short of an answer: a try, never a receipt.
            log.warn("Document deletion request {}: step {} failed unexpectedly ({})", requestId, step.kind(),
                     unexpected.getClass().getName());
            return StepOutcome.retry("UNEXPECTED");
        }
    }

    /**
     * Every step is {@code Done}: the local completion, in one transaction under the case, document, request.
     */
    private void complete(UUID requestId, UUID owner) {
        DocumentDeletionRequest request = requests.findById(requestId).orElseThrow(LeaseLost::new);
        try {
            transactions.executeWithoutResult(status -> {
                if (!requests.lockCase(request.caseId())) {
                    throw new LeaseLost();
                }
                Optional<DocumentRecord> document = requests.lockDocumentRecord(request.caseId(),
                                                                                request.documentId());
                if (!requests.holds(requestId, owner, now())) {
                    throw new LeaseLost();
                }
                DocumentDeletionRequest held = requests.findByCaseAndId(request.caseId(), requestId)
                    .orElseThrow(LeaseLost::new);
                completion.complete(held, document.orElseThrow(
                    () -> new DocumentDeletionCompletion.Refused("The document to delete was not there")), now());
            });
            log.info("Document deletion request {} completed", requestId);
        } catch (LeaseLost lost) {
            throw lost;
        } catch (RuntimeException failed) {
            log.warn("Document deletion request {} could not be completed locally ({})", requestId,
                     failed.getClass().getName());
            int passes = requests.findById(requestId).orElseThrow(LeaseLost::new).attempts();
            if (passes >= properties.recovery().maxAttempts()) {
                needsAttention(requestId, owner, DocumentDeletionFailure.LOCAL_COMPLETION_REFUSED);
            } else {
                release(requestId, owner, DocumentDeletionStatus.InProgress, null,
                        now().plus(properties.retryDelay(passes)));
            }
        }
    }

    private void needsAttention(UUID requestId, UUID owner, DocumentDeletionFailure failure) {
        log.warn("Document deletion request {} needs attention: {}", requestId, failure);
        release(requestId, owner, DocumentDeletionStatus.NeedsAttention, failure.name(), now());
    }

    private void release(UUID requestId, UUID owner, DocumentDeletionStatus status, String failure,
                         LocalDateTime nextAttemptAt) {
        owned(requestId, owner, () -> requests.release(requestId, owner, status, failure, nextAttemptAt) ? 1 : 0);
    }

    private static DocumentDeletionFailure exhausted(DocumentDeletionStepKind kind) {
        return switch (kind) {
            case CCD_REFERENCE -> DocumentDeletionFailure.CCD_REFERENCE_FAILED;
            case CDAM_BINARY -> DocumentDeletionFailure.CDAM_DELETE_FAILED;
            case LOCAL_FILE -> DocumentDeletionFailure.LOCAL_FILE_UNREMOVED;
        };
    }

    private boolean covers(LocalDateTime deadline, Duration allowance) {
        return !now().plus(allowance).isAfter(deadline);
    }

    private Optional<DocumentDeletionStatus> statusOf(UUID requestId) {
        return requests.findById(requestId).map(DocumentDeletionRequest::status);
    }

    private LocalDateTime now() {
        return clock.get();
    }

    /**
     * Runs {@code body} in a transaction of its own while the claim is still {@code owner}'s, locking the request.
     */
    private <T> T owned(UUID requestId, UUID owner, Supplier<T> body) {
        return transactions.execute(status -> {
            if (!requests.holds(requestId, owner, now())) {
                throw new LeaseLost();
            }
            return body.get();
        });
    }

    private static void requireNoTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("A document deletion must be carried out outside any caller transaction");
        }
    }
}
