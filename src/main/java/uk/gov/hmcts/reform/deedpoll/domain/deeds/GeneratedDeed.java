package uk.gov.hmcts.reform.deedpoll.domain.deeds;

import java.util.Arrays;
import java.util.Objects;

/**
 * A deed generated for one case: the bytes, and enough about them to store and serve the file.
 *
 * <p>{@link #format()} travels with the bytes rather than being assumed by the caller, because the format is a
 * deferred decision (decision 5) and a caller that hard-coded {@code .docx} would be the thing a later PDF step
 * had to find and fix.
 *
 * <p>No filename here. What an applicant should see the file called depends on the case -- its reference, and
 * whose deed it is -- and that is the document row's business rather than the generator's.
 *
 * @param template which deed this is
 * @param format what kind of file the bytes are
 * @param content the file itself
 */
public record GeneratedDeed(DeedTemplate template, DeedOutputFormat format, byte[] content) {

    /** The mime type to record and to serve, from the format. */
    public String mimeType() {
        return format.mimeType();
    }

    /** The length in bytes, which is what a document row records as its size. */
    public int size() {
        return content.length;
    }

    /** Equal when the bytes are, rather than when the array is the same object, which is a record's default. */
    @Override
    public boolean equals(Object other) {
        return other instanceof GeneratedDeed that
            && template == that.template
            && format == that.format
            && Arrays.equals(content, that.content);
    }

    @Override
    public int hashCode() {
        return Objects.hash(template, format, Arrays.hashCode(content));
    }

    /** The length rather than the bytes: a whole document in a log line helps nobody. */
    @Override
    public String toString() {
        return "GeneratedDeed[template=" + template + ", format=" + format + ", size=" + size() + "]";
    }
}
