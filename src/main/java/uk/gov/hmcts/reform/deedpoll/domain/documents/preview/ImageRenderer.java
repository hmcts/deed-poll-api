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
import java.awt.image.DataBuffer;
import java.awt.image.SampleModel;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.Locale;
import java.util.OptionalInt;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.imageio.IIOException;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageTypeSpecifier;
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
 * so a PNG stored as a JPEG is an invalid document. A TIFF's directory structure is held to the file's own bytes by
 * {@link TiffStructure} before ImageIO is given the file at all, and a JPEG-compressed TIFF is refused there. Each
 * frame's declared dimensions, and a tiled TIFF frame's declared tile, are checked against the pixel limit, and a PNG
 * or TIFF frame's sample layout against the decoded-size limit, before it is decoded; frames are decoded one at a
 * time and dropped once drawn, and the budget is checked between them. A JPEG is decoded only to prove its data is
 * whole and then embedded as it was stored, so a photograph is not recompressed; a PNG or TIFF frame is embedded
 * losslessly. A PNG's metadata is never read, so its
 * text chunks are never inflated, and a TIFF's orientation is read by {@link TiffOrientation}, never as ImageIO's
 * metadata tree.
 */
final class ImageRenderer implements SourceRenderer {

    private static final Logger LOG = LoggerFactory.getLogger(ImageRenderer.class);

    /**
     * The decoded bytes a full-size frame may take, per pixel of {@link PreviewProperties#maxFramePixels}: ARGB at
     * eight bits a sample, the figure decision 5's heap estimate rests on.
     */
    private static final long MAX_DECODED_BYTES_PER_FRAME_PIXEL = 4;

    private final PreviewFormat format;
    private final String formatName;
    private final PreviewProperties properties;

    /**
     * A renderer for {@code format}, read by ImageIO's readers for the format's lower-case name.
     */
    ImageRenderer(PreviewFormat format, PreviewProperties properties) {
        this.format = format;
        this.formatName = format.name().toLowerCase(Locale.ROOT);
        this.properties = properties;
    }

