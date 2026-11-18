package uk.gov.hmcts.reform.deedpoll.domain.deletion;

import uk.gov.hmcts.reform.deedpoll.repository.DeletionWorkRepository.CcdCaseState;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Where a deletion request has got to, decided from its inventory and the SDK's local copy of its CCD case, with no
 * database in it (plan 20261118-dm-application-deletion, Task 5).
 *
 * <p>The SDK's Retain and Dispose task records no outcome, so the status is derived from what can be read: a linked
 * request is {@link DeletionStatus#AwaitingDisposal} once its case is marked and its TTL confirmed, and only the
 * policy's {@code dispose} -- which runs after data-store has answered 404 -- records the CCD step done. Nothing here
 * ever answers {@link DeletionStatus#Completed}: that is finalisation's alone, and only after every external step.
 */
public final class DeletionProgress {

    /** The tries a filesystem document gets before the request needs somebody. */
    private final int maxAttempts;
    /** The first retry's delay, doubled for each further try. */
    private final Duration retryBase;
    /** How often a request that is waiting, on CCD or on finalisation, is looked at again; also the retry cap. */
    private final Duration pollInterval;

    public DeletionProgress(int maxAttempts, Duration retryBase, Duration pollInterval) {
        this.maxAttempts = maxAttempts;
        this.retryBase = retryBase;
        this.pollInterval = pollInterval;
    }

    /**
     * What one pass decided.
     *
     * @param failure               the allowlisted code with {@link DeletionStatus#NeedsAttention}, else null
     * @param delay                 how long until the request is due again
     * @param externalCleanupDone   every external and binary step is verified, so only local finalisation is left
     */
    public record Decision(DeletionStatus status, DeletionFailure failure, Duration delay,
                           boolean externalCleanupDone) {
    }

    /**
     * Decides the request's status.
     *
     * @param steps   the inventory as it stands after this pass's removals
     * @param ccdCase the SDK's local copy of the linked case, read after them; empty if it has none
     * @param found   a permanent problem this pass found, if any
     */
    public Decision decide(List<DeletionStep> steps, Optional<CcdCaseState> ccdCase, Optional<DeletionFailure> found) {
        if (found.isPresent()) {
            return attention(found.get());
        }
        if (steps.stream().anyMatch(step -> step.state() == DeletionStepState.Failed)) {
            return attention(DeletionFailure.CLEANUP_STEP_FAILED);
        }
        List<DeletionStep> localLeft = pending(steps, DeletionStepKind.LOCAL_DOCUMENT);
        if (!localLeft.isEmpty()) {
            int tries = localLeft.stream().mapToInt(DeletionStep::attempts).max().orElse(0);
            if (tries >= maxAttempts) {
                return attention(DeletionFailure.LOCAL_DOCUMENT_UNREMOVED);
            }
            return new Decision(DeletionStatus.InProgress, null, backoff(tries), false);
        }
        boolean ccdLeft = !pending(steps, DeletionStepKind.CCD_DISPOSAL).isEmpty();
        if (!ccdLeft && !pending(steps, DeletionStepKind.CDAM_DOCUMENT).isEmpty()) {
            return attention(DeletionFailure.CDAM_WITHOUT_CCD_CASE);
        }
        if (ccdLeft) {
            if (ccdCase.isEmpty()) {
                return attention(DeletionFailure.CCD_CASE_MISSING);
            }
            DeletionStatus status = ccdCase.get().awaitingDisposal()
                ? DeletionStatus.AwaitingDisposal : DeletionStatus.InProgress;
            return new Decision(status, null, pollInterval, false);
        }
        return new Decision(DeletionStatus.InProgress, null, pollInterval, true);
    }

    /**
     * The delay after {@code tries} failed tries: {@code retryBase} doubled per try, capped at the poll interval.
     */
    Duration backoff(int tries) {
        Duration delay = retryBase;
        for (int i = 1; i < tries && delay.compareTo(pollInterval) < 0; i++) {
            delay = delay.multipliedBy(2);
        }
        return delay.compareTo(pollInterval) > 0 ? pollInterval : delay;
    }

    private Decision attention(DeletionFailure failure) {
        return new Decision(DeletionStatus.NeedsAttention, failure, pollInterval, false);
    }

    private static List<DeletionStep> pending(List<DeletionStep> steps, DeletionStepKind kind) {
        return steps.stream()
            .filter(step -> step.kind() == kind && step.state() == DeletionStepState.Pending)
            .toList();
    }
}
