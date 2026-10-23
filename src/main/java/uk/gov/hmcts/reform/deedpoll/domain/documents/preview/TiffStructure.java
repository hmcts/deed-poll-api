package uk.gov.hmcts.reform.deedpoll.domain.documents.preview;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.imageio.IIOException;

/**
 * Holds a TIFF's directory structure to the file's own bytes before any ImageIO code sees the file.
 *
 * <p>The JDK's reader materialises a frame's whole directory the first time it is asked anything about the frame,
 * reading every entry's values wherever they lie. Entries may name the same bytes, so a check that each field fits
 * inside the file bounds nothing: sixty-four entries naming one 64 KiB payload read four megabytes from a file of
 * sixty-six kilobytes, and within the upload limit that scales to terabytes of reads and allocations with no budget
 * checkpoint between them. So this walks the structure with plain bounded reads first, and every byte a directory
 * table or an out-of-line field value occupies must be its own: no two of them may overlap, none may run past the end
 * of the file, and together they may cover no more bytes than the file holds. Whatever the JDK then reads from the
 * directories is therefore read once. Image data is aliased the same way: the JDK's decompressors allocate and read
 * each strip's or tile's declared byte count, so a million strips naming one large payload would read it a million
 * times inside one frame's decode, well inside the pixel limits. So every StripByteCounts and TileByteCounts value is
 * added to the same total, as is JPEGInterchangeFormatLength, which the JDK falls back to, and a directory naming
 * strip, tile or JPEGInterchangeFormat offsets must give byte counts rather than leave the JDK to work them out.
 * The work is therefore bounded by the file's size.
 *
 * <p>Beyond that it insists on what every real TIFF already is: {@code II} or {@code MM} with the magic number 42
 * (BigTIFF, 43, is refused: the JDK cannot read it), at least one directory, no more directories in the chain than
 * the page limit and no loop in it, every directory of 1 to {@link #MAX_ENTRIES} entries, and every entry of one of
 * the thirteen types TIFF 6.0 and the JDK know, so that every entry is the twelve bytes both sides step. SubIFDs are
 * refused. The EXIF, GPS and Interoperability pointers are bounded identically rather than refused, because a
 * scanner or a phone may write an EXIF directory and the JDK follows these pointers once its default read parameter
 * is in force: each is followed once, at most {@link #MAX_POINTER_DEPTH} deep, and its directory and values are held
 * to the same rules. Nothing is allocated from a declared count: the largest read is one directory table, after its
 * entry count has been checked.
 *
 * <p>JPEG-compressed TIFFs, old-style (Compression 6) and JPEG (7), are refused here too, deliberately: the JDK's
 * JPEG-in-TIFF path, with its per-packet headers, JPEGTables and JPEGInterchangeFormat fallback, is its most complex
 * and least trusted, and is not previewed rather than defended. The original still downloads. Allowing it again is a
 * self-contained change, made with its own checks, once a business case for it exists.
 */
final class TiffStructure {

    /**
     * Entries a directory may hold. TIFF 6.0 defines 74 tags, many mutually exclusive; the JDK's writer puts about
     * fifteen in a frame, and scanners, fax software and Photoshop twenty to forty with their XMP, ICC and IPTC
     * blocks. Sixty-four is comfortably above any of them, and keeps one directory table under 800 bytes.
     */
    static final int MAX_ENTRIES = 64;

    /** A frame's EXIF directory, and the Interoperability directory inside that: nothing deeper exists. */
    static final int MAX_POINTER_DEPTH = 2;

    private static final int HEADER = 8;
    private static final int ENTRY = 12;
    private static final int LITTLE_ENDIAN = 0x4949;
    private static final int BIG_ENDIAN = 0x4D4D;
    private static final int MAGIC = 42;
    private static final int SHORT = 3;
    private static final int LONG = 4;
    private static final int IFD = 13;
    private static final int COMPRESSION = 259;
    private static final int STRIP_OFFSETS = 273;
    private static final int STRIP_BYTE_COUNTS = 279;
    private static final int TILE_OFFSETS = 324;
    private static final int TILE_BYTE_COUNTS = 325;
    private static final int JPEG_INTERCHANGE_FORMAT = 513;
    private static final int JPEG_INTERCHANGE_FORMAT_LENGTH = 514;
    private static final int OLD_JPEG = 6;
    private static final int JPEG = 7;
    private static final int SUB_IFDS = 330;
    private static final int EXIF_IFD = 34_665;
    private static final int GPS_IFD = 34_853;
    private static final int INTEROPERABILITY_IFD = 40_965;
    /** A value of at most four bytes sits in the entry itself, and occupies nothing outside the table. */
    private static final int INLINE = 4;
    /** How much of an out-of-line byte-count array is read at once. */
    private static final int CHUNK = 64 * 1024;