    @Override
    public Rendition render(Path source, PreviewWorkspace workspace, PreviewBudget budget) {
        Path output = workspace.newFile(".pdf");
        int frames = 0;
        try (ImageInputStream input = checkedInput(source, budget);
             // A second stream for the TIFF directories, so the reader's own position and byte order are untouched.
             ImageInputStream directories = format == PreviewFormat.TIFF
                 ? new FileImageInputStream(source.toFile()) : null;
             PDDocument document = new PDDocument(workspace.streamCache(properties.maxOutputBytes()))) {
            ImageReader reader = readerFor(input);
            // A JPEG is embedded as stored, so it is read whole once, and its orientation comes from those bytes.
            byte[] jpeg = format == PreviewFormat.JPEG ? Files.readAllBytes(source) : null;
            try {
                // A PNG's metadata must be ignored: the JDK inflates every compressed zTXt and iTXt chunk while
                // reading it, with no bound, so a few kilobytes of text could fill the heap past every limit here.
                // Ignored, the reader skips them unread. A TIFF reader keeps its fields, each read only if it lies
                // inside the file, and never inflates one; they are read through its tile accessors, never as a tree.
                reader.setInput(input, false, format != PreviewFormat.TIFF);
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
                    long decodedPixels = (long) width * height;
                    if (format == PreviewFormat.TIFF && reader.isImageTiled(frames)) {
                        // The JDK's TIFF decoder sizes its rasters from the declared tile (TileWidth by TileLength),
                        // which may legally be far larger than the frame. A strip is clipped to the frame before it
                        // is decoded, so a RowsPerStrip past the height is already bounded by the frame check. These
                        // are the reader's own values, read from the directory it has already parsed.
                        int tileWidth = reader.getTileWidth(frames);
                        int tileHeight = reader.getTileHeight(frames);
                        properties.requireFrame(tileWidth, tileHeight);
                        decodedPixels = Math.max(decodedPixels, (long) tileWidth * tileHeight);
                    }
                    Orientation orientation = jpeg != null ? ExifOrientation.of(jpeg)
                        : directories != null ? TiffOrientation.of(directories, frames) : Orientation.TOP_LEFT;
                    PDImageXObject image = frame(reader, frames, jpeg, document, decodedPixels);
                    budget.checkpoint();
                    place(document, image, width, height, orientation);
                    frames++;
                }
            } finally {
                reader.dispose();
            }
            budget.checkpoint();
            building(() -> {
                try (OutputStream out = workspace.boundedOutput(output, properties.maxOutputBytes())) {
                    document.save(out);
                }
                return null;
            });
        } catch (PreviewError own) {
            throw own;
        } catch (Building scratch) {
            // Writing, not reading: the frame was decoded and it is the bounded scratch that is full.
            throw new PreviewError(PreviewFailure.LIMIT_EXCEEDED);
        } catch (IOException | RuntimeException unreadable) {
            // The class name only: a decoder's message can quote the file's own tags and values.
            LOG.info("A {} preview was refused as unreadable ({})", format, unreadable.getClass().getSimpleName());
            throw new PreviewError(PreviewFailure.INVALID_DOCUMENT);
        }
        return new Rendition(output, OptionalInt.of(frames));
    }

    /**
     * The source as ImageIO reads it. A TIFF's structure is checked first ({@link TiffStructure}), so no ImageIO
     * code, this stream included, ever sees a TIFF whose directories could make the JDK read one payload many times.
     */
    private ImageInputStream checkedInput(Path source, PreviewBudget budget) throws IOException {
        if (format == PreviewFormat.TIFF) {
            TiffStructure.require(source, properties.maxPages(), budget);
        }
        return new FileImageInputStream(source.toFile());
    }

    private ImageReader readerFor(ImageInputStream input) throws IOException {
        Iterator<ImageReader> readers = ImageIO.getImageReadersByFormatName(formatName);
        while (readers.hasNext()) {
            ImageReader reader = readers.next();
            if (reader.getOriginatingProvider().canDecodeInput(input)) {
                return reader;
            }
            reader.dispose();
        }
        throw new PreviewError(PreviewFailure.INVALID_DOCUMENT);
    }

    private PDImageXObject frame(ImageReader reader, int index, byte[] jpeg, PDDocument document,
                                 long decodedPixels) throws IOException {
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
        requireDecodedSize(reader, index, decodedPixels);
        BufferedImage image = reader.read(index);
        return building(() -> LosslessFactory.createFromImage(document, image));
    }

    /**
     * The pixel limit alone does not bound the heap: a 16-bit RGBA frame is eight bytes a pixel and a floating-point
     * TIFF sixteen or more, so the frame's own sample layout is held to what a full-size ARGB frame would take, over
     * the frame or its larger declared tile. A JPEG needs no such check, because the JDK decodes it only at
     * eight bits a sample and at most four samples.
     */
    private void requireDecodedSize(ImageReader reader, int index, long pixels) throws IOException {
        ImageTypeSpecifier type = reader.getRawImageType(index);
        if (type == null) {
            Iterator<ImageTypeSpecifier> types = reader.getImageTypes(index);
            if (!types.hasNext()) {
                throw new IIOException("no decodable image type");
            }
            type = types.next();
        }
        SampleModel samples = type.getSampleModel();
        long bitsPerPixel = (long) DataBuffer.getDataTypeSize(samples.getDataType()) * samples.getNumDataElements();
        long bytesPerPixel = Math.max(1, (bitsPerPixel + 7) / 8);
        if (pixels * bytesPerPixel > properties.maxFramePixels() * MAX_DECODED_BYTES_PER_FRAME_PIXEL) {
            throw new PreviewError(PreviewFailure.LIMIT_EXCEEDED);
        }
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
