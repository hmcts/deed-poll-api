package uk.gov.hmcts.reform.deedpoll.domain.documents.preview;

/**
 * A JPEG's EXIF {@code Orientation}, which is how a phone photograph taken on its side says so.
 *
 * <p>Read from the bytes, by walking the segments ahead of the image data to the first {@code Exif} APP1, and then
 * the one tag out of its first image directory. ImageIO's JPEG metadata is not used: it refuses a file whose APP1
 * comes before its JFIF APP0, which would turn a readable photograph into an invalid document. Every length and
 * offset is checked against the array, so a damaged or hostile segment is drawn as stored, never an exception and
 * never a read past the end.
 */
final class ExifOrientation {

    private static final int APP1 = 0xE1;
    private static final int START_OF_SCAN = 0xDA;
    private static final byte[] EXIF = {'E', 'x', 'i', 'f', 0, 0};
    private static final int ENTRY = 12;

    private ExifOrientation() {
    }

    static Orientation of(byte[] jpeg) {
        if (jpeg.length < 4 || (jpeg[0] & 0xFF) != 0xFF || (jpeg[1] & 0xFF) != 0xD8) {
            return Orientation.TOP_LEFT;
        }
        int at = 2;
        while (at + 4 <= jpeg.length && (jpeg[at] & 0xFF) == 0xFF) {
            int marker = jpeg[at + 1] & 0xFF;
            if (marker == 0xFF) {
                at++;
                continue;
            }
            if (marker == START_OF_SCAN || marker == 0xD9) {
                break;
            }
            int length = unsigned16(jpeg, at + 2, false);
            if (length < 2 || at + 2 + length > jpeg.length) {
                break;
            }
            int segment = at + 4;
            if (marker == APP1 && startsWithExif(jpeg, segment, length - 2)) {
                return Orientation.of(read(jpeg, segment + EXIF.length, segment + length - 2));
            }
            at += 2 + length;
        }
        return Orientation.TOP_LEFT;
    }

    /**
     * The orientation in the TIFF structure from {@code base} to {@code end}; 1 if there is none.
     */
    static int read(byte[] segment, int base, int end) {
        if (end < base + 8) {
            return 1;
        }
        boolean little;
        if (segment[base] == 'I' && segment[base + 1] == 'I') {
            little = true;
        } else if (segment[base] == 'M' && segment[base + 1] == 'M') {
            little = false;
        } else {
            return 1;
        }
        if (unsigned16(segment, base + 2, little) != 42) {
            return 1;
        }
        long directory = base + unsigned32(segment, base + 4, little);
        if (directory + 2 > end) {
            return 1;
        }
        int entries = unsigned16(segment, (int) directory, little);
        for (int index = 0; index < entries; index++) {
            long entry = directory + 2 + (long) index * ENTRY;
            if (entry + ENTRY > end) {
                return 1;
            }
            int at = (int) entry;
            // Tag, type SHORT (3), one value: the value sits in the first two bytes of the value field.
            if (unsigned16(segment, at, little) == Orientation.TAG && unsigned16(segment, at + 2, little) == 3
                && unsigned32(segment, at + 4, little) == 1) {
                return unsigned16(segment, at + 8, little);
            }
        }
        return 1;
    }

    private static boolean startsWithExif(byte[] bytes, int from, int length) {
        if (length < EXIF.length) {
            return false;
        }
        for (int index = 0; index < EXIF.length; index++) {
            if (bytes[from + index] != EXIF[index]) {
                return false;
            }
        }
        return true;
    }

    private static int unsigned16(byte[] bytes, int at, boolean little) {
        int first = bytes[at] & 0xFF;
        int second = bytes[at + 1] & 0xFF;
        return little ? first | second << 8 : first << 8 | second;
    }

    private static long unsigned32(byte[] bytes, int at, boolean little) {
        long high = unsigned16(bytes, little ? at + 2 : at, little);
        long low = unsigned16(bytes, little ? at : at + 2, little);
        return high << 16 | low;
    }
}
