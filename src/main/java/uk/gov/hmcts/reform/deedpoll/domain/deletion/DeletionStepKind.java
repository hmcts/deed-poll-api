package uk.gov.hmcts.reform.deedpoll.domain.deletion;

/**
 * What one line of a deletion's cleanup inventory removes. Stored by {@link #name()} in
 * {@code deedpoll_deletion_step.kind}, whose check constraint lists the same members.
 */
public enum DeletionStepKind {
    /** A supporting document held on the filesystem store; the locator is its storage key. */
    LOCAL_DOCUMENT,
    /** A supporting document held in CDAM; the locator is its CDAM document id. CCD's disposer removes it. */
    CDAM_DOCUMENT,
    /**
     * A {@code deedpoll_stagedpayload} row; the locator is its id.
     */
    STAGED_PAYLOAD,
    /**
     * A {@code deedpoll_ownedstage} row owned by the case; the locator is its id.
     */
    OWNED_STAGE,
    /** The CCD case, removed by Retain and Dispose; the locator is its 16-digit reference. */
    CCD_DISPOSAL
}
