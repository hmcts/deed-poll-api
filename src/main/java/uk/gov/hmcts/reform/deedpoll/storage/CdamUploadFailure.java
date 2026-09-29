package uk.gov.hmcts.reform.deedpoll.storage;

/**
 * A CDAM upload that did not end in a readable receipt, with which of the two things that means.
 *
 * <p>{@link #mayHaveStored()} is the whole of the decision the upload's row depends on. False when nothing can have
 * been stored -- the tokens failed before anything was sent, or CDAM refused the request -- so the row is
 * {@code Failed}. True when the request may have landed and the answer was lost: a timeout, a 5xx, or a response this
 * service could not read -- so the row is {@code UploadUncertain}, and never {@code Uploaded}.
 *
 * <p>The message is fixed prose and there is no cause. A Feign exception's message carries the response body, and an
 * upload's response body carries the hash token; a Jackson failure quotes the source it could not read.
 */
public final class CdamUploadFailure extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final boolean mayHaveStored;

    private CdamUploadFailure(String message, boolean mayHaveStored) {
        super(message, null, false, false);
        this.mayHaveStored = mayHaveStored;
    }

    /** Nothing can have been stored. */
    public static CdamUploadFailure nothingStored(String message) {
        return new CdamUploadFailure(message, false);
    }

    /** The upload may have landed, and its answer is lost. */
    public static CdamUploadFailure uncertain(String message) {
        return new CdamUploadFailure(message, true);
    }

    public boolean mayHaveStored() {
        return mayHaveStored;
    }
}
