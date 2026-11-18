package uk.gov.hmcts.reform.deedpoll.domain.deletion;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import uk.gov.hmcts.reform.deedpoll.domain.audit.AuditAction;
import uk.gov.hmcts.reform.deedpoll.domain.audit.AuditActor;
import uk.gov.hmcts.reform.deedpoll.domain.audit.AuditEntities;
import uk.gov.hmcts.reform.deedpoll.domain.audit.AuditEntry;
import uk.gov.hmcts.reform.deedpoll.domain.audit.AuditTrail;
import uk.gov.hmcts.reform.deedpoll.repository.DeletionFinalisationRepository;
import uk.gov.hmcts.reform.deedpoll.repository.DeletionRequestRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * The last step of an application deletion (plan 20261118-dm-application-deletion, Task 6): once every external and
 * binary step is verified, it removes what is left of the application locally, disposes of the case's audit values,
 * records {@code CASE_DELETED} under the original Delivery Manager, and turns the request into its tombstone, all in
 * the caller's one transaction.
 *
 * <p><strong>Only inside the worker's owned transaction.</strong> {@link Propagation#MANDATORY}, and the caller holds
 * the request's lock under an unexpired claim ({@code DeletionWorkRepository.holds}), so two workers, an expired lease
 * or a lost acknowledgement cannot complete a request twice: the second finds it {@code Completed}, outside the set a
 * claim can hold. A refusal is a {@link Refused}, which rolls everything here back; the external steps already
 * recorded stay recorded, so the next pass resumes at finalisation without repeating them.
 *
 * <p><strong>What it removes.</strong> The case row by {@code DELETE} (V49 refuses an {@code UPDATE}), so notes,
 * witnesses, documents, referrals, notifications, CCD creation attempts and the deed preparation go by their cascades;
 * the owned stages the inventory lists; and every audit value row for the case through
 * {@code deedpoll_audit_dispose_case_values}. <strong>What it keeps.</strong> Every audit event (the request, the
 * case's history, and this completion), the Master email ledger (ids only, and shared with other cases), the reference
 * counter, and the request itself with its reason, note, actor, timestamps and each line's kind and outcome code.
 * Every locator and item id is erased.
 */
@Component
public class DeletionFinalisation {

    private final DeletionFinalisationRepository finalisation;
    private final DeletionRequestRepository requests;
    private final AuditTrail audit;

    public DeletionFinalisation(DeletionFinalisationRepository finalisation, DeletionRequestRepository requests,
                                AuditTrail audit) {
        this.finalisation = finalisation;
        this.requests = requests;
        this.audit = audit;
    }

    /** Finalisation refused for a reason the request should show; thrown so the caller's transaction rolls back. */
    public static final class Refused extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private final DeletionFailure failure;

        Refused(DeletionFailure failure) {
            super("Application deletion finalisation refused: " + failure, null, false, false);
            this.failure = failure;
        }

        public DeletionFailure failure() {
            return failure;
        }
    }

    /**
     * Finalises the request {@code owner} holds.
     *
     * @throws Refused if a step is not verified done, the SDK still holds the CCD copy, retained bytes or an unlisted
     *     owned stage remain; nothing is written
     * @throws IllegalStateException if the claim is no longer {@code owner}'s, which rolls everything back
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void finalise(DeletionRequest request, UUID owner, LocalDateTime now) {
        List<DeletionStep> steps = requests.stepsOf(request.id());
        boolean externalLeft = steps.stream().anyMatch(step -> step.kind() != DeletionStepKind.OWNED_STAGE
            && step.state() != DeletionStepState.Done);
        if (externalLeft) {
            // DeletionProgress decided otherwise in an earlier transaction; asked again under the lock, never assumed.
            throw new Refused(DeletionFailure.CLEANUP_STEP_FAILED);
        }
        if (request.ccdCaseReference() != null && finalisation.ccdCopyPresent(request.ccdCaseReference())) {
            throw new Refused(DeletionFailure.CCD_COPY_PRESENT);
        }
        String caseId = request.caseId();
        finalisation.lockCase(caseId);
        if (finalisation.hasRetainedPayload(caseId)) {
            throw new Refused(DeletionFailure.RETAINED_PAYLOAD);
        }

        // Only finalisation completes an owned-stage line, in this transaction, so every one is still Pending here.
        for (DeletionStep stage : steps) {
            if (stage.kind() == DeletionStepKind.OWNED_STAGE && stage.state() == DeletionStepState.Pending) {
                int removed = finalisation.deleteOwnedStage(stage.locator());
                finalisation.ownedStageDone(stage.id(), removed == 1
                    ? DeletionStepOutcome.DELETED : DeletionStepOutcome.ALREADY_ABSENT, now);
            }
        }
        if (finalisation.remainingOwnedStages(caseId) > 0) {
            throw new Refused(DeletionFailure.UNLISTED_OWNED_STAGE);
        }

        finalisation.deleteCase(caseId);
        finalisation.disposeAuditValues(caseId);
        audit.record(AuditEntry.of(AuditAction.CASE_DELETED,
                                   new AuditActor(request.requestedBySubject(), request.requestedByName(), false),
                                   AuditEntities.CASE, caseId, caseId)
                         .withDetail(AuditEntry.DELETION_REASON, request.reason().name())
                         .withDetail(AuditEntry.DELETION_REQUEST_ID, request.id().toString()),
                     List.of());
        finalisation.eraseInventory(request.id());
        if (finalisation.complete(request.id(), owner, now) != 1) {
            throw new IllegalStateException("The deletion request's claim was lost during finalisation");
        }
    }
}
