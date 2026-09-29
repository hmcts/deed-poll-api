package uk.gov.hmcts.reform.deedpoll.domain.documents;

import java.util.Objects;

/**
 * What {@link DocumentStore#save} answers with, for the document's row to record.
 *
 * <p>A value type rather than the bare key the seam used to return, so that the CDAM store can hand back what it
 * needs recorded as well -- its document id and the transient hash token -- without every caller's signature
 * changing again. Today it carries the key alone.
 *
 * @param storageKey the opaque key the row records and {@link DocumentStore#open} is later given
 */
public record StorageReceipt(String storageKey) {

    public StorageReceipt {
        Objects.requireNonNull(storageKey, "storageKey");
    }
}
