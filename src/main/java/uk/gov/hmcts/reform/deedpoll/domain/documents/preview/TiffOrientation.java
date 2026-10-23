package uk.gov.hmcts.reform.deedpoll.domain.documents.preview;

import java.io.IOException;
import javax.imageio.stream.ImageInputStream;

/**
 * A TIFF frame's {@code Orientation}, read from its directory one entry at a time.
 *
 * <p>ImageIO's metadata is not used: the JDK's TIFF reader answers {@code getImageMetadata} by rebuilding every
 * value of every field as a DOM node of its own, hundreds of bytes each, so a TIFF of a few megabytes that is nothing
 * but strip offsets fills the heap before any limit here is looked at. This finds the directory through
 * {@link TiffDirectories}, reads only each entry's own bytes, and keeps nothing but the one value. Like the
 * JDK, the last {@code Orientation} entry of the right type and count wins. Only ever asked about a frame the
 * reader has already found, so a chain that ends early is drawn as stored.
 */
final class TiffOrientation {

    private static final int SHORT = 3;

    private TiffOrientation() {
    }

    static Orientation of(ImageInputStream tiff, int index) throws IOException {
        long directory = TiffDirectories.find(tiff, index);
        if (directory < 0) {
            return Orientation.TOP_LEFT;
        }
        int[] value = {Orientation.TOP_LEFT.value()};
        TiffDirectories.forEachEntry(tiff, directory, (tag, type, count, valueField) -> {
            if (tag == Orientation.TAG && type == SHORT && count == 1) {
                // One SHORT sits in the first two bytes of the value field.
                value[0] = tiff.readUnsignedShort();
            }
        });
        return Orientation.of(value[0]);
    }
}
