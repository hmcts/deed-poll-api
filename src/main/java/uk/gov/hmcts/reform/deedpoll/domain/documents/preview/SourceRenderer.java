package uk.gov.hmcts.reform.deedpoll.domain.documents.preview;

import java.nio.file.Path;

/**
 * Makes one format's preview from a source already spooled into the request's workspace.
 *
 * <p>An implementation returns a complete PDF or throws: {@link PreviewError} for a refusal, and
 * {@link PreviewCancelledException} when the budget's caller has gone. It never lets a parser's or a client's own
 * exception out, because its message may quote the document.
 */
interface SourceRenderer {

    Rendition render(Path source, PreviewWorkspace workspace, PreviewBudget budget);

    /** The PDF, inside the workspace, and the pages it has. */
    record Rendition(Path file, int pages) {
    }
}
