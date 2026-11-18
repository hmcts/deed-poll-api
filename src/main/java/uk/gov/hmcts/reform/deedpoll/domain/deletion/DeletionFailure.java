package uk.gov.hmcts.reform.deedpoll.domain.deletion;

/**
 * Why a deletion request is {@link DeletionStatus#NeedsAttention}: the allowlisted codes the status resource may
 * publish in {@code deedpoll_deletion_request.failure}. Never a provider message, an exception text or a locator.
 *
 * <p>Every code is a request that cleanup cannot finish without somebody, and none of them means anything was
 * deleted: the inventory still holds every locator whose removal is not verified, so the work resumes where it
 * stopped once the cause is fixed.
 */
public enum DeletionFailure {
    /** A filesystem document is in the inventory, and this deployment has no filesystem store to remove it from. */
    LOCAL_STORE_UNAVAILABLE,
    /** A filesystem document's storage key resolves outside the store's root, so nothing was touched. */
    LOCAL_DOCUMENT_INVALID_KEY,
    /** A filesystem document could not be removed, or its absence verified, within the allowed tries. */
    LOCAL_DOCUMENT_UNREMOVED,
    /**
     * The linked case's local {@code ccd.case_data} row is gone, but the CCD step was never recorded as disposed:
     * something other than Retain and Dispose removed it, so CCD's own copy is in an unknown state.
     */
    CCD_CASE_MISSING,
    /** CDAM documents are in the inventory of a case with no CCD case, which an upload cannot produce. */
    CDAM_WITHOUT_CCD_CASE,
    /** An inventory line was recorded as failed for a reason no other code covers. */
    CLEANUP_STEP_FAILED
}
