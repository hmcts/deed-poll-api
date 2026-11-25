package uk.gov.hmcts.reform.deedpoll.domain.deletion;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import uk.gov.hmcts.reform.deedpoll.repository.DeletionFenceRepository;

import java.sql.SQLException;

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
 * <p>{@link #admits} joins the caller's transaction and refuses to run without one, because the lock is worth nothing
 * once the statement that took it has committed.
 */
@Component
public class DeletionFence {

    /** The same words an unknown case gets, so a fenced case discloses nothing. */
    public static final String NOT_FOUND = "DeedPoll not found";

    /**
     * V49's {@code deedpoll_refuse_fenced_write}: its SQLSTATE and its message.
     */
    private static final String TRIGGER_STATE = "P0001";
    private static final String TRIGGER_REFUSAL = "The application is being deleted";

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
     * Whether a failure is V49's deferred trigger refusing a fenced case's write at commit: a writer that did not ask
     * {@link #admits} and raced admission. Recognised by the trigger's SQLSTATE and its words anywhere in the cause
     * chain, because the persistence layer wraps it differently on the JPA and JDBC paths.
     */
    public static boolean refusedByTrigger(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause() == cause ? null : cause.getCause()) {
            if (cause instanceof SQLException sql && TRIGGER_STATE.equals(sql.getSQLState())
                && sql.getMessage() != null && sql.getMessage().contains(TRIGGER_REFUSAL)) {
                return true;
            }
        }
        return false;
    }
}
