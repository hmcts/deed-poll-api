package uk.gov.hmcts.reform.deedpoll.storage;

import uk.gov.hmcts.reform.deedpoll.entity.enums.DocumentStorageState;

/**
 * The recovery rule for a document's remote storage, where it depends on the row rather than the state (plan 03a,
 * task 3). No database in it, so that Docker-free CI runs it, as {@code CcdLinkingRule} does for the CCD case.
 */
public final class DocumentStorageRule {

    /**
     * How many uploads one row may ever send (plan 03a, flag 2): the first, and one fresh retry after a lost answer.
     * {@code V25}'s {@code storageUploadAttempts_capped} CHECK states the same bound, and
     * {@code DocumentStorageStateTest} holds the two together.
     */
    public static final int MAX_UPLOAD_ATTEMPTS = 2;

    private DocumentStorageRule() {
    }

    /**
     * Whether an uncertain upload may be sent again, as a fresh upload, by moving the row back to
     * {@code UploadPending}: only from {@code UploadUncertain}, and only while the row has sent fewer than
     * {@link #MAX_UPLOAD_ATTEMPTS}. The caller is also the request that sent the first, with the bytes still
     * buffered; a later request has nothing to send.
     *
     * <p>Safe because a first upload that did land is never attached, so it expires on CDAM's upload TTL. A second
     * uncertain outcome stays {@code UploadUncertain} for good.
     *
     * @param state          the row's state as last read
     * @param uploadAttempts how many uploads the row has sent, counted before each was sent
     */
    public static boolean mayUploadAgain(DocumentStorageState state, int uploadAttempts) {
        return state == DocumentStorageState.UploadUncertain && uploadAttempts < MAX_UPLOAD_ATTEMPTS;
    }

    /**
     * Whether an attach may be sent (again) for a row in this state: once it is {@code Uploaded}, and again after a
     * lost answer, because CDAM skips a document already attached to the same case (CDAM-SEQUENCE.md section 2).
     *
     * @param state the row's state as last read
     */
    public static boolean mayAttach(DocumentStorageState state) {
        return state == DocumentStorageState.Uploaded || state == DocumentStorageState.AttachUncertain;
    }
}
