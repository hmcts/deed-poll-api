package uk.gov.hmcts.reform.deedpoll.domain.documents.preview;

import java.io.IOException;
import java.nio.ByteOrder;
import javax.imageio.stream.ImageInputStream;

/**
 * Finds a TIFF frame's directory the way the JDK's reader does: a byte order other than {@code MM} is
 * little-endian, the magic number is not insisted on, and the chain is followed one next-directory offset at a time.
 */
final class TiffDirectories {

    /** The size of one directory entry: tag, type, count and the four-byte value or offset. */
    static final int ENTRY = 12;

    private static final int BIG_ENDIAN = 0x4D4D;

    private TiffDirectories() {
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
}
