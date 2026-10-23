package uk.gov.hmcts.reform.deedpoll.domain.documents.preview;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.JPEGFactory;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.imageio.IIOException;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.plugins.tiff.TIFFDirectory;
import javax.imageio.plugins.tiff.TIFFField;
import javax.imageio.stream.FileImageInputStream;
import javax.imageio.stream.ImageInputStream;

/**
 * JPEG, PNG and TIFF: one PDF page for each image frame, the way up its orientation tag says, in source order.
 *
 * <p>A JPEG or a PNG is one page. A TIFF is one page for <em>every</em> frame: a scanned application is often one
 * multi-page TIFF, and a preview of its first page alone would look complete while hiding the rest. Each page is A4,
 * portrait or landscape to match the frame as it is seen, with the frame scaled to fit, aspect ratio kept, centred,
 * and never cropped.
 *
 * <p>The bytes decide, not the stored type: only a reader for the stored format that recognises the bytes is used,
 * so a PNG stored as a JPEG is an invalid document. Each frame's declared dimensions are checked against the pixel
 * limit before it is decoded, frames are decoded one at a time and dropped once drawn, and the budget is checked
 * between them. A JPEG is decoded only to prove its data is whole and then embedded as it was stored, so a
 * photograph is not recompressed; a PNG or TIFF frame is embedded losslessly.
 */
final class ImageRenderer implements SourceRenderer {

    private static final Logger LOG = LoggerFactory.getLogger(ImageRenderer.class);

    private final PreviewFormat format;
    private final PreviewProperties properties;

    ImageRenderer(PreviewFormat format, PreviewProperties properties) {
        if (format != PreviewFormat.JPEG && format != PreviewFormat.PNG && format != PreviewFormat.TIFF) {
            throw new IllegalArgumentException("not an image format: " + format);
        }
        this.format = format;
        this.properties = properties;
    }

    @Override
    public Rendition render(Path source, PreviewWorkspace workspace, PreviewBudget budget) {
        Path output = workspace.newFile(".pdf");
        int frames = 0;
        try (ImageInputStream input = new FileImageInputStream(source.toFile());
             PDDocument document = new PDDocument(workspace.streamCache(properties.maxOutputBytes()))) {
            ImageReader reader = readerFor(input);
            // A JPEG is embedded as stored, so it is read whole once, and its orientation comes from those bytes.
            byte[] jpeg = format == PreviewFormat.JPEG ? Files.readAllBytes(source) : null;
            try {
                reader.setInput(input, false, format == PreviewFormat.JPEG);
                while (format == PreviewFormat.TIFF || frames == 0) {
                    budget.checkpoint();
                    int width;
                    int height;
                    try {
                        width = reader.getWidth(frames);
                        height = reader.getHeight(frames);
                    } catch (IndexOutOfBoundsException noMoreFrames) {
                        break;
                    }
                    if (frames >= properties.maxPages()) {
                        throw new PreviewError(PreviewFailure.LIMIT_EXCEEDED);
                    }
                    properties.requireFrame(width, height);
                    Orientation orientation = jpeg != null ? ExifOrientation.of(jpeg) : orientation(reader, frames);
                    PDImageXObject image = frame(reader, frames, jpeg, document);
                    budget.checkpoint();
                    place(document, image, width, height, orientation);
                    frames++;
                }
            } finally {
                reader.dispose();
            }
            properties.requirePages(frames);
            budget.checkpoint();
            building(() -> {
                try (OutputStream out = workspace.boundedOutput(output, properties.maxOutputBytes())) {
                    document.save(out);
                }
                return null;
            });
        } catch (PreviewError | PreviewCancelledException own) {
            throw own;
        } catch (Building scratch) {
            // Writing, not reading: the frame was decoded and it is the bounded scratch that is full.
            throw new PreviewError(PreviewFailure.LIMIT_EXCEEDED);
        } catch (IOException | RuntimeException unreadable) {
            // The class name only: a decoder's message can quote the file's own tags and values.
            LOG.info("A {} preview was refused as unreadable ({})", format, unreadable.getClass().getSimpleName());
            throw new PreviewError(PreviewFailure.INVALID_DOCUMENT);
        }
        return new Rendition(output, frames);
    }

    private ImageReader readerFor(ImageInputStream input) throws IOException {
        Iterator<ImageReader> readers = ImageIO.getImageReadersByFormatName(formatName());
        while (readers.hasNext()) {
            ImageReader reader = readers.next();
            if (reader.getOriginatingProvider().canDecodeInput(input)) {
                return reader;
            }
            reader.dispose();
        }
        throw new PreviewError(PreviewFailure.INVALID_DOCUMENT);
    }

    private String formatName() {
        return switch (format) {
            case JPEG -> "jpeg";
            case PNG -> "png";
            case TIFF -> "tiff";
            case PDF, DOCX -> throw new IllegalStateException("not an image format");
        };
    }

    private Orientation orientation(ImageReader reader, int index) throws IOException {
        if (format != PreviewFormat.TIFF) {
            return Orientation.TOP_LEFT;
        }
        IIOMetadata metadata = reader.getImageMetadata(index);
        TIFFField field = metadata == null ? null
            : TIFFDirectory.createFromMetadata(metadata).getTIFFField(Orientation.TAG);
        return field == null ? Orientation.TOP_LEFT : Orientation.of(field.getAsInt(0));
    }

    private static PDImageXObject frame(ImageReader reader, int index, byte[] jpeg, PDDocument document)
        throws IOException {
        if (jpeg != null) {
            AtomicBoolean damaged = new AtomicBoolean();
            reader.addIIOReadWarningListener((ignored, warning) -> damaged.set(true));
            // Decoded to the raster, which needs no colour conversion, only to prove the entropy data is whole: a
            // truncated JPEG decodes with a warning and a grey remainder rather than an exception.
            reader.readRaster(index, null);
            if (damaged.get()) {
                throw new IIOException("damaged JPEG data");
            }
            return building(() -> JPEGFactory.createFromByteArray(document, jpeg));
        }
        BufferedImage image = reader.read(index);
        return building(() -> LosslessFactory.createFromImage(document, image));
    }

    private static void place(PDDocument document, PDImageXObject image, int width, int height,
                              Orientation orientation) {
        float seenWidth = orientation.swapsAxes() ? height : width;
        float seenHeight = orientation.swapsAxes() ? width : height;
        PDRectangle a4 = PDRectangle.A4;
        PDRectangle box = seenWidth > seenHeight ? new PDRectangle(a4.getHeight(), a4.getWidth()) : a4;
        float scale = Math.min(box.getWidth() / seenWidth, box.getHeight() / seenHeight);
        float drawnWidth = seenWidth * scale;
        float drawnHeight = seenHeight * scale;
        PDPage page = new PDPage(box);
        document.addPage(page);
        building(() -> {
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.drawImage(image, orientation.placement((box.getWidth() - drawnWidth) / 2,
                                                               (box.getHeight() - drawnHeight) / 2,
                                                               drawnWidth, drawnHeight));
            }
            return null;
        });
    }

    private static <T> T building(PdfWrite<T> write) {
        try {
            return write.run();
        } catch (IOException full) {
            throw new Building();
        }
    }

    @FunctionalInterface
    private interface PdfWrite<T> {
        T run() throws IOException;
    }

    /** A write into the PDF under construction failed, which is its bounded scratch running out. */
    private static final class Building extends RuntimeException {
        private static final long serialVersionUID = 1L;

        Building() {
            super(null, null, false, false);
        }
    }
}
