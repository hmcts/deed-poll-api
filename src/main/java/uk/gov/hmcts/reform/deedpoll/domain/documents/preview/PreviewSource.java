package uk.gov.hmcts.reform.deedpoll.domain.documents.preview;

import java.io.InputStream;

/**
 * Opens the original's bytes, once, after the caller has been authorised and the read audited.
 *
 * <p>Whatever it throws -- a missing document, a refused store read -- reaches the caller unchanged, so a source
 * read that was refused keeps its own meaning and is never reported as a preview failure.
 */
@FunctionalInterface
public interface PreviewSource {

    InputStream open();
}
