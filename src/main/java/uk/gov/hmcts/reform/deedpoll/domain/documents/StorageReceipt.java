package uk.gov.hmcts.reform.deedpoll.domain.documents;

import java.util.Objects;
import java.util.Optional;

/**
 * What {@link DocumentStore#save} answers with, for the document's row to record.
 *
 * <p>A value type rather than the bare key the seam used to return, so that the CDAM store can hand back what it
 * needs recorded as well. The filesystem store answers a key alone; the CDAM store answers its document id as the key
 * and the upload's hash token beside it.
 *
 * <p><strong>The hash token is transient</strong> (plan 03a, decision 8): it is kept on the row only between upload
 * and attach, and never appears in a log line. So {@link #toString} says whether there is one and never what it is --
 * a receipt that reaches a log statement by accident must not carry the token with it.
 *
 * @param storageKey the opaque key the row records and {@link DocumentStore#open} is later given
 * @param hashToken  the token the document must be attached with, when the store issues one
 */
public record StorageReceipt(String storageKey, Optional<String> hashToken) {

    public StorageReceipt {
        Objects.requireNonNull(storageKey, "storageKey");
        Objects.requireNonNull(hashToken, "hashToken");
    }

    /** A receipt carrying a key and nothing else, which is all the filesystem store has to say. */
    public StorageReceipt(String storageKey) {
        this(storageKey, Optional.empty());
    }

    @Override
    public String toString() {
        return "StorageReceipt[storageKey=" + storageKey + ", hashToken="
            + (hashToken.isPresent() ? "<redacted>" : "<none>") + "]";
    }
}
