package uk.gov.hmcts.reform.deedpoll.domain.documents.preview;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.IIOException;
import javax.imageio.stream.ImageInputStream;

/**
 * Holds the JPEG stream inside each strip or tile of a JPEG-compressed TIFF frame to the strip or tile it fills.
 *
 * <p>Every packet of a Compression 7 frame is a JPEG of its own, and its start-of-frame header declares its own
 * width and height. The JDK hands each packet to its JPEG reader without comparing those with the frame, and for a
 * progressive JPEG the native decoder allocates and clears a coefficient buffer for the whole declared image, outside
 * the heap, before anything is decoded: a 16 by 16 frame of a few hundred bytes whose strip declares 40,000 by
 * 40,000 takes gigabytes. So each packet's header is read, marker by marker, and its declared size held to the
 * packet's region rounded up to whole 16-pixel MCUs, before the frame is decoded.
 *
 * <p>Old-style JPEG (Compression 6, withdrawn in 1995) is refused outright: the JDK may decode it from a stream the
 * directory points at rather than the packets, so there is no one header to check. Every Compression entry is
 * looked at, whatever its type, so that two contradictory entries cannot hide a JPEG frame; a frame that is not JPEG
 * is left alone. The directory is read entry by entry as the JDK reads it ({@link TiffDirectories#forEachEntry}), the
 * packets are found where the JDK finds them (TileOffsets, StripOffsets, or failing both JPEGInterchangeFormat), and
 * JPEGTables, which the JDK puts ahead of every packet, may carry tables but never a frame header or a scan.
 */
final class TiffJpegPackets {

    private static final int COMPRESSION = 259;
    private static final int OLD_JPEG = 6;
    private static final int JPEG = 7;
    private static final int STRIP_OFFSETS = 273;
    private static final int TILE_OFFSETS = 324;
    private static final int JPEG_TABLES = 347;
    private static final int JPEG_INTERCHANGE_FORMAT = 513;
    private static final int BYTE = 1;
    private static final int UNDEFINED = 7;
    private static final int SHORT = 3;
    private static final int LONG = 4;
    private static final int MCU = 16;
    /** The JDK decodes at most four components; anything more is not a document it can draw. */
    private static final int MAX_COMPONENTS = 4;
    /** Far more segments than a packet's header carries ahead of its frame header (tables and APPn markers). */
    private static final int MAX_SEGMENTS = 256;

    private TiffJpegPackets() {
    }

    /**
     * Refuses frame {@code index} if it is old-style JPEG, or JPEG with a packet declaring more than
     * {@code regionWidth} by {@code regionHeight}.
     *
     * @throws PreviewError {@link PreviewFailure#INVALID_DOCUMENT} for old-style JPEG or an unreadable header,
     *     {@link PreviewFailure#LIMIT_EXCEEDED} for a packet declaring more than its region
     */
    static void require(ImageInputStream tiff, int index, int regionWidth, int regionHeight, PreviewBudget budget)
        throws IOException {
        long directory = TiffDirectories.find(tiff, index);
        if (directory < 0) {
            // The reader found this frame, so a walk that does not is a file read two ways: not drawn at all.
            throw new IIOException("directory chain disagrees with the reader");
        }
        boolean[] jpeg = {false};
        List<long[]> offsetFields = new ArrayList<>();
        List<long[]> interchange = new ArrayList<>();
        List<long[]> tables = new ArrayList<>();
        TiffDirectories.forEachEntry(tiff, directory, (tag, type, count, valueField) -> {
            if (tag == COMPRESSION) {
                int compression = type == LONG ? (int) tiff.readUnsignedInt() : tiff.readUnsignedShort();
                if (compression == OLD_JPEG) {
                    throw new IIOException("old-style JPEG compression");
                }
                jpeg[0] |= compression == JPEG;
            } else if (tag == STRIP_OFFSETS || tag == TILE_OFFSETS) {
                offsetFields.add(new long[] {type, count, valueField});
            } else if (tag == JPEG_INTERCHANGE_FORMAT) {
                interchange.add(new long[] {type, count, valueField});
            } else if (tag == JPEG_TABLES) {
                tables.add(new long[] {type, count, valueField});
            }
        });
        if (!jpeg[0]) {
            return;
        }
        if (offsetFields.isEmpty()) {
            // The JDK falls back to JPEGInterchangeFormat for a frame with neither offsets field.
            offsetFields.addAll(interchange);
        }
        for (long[] field : tables) {
            requireTables(tiff, field[0], field[1], field[2]);
        }
        long maxWidth = roundUp(regionWidth);
        long maxHeight = roundUp(regionHeight);
        for (long[] field : offsetFields) {
            int type = (int) field[0];
            long count = field[1];
            if (type != SHORT && type != LONG) {
                throw new IIOException("packet offsets of an unexpected type");
            }
            int size = type == SHORT ? 2 : 4;
            tiff.seek(field[2]);
            long values = count * size <= 4 ? field[2] : tiff.readUnsignedInt();
            for (long packet = 0; packet < count; packet++) {
                budget.checkpoint();
                tiff.seek(values + packet * size);
                long offset = type == SHORT ? tiff.readUnsignedShort() : tiff.readUnsignedInt();
                requireHeader(tiff, offset, maxWidth, maxHeight);
            }
        }
    }

