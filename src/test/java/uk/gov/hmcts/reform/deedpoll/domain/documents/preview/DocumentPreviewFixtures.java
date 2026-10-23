package uk.gov.hmcts.reform.deedpoll.domain.documents.preview;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.openxml4j.opc.PackagePart;
import org.apache.poi.openxml4j.opc.PackagingURIHelper;
import org.apache.poi.poifs.crypt.EncryptionInfo;
import org.apache.poi.poifs.crypt.EncryptionMode;
import org.apache.poi.poifs.crypt.Encryptor;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;
import org.apache.poi.util.Units;
import org.apache.poi.xwpf.usermodel.BreakType;
import org.apache.poi.xwpf.usermodel.Document;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
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
    /**
     * {@link #SCAN}'s image with a compressed {@code zTXt} and a compressed {@code iTXt} chunk after its IHDR.
     */
    static final Fixture TEXT_CHUNKS = new Fixture("text-chunks.png", SCAN.frames());
    /**
     * What each of {@link #TEXT_CHUNKS}' text chunks inflates to: zeros, a thousand times their stored size.
     */
    static final int TEXT_CHUNK_INFLATED_BYTES = 8 * 1024 * 1024;
    /**
     * A 16 by 16 grey TIFF declaring one 8192 by 8192 tile, about 67 million pixels, whose only packet is
     * {@link #NOT_DEFLATE}: a decoder would size its tile raster from the declaration before failing on the data.
     */
    static final String OVERSIZED_TILE = "oversized-tile.tiff";
    static final int OVERSIZED_TILE_SIDE = 8192;
    /** A packet labelled Deflate that is not a zlib stream, so decoding it fails rather than drawing anything. */
    static final byte[] NOT_DEFLATE = "not a deflate stream".getBytes(StandardCharsets.US_ASCII);
    /** TIFF Compression values: none, and Adobe Deflate. */
    static final int UNCOMPRESSED = 1;
    static final int DEFLATE = 8;
    static final String PDF = "three-pages.pdf";
    static final int PDF_PAGES = 3;

    /** A Word document of three pages, each ending in a hard page break but the last. */
    static final String MULTI_PAGE_DOCX = "three-pages.docx";
    static final int MULTI_PAGE_DOCX_PAGES = 3;
    /** A Word document holding a table and an embedded PNG picture, with a page break between them. */
    static final String TABLE_AND_IMAGE_DOCX = "table-and-image.docx";
    static final int TABLE_AND_IMAGE_DOCX_PAGES = 2;
    static final int TABLE_ROWS = 4;
    static final int TABLE_COLUMNS = 3;

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
     * A one-frame, 8-bit grey TIFF, every packet {@code shade}, split into {@code tileWidth} by {@code tileLength}
     * tiles. Written field by field rather than by ImageIO, so a declaration can say what no writer would.
     */
    static byte[] tiledGrey(int width, int height, long tileWidth, long tileLength, int shade) {
        long across = (width + tileWidth - 1) / tileWidth;
        long down = (height + tileLength - 1) / tileLength;
        byte[] tile = new byte[Math.toIntExact(tileWidth * tileLength)];
        Arrays.fill(tile, (byte) shade);
        return greyTiff(width, height, UNCOMPRESSED, List.of(new long[] {322, tileWidth}, new long[] {323, tileLength}),
                        true, Collections.nCopies(Math.toIntExact(across * down), tile));
    }

    /**
     * One {@code tileWidth} by {@code tileLength} tile declaration over a single {@code compression} packet.
     */
    static byte[] tiledGrey(int width, int height, long tileWidth, long tileLength, int compression, byte[] packet) {
        return greyTiff(width, height, compression,
                        List.of(new long[] {322, tileWidth}, new long[] {323, tileLength}), true, List.of(packet));
    }

    /**
     * A one-frame, 8-bit grey TIFF, every pixel {@code shade}, in strips of {@code rowsPerStrip} rows ({@code null}
     * for no RowsPerStrip field at all, which means the whole height).
     */
    static byte[] strippedGrey(int width, int height, Long rowsPerStrip, int shade) {
        long rows = rowsPerStrip == null || rowsPerStrip >= height ? height : rowsPerStrip;
        List<byte[]> strips = new ArrayList<>();
        for (long top = 0; top < height; top += rows) {
            byte[] strip = new byte[Math.toIntExact(width * Math.min(rows, height - top))];
            Arrays.fill(strip, (byte) shade);
            strips.add(strip);
        }
        return greyTiff(width, height, UNCOMPRESSED, rowsPerStrip(rowsPerStrip), false, strips);
    }

    /**
     * A RowsPerStrip declaration over a single {@code compression} packet.
     */
    static byte[] strippedGrey(int width, int height, long rowsPerStrip, int compression, byte[] packet) {
        return greyTiff(width, height, compression, rowsPerStrip(rowsPerStrip), false, List.of(packet));
    }

    private static List<long[]> rowsPerStrip(Long rows) {
        return rows == null ? List.of() : List.<long[]>of(new long[] {278, rows});
    }

    /**
     * A little-endian, one-directory TIFF: BlackIsZero, one 8-bit sample, {@code layout}'s LONG fields, and the
     * packets after the directory with their offsets and byte counts as strips or tiles.
     */
    private static byte[] greyTiff(int width, int height, int compression, List<long[]> layout, boolean tiled,
                                   List<byte[]> packets) {
        Map<Integer, long[]> fields = new TreeMap<>();
        // Each field: TIFF type (3 SHORT, 4 LONG), then its values.
        fields.put(256, new long[] {4, width});
        fields.put(257, new long[] {4, height});
        fields.put(258, new long[] {3, 8});
        fields.put(259, new long[] {3, compression});
        fields.put(262, new long[] {3, 1});
        fields.put(277, new long[] {3, 1});
        for (long[] field : layout) {
            fields.put((int) field[0], new long[] {4, field[1]});
        }
        int entries = fields.size() + 2;
        int directoryEnd = 8 + 2 + entries * 12 + 4;
        int countsAt = directoryEnd + packets.size() * 4;
        int dataAt = countsAt + packets.size() * 4;
        long[] offsets = new long[packets.size()];
        long[] counts = new long[packets.size()];
        long next = dataAt;
        for (int index = 0; index < packets.size(); index++) {
            offsets[index] = next;
            counts[index] = packets.get(index).length;
            next += counts[index];
        }
        fields.put(tiled ? 324 : 273, offsets);
        fields.put(tiled ? 325 : 279, counts);
        ByteBuffer tiff = ByteBuffer.allocate(Math.toIntExact(next)).order(ByteOrder.LITTLE_ENDIAN);
        tiff.put(new byte[] {'I', 'I'}).putShort((short) 42).putInt(8).putShort((short) entries);
        for (Map.Entry<Integer, long[]> field : fields.entrySet()) {
            long[] value = field.getValue();
            boolean packetList = value == offsets || value == counts;
            tiff.putShort(field.getKey().shortValue());
            if (packetList) {
                tiff.putShort((short) 4).putInt(value.length);
                tiff.putInt(value.length == 1 ? (int) value[0] : value == offsets ? directoryEnd : countsAt);
            } else if (value[0] == 3) {
                tiff.putShort((short) 3).putInt(1).putShort((short) value[1]).putShort((short) 0);
            } else {
                tiff.putShort((short) 4).putInt(1).putInt((int) value[1]);
            }
        }
        tiff.putInt(0);
        if (packets.size() > 1) {
            for (long offset : offsets) {
                tiff.putInt((int) offset);
            }
            for (long count : counts) {
                tiff.putInt((int) count);
            }
        }
        tiff.position(dataAt);
        packets.forEach(tiff::put);
        return tiff.array();
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

    /**
     * {@code png} with a {@code zTXt} chunk keyed {@code Comment} and an {@code iTXt} chunk keyed {@code Description}
     * inserted straight after its IHDR, each holding {@code inflatedBytes} of zeros deflated. {@code truncated} cuts
     * each deflated stream in half, so a reader that inflates either fails, and one that skips them does not notice.
     */
    static byte[] withCompressedText(byte[] png, int inflatedBytes, boolean truncated) {
        byte[] deflated = deflatedZeros(inflatedBytes);
        if (truncated) {
            deflated = Arrays.copyOf(deflated, deflated.length / 2);
        }
        ByteArrayOutputStream ztxt = new ByteArrayOutputStream();
        ztxt.writeBytes("Comment".getBytes(StandardCharsets.ISO_8859_1));
        // The keyword's terminator, then compression method 0 (deflate).
        ztxt.writeBytes(new byte[] {0, 0});
        ztxt.writeBytes(deflated);
        ByteArrayOutputStream itxt = new ByteArrayOutputStream();
        itxt.writeBytes("Description".getBytes(StandardCharsets.ISO_8859_1));
        // The keyword's terminator, compressed, method 0, then an empty language tag and translated keyword.
        itxt.writeBytes(new byte[] {0, 1, 0, 0, 0});
        itxt.writeBytes(deflated);
        // The signature is 8 bytes and the IHDR chunk 25: length, type, 13 bytes of data and the CRC.
        int afterIhdr = 8 + 25;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(png, 0, afterIhdr);
        pngChunk(out, "zTXt", ztxt.toByteArray());
        pngChunk(out, "iTXt", itxt.toByteArray());
        out.write(png, afterIhdr, png.length - afterIhdr);
        return out.toByteArray();
    }

    private static byte[] deflatedZeros(int length) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        Deflater deflater = new Deflater(Deflater.BEST_COMPRESSION);
        try (DeflaterOutputStream out = new DeflaterOutputStream(bytes, deflater)) {
            byte[] zeros = new byte[64 * 1024];
            for (int written = 0; written < length; written += zeros.length) {
                out.write(zeros, 0, Math.min(zeros.length, length - written));
            }
        } catch (IOException unwritable) {
            throw new UncheckedIOException(unwritable);
        } finally {
            deflater.end();
        }
        return bytes.toByteArray();
    }

    private static void pngChunk(ByteArrayOutputStream out, String type, byte[] data) {
        byte[] name = type.getBytes(StandardCharsets.ISO_8859_1);
        CRC32 crc = new CRC32();
        crc.update(name);
        crc.update(data);
        ByteBuffer chunk = ByteBuffer.allocate(4 + name.length + data.length + 4);
        chunk.putInt(data.length).put(name).put(data).putInt((int) crc.getValue());
        out.writeBytes(chunk.array());
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

    /**
     * {@link #MULTI_PAGE_DOCX}: "Synthetic Word fixture, page N", with a page break after each page but the last.
     */
    static byte[] multiPageDocx() {
        try (XWPFDocument document = new XWPFDocument()) {
            for (int number = 1; number <= MULTI_PAGE_DOCX_PAGES; number++) {
                XWPFRun run = document.createParagraph().createRun();
                run.setText("Synthetic Word fixture, page " + number);
                if (number < MULTI_PAGE_DOCX_PAGES) {
                    run.addBreak(BreakType.PAGE);
                }
            }
            return docx(document);
        } catch (IOException unwritable) {
            throw new UncheckedIOException(unwritable);
        }
    }

    /**
     * {@link #TABLE_AND_IMAGE_DOCX}: a table on the first page, the {@link #SCAN} picture on the second.
     */
    static byte[] tableAndImageDocx() {
        try (XWPFDocument document = new XWPFDocument()) {
            document.createParagraph().createRun().setText("Synthetic Word fixture with a table");
            XWPFTable table = document.createTable(TABLE_ROWS, TABLE_COLUMNS);
            for (int row = 0; row < TABLE_ROWS; row++) {
                for (int column = 0; column < TABLE_COLUMNS; column++) {
                    table.getRow(row).getCell(column).setText("r" + row + "c" + column);
                }
            }
            XWPFParagraph pictured = document.createParagraph();
            pictured.setPageBreak(true);
            Frame frame = SCAN.frames().getFirst();
            try (InputStream picture = new ByteArrayInputStream(encoded("png", frame))) {
                pictured.createRun().addPicture(picture, Document.PICTURE_TYPE_PNG, "scan.png",
                                                Units.toEMU(frame.width() / 2.0), Units.toEMU(frame.height() / 2.0));
            }
            return docx(document);
        } catch (IOException | org.apache.poi.openxml4j.exceptions.InvalidFormatException unwritable) {
            throw new IllegalStateException(unwritable);
        }
    }

    /**
     * {@code docx} with a VBA project part added, the macro-enabled feature an upload refuses.
     */
    static byte[] withMacros(byte[] docx) {
        try (OPCPackage document = OPCPackage.open(new ByteArrayInputStream(docx))) {
            PackagePart part = document.createPart(PackagingURIHelper.createPartName("/word/vbaProject.bin"),
                                                   "application/vnd.ms-office.vbaProject");
            try (OutputStream out = part.getOutputStream()) {
                out.write(new byte[] {1, 2, 3, 4});
            }
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            document.save(bytes);
            return bytes.toByteArray();
        } catch (IOException | org.apache.poi.openxml4j.exceptions.InvalidFormatException unwritable) {
            throw new IllegalStateException(unwritable);
        }
    }

    /**
     * {@code docx} password-protected as Word does it: the package encrypted inside an OLE container.
     */
    static byte[] encrypted(byte[] docx) {
        try (POIFSFileSystem container = new POIFSFileSystem()) {
            Encryptor encryptor = new EncryptionInfo(EncryptionMode.standard).getEncryptor();
            encryptor.confirmPassword("synthetic");
            try (OPCPackage document = OPCPackage.open(new ByteArrayInputStream(docx));
                 OutputStream out = encryptor.getDataStream(container)) {
                document.save(out);
            }
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            container.writeFilesystem(bytes);
            return bytes.toByteArray();
        } catch (IOException | GeneralSecurityException | org.apache.poi.openxml4j.exceptions.InvalidFormatException
                     unwritable) {
            throw new IllegalStateException(unwritable);
        }
    }

    private static byte[] docx(XWPFDocument document) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        document.write(bytes);
        return bytes.toByteArray();
    }

    /**
     * A one-page PDF whose catalog, page tree and page sit in a compressed object stream behind {@code padding} zero
     * bytes: a few hundred KB on disk that a parser must inflate to {@code padding} bytes in memory to reach the
     * page tree. Built on demand rather than committed; the bytes are the same every time.
     */
    static byte[] objectStreamBomb(int padding) {
        String[] objects = {
            "<< /Type /Catalog /Pages 2 0 R >>",
            "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] >>",
        };
        StringBuilder index = new StringBuilder();
        StringBuilder body = new StringBuilder();
        for (int number = 0; number < objects.length; number++) {
            index.append(number + 1).append(' ').append(padding + body.length()).append(' ');
            body.append(objects[number]).append('\n');
        }
        byte[] header = index.toString().getBytes(StandardCharsets.US_ASCII);
        ByteArrayOutputStream deflated = new ByteArrayOutputStream();
        Deflater deflater = new Deflater(Deflater.BEST_COMPRESSION);
        try (DeflaterOutputStream out = new DeflaterOutputStream(deflated, deflater)) {
            out.write(header);
            byte[] zeros = new byte[64 * 1024];
            for (int written = 0; written < padding; written += zeros.length) {
                out.write(zeros, 0, Math.min(zeros.length, padding - written));
            }
            out.write(body.toString().getBytes(StandardCharsets.US_ASCII));
        } catch (IOException unwritable) {
            throw new UncheckedIOException(unwritable);
        } finally {
            deflater.end();
        }
        byte[] stream = deflated.toByteArray();

        ByteArrayOutputStream pdf = new ByteArrayOutputStream();
        pdf.writeBytes("%PDF-1.7\n".getBytes(StandardCharsets.US_ASCII));
        final int objectStreamAt = pdf.size();
        pdf.writeBytes(("4 0 obj\n<< /Type /ObjStm /N " + objects.length + " /First " + header.length
            + " /Filter /FlateDecode /Length " + stream.length + " >>\nstream\n")
                           .getBytes(StandardCharsets.US_ASCII));
        pdf.writeBytes(stream);
        pdf.writeBytes("\nendstream\nendobj\n".getBytes(StandardCharsets.US_ASCII));
        final int xrefAt = pdf.size();
        // Six entries of /W [1 4 2]: the free head, three objects in stream 4, then 4 and 5 themselves.
        ByteBuffer xref = ByteBuffer.allocate(6 * 7);
        xref.put((byte) 0).putInt(0).putShort((short) 0xFFFF);
        for (int number = 0; number < objects.length; number++) {
            xref.put((byte) 2).putInt(4).putShort((short) number);
        }
        xref.put((byte) 1).putInt(objectStreamAt).putShort((short) 0);
        xref.put((byte) 1).putInt(xrefAt).putShort((short) 0);
        pdf.writeBytes(("5 0 obj\n<< /Type /XRef /Size 6 /Root 1 0 R /W [1 4 2] /Length " + xref.capacity()
            + " >>\nstream\n").getBytes(StandardCharsets.US_ASCII));
        pdf.writeBytes(xref.array());
        pdf.writeBytes(("\nendstream\nendobj\nstartxref\n" + xrefAt + "\n%%EOF\n").getBytes(StandardCharsets.US_ASCII));
        return pdf.toByteArray();
    }

    /** What each committed fixture holds, by name. */
    static byte[] generate(String name) {
        return switch (name) {
            case PDF -> pdf();
            case "photo.jpeg" -> encoded("jpeg", PHOTO.frames().getFirst());
            case "photo-exif-6.jpeg" -> encoded("jpeg", PHOTO_ON_ITS_SIDE.frames().getFirst());
            case "scan.png" -> encoded("png", SCAN.frames().getFirst());
            case "text-chunks.png" -> withCompressedText(encoded("png", TEXT_CHUNKS.frames().getFirst()),
                                                         TEXT_CHUNK_INFLATED_BYTES, false);
            case "two-frames.tiff" -> tiff(TWO_FRAMES);
            case "three-frames.tiff" -> tiff(THREE_FRAMES);
            case OVERSIZED_TILE -> tiledGrey(16, 16, OVERSIZED_TILE_SIDE, OVERSIZED_TILE_SIDE, DEFLATE, NOT_DEFLATE);
            case MULTI_PAGE_DOCX -> multiPageDocx();
            case TABLE_AND_IMAGE_DOCX -> tableAndImageDocx();
            default -> throw new IllegalArgumentException(name);
        };
    }

    static final List<String> NAMES = List.of(PDF, "photo.jpeg", "photo-exif-6.jpeg", "scan.png",
                                              "text-chunks.png",
                                              "two-frames.tiff", "three-frames.tiff", OVERSIZED_TILE,
                                              MULTI_PAGE_DOCX,
                                              TABLE_AND_IMAGE_DOCX);
}
