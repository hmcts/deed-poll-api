package uk.gov.hmcts.reform.deedpoll.domain.documents.preview;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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
        byte[] farOffset = sideways.clone();
        // The IFD offset (bytes 4-7 of the TIFF header, after FFD8 FFE1 len "Exif\0\0" "II" 2A00).
        farOffset[4 + 6 + 4] = (byte) 0xFF;
        farOffset[4 + 6 + 7] = (byte) 0x7F;
        assertThat(ExifOrientation.of(farOffset)).isEqualTo(Orientation.TOP_LEFT);
        byte[] manyEntries = sideways.clone();
        manyEntries[4 + 6 + 8] = (byte) 0xFF;
        manyEntries[4 + 6 + 9] = (byte) 0xFF;
        assertThat(ExifOrientation.of(manyEntries)).isIn(Orientation.RIGHT_TOP, Orientation.TOP_LEFT);
        byte[] longSegment = sideways.clone();
        longSegment[4] = (byte) 0xFF;
        assertThat(ExifOrientation.of(longSegment)).isEqualTo(Orientation.TOP_LEFT);
        assertThat(ExifOrientation.of(new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE1, 0, 0}))
            .isEqualTo(Orientation.TOP_LEFT);
    }
}
