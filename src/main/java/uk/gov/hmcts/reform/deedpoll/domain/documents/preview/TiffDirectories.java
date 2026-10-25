package uk.gov.hmcts.reform.deedpoll.domain.documents.preview;

import java.io.IOException;
import java.nio.ByteOrder;
import javax.imageio.stream.ImageInputStream;

/**
 * Finds a TIFF frame's directory, and reads its entries, the way the JDK's reader does: a byte order other than
 * {@code MM} is little-endian, the magic number is not insisted on, the chain is followed one next-directory offset at
 * a time, and an entry whose type the JDK does not know is eight bytes long rather than twelve.
 */
final class TiffDirectories {

    /** The size of one directory entry: tag, type, count and the four-byte value or offset. */
    static final int ENTRY = 12;

    private static final int BIG_ENDIAN = 0x4D4D;
    /**
     * BYTE to IFD, the types {@code TIFFTag.getSizeOfType} knows.
     */
    private static final int FIRST_TYPE = 1;
    private static final int LAST_TYPE = 13;

    private TiffDirectories() {
    }

    /**
     * One directory entry, its value field at {@code valueField}.
     */
    @FunctionalInterface
    interface EntryVisitor {
        void visit(int tag, int type, long count, long valueField) throws IOException;
    }

    /**
     * The offset of frame {@code index}'s directory, with {@code tiff}'s byte order set to the file's, or -1 if the
     * chain ends before it.
     */
    static long find(ImageInputStream tiff, int index) throws IOException {
        tiff.seek(0);
        tiff.setByteOrder(tiff.readUnsignedShort() == BIG_ENDIAN ? ByteOrder.BIG_ENDIAN : ByteOrder.LITTLE_ENDIAN);
        tiff.readUnsignedShort();
        long directory = tiff.readUnsignedInt();
        for (int skipped = 0; skipped < index; skipped++) {
            // The JDK's walk between directories steps twelve bytes an entry, whatever the type.
            tiff.seek(directory);
            int entries = tiff.readUnsignedShort();
            tiff.seek(directory + 2 + (long) entries * ENTRY);
            directory = tiff.readUnsignedInt();
            if (entries == 0 || directory == 0) {
                return -1;
            }
        }
        return directory;
    }

    /**
     * Visits every entry of the directory at {@code directory} that the JDK reads. Inside a directory the JDK skips an
     * entry of unknown type after its tag and type, without its count, so that entry takes eight bytes and every entry
     * after it is read four bytes on; stepping twelve would read different entries from the ones the JDK decodes.
     * A visitor may move the stream; the next entry is found from the value field regardless.
     */
    static void forEachEntry(ImageInputStream tiff, long directory, EntryVisitor visitor) throws IOException {
        tiff.seek(directory);
        int entries = tiff.readUnsignedShort();
        long next = directory + 2;
        for (int entry = 0; entry < entries; entry++) {
            tiff.seek(next);
            int tag = tiff.readUnsignedShort();
            int type = tiff.readUnsignedShort();
            if (type < FIRST_TYPE || type > LAST_TYPE) {
                next += 8;
                continue;
            }
            long count = tiff.readUnsignedInt();
            long valueField = tiff.getStreamPosition();
            next = valueField + 4;
            visitor.visit(tag, type, count, valueField);
        }
    }
}
