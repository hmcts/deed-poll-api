package uk.gov.hmcts.reform.deedpoll.domain.deletion;

/**
 * The outcome code an inventory line keeps ({@code deedpoll_deletion_step.outcome}): how its removal was verified,
 * or why the last try did not verify it. Allowlisted, as {@link DeletionFailure} is.
 */
public enum DeletionStepOutcome {
    /** This worker removed the file and then found it absent. */
    DELETED,
    /**
     * The file was already absent from a store that was available. The inventory proves the service wrote it under
     * this key, so absence is the deletion's own earlier, unacknowledged try: the store is the service's own.
     */
    ALREADY_ABSENT,
    /** Retain and Dispose removed it: data-store answered 404 for the case, and the SDK called the policy. */
    DISPOSED_BY_CCD,
    /** The last try could not remove the file or verify its absence; another try follows. */
    REMOVAL_FAILED,
    /** No filesystem store is configured in this deployment. */
    STORE_UNAVAILABLE,
    /** The storage key resolves outside the store's root. */
    INVALID_STORAGE_KEY
}
