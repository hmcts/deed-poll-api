package uk.gov.hmcts.reform.deedpoll.domain.documents.preview;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/**
 * A PDF is its own preview: it is checked and then served byte for byte as it was stored.
 *
 * <p>The checks are the ones a viewer depends on. The bytes start {@code %PDF-} as an upload's did, PDFBox can open
 * the document and walk its page tree to the end, it is not encrypted (an upload refuses that too, so only an old
 * row can be), and its pages are between one and the limit -- counted by walking the tree, not by trusting the
 * {@code /Count} a damaged or hostile file declares. Nothing is rewritten, so nothing is lost: what the browser shows
 * is what was uploaded.
 */
final class PdfRenderer implements SourceRenderer {

    private static final Logger LOG = LoggerFactory.getLogger(PdfRenderer.class);
    private static final byte[] HEADER = "%PDF-".getBytes(StandardCharsets.US_ASCII);

    private final PreviewProperties properties;

    PdfRenderer(PreviewProperties properties) {
        this.properties = properties;
    }

    @Override
    public Rendition render(Path source, PreviewWorkspace workspace, PreviewBudget budget) {
        requireHeader(source);
        int pages = 0;
        try (PDDocument document = Loader.loadPDF(source.toFile(), "",
                                                  workspace.streamCache(properties.maxOutputBytes()))) {
            if (document.isEncrypted()) {
                throw new PreviewError(PreviewFailure.INVALID_DOCUMENT);
            }
            for (PDPage ignored : document.getPages()) {
                budget.checkpoint();
                pages++;
                if (pages > properties.maxPages()) {
                    throw new PreviewError(PreviewFailure.LIMIT_EXCEEDED);
                }
            }
        } catch (PreviewError own) {
            throw own;
        } catch (IOException | RuntimeException unreadable) {
            // The class name only: PDFBox's messages quote object numbers, names and strings from the file.
            LOG.info("A PDF preview was refused as unreadable ({})", unreadable.getClass().getSimpleName());
            throw new PreviewError(PreviewFailure.INVALID_DOCUMENT);
        }
        // Repeated by PreviewRenderer for every format, but needed here: a converted DOCX with no pages must reach
        // DocxRenderer as this invalid document, which it reports as the converter's failure.
        properties.requirePages(pages);
        return new Rendition(source, pages);
    }

    private static void requireHeader(Path source) {
        byte[] start = new byte[HEADER.length];
        try (InputStream in = Files.newInputStream(source)) {
            if (in.readNBytes(start, 0, start.length) != start.length || !Arrays.equals(start, HEADER)) {
                throw new PreviewError(PreviewFailure.INVALID_DOCUMENT);
            }
        } catch (IOException scratch) {
            throw new PreviewError(PreviewFailure.UNAVAILABLE);
        }
    }
}
