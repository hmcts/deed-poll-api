package uk.gov.hmcts.reform.deedpoll.domain.documents.preview;

import org.apache.pdfbox.util.Matrix;

/**
 * Which way up a stored image is meant to be seen: the TIFF/EXIF {@code Orientation} tag (274), values 1 to 8.
 *
 * <p>ImageIO decodes the stored rows as they are and ignores the tag, so a scanner's or a phone's rotated frame
 * would be previewed on its side. The tag says where the stored first row and first column belong on the visual
 * page; {@link #placement} turns that into the PDF matrix that draws the image's unit square into a box, which
 * rotates or mirrors the drawing instead of allocating a turned copy of the decoded frame.
 */
enum Orientation {
    // Each member gives the visual (X, Y), Y upwards, of the image's unit-square point (u, v), where u runs along
    // the stored row from its first column and v = 1 is the stored first row, as X = xu·u + xv·v + x0 and
    // Y = yu·u + yv·v + y0, given in PDF matrix order (xu, yu, xv, yv, x0, y0). The tag's own wording is in the
    // comment.
    TOP_LEFT(1, 1, 0, 0, 1, 0, 0),        // row 0 at the top, column 0 on the left: as stored
    TOP_RIGHT(2, -1, 0, 0, 1, 1, 0),      // row 0 at the top, column 0 on the right
    BOTTOM_RIGHT(3, -1, 0, 0, -1, 1, 1),  // row 0 at the bottom, column 0 on the right: turned half round
    BOTTOM_LEFT(4, 1, 0, 0, -1, 0, 1),    // row 0 at the bottom, column 0 on the left
    LEFT_TOP(5, 0, -1, -1, 0, 1, 1),      // row 0 on the left, column 0 at the top
    RIGHT_TOP(6, 0, -1, 1, 0, 0, 1),      // row 0 on the right, column 0 at the top: a quarter turn clockwise
    RIGHT_BOTTOM(7, 0, 1, 1, 0, 0, 0),    // row 0 on the right, column 0 at the bottom
    LEFT_BOTTOM(8, 0, 1, -1, 0, 1, 0);    // row 0 on the left, column 0 at the bottom: a quarter turn back

    static final int TAG = 274;

    private final int value;
    private final float xu;
    private final float yu;
    private final float xv;
    private final float yv;
    private final float x0;
    private final float y0;

    Orientation(int value, float xu, float yu, float xv, float yv, float x0, float y0) {
        this.value = value;
        this.xu = xu;
        this.yu = yu;
        this.xv = xv;
        this.yv = yv;
        this.x0 = x0;
        this.y0 = y0;
    }

    /**
     * The tag's member; an absent or out-of-range value is drawn as stored, which is what the tag's default says.
     */
    static Orientation of(int value) {
        for (Orientation orientation : values()) {
            if (orientation.value == value) {
                return orientation;
            }
        }
        return TOP_LEFT;
    }

    int value() {
        return value;
    }

    /** Whether the stored rows run up and down the visual page, so its width is the stored height. */
    boolean swapsAxes() {
        return yu != 0;
    }

    /**
     * Draws the image's unit square, the way up this orientation names, into the box at ({@code x}, {@code y})
     * of the visual {@code width} and {@code height}.
     */
    Matrix placement(float x, float y, float width, float height) {
        return new Matrix(width * xu, height * yu, width * xv, height * yv, x + width * x0, y + height * y0);
    }
}
