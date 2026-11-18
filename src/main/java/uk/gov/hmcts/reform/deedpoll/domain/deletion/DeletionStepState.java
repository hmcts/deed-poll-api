package uk.gov.hmcts.reform.deedpoll.domain.deletion;

/**
 * Whether one inventory line's cleanup is verified. Stored by {@link #name()} in {@code deedpoll_deletion_step}.
 */
public enum DeletionStepState {
    /** Not yet verified removed; the locator is still held. */
    Pending,
    /** Verified removed under a proven ownership and deletion contract. */
    Done,
    /** Cleanup failed in a way another attempt will not fix; the outcome code says how. */
    Failed
}
