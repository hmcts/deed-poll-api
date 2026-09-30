package uk.gov.hmcts.reform.deedpoll.domain.documents;

import java.util.Arrays;
import java.util.Objects;

/**
 * What {@link DocumentStore#save} is asked to store: the bytes, what they are, and whose they are.
 *
 * <p>The seam used to take bare bytes, which was enough for a directory of UUID-named files and is not enough
 * for CDAM, whose upload carries a filename, a MIME type and the case type and jurisdiction it is filed under.
 * The store is told everything here and decides for itself what it needs.
 *
 * @param content the bytes, buffered -- see {@link UploadedFile} on why
 * @param filename the name the document goes under, which for an upload is the client's own
 * @param mimeType the declared type
 * @param owner the record the document belongs to
 */
public record DocumentToStore(byte[] content, String filename, String mimeType, DocumentOwner owner) {

    public DocumentToStore {
        Objects.requireNonNull(content, "content");
        Objects.requireNonNull(owner, "owner");
    }

    /** Equal when the bytes are, rather than when the array is the same object, which is a record's default. */
    @Override
    public boolean equals(Object other) {
        return other instanceof DocumentToStore that
            && Objects.equals(filename, that.filename)
            && Objects.equals(mimeType, that.mimeType)
            && Objects.equals(owner, that.owner)
            && Arrays.equals(content, that.content);
    }

    @Override
    public int hashCode() {
        return Objects.hash(Arrays.hashCode(content), filename, mimeType, owner);
    }

    /** The length rather than the bytes: a whole document in a log line helps nobody. */
    @Override
    public String toString() {
        return "DocumentToStore[filename=" + filename + ", mimeType=" + mimeType + ", size=" + content.length
            + ", owner=" + owner + "]";
    }
}
