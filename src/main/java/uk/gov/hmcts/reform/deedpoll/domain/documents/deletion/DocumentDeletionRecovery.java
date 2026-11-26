package uk.gov.hmcts.reform.deedpoll.domain.documents.deletion;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Optional;
import java.util.UUID;

/**
 * The recovery sweep (plan 20261125-permanent-document-deletion, Task 4): resumes {@code InProgress} document deletion
 * requests whose staff request ran out of budget, met an unknown answer or went away, through the same
 * {@link DocumentDeletionWork} the request uses. It starts no new deletion: only an admitted request is ever claimed.
 *
 * <p>Bounded ({@code recovery.batch-size} per run, each within the request budget) and multi-pod safe: a claim is
 * {@code FOR UPDATE SKIP LOCKED} under a fresh owner with a lease, on the request row only. Independent of application
 * deletion's worker and its switch. With the timer disabled, a request stays {@code InProgress}, never
 * {@code Completed}.
 */
@Slf4j
@Component
public class DocumentDeletionRecovery {

    private final DocumentDeletionWork work;
    private final DocumentDeletionProperties properties;

    public DocumentDeletionRecovery(DocumentDeletionWork work, DocumentDeletionProperties properties) {
        this.work = work;
        this.properties = properties;
    }

    /**
     * One run. Never throws: a failure is one WARN naming the exception class only, and the request is due again when
     * its lease lapses.
     *
     * @return how many requests were claimed
     * @throws IllegalStateException if called inside a transaction, where its commits would be the caller's
     */
    public int run() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("The document deletion sweep must run outside any caller transaction");
        }
        int claimed = 0;
        try {
            while (claimed < properties.recovery().batchSize()) {
                UUID owner = UUID.randomUUID();
                Optional<UUID> request = work.claimNext(owner);
                if (request.isEmpty()) {
                    break;
                }
                claimed++;
                work.process(request.get(), owner, work.deadline());
            }
        } catch (RuntimeException failure) {
            log.warn("Document deletion sweep failed; the request is due again when its lease lapses ({})",
                     failure.getClass().getName());
        }
        return claimed;
    }
}
