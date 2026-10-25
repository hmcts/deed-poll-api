package uk.gov.hmcts.reform.deedpoll.domain.documents.preview;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import javax.imageio.stream.MemoryCacheImageInputStream;

import static org.assertj.core.api.Assertions.assertThat;

class TiffOrientationTest {

    private static final int SHORT = 3;
    private static final int LONG = 4;
    private static final int WIDTH = 256;

    @Test
    @DisplayName("reads every value in either byte order, past the entries ahead of it")
    void byteOrders() throws IOException {
        for (ByteOrder order : new ByteOrder[] {ByteOrder.LITTLE_ENDIAN, ByteOrder.BIG_ENDIAN}) {
            for (int value = 1; value <= 8; value++) {
                byte[] tiff = tiff(order, List.of(List.of(entry(WIDTH, LONG, 1, 16), orientation(value))));
                assertThat(of(tiff, 0).value()).as("%s %d", order, value).isEqualTo(value);
            }
        }
    }

    @Test
    @DisplayName("the last Orientation of the right type and count wins, and any other is ignored")
    void lastRightEntryWins() throws IOException {
        for (ByteOrder order : new ByteOrder[] {ByteOrder.LITTLE_ENDIAN, ByteOrder.BIG_ENDIAN}) {
            assertThat(of(tiff(order, List.of(List.of(orientation(3), orientation(6)))), 0))
                .isEqualTo(Orientation.of(6));
            assertThat(of(tiff(order, List.of(List.of(orientation(6), entry(Orientation.TAG, LONG, 1, 3)))), 0))
                .isEqualTo(Orientation.of(6));
            assertThat(of(tiff(order, List.of(List.of(entry(Orientation.TAG, SHORT, 2, 6)))), 0))
                .isEqualTo(Orientation.TOP_LEFT);
        }
    }

    @Test
    @DisplayName("each frame has its own orientation, found by walking the directory chain")
    void laterFrames() throws IOException {
        for (ByteOrder order : new ByteOrder[] {ByteOrder.LITTLE_ENDIAN, ByteOrder.BIG_ENDIAN}) {
            byte[] tiff = tiff(order, List.of(List.of(entry(WIDTH, LONG, 1, 16), orientation(8)),
                                              List.of(entry(WIDTH, LONG, 1, 16)),
                                              List.of(orientation(6), entry(WIDTH, LONG, 1, 16))));
            assertThat(of(tiff, 0)).isEqualTo(Orientation.of(8));
            assertThat(of(tiff, 1)).isEqualTo(Orientation.TOP_LEFT);
            assertThat(of(tiff, 2)).isEqualTo(Orientation.of(6));
        }
    }

    @Test
    @DisplayName("a chain that ends before the frame asked about is drawn as stored")
    void chainEndsEarly() throws IOException {
        byte[] tiff = tiff(ByteOrder.LITTLE_ENDIAN, List.of(List.of(orientation(6))));
        assertThat(of(tiff, 1)).isEqualTo(Orientation.TOP_LEFT);
        assertThat(of(tiff, 3)).isEqualTo(Orientation.TOP_LEFT);
    }

    private static Orientation of(byte[] tiff, int index) throws IOException {
        try (MemoryCacheImageInputStream input = new MemoryCacheImageInputStream(new ByteArrayInputStream(tiff))) {
            return TiffOrientation.of(input, index);
        }
    }

    private static int[] orientation(int value) {
        return entry(Orientation.TAG, SHORT, 1, value);
    }

    private static int[] entry(int tag, int type, int count, int value) {
        return new int[] {tag, type, count, value};
    }

    /**
     * A TIFF header and a chain of {@code directories}, each entry's value inline: a SHORT in the first two bytes of
     * the value field, anything else as a LONG.
     */
    private static byte[] tiff(ByteOrder order, List<List<int[]>> directories) {
        int size = 8 + directories.stream().mapToInt(entries -> 2 + entries.size() * 12 + 4).sum();
        ByteBuffer tiff = ByteBuffer.allocate(size).order(order);
        tiff.put(order == ByteOrder.LITTLE_ENDIAN ? new byte[] {'I', 'I'} : new byte[] {'M', 'M'});
        tiff.putShort((short) 42).putInt(8);
        for (int index = 0; index < directories.size(); index++) {
            List<int[]> entries = directories.get(index);
            tiff.putShort((short) entries.size());
            for (int[] entry : entries) {
                tiff.putShort((short) entry[0]).putShort((short) entry[1]).putInt(entry[2]);
                if (entry[1] == SHORT) {
                    tiff.putShort((short) entry[3]).putShort((short) 0);
                } else {
                    tiff.putInt(entry[3]);
                }
            }
            tiff.putInt(index == directories.size() - 1 ? 0 : tiff.position() + 4);
        }
        return tiff.array();
    }
}
