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
    /**
     * CCD has disposed of the case, but documents a caseworker had removed while they were in CDAM are still
     * soft-deleted in dm-store, which neither the disposer nor this service removes: the dm-store team has to purge
     * them before the request can complete.
     */
    CDAM_REMOVED_DOCUMENT_UNPURGED,
    /** An inventory line was recorded as failed for a reason no other code covers. */
    CLEANUP_STEP_FAILED,
    /**
     * Every external step is recorded done, but the SDK still holds its local copy of the CCD case, which only
     * Retain and Dispose's own delete removes: finalisation is refused, so nothing local is removed.
     */
    CCD_COPY_PRESENT,
    /** The case still has retained deed preparation or staged bytes, which finalisation never removes. */
    RETAINED_PAYLOAD,
    /** An owned stage names the case that the inventory did not list, so the fence did not hold for it. */
    UNLISTED_OWNED_STAGE,
    /**
     * A permanent document deletion on the case has not completed, which admission refused and the fence should have
     * kept from starting: finalisation would cascade its request and steps away, so nothing local is removed.
     */
    DOCUMENT_DELETION_UNRESOLVED,
    /**
     * The database refused local finalisation (a restricting key or a protecting trigger), and the transaction rolled
     * back: nothing local was removed and no completion was recorded.
     */
    LOCAL_FINALISATION_REFUSED
}
