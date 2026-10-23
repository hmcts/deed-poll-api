package uk.gov.hmcts.reform.deedpoll.domain.documents.preview;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.plugins.tiff.BaselineTIFFTagSet;
import javax.imageio.plugins.tiff.TIFFDirectory;
import javax.imageio.plugins.tiff.TIFFField;
import javax.imageio.plugins.tiff.TIFFTag;
import javax.imageio.stream.ImageOutputStream;

/**
 * The synthetic preview fixtures: how each file under {@code src/test/resources/document-previews/} was made.
 *
 * <p>Every image is a solid colour with a black marker square in its <em>stored</em> top-left corner, so a rendered
 * page shows both which frame it came from (the colour) and which way up it was drawn (where the marker landed).
 * {@code DocumentPreviewFixturesTest} regenerates the committed files when {@code REGENERATE_DOCUMENT_PREVIEWS=true}
 * and otherwise checks that the committed files still have the structure described here. Nothing is real case data.
 */
final class DocumentPreviewFixtures {

    static final Color RED = new Color(220, 30, 30);
    static final Color BLUE = new Color(30, 60, 220);
    static final Color GREEN = new Color(30, 170, 60);
    static final Color YELLOW = new Color(240, 200, 20);

    /** One fixture file and the frames, compression and orientation it was written with. */
    record Fixture(String name, List<Frame> frames) {
    }

    /**
     * One image frame: stored dimensions, colour, TIFF compression ({@code null} for none or a non-TIFF format),
     * and orientation tag value (1 is as stored).
     */
    record Frame(int width, int height, Color colour, String compression, int orientation) {
    }

    static final Fixture TWO_FRAMES = new Fixture("two-frames.tiff", List.of(
        new Frame(400, 600, RED, "LZW", 1),
        new Frame(600, 400, BLUE, "Deflate", 1)));

    static final Fixture THREE_FRAMES = new Fixture("three-frames.tiff", List.of(
        new Frame(400, 600, Color.WHITE, "CCITT T.6", 1),
        new Frame(300, 200, GREEN, "PackBits", 1),
        new Frame(300, 200, YELLOW, null, 6)));

    static final Fixture PHOTO = new Fixture("photo.jpeg", List.of(new Frame(640, 480, BLUE, null, 1)));
    static final Fixture PHOTO_ON_ITS_SIDE = new Fixture("photo-exif-6.jpeg",
        List.of(new Frame(640, 480, GREEN, null, 6)));
    static final Fixture SCAN = new Fixture("scan.png", List.of(new Frame(480, 640, RED, null, 1)));
    static final String PDF = "three-pages.pdf";
    static final int PDF_PAGES = 3;

    private DocumentPreviewFixtures() {
    }

    /** A committed fixture's bytes. */
    static byte[] read(String name) {
        try (InputStream in = DocumentPreviewFixtures.class.getResourceAsStream("/document-previews/" + name)) {
            if (in == null) {
                throw new IllegalStateException("no fixture " + name);
            }
            return in.readAllBytes();
        } catch (IOException unreadable) {
            throw new UncheckedIOException(unreadable);
        }
    }

    /** The image for one frame: its colour, with the marker in its stored top-left corner. */
    static BufferedImage image(Frame frame) {
        boolean bilevel = "CCITT T.6".equals(frame.compression()) || "CCITT T.4".equals(frame.compression())
            || "CCITT RLE".equals(frame.compression());
        BufferedImage image = new BufferedImage(frame.width(), frame.height(),
                                                bilevel ? BufferedImage.TYPE_BYTE_BINARY
                                                    : BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(bilevel ? Color.WHITE : frame.colour());
            graphics.fillRect(0, 0, frame.width(), frame.height());
            graphics.setColor(Color.BLACK);
            graphics.fillRect(0, 0, frame.width() / 4, frame.height() / 4);
        } finally {
            graphics.dispose();
        }
        return image;
    }

    /**
     * A TIFF holding every frame of {@code fixture}, in order, each with its own compression and orientation.
     */
    static byte[] tiff(Fixture fixture) {
        return tiff(fixture.frames());
    }

