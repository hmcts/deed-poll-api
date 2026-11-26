package uk.gov.hmcts.reform.deedpoll.domain.documents.deletion;

import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import uk.gov.hmcts.reform.deedpoll.domain.auth.CurrentUser;
import uk.gov.hmcts.reform.deedpoll.domain.documents.deletion.DocumentDeletionService.Admission;
import uk.gov.hmcts.reform.deedpoll.domain.documents.deletion.DocumentDeletionService.DocumentDeletionCommand;
import uk.gov.hmcts.reform.deedpoll.repository.DocumentDeletionRequestRepository;

import java.time.LocalDateTime;

/**
 * A member of staff's permanent document deletion, while they wait (plan 20261125-permanent-document-deletion,
 * Task 4): admission in its own committed transaction, then the remote steps and the local completion in the same
 * HTTP request, inside the request budget.
 *
 * <p>The answer is the request as it then stands. {@link DocumentDeletionStatus#Completed} means every step is
 * confirmed and the audit event and case note are written; {@code InProgress} means a step is unconfirmed, out of
 * budget or held by another worker, and the recovery sweep finishes it; {@code NeedsAttention} needs a person. A
 * replay of the same key and body carries the same request on if nobody holds it, and otherwise answers it as it is:
 * it never creates a second request, completion or note.
 *
 * <p>Must be called outside any transaction: admission has to commit, fencing the document, before anything remote
 * is called, and every later write commits on its own.
 */
@Service
public class DocumentDeletions {

    private final DocumentDeletionService admission;
    private final DocumentDeletionWork work;
    private final DocumentDeletionRequestRepository requests;

    public DocumentDeletions(DocumentDeletionService admission, DocumentDeletionWork work,
                             DocumentDeletionRequestRepository requests) {
        this.admission = admission;
        this.work = work;
        this.requests = requests;
    }

    /**
     * What a deletion answered.
     *
     * @param request  the request as it stands after this call's work
     * @param admitted whether this call wrote the request; false for a replay
     */
    public record Outcome(DocumentDeletionRequest request, boolean admitted) {
    }

    /**
     * Admits the deletion (or finds the request this key or body already has) and carries it as far as the budget
     * allows.
     *
     * @throws IllegalStateException if a transaction is active
     * @see DocumentDeletionService#request for every refusal, each of which writes nothing
     */
    public Outcome delete(CurrentUser caller, String caseId, String documentId, DocumentDeletionCommand command) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("A document deletion must not be called inside a transaction");
        }
        LocalDateTime deadline = work.deadline();
        Admission admitted = admission.request(caller, caseId, documentId, command);
        DocumentDeletionRequest request = admitted.request();
        if (request.status() == DocumentDeletionStatus.InProgress) {
            work.carryOut(request.id(), deadline);
            request = requests.findByCaseAndId(request.caseId(), request.id()).orElse(request);
        }
        return new Outcome(request, admitted.admitted());
    }
}
