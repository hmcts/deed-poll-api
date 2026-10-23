package uk.gov.hmcts.reform.deedpoll.domain.documents.preview;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class ExifOrientationTest {

    private static final byte[] UPRIGHT = DocumentPreviewFixtures.read("photo.jpeg");

    @Test
    @DisplayName("finds the orientation after the JFIF segment and ahead of it")
    void found() {
        for (int value = 1; value <= 8; value++) {
            assertThat(ExifOrientation.of(DocumentPreviewFixtures.withExifOrientation(UPRIGHT, value, true)).value())
                .isEqualTo(value);
            assertThat(ExifOrientation.of(DocumentPreviewFixtures.withExifOrientation(UPRIGHT, value, false))
                           .value()).isEqualTo(value);
        }
        assertThat(ExifOrientation.of(UPRIGHT)).isEqualTo(Orientation.TOP_LEFT);
    }

    @Test
    @DisplayName("reads either byte order, past the entries a camera writes ahead of the orientation")
    void byteOrdersAndLeadingEntries() {
        for (ByteOrder order : new ByteOrder[] {ByteOrder.LITTLE_ENDIAN, ByteOrder.BIG_ENDIAN}) {
            for (int value = 1; value <= 8; value++) {
                byte[] tiff = tiff(order, value);
                assertThat(ExifOrientation.read(tiff, 0, tiff.length)).as("%s %d", order, value).isEqualTo(value);
            }
        }
    }

    @Test
    @DisplayName("an out-of-range value is drawn as stored")
    void outOfRange() {
        assertThat(ExifOrientation.of(DocumentPreviewFixtures.withExifOrientation(UPRIGHT, 9, true)))
            .isEqualTo(Orientation.TOP_LEFT);
    }

    @Test
    @DisplayName("a damaged or hostile segment is drawn as stored, never an exception")
    void hostile() {
        byte[] sideways = DocumentPreviewFixtures.withExifOrientation(UPRIGHT, 6, false);
        // Every truncation of the segments ahead of the image data.
        for (int length = 0; length < 80; length++) {
            ExifOrientation.of(Arrays.copyOf(sideways, length));
        }
        // The TIFF header starts after FFD8, FFE1, the two length bytes and "Exif\0\0": "II", 2A00, then the
        // directory's offset, then (at that offset, 8) the directory's entry count.
        int tiff = 2 + 2 + 2 + 6;
        byte[] farOffset = sideways.clone();
        farOffset[tiff + 7] = (byte) 0x7F;
        assertThat(ExifOrientation.of(farOffset)).isEqualTo(Orientation.TOP_LEFT);
        byte[] manyEntries = sideways.clone();
        manyEntries[tiff + 8] = (byte) 0xFF;
        manyEntries[tiff + 9] = (byte) 0xFF;
        // The first entry is the orientation, so it is found before the declared count could run past the end.
        assertThat(ExifOrientation.of(manyEntries)).isEqualTo(Orientation.RIGHT_TOP);
        byte[] longSegment = sideways.clone();
        longSegment[4] = (byte) 0xFF;
        assertThat(ExifOrientation.of(longSegment)).isEqualTo(Orientation.TOP_LEFT);
        assertThat(ExifOrientation.of(new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE1, 0, 0}))
            .isEqualTo(Orientation.TOP_LEFT);
    }

    /**
     * A TIFF header and one directory of three entries, Make and Model (ASCII, by offset) ahead of the orientation,
     * as a camera writes them, in {@code order}.
     */
    private static byte[] tiff(ByteOrder order, int orientation) {
        ByteBuffer tiff = ByteBuffer.allocate(8 + 2 + 3 * 12 + 4).order(order);
        tiff.put(order == ByteOrder.LITTLE_ENDIAN ? new byte[] {'I', 'I'} : new byte[] {'M', 'M'});
        tiff.putShort((short) 42).putInt(8);
        tiff.putShort((short) 3);
        tiff.putShort((short) 271).putShort((short) 2).putInt(6).putInt(0x1000);
        tiff.putShort((short) 272).putShort((short) 2).putInt(8).putInt(0x2000);
        tiff.putShort((short) Orientation.TAG).putShort((short) 3).putInt(1).putShort((short) orientation)
            .putShort((short) 0);
        tiff.putInt(0);
        return tiff.array();
    }
}
