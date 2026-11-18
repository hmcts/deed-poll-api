package uk.gov.hmcts.reform.deedpoll.domain.deletion;

/**
 * Where a deletion request has got to. Stored by {@link #name()} in {@code deedpoll_deletion_request.status}.
 *
 * <p>Only {@link #Completed} means the application is gone. Every other status is a request that is still being
 * carried out, or one that needs somebody to look at it, and no response may call it deleted.
 */
public enum DeletionStatus {
    /** Admitted and fenced; no cleanup has started. */
    Pending,
    /** Cleanup has started: local binaries, or the CCD case being marked for disposal. */
    InProgress,
    /** A linked case's TTL is set, and CCD's nightly disposer has not yet removed it. */
    AwaitingDisposal,
    /** Cleanup cannot go on without somebody; the request's failure code says why. */
    NeedsAttention,
    /**
     * Every in-scope copy is removed and {@code CASE_DELETED} is recorded. Terminal.
     */
    Completed
}
