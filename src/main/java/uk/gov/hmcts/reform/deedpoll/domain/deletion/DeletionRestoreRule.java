package uk.gov.hmcts.reform.deedpoll.domain.deletion;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * Whether a deletion request may still be restored, stated once and with no database in it (addendum
 * 20261119-deletion-grace-period-and-restore).
 *
 * <p>A request is restorable while nothing has been removed: it is {@link DeletionStatus#Pending}, nobody has claimed
 * it, it has never been attempted, and its grace period has not ended. The first three together say the worker has
 * not started; any of them failing is {@link DeletionRefusal#CLEANUP_STARTED}, checked first because it is the more
 * specific answer once the period has also passed. Then a period that has ended is
 * {@link DeletionRefusal#RESTORE_WINDOW_CLOSED}: the worker claims nothing before {@code restorable_until} and a
 * restore takes nothing from it, so the two never overlap.
 *
 * <p>{@code DeletionRequestRepository.deleteRestorable} repeats the same conditions in its SQL, so a stale read
 * cannot slip past them.
 */
public final class DeletionRestoreRule {

    private DeletionRestoreRule() {
    }

    /**
     * Why {@code request} may not be restored at {@code now}, or empty if it may.
     */
    public static Optional<DeletionRefusal> refusal(DeletionRequest request, LocalDateTime now) {
        if (request.status() != DeletionStatus.Pending || request.owner() != null || request.attempts() > 0) {
            return Optional.of(DeletionRefusal.CLEANUP_STARTED);
        }
        if (!now.isBefore(request.restorableUntil())) {
            return Optional.of(DeletionRefusal.RESTORE_WINDOW_CLOSED);
        }
        return Optional.empty();
    }
}