    static byte[] tiff(List<Frame> frames) {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("tiff").next();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ImageOutputStream out = ImageIO.createImageOutputStream(bytes)) {
            writer.setOutput(out);
            writer.prepareWriteSequence(null);
            for (Frame frame : frames) {
                BufferedImage image = image(frame);
                ImageWriteParam param = writer.getDefaultWriteParam();
                if (frame.compression() != null) {
                    param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                    param.setCompressionType(frame.compression());
                }
                IIOMetadata metadata = writer.getDefaultImageMetadata(new ImageTypeSpecifier(image), param);
                if (frame.orientation() != 1) {
                    TIFFDirectory directory = TIFFDirectory.createFromMetadata(metadata);
                    directory.addTIFFField(new TIFFField(
                        BaselineTIFFTagSet.getInstance().getTag(BaselineTIFFTagSet.TAG_ORIENTATION),
                        TIFFTag.TIFF_SHORT, 1, new char[] {(char) frame.orientation()}));
                    metadata = directory.getAsMetadata();
                }
                writer.writeToSequence(new IIOImage(image, null, metadata), param);
            }
            writer.endWriteSequence();
        } catch (IOException unwritable) {
            throw new UncheckedIOException(unwritable);
        } finally {
            writer.dispose();
        }
        return bytes.toByteArray();
    }

    /**
     * A one-frame image in {@code format} ({@code jpeg} or {@code png}), with an EXIF orientation if not 1.
     */
    static byte[] encoded(String format, Frame frame) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try {
            if (!ImageIO.write(image(frame), format, bytes)) {
                throw new IllegalStateException("no writer for " + format);
            }
        } catch (IOException unwritable) {
            throw new UncheckedIOException(unwritable);
        }
        byte[] written = bytes.toByteArray();
        return frame.orientation() == 1 ? written : withExifOrientation(written, frame.orientation(), true);
    }

    /**
     * {@code jpeg} with an APP1 EXIF segment carrying only an orientation, inserted after ImageIO's JFIF APP0 as a
     * camera writing both puts it, or straight after SOI ahead of the APP0, which ImageIO's own metadata refuses.
     */
    static byte[] withExifOrientation(byte[] jpeg, int orientation, boolean afterJfif) {
        // "Exif\0\0", then a little-endian TIFF header, then one directory with one SHORT entry and no next IFD.
        ByteBuffer exif = ByteBuffer.allocate(6 + 8 + 2 + 12 + 4).order(ByteOrder.LITTLE_ENDIAN);
        exif.put(new byte[] {'E', 'x', 'i', 'f', 0, 0});
        exif.put(new byte[] {'I', 'I'}).putShort((short) 42).putInt(8);
        exif.putShort((short) 1);
        exif.putShort((short) 274).putShort((short) 3).putInt(1).putShort((short) orientation).putShort((short) 0);
        exif.putInt(0);
        byte[] payload = exif.array();
        int length = payload.length + 2;
        // SOI, then (if asked) the APP0 segment, whose length follows its marker.
        int insertAt = afterJfif ? 4 + ((jpeg[4] & 0xFF) << 8 | jpeg[5] & 0xFF) : 2;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(jpeg, 0, insertAt);
        out.write(0xFF);
        out.write(0xE1);
        out.write(length >> 8);
        out.write(length & 0xFF);
        out.write(payload, 0, payload.length);
        out.write(jpeg, insertAt, jpeg.length - insertAt);
        return out.toByteArray();
    }

    /** A three-page PDF, portrait, landscape and portrait, each page saying its number. */
    static byte[] pdf() {
        return pdf(PDF_PAGES);
    }

    static byte[] pdf(int pages) {
        try (PDDocument document = new PDDocument()) {
            PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            for (int number = 1; number <= pages; number++) {
                PDRectangle a4 = PDRectangle.A4;
                PDPage page = new PDPage(number % 2 == 0 ? new PDRectangle(a4.getHeight(), a4.getWidth()) : a4);
                document.addPage(page);
                try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                    content.beginText();
                    content.setFont(font, 24);
                    content.newLineAtOffset(72, page.getMediaBox().getHeight() - 96);
                    content.showText("Synthetic preview fixture, page " + number);
                    content.endText();
                }
            }
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            document.save(bytes);
            return bytes.toByteArray();
        } catch (IOException unwritable) {
            throw new UncheckedIOException(unwritable);
        }
    }

    /** What each committed fixture holds, by name. */
    static byte[] generate(String name) {
        return switch (name) {
            case PDF -> pdf();
            case "photo.jpeg" -> encoded("jpeg", PHOTO.frames().getFirst());
            case "photo-exif-6.jpeg" -> encoded("jpeg", PHOTO_ON_ITS_SIDE.frames().getFirst());
            case "scan.png" -> encoded("png", SCAN.frames().getFirst());
            case "two-frames.tiff" -> tiff(TWO_FRAMES);
            case "three-frames.tiff" -> tiff(THREE_FRAMES);
            default -> throw new IllegalArgumentException(name);
        };
    }

    static final List<String> NAMES = List.of(PDF, "photo.jpeg", "photo-exif-6.jpeg", "scan.png",
                                              "two-frames.tiff", "three-frames.tiff");
}
