package uk.gov.hmcts.reform.deedpoll.domain.documents;

import java.util.Arrays;
import java.util.Objects;

/**
 * The parts of an uploaded file this domain needs.
 *
 * <p>Ported from the {@code UploadedFile} interface in {@code reference/ts/documents/service.ts}, whose own
 * comment explains why it exists: the prototype typed this as {@code Express.Multer.File}, which put a
 * transport type in the domain layer. These four values are all it ever read, so whatever serves the
 * request maps its own multipart type onto this -- {@code MultipartFile} here, Multer's file there.
 *
 * <p>{@code content} is the bytes themselves, buffered. That is the reason {@link UploadPolicy#MAX_UPLOAD_BYTES}
 * has the ceiling it does, and the reason plan 03's CDAM store is where streaming belongs.
 *
 * @param content the file's bytes
 * @param originalName the name the client gave the file, kept so a download can offer it back
 * @param mimeType the type the client declared -- see {@link UploadPolicy} on why that is hygiene, not proof
 * @param size the length in bytes, recorded on the metadata row
 */
public record UploadedFile(byte[] content, String originalName, String mimeType, int size) {

    /** A file whose recorded size is its actual length, which is the only honest pairing. */
    public static UploadedFile of(byte[] content, String originalName, String mimeType) {
        return new UploadedFile(content, originalName, mimeType, content.length);
    }

    /** Equal when the bytes are, rather than when the array is the same object, which is a record's default. */
    @Override
    public boolean equals(Object other) {
        return other instanceof UploadedFile that
            && size == that.size
            && Objects.equals(originalName, that.originalName)
            && Objects.equals(mimeType, that.mimeType)
            && Arrays.equals(content, that.content);
    }

    @Override
    public int hashCode() {
        return Objects.hash(Arrays.hashCode(content), originalName, mimeType, size);
    }

    /** The length rather than the bytes: a whole upload in a log line helps nobody. */
    @Override
    public String toString() {
        return "UploadedFile[originalName=" + originalName + ", mimeType=" + mimeType + ", size=" + size + "]";
    }
}
