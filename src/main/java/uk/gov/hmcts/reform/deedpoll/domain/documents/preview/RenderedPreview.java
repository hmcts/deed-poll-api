package uk.gov.hmcts.reform.deedpoll.domain.documents.preview;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.OptionalInt;

/**
 * A finished preview: a complete PDF in its request's private scratch directory, until {@link #close}.
 *
 * <p>Closing it deletes the file, whether the response was written in full or the caller went away half way
 * through. It is never anything but whole: a renderer that reached a limit threw instead of returning one.
 */
public final class RenderedPreview implements AutoCloseable {

    private final PreviewWorkspace workspace;
    private final Path file;
    private final OptionalInt pages;
    private final long size;

    RenderedPreview(PreviewWorkspace workspace, Path file, OptionalInt pages, long size) {
        this.workspace = workspace;
        this.file = file;
        this.pages = pages;
        this.size = size;
    }

    /**
     * The pages of a preview this service built (an image or TIFF), and empty for a PDF or a converted DOCX, which
     * are served unparsed and whose pages the viewer counts.
     */
    public OptionalInt pages() {
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
