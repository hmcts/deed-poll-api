package uk.gov.hmcts.reform.deedpoll.domain.documents.preview;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * A finished preview: a complete PDF in its request's private scratch directory, until {@link #close}.
 *
 * <p>Closing it deletes the file, whether the response was written in full or the caller went away half way
 * through. It is never anything but whole: a renderer that reached a limit threw instead of returning one.
 */
public final class RenderedPreview implements AutoCloseable {

    private final PreviewWorkspace workspace;
    private final Path file;
    private final int pages;
    private final long size;

    RenderedPreview(PreviewWorkspace workspace, Path file, int pages, long size) {
        this.workspace = workspace;
        this.file = file;
        this.pages = pages;
        this.size = size;
    }

    /** The PDF's page count, as read back from what was written. */
    public int pages() {
        return pages;
    }

    /** The PDF's length in bytes. */
    public long size() {
        return size;
    }

    /** The PDF's bytes; the caller closes the stream, and closes this when the response is done. */
    public InputStream open() throws IOException {
        return Files.newInputStream(file);
    }

    @Override
    public void close() {
        workspace.close();
    }
}