    /**
     * Holds a JPEGTables field to tables alone. The JDK copies its bytes, up to the last EOI, ahead of each packet
     * with the packet's SOI removed, so a frame header there would be the one the decoder sizes its buffers from and
     * the packet's own would never be read. The segments must end exactly where the copy does, so that the packet
     * is read from its first marker, as {@link #requireHeader} reads it.
     */
    private static void requireTables(ImageInputStream tiff, long type, long count, long valueField)
        throws IOException {
        if (type != BYTE && type != UNDEFINED) {
            throw new IIOException("JPEG tables of an unexpected type");
        }
        tiff.seek(valueField);
        long start = count <= 4 ? valueField : tiff.readUnsignedInt();
        long length = tiff.length();
        if (length < 0 || start + count > length) {
            throw new IIOException("JPEG tables outside the file");
        }
        byte[] tables = new byte[(int) count];
        tiff.seek(start);
        tiff.readFully(tables);
        int end = tables.length;
        // The JDK's own search, from the end back to the second byte.
        for (int at = tables.length - 2; at > 0; at--) {
            if ((tables[at] & 0xFF) == 0xFF && (tables[at + 1] & 0xFF) == 0xD9) {
                end = at;
                break;
            }
        }
        if (end < 2 || (tables[0] & 0xFF) != 0xFF || (tables[1] & 0xFF) != 0xD8) {
            throw new IIOException("JPEG tables are not a JPEG stream");
        }
        int at = 2;
        while (at < end) {
            if ((tables[at++] & 0xFF) != 0xFF) {
                throw new IIOException("JPEG marker expected");
            }
            while (at < end && (tables[at] & 0xFF) == 0xFF) {
                at++;
            }
            if (at >= end) {
                throw new IIOException("JPEG tables end inside a marker");
            }
            int marker = tables[at++] & 0xFF;
            if (marker == 0x01 || (marker >= 0xD0 && marker <= 0xD7)) {
                continue;
            }
            if (marker == 0xD8 || marker == 0xD9 || marker == 0xDA || isFrameHeader(marker) || at + 2 > end) {
                throw new IIOException("JPEG tables carry more than tables");
            }
            int segment = (tables[at] & 0xFF) << 8 | tables[at + 1] & 0xFF;
            if (segment < 2 || at + segment > end) {
                throw new IIOException("JPEG tables segment length");
            }
            at += segment;
        }
    }

    /**
     * Reads the JPEG at {@code offset} up to its frame header and holds the declared size to the region.
     */
    private static void requireHeader(ImageInputStream tiff, long offset, long maxWidth, long maxHeight)
        throws IOException {
        tiff.seek(offset);
        if (tiff.readUnsignedByte() != 0xFF || tiff.readUnsignedByte() != 0xD8) {
            throw new IIOException("packet is not a JPEG stream");
        }
        for (int segment = 0; segment < MAX_SEGMENTS; segment++) {
            if (tiff.readUnsignedByte() != 0xFF) {
                throw new IIOException("JPEG marker expected");
            }
            int marker = tiff.readUnsignedByte();
            while (marker == 0xFF) {
                marker = tiff.readUnsignedByte();
            }
            if (marker == 0x01 || (marker >= 0xD0 && marker <= 0xD7)) {
                continue;
            }
            if (marker == 0xD9 || marker == 0xDA || marker == 0xD8) {
                throw new IIOException("JPEG scan or end before its frame header");
            }
            int length = bigEndianShort(tiff);
            if (length < 2) {
                throw new IIOException("JPEG segment length");
            }
            long next = tiff.getStreamPosition() + length - 2;
            if (isFrameHeader(marker)) {
                tiff.readUnsignedByte();
                int height = bigEndianShort(tiff);
                int width = bigEndianShort(tiff);
                int components = tiff.readUnsignedByte();
                if (components == 0 || components > MAX_COMPONENTS) {
                    throw new IIOException("JPEG components");
                }
                if (width > maxWidth || height > maxHeight) {
                    throw new PreviewError(PreviewFailure.LIMIT_EXCEEDED);
                }
                return;
            }
            tiff.seek(next);
        }
        throw new IIOException("no JPEG frame header");
    }

    /** A JPEG is big-endian whatever the TIFF around it is, so not the stream's own byte order. */
    private static int bigEndianShort(ImageInputStream tiff) throws IOException {
        return tiff.readUnsignedByte() << 8 | tiff.readUnsignedByte();
    }

    /** SOF0 to SOF15, which are every frame header: not DHT (C4), JPG (C8) or DAC (CC). */
    private static boolean isFrameHeader(int marker) {
        return marker >= 0xC0 && marker <= 0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC;
    }

    private static long roundUp(int pixels) {
        return ((long) pixels + MCU - 1) / MCU * MCU;
    }
}