    private final FileChannel file;
    private final long length;
    private final PreviewBudget budget;
    private final List<long[]> occupied = new ArrayList<>();
    private final Set<Long> directories = new HashSet<>();
    /**
     * Big-endian until the header says otherwise: {@code II} and {@code MM} read the same either way.
     */
    private ByteOrder order = ByteOrder.BIG_ENDIAN;
    private long covered;

    private TiffStructure(FileChannel file, PreviewBudget budget) throws IOException {
        this.file = file;
        this.length = file.size();
        this.budget = budget;
    }

    /**
     * Refuses {@code tiff} unless its structure is as described above.
     *
     * @throws IIOException for a structure the preview does not read, in a fixed sentence naming the rule
     * @throws PreviewError {@link PreviewFailure#LIMIT_EXCEEDED} for more directories than {@code maxPages}
     */
    static void require(Path tiff, int maxPages, PreviewBudget budget) throws IOException {
        try (FileChannel file = FileChannel.open(tiff, StandardOpenOption.READ)) {
            new TiffStructure(file, budget).check(maxPages);
        }
    }

    private void check(int maxPages) throws IOException {
        if (length < HEADER) {
            throw new IIOException("TIFF header outside the file");
        }
        ByteBuffer header = read(0, HEADER);
        int byteOrder = header.getShort(0) & 0xFFFF;
        if (byteOrder != LITTLE_ENDIAN && byteOrder != BIG_ENDIAN) {
            throw new IIOException("TIFF byte order");
        }
        order = byteOrder == LITTLE_ENDIAN ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN;
        header.order(order);
        if ((header.getShort(2) & 0xFFFF) != MAGIC) {
            throw new IIOException("TIFF magic number");
        }
        occupy(0, HEADER);
        long next = Integer.toUnsignedLong(header.getInt(4));
        if (next == 0) {
            throw new IIOException("TIFF with no directory");
        }
        int frames = 0;
        while (next != 0) {
            budget.checkpoint();
            if (++frames > maxPages) {
                throw new PreviewError(PreviewFailure.LIMIT_EXCEEDED);
            }
            next = directory(next, 0);
        }
        requireDisjoint();
    }

    /**
     * Checks the directory at {@code offset} and every directory its pointers name, and answers its next-directory
     * offset.
     */
    private long directory(long offset, int depth) throws IOException {
        if (offset < HEADER || offset > length - 2) {
            throw new IIOException("TIFF directory outside the file");
        }
        if (!directories.add(offset)) {
            // Named twice, by the chain or by a pointer: either way the walk would come back to where it has been.
            throw new IIOException("TIFF directory chain loops");
        }
        int entries = read(offset, 2).getShort(0) & 0xFFFF;
        if (entries == 0) {
            throw new IIOException("TIFF directory with no entries");
        }
        if (entries > MAX_ENTRIES) {
            throw new IIOException("TIFF directory with too many entries");
        }
        long size = 2 + (long) entries * ENTRY + 4;
        if (size > length - offset) {
            throw new IIOException("TIFF directory outside the file");
        }
        occupy(offset, size);
        ByteBuffer table = read(offset + 2, (int) size - 2);
        List<Long> pointers = new ArrayList<>();
        Set<Integer> pointerTags = new HashSet<>();
        boolean offsets = false;
        boolean byteCounts = false;
        for (int entry = 0; entry < entries; entry++) {
            int at = entry * ENTRY;
            int tag = table.getShort(at) & 0xFFFF;
            int type = table.getShort(at + 2) & 0xFFFF;
            long count = Integer.toUnsignedLong(table.getInt(at + 4));
            long value = Integer.toUnsignedLong(table.getInt(at + 8));
            long bytes = Math.multiplyExact(count, sizeOf(type));
            if (tag == SUB_IFDS) {
                throw new IIOException("TIFF SubIFDs");
            }
            if (tag == COMPRESSION) {
                requireNotJpeg(type, count, table, at + 8);
            }
            if (tag == EXIF_IFD || tag == GPS_IFD || tag == INTEROPERABILITY_IFD) {
                if (type != LONG && type != IFD || count != 1) {
                    throw new IIOException("TIFF directory pointer of an unexpected type or count");
                }
                if (depth >= MAX_POINTER_DEPTH || !pointerTags.add(tag)) {
                    throw new IIOException("TIFF directory pointers too deep or repeated");
                }
                pointers.add(value);
            } else if (bytes > INLINE) {
                if (value > length || bytes > length - value) {
                    throw new IIOException("TIFF field value outside the file");
                }
                occupy(value, bytes);
            }
            // The JDK takes the first of TileOffsets, StripOffsets and JPEGInterchangeFormat a directory holds, and
            // the first of TileByteCounts, StripByteCounts and JPEGInterchangeFormatLength, whichever kind the
            // offsets were, and estimates counts only when it finds none. So any offsets need any counts, and every
            // count is added, the JPEG pair's included: it is read whatever the compression.
            offsets |= tag == STRIP_OFFSETS || tag == TILE_OFFSETS || tag == JPEG_INTERCHANGE_FORMAT;
            if (tag == STRIP_BYTE_COUNTS || tag == TILE_BYTE_COUNTS || tag == JPEG_INTERCHANGE_FORMAT_LENGTH) {
                byteCounts = true;
                imageData(type, count, bytes, table, at + 8, value);
            }
        }
        if (offsets && !byteCounts) {
            throw new IIOException("TIFF image data without byte counts");
        }
        for (long pointer : pointers) {
            budget.checkpoint();
            // The JDK reads a pointed-to directory's entries and not its next-directory offset.
            directory(pointer, depth + 1);
        }
        return Integer.toUnsignedLong(table.getInt(entries * ENTRY));
    }

