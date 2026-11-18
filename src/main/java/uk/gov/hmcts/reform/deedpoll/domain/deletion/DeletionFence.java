package uk.gov.hmcts.reform.deedpoll.domain.deletion;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import uk.gov.hmcts.reform.deedpoll.domain.NotFoundError;
import uk.gov.hmcts.reform.deedpoll.repository.DeletionFenceRepository;

/**
 * The deletion fence for a write (plan 20261118-dm-application-deletion, Task 4): once a deletion request names a
 * case, no writer and no background job may use it again, and it reads as an unknown id.
 *
 * <p><strong>Lock, then look.</strong> {@link #admits} takes the case's {@code FOR KEY SHARE} lock in one statement and
 * reads the request in the next. Admission holds the case {@code FOR UPDATE} until it commits, so either the request
 * had committed before the lock was granted, and the second statement's fresh snapshot sees it, or admission starts
 * afterwards and waits for this transaction, and then reads what it wrote (an unsettled send or an upload in flight
 * is then a refusal of its own). A check made in the statement that waited, or before the lock, can miss it: that is
 * the race Hibernate's entity restriction ({@code DeletionFenceSql}) does not close by itself, and V49's deferred
 * triggers are the backstop for any writer that forgets this.
 *
 * <p>Both methods join the caller's transaction and refuse to run without one, because the lock is worth nothing once
 * the statement that took it has committed.
 */
@Component
public class DeletionFence {

    /** The same words an unknown case gets, so a fenced case discloses nothing. */
    public static final String NOT_FOUND = "DeedPoll not found";

    private final DeletionFenceRepository fence;

    public DeletionFence(DeletionFenceRepository fence) {
        this.fence = fence;
    }

    /**
     * Whether the case exists and no deletion request names it, holding the case's lock until the caller commits.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean admits(String caseId) {
        return caseId != null && fence.shareCase(caseId) && !fence.hasRequest(caseId);
    }

    /**
     * As {@link #admits}, refusing a fenced or unknown case with a 404 in an unknown case's words.
     *
     * @throws NotFoundError when the case is gone or being deleted
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void require(String caseId) {
        if (!admits(caseId)) {
            throw new NotFoundError(NOT_FOUND);
        }
    }
}
