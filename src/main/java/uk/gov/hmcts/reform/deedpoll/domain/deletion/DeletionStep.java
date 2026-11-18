package uk.gov.hmcts.reform.deedpoll.domain.deletion;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One line of a deletion's cleanup inventory ({@code deedpoll_deletion_step}, V48). The locator stays until the
 * line's removal is verified; the kind and outcome code are what the completed request keeps.
 *
 * @param itemId the local row the line came from (a supporting document or payload id), if any
 * @param locator the storage key, CDAM id, payload id or CCD reference to remove; null only once {@code Done}
 */
public record DeletionStep(UUID id, UUID requestId, DeletionStepKind kind, String itemId, String locator,
                           DeletionStepState state, String outcome, int attempts, LocalDateTime completedAt) {

    /** Never the locator: a storage key or document id in a log is a way back to the bytes. */
    @Override
    public String toString() {
        return "DeletionStep[id=" + id + ", kind=" + kind + ", state=" + state + "]";
    }
}
