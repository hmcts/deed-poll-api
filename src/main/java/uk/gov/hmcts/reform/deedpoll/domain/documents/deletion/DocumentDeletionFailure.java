package uk.gov.hmcts.reform.deedpoll.domain.documents.deletion;

/**
 * Why a document deletion request is {@link DocumentDeletionStatus#NeedsAttention}: the allowlisted codes the status
 * resource may publish. Never a provider message, an exception text or a locator. None of them means the document
 * was deleted; the steps still hold every locator whose removal is not confirmed.
 */
public enum DocumentDeletionFailure {
    /** CDAM answered 404 to the permanent delete: already purged, soft-deleted elsewhere, or never there. */
    CDAM_PURGE_UNPROVEN,
    /**
     * CDAM refused the service permission ({@code UPDATE} on the case type): an activation blocker.
     */
    CDAM_PERMISSION,
    /** CDAM refused the permanent delete for another reason, or its unknown answers used up the attempts. */
    CDAM_DELETE_FAILED,
    /** data-store refused the document event that removes the document from the CCD case. */
    CCD_REFERENCE_FAILED,
    /** The filesystem file could not be removed, or its absence verified. */
    LOCAL_FILE_UNREMOVED,
    /** The database refused local completion; nothing local was removed and nothing was recorded. */
    LOCAL_COMPLETION_REFUSED
}
