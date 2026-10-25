package uk.gov.hmcts.reform.deedpoll.domain.documents.preview;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Makes one document's preview: the source, then a workspace slot, then a permit if its format needs one, then a
 * complete PDF.
 *
 * <p>The order is the point. The source is opened first. Opening it is where {@code DOCUMENT_PREVIEWED} is committed
 * and where the store (CDAM) checks the caller, so a caller the store refuses gets the download's own refusal, never
 * {@link PreviewFailure#BUSY} or {@link PreviewFailure#UNAVAILABLE}. Only then is one of the pod's workspace slots
 * taken ({@link ConversionCapacity#acquireWorkspace}, {@link PreviewFailure#BUSY} if none is free), which bounds how
 * many previews of any format hold scratch at once, and the source is spooled into the request's private workspace
 * under the input ceiling and the budget; a slow store read holds no conversion permit while it trickles in. Only
 * then is the converter's configuration checked and the permit taken, the format's renderer writes the PDF there,
 * and its size (and, for a preview this service built, its pages) is checked against the limits before it is handed
 * back. A PDF and a converted DOCX are never parsed, so their pages are not counted here; see {@link PdfRenderer}.
 * Whatever happens, the source stream is closed and a failed preview's workspace is deleted, and its slot given back,
 * before this returns.
 *
 * <p><strong>The permit is released here, on the thread doing the work, and only when that work has
 * stopped.</strong> The request thread is the worker, so a caller that disconnects mid-conversion is noticed only
 * when the PDF is written; the work runs on to its end or to the {@link PreviewBudget}, holding the memory the permit
 * stands for, and only then gives the permit back. A finished {@link RenderedPreview} holds no permit: it is a
 * file being streamed. It does hold its workspace slot, which is given back when it is closed and its file deleted,
 * so a slow reader of the response still counts against the pod's scratch.
 *
 * <p>A DOCX is converted by {@link DocxRenderer}. With its converter unconfigured it answers
 * {@link PreviewFailure#UNAVAILABLE} once the source has been read, before a permit is taken, so that refusal too
 * reaches only a caller the store would have served.
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
        PdfRenderer pdfRenderer = new PdfRenderer();
        this.pdf = pdfRenderer;
        this.jpeg = new ImageRenderer(PreviewFormat.JPEG, properties);
        this.png = new ImageRenderer(PreviewFormat.PNG, properties);
        this.tiff = new ImageRenderer(PreviewFormat.TIFF, properties);
        this.docx = new DocxRenderer(properties, pdfRenderer);
    }

    /**
     * The preview of {@code source}, stored as {@code format}.
     *
     * @throws PreviewError for any of {@link PreviewFailure}'s reasons; nothing partial is ever returned
     */
    public RenderedPreview render(PreviewFormat format, PreviewSource source, PreviewBudget budget) {
        budget.checkpoint();
        PreviewWorkspace workspace = null;
        boolean handedBack = false;
        try {
            Path spooled;
            try (InputStream in = source.open()) {
                workspace = PreviewWorkspace.create(scratchRoot, capacity.acquireWorkspace());
                spooled = workspace.spool(in, properties.maxInputBytes(), budget);
            } catch (IOException unclosable) {
                // Closing the store's stream failed after every byte was read; nothing about the document is wrong.
                throw new PreviewError(PreviewFailure.UNAVAILABLE);
            }
            SourceRenderer renderer = rendererFor(format);
            try (ConversionCapacity.Permit permit = capacity.acquire(format)) {
                SourceRenderer.Rendition rendition = renderer.render(spooled, workspace, budget);
                rendition.pages().ifPresent(properties::requirePages);
                long size = size(rendition.file());
                properties.requireOutputBytes(size);
                budget.checkpoint();
                RenderedPreview preview = new RenderedPreview(workspace, rendition.file(), rendition.pages(), size);
                handedBack = true;
                return preview;
            }
        } finally {
            if (!handedBack && workspace != null) {
                workspace.close();
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

    private static long size(Path file) {
        try {
            return Files.size(file);
        } catch (IOException scratch) {
            throw new PreviewError(PreviewFailure.UNAVAILABLE);
        }
    }
}
