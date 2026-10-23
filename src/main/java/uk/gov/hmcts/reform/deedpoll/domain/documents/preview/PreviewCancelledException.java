package uk.gov.hmcts.reform.deedpoll.domain.documents.preview;

/**
 * The caller went away, so the preview's remaining work is abandoned.
 *
 * <p>Not a {@link PreviewError}: nobody is left to read a status, so it has no code, and it must not be counted or
 * reported as the document's fault. What matters is that work stops and resources are released.
 */
public class PreviewCancelledException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public PreviewCancelledException() {
        super("The preview was cancelled", null, false, false);
    }
}
