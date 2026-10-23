package uk.gov.hmcts.reform.deedpoll.domain.documents.preview;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Makes one document's preview: a permit if its format needs one, then the source, then a complete PDF.
 *
 * <p>The order is the point. The permit is taken before the source is opened, so a busy pod refuses without reading
 * a byte; the source is spooled into the request's private workspace under the input ceiling and the budget; the
 * format's renderer writes the PDF there; and its pages and size are checked against the limits before it is
 * handed back. Whatever happens, the source stream is closed and a failed preview's workspace is deleted
 * before this returns.
 *
 * <p><strong>The permit is released here, on the thread doing the work, and only when that work has
 * stopped.</strong> The request thread is the worker, so a caller that disconnects mid-conversion is noticed only
 * when the PDF is written; the work runs on to its end or to the {@link PreviewBudget}, holding the memory the permit
 * stands for, and only then gives the permit back. A finished {@link RenderedPreview} holds no permit: it is a
 * file being streamed.
 *
 * <p>A DOCX is converted by {@link DocxRenderer}. With its converter unconfigured it answers
 * {@link PreviewFailure#UNAVAILABLE} at once, before a permit is taken or the source opened, so nothing is read for
 * a conversion that cannot happen.
 */
public class PreviewRenderer {

    private final PreviewProperties properties;
    private final ConversionCapacity capacity;
    private final Path scratchRoot;
    private final SourceRenderer pdf;
    private final SourceRenderer jpeg;
    private final SourceRenderer png;
    private final SourceRenderer tiff;
    private final SourceRenderer docx;

    public PreviewRenderer(PreviewProperties properties, ConversionCapacity capacity, Path scratchRoot) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.capacity = Objects.requireNonNull(capacity, "capacity");
        this.scratchRoot = Objects.requireNonNull(scratchRoot, "scratchRoot");
        PdfRenderer pdfRenderer = new PdfRenderer(properties);
        this.pdf = pdfRenderer;
        this.jpeg = new ImageRenderer(PreviewFormat.JPEG, "jpeg", properties);
        this.png = new ImageRenderer(PreviewFormat.PNG, "png", properties);
        this.tiff = new ImageRenderer(PreviewFormat.TIFF, "tiff", properties);
        this.docx = new DocxRenderer(properties, pdfRenderer);
    }

    /**
     * The preview of {@code source}, stored as {@code format}.
     *
     * @throws PreviewError for any of {@link PreviewFailure}'s reasons; nothing partial is ever returned
     */
    public RenderedPreview render(PreviewFormat format, PreviewSource source, PreviewBudget budget) {
        budget.checkpoint();
        SourceRenderer renderer = rendererFor(format);
        try (ConversionCapacity.Permit permit = capacity.acquire(format)) {
            PreviewWorkspace workspace = PreviewWorkspace.create(scratchRoot);
            boolean handedBack = false;
            try {
                Path spooled = spool(source, workspace, budget);
                SourceRenderer.Rendition rendition = renderer.render(spooled, workspace, budget);
                properties.requirePages(rendition.pages());
                long size = size(rendition.file());
                properties.requireOutputBytes(size);
                budget.checkpoint();
                RenderedPreview preview = new RenderedPreview(workspace, rendition.file(), rendition.pages(), size);
                handedBack = true;
                return preview;
            } finally {
                if (!handedBack) {
                    workspace.close();
                }
            }
        }
    }

    private SourceRenderer rendererFor(PreviewFormat format) {
        return switch (format) {
            case PDF -> pdf;
            case JPEG -> jpeg;
            case PNG -> png;
            case TIFF -> tiff;
            case DOCX -> {
                if (!properties.converter().configured()) {
                    throw new PreviewError(PreviewFailure.UNAVAILABLE);
                }
                yield docx;
            }
        };
    }

    private Path spool(PreviewSource source, PreviewWorkspace workspace, PreviewBudget budget) {
        try (InputStream in = source.open()) {
            return workspace.spool(in, properties.maxInputBytes(), budget);
        } catch (IOException unclosable) {
            // Closing the store's stream failed after every byte was read; nothing about the document is wrong.
            throw new PreviewError(PreviewFailure.UNAVAILABLE);
        }
    }

    private static long size(Path file) {
        try {
            return Files.size(file);
        } catch (IOException scratch) {
            throw new PreviewError(PreviewFailure.UNAVAILABLE);
        }
    }
}
