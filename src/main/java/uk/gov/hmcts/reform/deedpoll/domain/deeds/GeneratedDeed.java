package uk.gov.hmcts.reform.deedpoll.domain.deeds;

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
}
