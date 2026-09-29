package uk.gov.hmcts.reform.deedpoll.domain.documents;

import uk.gov.hmcts.reform.deedpoll.domain.NotFoundError;

import java.io.InputStream;

/**
 * Where a supporting document's bytes live.
 *
 * <p>The {@code deedpoll_supportingdocument} row keeps the metadata; the blob lives behind this interface,
 * keyed by the opaque {@code storageKey} the row carries. Nothing else in the service touches storage, so
 * this is the whole of the seam.
 *
 * <p><strong>{@code reference/ts/documents/file-storage.ts} is deliberately not ported.</strong> Real
 * storage is CDAM, in plan 03, and it is a different shape: content is uploaded to a document store that
 * returns a document id and a hash token, and downloads go through it rather than through this service's own
 * filesystem. Porting the reference's local implementation as the production one would have to be undone.
 * So there is an interface, and behind it exactly one implementation --
 * {@code LocalFilesystemDocumentStore}, for development -- and nothing else. Plan 03 adds a CDAM
 * implementation beside it rather than editing anything above it.
 *
 * <p>The reference's own comment says the same thing about its local storage from the other direction: local
 * disk does not survive AKS, because pods are ephemeral and replicas do not share a volume. The local
 * implementation is a development convenience and must not reach a deployed environment.
 */
public interface DocumentStore {

    /**
     * Persists a document under a fresh storage key and returns what its row should record.
     *
     * <p>The key is the store's to choose: a caller must treat it as opaque, so that a store which keys by
     * something other than a path can be dropped in without the metadata rows meaning something different.
     * The store is told the filename, type and owning record, and may ignore any of them -- the filesystem store
     * ignores all three.
     */
    StorageReceipt save(DocumentToStore document);

    /**
     * Opens a stream over a stored blob.
     *
     * <p>The caller closes it. Returning a stream rather than a byte array is what keeps a 25 MB download
     * off the heap, and it is the shape CDAM's own response has.
     *
     * @throws NotFoundError if the key is unknown to this store
     */
    InputStream open(String storageKey);

    /**
     * Deletes a stored blob.
     *
     * <p>Idempotent, and a no-op for a null or unknown key -- a metadata row may legitimately have no blob,
     * and the reference relies on this so that deleting such a row does not have to special-case it.
     */
    void delete(String storageKey);
}