    /**
     * Refuses a Compression entry that is not one SHORT or LONG, and old-style JPEG and JPEG. Every Compression entry
     * in every directory is looked at, so a second or mistyped entry cannot leave one the JDK reads unchecked.
     */
    private static void requireNotJpeg(int type, long count, ByteBuffer table, int valueAt) throws IIOException {
        if (type != SHORT && type != LONG || count != 1) {
            throw new IIOException("TIFF Compression of an unexpected type or count");
        }
        long compression = type == SHORT ? table.getShort(valueAt) & 0xFFFF
            : Integer.toUnsignedLong(table.getInt(valueAt));
        if (compression == OLD_JPEG || compression == JPEG) {
            throw new IIOException("TIFF JPEG compression");
        }
    }

    /**
     * Adds every strip's or tile's byte count to the bytes taken, so that however the offsets alias each other, the
     * decompressors together read no more than the file holds. An out-of-line array is already known to lie inside
     * the file and within its size, and is read a chunk at a time.
     */
    private void imageData(int type, long count, long bytes, ByteBuffer table, int valueAt, long value)
        throws IOException {
        if (type != SHORT && type != LONG) {
            throw new IIOException("TIFF byte counts of an unexpected type");
        }
        int size = (int) sizeOf(type);
        if (bytes <= INLINE) {
            for (int index = 0; index < count; index++) {
                addImageData(table, valueAt + index * size, type);
            }
            return;
        }
        for (long done = 0; done < bytes; done += CHUNK) {
            budget.checkpoint();
            ByteBuffer chunk = read(value + done, (int) Math.min(CHUNK, bytes - done));
            for (int at = 0; at < chunk.limit(); at += size) {
                addImageData(chunk, at, type);
            }
        }
    }

    private void addImageData(ByteBuffer values, int at, int type) throws IIOException {
        covered += type == SHORT ? values.getShort(at) & 0xFFFF : Integer.toUnsignedLong(values.getInt(at));
        if (covered > length) {
            throw new IIOException("TIFF image data cover more than the file");
        }
    }

    /**
     * Records {@code size} bytes at {@code start} as taken, refusing as soon as everything taken exceeds the file.
     */
    private void occupy(long start, long size) throws IIOException {
        covered += size;
        if (covered > length) {
            throw new IIOException("TIFF fields cover more than the file");
        }
        occupied.add(new long[] {start, start + size});
    }

    private void requireDisjoint() throws IIOException {
        occupied.sort(Comparator.comparingLong(region -> region[0]));
        for (int index = 1; index < occupied.size(); index++) {
            if (occupied.get(index)[0] < occupied.get(index - 1)[1]) {
                throw new IIOException("TIFF fields overlap");
            }
        }
    }

    /**
     * The bytes of one value of {@code type}: BYTE to IFD, the types TIFF 6.0 and the JDK's reader know.
     */
    private static long sizeOf(int type) throws IIOException {
        return switch (type) {
            case 1, 2, 6, 7 -> 1;
            case 3, 8 -> 2;
            case 4, 9, 11, 13 -> 4;
            case 5, 10, 12 -> 8;
            default -> throw new IIOException("TIFF field of an unknown type");
        };
    }

    /**
     * {@code size} bytes at {@code position}, already known to lie inside the file.
     */
    private ByteBuffer read(long position, int size) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(size);
        while (buffer.hasRemaining()) {
            if (file.read(buffer, position + buffer.position()) < 0) {
                throw new EOFException();
            }
        }
        return buffer.flip().order(order);
    }
}
