package uk.gov.hmcts.reform.deedpoll.domain.documents.preview;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import uk.gov.hmcts.reform.deedpoll.domain.ForbiddenError;
import uk.gov.hmcts.reform.deedpoll.domain.NotFoundError;
import uk.gov.hmcts.reform.deedpoll.domain.documents.UploadPolicy;

import java.awt.Color;
import java.awt.Transparency;
import java.awt.color.ColorSpace;
import java.awt.image.BufferedImage;
import java.awt.image.ComponentColorModel;
import java.awt.image.DataBuffer;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.stream.Stream;
import java.util.zip.CRC32;
import javax.imageio.IIOException;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.FileImageInputStream;
import javax.imageio.stream.ImageInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.Assertions.fail;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static uk.gov.hmcts.reform.deedpoll.domain.documents.preview.DocumentPreviewFixtures.BLUE;
import static uk.gov.hmcts.reform.deedpoll.domain.documents.preview.DocumentPreviewFixtures.DEFLATE;
import static uk.gov.hmcts.reform.deedpoll.domain.documents.preview.DocumentPreviewFixtures.Frame;
import static uk.gov.hmcts.reform.deedpoll.domain.documents.preview.DocumentPreviewFixtures.GREEN;
import static uk.gov.hmcts.reform.deedpoll.domain.documents.preview.DocumentPreviewFixtures.NOT_DEFLATE;
import static uk.gov.hmcts.reform.deedpoll.domain.documents.preview.DocumentPreviewFixtures.RED;
import static uk.gov.hmcts.reform.deedpoll.domain.documents.preview.DocumentPreviewFixtures.YELLOW;

/**
 * PDF, JPEG, PNG and TIFF previews made from real bytes and read back with a real PDF parser and rasteriser.
 */
class PreviewRendererTest {

    /** TIFF Compression values: old-style JPEG, and JPEG. */
    private static final int OLD_JPEG = 6;
    private static final int JPEG = 7;
    private static final float A4_SHORT = PDRectangle.A4.getWidth();
    private static final float A4_LONG = PDRectangle.A4.getHeight();

    @TempDir
    Path scratch;

    private ConversionCapacity capacity = new ConversionCapacity(3, Duration.ofSeconds(5));

    @Nested
    @DisplayName("a PDF")
    class Pdf {

        @Test
        @DisplayName("passes through byte for byte, unparsed and uncounted, and takes no permit")
        void passthrough() throws IOException {
            byte[] original = DocumentPreviewFixtures.read(DocumentPreviewFixtures.PDF);
            byte[] copy = original.clone();
            try (RenderedPreview preview = unparsed(() -> render(PreviewFormat.PDF, original))) {
                assertThat(capacity.available()).isEqualTo(3);
                assertThat(preview.pages()).isEmpty();
                byte[] served = bytes(preview);
                assertThat(sha256(served)).isEqualTo(sha256(copy));
                assertThat(preview.size()).isEqualTo(original.length);
            }
            assertThat(original).isEqualTo(copy);
            assertScratchEmpty();
        }

        @Test
        @DisplayName("over the page limit is still served: the viewer counts a PDF's pages, not this service")
        void pagesAreTheViewers() throws IOException {
            PreviewRenderer two = renderer(limits(2, PreviewProperties.MAX_FRAME_PIXELS,
                                                  PreviewProperties.MAX_OUTPUT_BYTES));
            byte[] three = DocumentPreviewFixtures.pdf(3);
            try (RenderedPreview preview = unparsed(() -> two.render(PreviewFormat.PDF, new TrackedSource(three),
                                                                     budget()))) {
                assertThat(preview.pages()).isEmpty();
                assertThat(bytes(preview)).isEqualTo(three);
            }
        }

        @Test
        @DisplayName("a compressed object-stream bomb is served as stored, never inflated")
        void objectStreamBomb() throws IOException {
            byte[] bomb = DocumentPreviewFixtures.objectStreamBomb(64 * 1024 * 1024);
            try (RenderedPreview preview = unparsed(() -> render(PreviewFormat.PDF, bomb))) {
                assertThat(bytes(preview)).isEqualTo(bomb);
            }
            assertScratchEmpty();
        }

        @Test
        @DisplayName("an encrypted PDF or one with no pages is served as stored; PDF.js is given no password")
        void unparsedContent() throws IOException {
            byte[] encrypted;
            try (PDDocument document = Loader.loadPDF(DocumentPreviewFixtures.pdf())) {
                document.protect(new StandardProtectionPolicy("owner", "", new AccessPermission()));
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                document.save(out);
                encrypted = out.toByteArray();
            }
            byte[] empty;
            try (PDDocument none = new PDDocument()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                none.save(out);
                empty = out.toByteArray();
            }
            byte[] trailing = concat(DocumentPreviewFixtures.pdf(), new byte[PdfRenderer.EOF_WINDOW - 6]);
            for (byte[] original : List.of(encrypted, empty, trailing)) {
                try (RenderedPreview preview = unparsed(() -> render(PreviewFormat.PDF, original))) {
                    assertThat(bytes(preview)).isEqualTo(original);
                }
            }
        }

        @Test
        @DisplayName("a truncated body, bytes that are not a PDF, and %%EOF too far from the end are invalid")
        void damaged() {
            byte[] whole = DocumentPreviewFixtures.pdf();
            for (byte[] refused : List.of(Arrays.copyOf(whole, 40), Arrays.copyOf(whole, whole.length - 10),
                                          DocumentPreviewFixtures.read("scan.png"),
                                          "%PDF-1.7 and then nothing".getBytes(StandardCharsets.US_ASCII),
                                          concat(" ".getBytes(StandardCharsets.US_ASCII), whole),
                                          concat(whole, new byte[PdfRenderer.EOF_WINDOW]),
                                          new byte[0])) {
                unparsed(() -> {
                    assertFailure(PreviewFormat.PDF, refused, PreviewFailure.INVALID_DOCUMENT);
                    return null;
                });
            }
        }

        @Test
        @DisplayName("%%EOF ending on the window's last byte passes, and one byte further out is invalid")
        void eofWindowEdge() throws IOException {
            byte[] minimal = "%PDF-1.7\n%%EOF".getBytes(StandardCharsets.US_ASCII);
            byte[] atTheEdge = concat(minimal, new byte[PdfRenderer.EOF_WINDOW - 5]);
            try (RenderedPreview preview = unparsed(() -> render(PreviewFormat.PDF, atTheEdge))) {
                assertThat(bytes(preview)).isEqualTo(atTheEdge);
            }
            byte[] outside = concat(minimal, new byte[PdfRenderer.EOF_WINDOW - 4]);
            unparsed(() -> {
                assertFailure(PreviewFormat.PDF, outside, PreviewFailure.INVALID_DOCUMENT);
                return null;
            });
        }
    }

    @Nested
    @DisplayName("a JPEG or PNG")
    class SingleImage {

        @Test
        @DisplayName("a JPEG becomes one landscape page embedding the stored JPEG unrecompressed")
        void jpeg() throws IOException {
            byte[] original = DocumentPreviewFixtures.read("photo.jpeg");
            byte[] copy = original.clone();
            try (RenderedPreview preview = render(PreviewFormat.JPEG, original);
                 PDDocument document = Loader.loadPDF(bytes(preview))) {
                assertThat(preview.pages()).hasValue(1);
                assertThat(document.getNumberOfPages()).isEqualTo(1);
                assertLandscape(document.getPage(0));
                PDImageXObject image = onlyImage(document.getPage(0));
                assertThat(image.getCOSObject().getItem(COSName.FILTER)).isEqualTo(COSName.DCT_DECODE);
                try (InputStream raw = image.getCOSObject().createRawInputStream()) {
                    assertThat(raw.readAllBytes()).isEqualTo(copy);
                }
                assertMarker(document, 0, BLUE, Corner.TOP_LEFT);
            }
            assertThat(original).isEqualTo(copy);
            assertScratchEmpty();
        }

        @Test
        @DisplayName("a PNG becomes one portrait page, its aspect ratio kept")
        void png() throws IOException {
            try (RenderedPreview preview = render(PreviewFormat.PNG, DocumentPreviewFixtures.read("scan.png"));
                 PDDocument document = Loader.loadPDF(bytes(preview))) {
                assertThat(document.getNumberOfPages()).isEqualTo(1);
                assertPortrait(document.getPage(0));
                assertMarker(document, 0, RED, Corner.TOP_LEFT);
                // 480 by 640 fitted to A4's width: drawn 595 wide, so 793.7 tall, centred, and nowhere cropped.
                BufferedImage page = raster(document, 0);
                assertThat(colourAt(page, 0.5, 0.01)).isEqualTo(Color.WHITE);
                assertThat(colourAt(page, 0.5, 0.05)).satisfies(colour -> assertNear(colour, RED));
            }
        }

        @Test
        @DisplayName("a PNG whose compressed text chunks inflate to megabytes is previewed like any other")
        void committedTextChunks() throws IOException {
            try (RenderedPreview preview = render(PreviewFormat.PNG,
                                                  DocumentPreviewFixtures.read("text-chunks.png"));
                 PDDocument document = Loader.loadPDF(bytes(preview))) {
                assertThat(document.getNumberOfPages()).isEqualTo(1);
                assertPortrait(document.getPage(0));
                assertMarker(document, 0, RED, Corner.TOP_LEFT);
            }
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"truecolour", "palette"})
        @DisplayName("a PNG's text chunks are never inflated: deflate streams cut in half do not stop the preview")
        void textChunksNeverInflated(String colours) throws IOException {
            BufferedImage image = "palette".equals(colours)
                ? new BufferedImage(480, 640, BufferedImage.TYPE_BYTE_INDEXED)
                : DocumentPreviewFixtures.image(DocumentPreviewFixtures.SCAN.frames().getFirst());
            byte[] png = DocumentPreviewFixtures.withCompressedText(encodedPng(image),
                                                                    DocumentPreviewFixtures.TEXT_CHUNK_INFLATED_BYTES,
                                                                    true);
            // A reader that reads metadata inflates them, and so refuses this file.
            ImageReader inflating = ImageIO.getImageReadersByFormatName("png").next();
            try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(png))) {
                inflating.setInput(input, false, false);
                assertThatThrownBy(() -> inflating.read(0)).isInstanceOf(IIOException.class);
            } finally {
                inflating.dispose();
            }
            try (RenderedPreview preview = render(PreviewFormat.PNG, png)) {
                assertThat(preview.pages()).hasValue(1);
            }
        }

        @ParameterizedTest(name = "EXIF APP1 {0} the JFIF APP0")
        @ValueSource(booleans = {true, false})
        @DisplayName("a photograph taken on its side is turned the way its EXIF orientation says")
        void exifOrientation(boolean afterJfif) throws IOException {
            byte[] upright = DocumentPreviewFixtures.encoded("jpeg", new Frame(640, 480, GREEN, null, 1));
            byte[] sideways = DocumentPreviewFixtures.withExifOrientation(upright, 6, afterJfif);
            try (RenderedPreview preview = render(PreviewFormat.JPEG, sideways);
                 PDDocument document = Loader.loadPDF(bytes(preview))) {
                assertPortrait(document.getPage(0));
                assertMarker(document, 0, GREEN, Corner.TOP_RIGHT);
            }
        }

        @Test
        @DisplayName("the committed sideways photograph is previewed upright")
        void committedSideways() throws IOException {
            try (RenderedPreview preview = render(PreviewFormat.JPEG,
                                                  DocumentPreviewFixtures.read("photo-exif-6.jpeg"));
                 PDDocument document = Loader.loadPDF(bytes(preview))) {
                assertPortrait(document.getPage(0));
                assertMarker(document, 0, GREEN, Corner.TOP_RIGHT);
            }
        }

        @Test
        @DisplayName("bytes of another format, or damaged bytes, are an invalid document")
        void mismatchedOrDamaged() {
            byte[] jpeg = DocumentPreviewFixtures.read("photo.jpeg");
            byte[] png = DocumentPreviewFixtures.read("scan.png");
            assertFailure(PreviewFormat.JPEG, png, PreviewFailure.INVALID_DOCUMENT);
            assertFailure(PreviewFormat.PNG, jpeg, PreviewFailure.INVALID_DOCUMENT);
            assertFailure(PreviewFormat.TIFF, png, PreviewFailure.INVALID_DOCUMENT);
            assertFailure(PreviewFormat.JPEG, Arrays.copyOf(jpeg, jpeg.length / 2), PreviewFailure.INVALID_DOCUMENT);
            assertFailure(PreviewFormat.PNG, Arrays.copyOf(png, png.length / 2), PreviewFailure.INVALID_DOCUMENT);
            assertFailure(PreviewFormat.JPEG, new byte[0], PreviewFailure.INVALID_DOCUMENT);
        }

        @Test
        @DisplayName("a PNG declaring 100,000 pixels square is refused from its header, before anything is decoded")
        void hugeDeclaredDimensions() {
            byte[] bomb = pngHeader(100_000, 100_000);
            assertTimeoutPreemptively(Duration.ofSeconds(5), () ->
                assertFailure(PreviewFormat.PNG, bomb, PreviewFailure.LIMIT_EXCEEDED));
        }

        @Test
        @DisplayName("a frame over the configured pixel limit is refused")
        void overPixels() {
            PreviewRenderer small = renderer(limits(PreviewProperties.MAX_PAGES, 640L * 480 - 1,
                                                    PreviewProperties.MAX_OUTPUT_BYTES));
            assertFailure(small, PreviewFormat.JPEG, DocumentPreviewFixtures.read("photo.jpeg"),
                          PreviewFailure.LIMIT_EXCEEDED);
        }

        @Test
        @DisplayName("a frame whose samples decode past an ARGB frame's bytes is refused, within the pixel limit")
        void deepSamples() {
            PreviewRenderer small = renderer(limits(PreviewProperties.MAX_PAGES, 100,
                                                    PreviewProperties.MAX_OUTPUT_BYTES));
            // Ten by ten is the whole pixel limit: four bytes a pixel fits, a 16-bit RGBA PNG's eight does not.
            try (RenderedPreview preview = small.render(PreviewFormat.PNG, new TrackedSource(
                encodedPng(new BufferedImage(10, 10, BufferedImage.TYPE_INT_ARGB))), budget())) {
                assertThat(preview.pages()).hasValue(1);
            }
            ComponentColorModel deep = new ComponentColorModel(ColorSpace.getInstance(ColorSpace.CS_sRGB), true,
                                                               false, Transparency.TRANSLUCENT, DataBuffer.TYPE_USHORT);
            BufferedImage sixteenBit =
                new BufferedImage(deep, deep.createCompatibleWritableRaster(10, 10), false, null);
            assertFailure(small, PreviewFormat.PNG, encodedPng(sixteenBit), PreviewFailure.LIMIT_EXCEEDED);
        }

        @Test
        @DisplayName("a preview over the output limit is refused rather than truncated")
        void overOutput() {
            PreviewRenderer small = renderer(limits(PreviewProperties.MAX_PAGES, PreviewProperties.MAX_FRAME_PIXELS,
                                                    2_000));
            assertFailure(small, PreviewFormat.JPEG, DocumentPreviewFixtures.read("photo.jpeg"),
                          PreviewFailure.LIMIT_EXCEEDED);
            assertFailure(small, PreviewFormat.PDF, DocumentPreviewFixtures.pdf(40), PreviewFailure.LIMIT_EXCEEDED);
        }
    }

    @Nested
    @DisplayName("a TIFF")
    class Tiff {

        @Test
        @DisplayName("a budget spent while frames are rendered is a limit, with the permit back and nothing left")
        void budgetSpentWhileRendering() {
            MutableClock clock = new MutableClock();
            PreviewBudget budget = PreviewBudget.start(clock, Duration.ofSeconds(25));
            TrackedSource source = new TrackedSource(DocumentPreviewFixtures.read("three-frames.tiff"));
            source.onClose = () -> clock.advance(Duration.ofSeconds(26));

            Throwable thrown = catchThrowable(() -> renderer().render(PreviewFormat.TIFF, source, budget));

            assertThat(thrown).isInstanceOfSatisfying(PreviewError.class,
                error -> assertThat(error.failure()).isEqualTo(PreviewFailure.LIMIT_EXCEEDED));
            assertThat(capacity.available()).isEqualTo(3);
            assertScratchEmpty();
        }

        @Test
        @DisplayName("two frames become two pages, in order, each its own way round")
        void twoFrames() throws IOException {
            byte[] original = DocumentPreviewFixtures.read("two-frames.tiff");
            byte[] copy = original.clone();
            try (RenderedPreview preview = render(PreviewFormat.TIFF, original);
                 PDDocument document = Loader.loadPDF(bytes(preview))) {
                assertThat(preview.pages()).hasValue(2);
                assertThat(document.getNumberOfPages()).isEqualTo(2);
                assertPortrait(document.getPage(0));
                assertMarker(document, 0, RED, Corner.TOP_LEFT);
                assertLandscape(document.getPage(1));
                assertMarker(document, 1, BLUE, Corner.TOP_LEFT);
            }
            assertThat(original).isEqualTo(copy);
            assertScratchEmpty();
        }

        @Test
        @DisplayName("three frames of three compressions become three pages, the rotated third drawn upright")
        void threeFrames() throws IOException {
            try (RenderedPreview preview = render(PreviewFormat.TIFF,
                                                  DocumentPreviewFixtures.read("three-frames.tiff"));
                 PDDocument document = Loader.loadPDF(bytes(preview))) {
                assertThat(document.getNumberOfPages()).isEqualTo(3);
                assertPortrait(document.getPage(0));
                assertMarker(document, 0, Color.WHITE, Corner.TOP_LEFT);
                assertLandscape(document.getPage(1));
                assertMarker(document, 1, GREEN, Corner.TOP_LEFT);
                // Stored 300 by 200 with orientation 6: seen 200 by 300, the stored top-left at the top right.
                assertPortrait(document.getPage(2));
                assertMarker(document, 2, YELLOW, Corner.TOP_RIGHT);
            }
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"None", "LZW", "Deflate", "ZLib", "PackBits", "CCITT RLE", "CCITT T.4", "CCITT T.6"})
        @DisplayName("every frame of every supported compression is a page")
        void compressions(String compression) throws IOException {
            String type = "None".equals(compression) ? null : compression;
            boolean bilevel = compression.startsWith("CCITT");
            Color first = bilevel ? Color.WHITE : RED;
            Color second = bilevel ? Color.WHITE : BLUE;
            byte[] tiff = DocumentPreviewFixtures.tiff(List.of(new Frame(200, 300, first, type, 1),
                                                               new Frame(300, 200, second, type, 1)));
            try (RenderedPreview preview = render(PreviewFormat.TIFF, tiff);
                 PDDocument document = Loader.loadPDF(bytes(preview))) {
                assertThat(document.getNumberOfPages()).isEqualTo(2);
                assertPortrait(document.getPage(0));
                assertLandscape(document.getPage(1));
                assertMarker(document, 0, first, Corner.TOP_LEFT);
                assertMarker(document, 1, second, Corner.TOP_LEFT);
            }
        }

        @ParameterizedTest(name = "orientation {0}: marker {1}, {2}")
        @CsvSource({
            "1, TOP_LEFT, landscape",
            "2, TOP_RIGHT, landscape",
            "3, BOTTOM_RIGHT, landscape",
            "4, BOTTOM_LEFT, landscape",
            "5, TOP_LEFT, portrait",
            "6, TOP_RIGHT, portrait",
            "7, BOTTOM_RIGHT, portrait",
            "8, BOTTOM_LEFT, portrait",
        })
        @DisplayName("each orientation tag puts the stored first row and column where the tag says")
        void orientations(int orientation, Corner corner, String shape) throws IOException {
            byte[] tiff = DocumentPreviewFixtures.tiff(List.of(new Frame(300, 200, BLUE, "LZW", orientation)));
            try (RenderedPreview preview = render(PreviewFormat.TIFF, tiff);
                 PDDocument document = Loader.loadPDF(bytes(preview))) {
                if ("portrait".equals(shape)) {
                    assertPortrait(document.getPage(0));
                } else {
                    assertLandscape(document.getPage(0));
                }
                assertMarker(document, 0, BLUE, corner);
            }
        }

        @Test
        @DisplayName("more frames than the page limit is refused, not cut short")
        void overPages() {
            assertFailure(renderer(limits(2, PreviewProperties.MAX_FRAME_PIXELS, PreviewProperties.MAX_OUTPUT_BYTES)),
                          PreviewFormat.TIFF, DocumentPreviewFixtures.read("three-frames.tiff"),
                          PreviewFailure.LIMIT_EXCEEDED);
        }

        @Test
        @DisplayName("exactly the page limit in frames is served")
        void atPageLimit() {
            PreviewRenderer exact = renderer(limits(3, PreviewProperties.MAX_FRAME_PIXELS,
                                                    PreviewProperties.MAX_OUTPUT_BYTES));
            try (RenderedPreview preview = exact.render(PreviewFormat.TIFF, new TrackedSource(
                DocumentPreviewFixtures.read("three-frames.tiff")), budget())) {
                assertThat(preview.pages()).hasValue(3);
            }
        }

        @Test
        @DisplayName("the default page limit is 100: a hundred frames are served and a hundred and one refused")
        void defaultPageLimit() {
            assertThat(PreviewProperties.MAX_PAGES).isEqualTo(100);
            Frame small = new Frame(16, 16, GREEN, null, 1);
            byte[] hundred = DocumentPreviewFixtures.tiff(Collections.nCopies(100, small));
            try (RenderedPreview preview = render(PreviewFormat.TIFF, hundred)) {
                assertThat(preview.pages()).hasValue(100);
            }
            assertFailure(PreviewFormat.TIFF, DocumentPreviewFixtures.tiff(Collections.nCopies(101, small)),
                          PreviewFailure.LIMIT_EXCEEDED);
        }

        @Test
        @DisplayName("a later frame over the pixel limit refuses the whole preview")
        void laterFrameOverPixels() {
            byte[] tiff = DocumentPreviewFixtures.tiff(List.of(new Frame(20, 20, RED, "LZW", 1),
                                                               new Frame(200, 200, BLUE, "LZW", 1)));
            assertFailure(renderer(limits(PreviewProperties.MAX_PAGES, 1_000, PreviewProperties.MAX_OUTPUT_BYTES)),
                          PreviewFormat.TIFF, tiff, PreviewFailure.LIMIT_EXCEEDED);
        }

        @Test
        @DisplayName("a small TIFF declaring a tile over the pixel limit is refused before the tile is decoded")
        void oversizedTile() {
            // The only packet is not a deflate stream, so decoding it is an invalid document: a limit can only be
            // the directory check answering first.
            assertFailure(PreviewFormat.TIFF, DocumentPreviewFixtures.read(DocumentPreviewFixtures.OVERSIZED_TILE),
                          PreviewFailure.LIMIT_EXCEEDED);
            assertFailure(PreviewFormat.TIFF, DocumentPreviewFixtures.tiledGrey(16, 16, 16, 16, DEFLATE, NOT_DEFLATE),
                          PreviewFailure.INVALID_DOCUMENT);
        }

        @ParameterizedTest(name = "{0}")
        @CsvSource({
            "tile at the limit, 64, 64, true",
            "tile over the limit, 64, 80, false",
        })
        @DisplayName("a tile is held to the pixel limit itself, not only the frame it belongs to")
        void tileAtTheLimit(String name, int tileWidth, int tileLength, boolean served) throws IOException {
            PreviewRenderer limited = renderer(limits(PreviewProperties.MAX_PAGES, 64 * 64,
                                                      PreviewProperties.MAX_OUTPUT_BYTES));
            byte[] tiff = DocumentPreviewFixtures.tiledGrey(16, 16, tileWidth, tileLength, 0x80);
            if (served) {
                try (RenderedPreview preview = limited.render(PreviewFormat.TIFF, new TrackedSource(tiff), budget())) {
                    assertThat(preview.pages()).hasValue(1);
                }
            } else {
                assertFailure(limited, PreviewFormat.TIFF, tiff, PreviewFailure.LIMIT_EXCEEDED);
            }
        }

        @Test
        @DisplayName("a tile within the pixel limit is still held to the decoded-size limit by its sample layout")
        void tileOverTheDecodedSize() {
            // Eight bytes a pixel over a 64 by 64 tile is twice what a full-size ARGB frame of 4,096 pixels takes,
            // for a frame of 256; the packet is not deflate, so only the declaration can answer with a limit.
            PreviewRenderer limited = renderer(limits(PreviewProperties.MAX_PAGES, 64 * 64,
                                                      PreviewProperties.MAX_OUTPUT_BYTES));
            assertFailure(limited, PreviewFormat.TIFF,
                          DocumentPreviewFixtures.tiledDoubleGrey(16, 16, 64, 64, DEFLATE, NOT_DEFLATE),
                          PreviewFailure.LIMIT_EXCEEDED);
            assertFailure(limited, PreviewFormat.TIFF,
                          DocumentPreviewFixtures.tiledDoubleGrey(16, 16, 32, 32, DEFLATE, NOT_DEFLATE),
                          PreviewFailure.INVALID_DOCUMENT);
        }

        @ParameterizedTest(name = "RowsPerStrip {0}")
        @ValueSource(longs = {256, 4096, 65_535, 2_000_000})
        @DisplayName("a strip declared past the frame's height is clipped to the frame, so only the frame is held")
        void stripPastTheHeight(long rowsPerStrip) throws IOException {
            // The JDK clips a strip, unlike a tile, to the frame before decoding it: 64 by 2,000,000 would be 128
            // million pixels, for a frame (and a limit) of 4,096. A writer putting 65,535 for "all rows" is legal.
            PreviewRenderer limited = renderer(limits(PreviewProperties.MAX_PAGES, 64 * 64,
                                                      PreviewProperties.MAX_OUTPUT_BYTES));
            byte[] tiff = DocumentPreviewFixtures.strippedGrey(64, 64, rowsPerStrip, 0x80);
            try (RenderedPreview preview = limited.render(PreviewFormat.TIFF, new TrackedSource(tiff), budget())) {
                assertThat(preview.pages()).hasValue(1);
            }
            assertFailure(limited, PreviewFormat.TIFF, DocumentPreviewFixtures.strippedGrey(64, 65, rowsPerStrip, 0x80),
                          PreviewFailure.LIMIT_EXCEEDED);
        }

        @ParameterizedTest(name = "{0}")
        @CsvSource({
            "tiled in 64 by 64 tiles, 64, 0",
            "16 rows a strip, 0, 16",
            "one strip with no RowsPerStrip, 0, -1",
            "RowsPerStrip 2^32 - 1 (the whole height), 0, 4294967295",
            "RowsPerStrip past the height but in the limit, 0, 65535",
        })
        @DisplayName("ordinary tiled and stripped TIFFs still render")
        void ordinaryTilesAndStrips(String name, int tile, long rowsPerStrip) throws IOException {
            // Black, because a grey sample is drawn through the grey colour space's own tone curve.
            byte[] tiff = tile > 0 ? DocumentPreviewFixtures.tiledGrey(300, 200, tile, tile, 0)
                : DocumentPreviewFixtures.strippedGrey(300, 200, rowsPerStrip < 0 ? null : rowsPerStrip, 0);
            try (RenderedPreview preview = render(PreviewFormat.TIFF, tiff);
                 PDDocument document = Loader.loadPDF(bytes(preview))) {
                assertThat(preview.pages()).hasValue(1);
                assertLandscape(document.getPage(0));
                assertNear(colourAt(raster(document, 0), 0.5, 0.5), Color.BLACK);
            }
        }

        @Test
        @DisplayName("a TIFF declaring a tile or strip of no rows is an invalid document")
        void emptyTileOrStrip() {
            assertFailure(PreviewFormat.TIFF, DocumentPreviewFixtures.tiledGrey(16, 16, 16, 0, DEFLATE, NOT_DEFLATE),
                          PreviewFailure.INVALID_DOCUMENT);
            assertFailure(PreviewFormat.TIFF, DocumentPreviewFixtures.strippedGrey(16, 16, 0L, DEFLATE, NOT_DEFLATE),
                          PreviewFailure.INVALID_DOCUMENT);
        }

        @Test
        @DisplayName("a JPEG-compressed TIFF, old-style or not, is an invalid document, not drawn")
        void jpegCompressed() {
            // Deliberately not previewed (plan 20261023-document-preview-review-fixes-3, decision 2): the viewer
            // offers the original's download instead.
            assertFailure(PreviewFormat.TIFF,
                          DocumentPreviewFixtures.tiff(List.of(new Frame(200, 300, RED, "JPEG", 1))),
                          PreviewFailure.INVALID_DOCUMENT);
            assertFailure(PreviewFormat.TIFF,
                          DocumentPreviewFixtures.strippedGrey(64, 64, 64, OLD_JPEG, greyJpeg(64, 64, 64, 64)),
                          PreviewFailure.INVALID_DOCUMENT);
            assertFailure(PreviewFormat.TIFF,
                          DocumentPreviewFixtures.strippedGrey(64, 64, 64, JPEG, greyJpeg(64, 64, 40_000, 40_000)),
                          PreviewFailure.INVALID_DOCUMENT);
        }

        @Test
        @DisplayName("a TIFF whose structure is refused never reaches ImageIO: no reader is looked up or stream opened")
        void structureRefusedBeforeImageIo() {
            byte[] tiff = DocumentPreviewFixtures.strippedGrey(16, 16, null, 0x80);
            byte[] selfLoop = tiff.clone();
            ByteBuffer.wrap(selfLoop).order(ByteOrder.LITTLE_ENDIAN).putInt(8 + 2 + 8 * 12, 8);
            List<byte[]> refused = List.of(
                DocumentPreviewFixtures.read(DocumentPreviewFixtures.ALIASED_PAYLOADS),
                DocumentPreviewFixtures.aliasedStrips(4096, 1024),
                DocumentPreviewFixtures.withLeadingEntries(tiff, List.of(
                    DocumentPreviewFixtures.entry(65_000, 7, 100, tiff.length + 24L),
                    DocumentPreviewFixtures.entry(65_001, 7, 100, tiff.length + 74L)), new byte[150]),
                DocumentPreviewFixtures.withLeadingEntry(tiff, DocumentPreviewFixtures.entry(65_000, 7, 100,
                                                                                             tiff.length), new byte[0]),
                DocumentPreviewFixtures.withLeadingEntry(tiff, DocumentPreviewFixtures.entry(65_000, 4, 0x8000_0000L,
                                                                                             8), new byte[0]),
                DocumentPreviewFixtures.withLeadingEntry(tiff, DocumentPreviewFixtures.entry(65_000, 16, 1, 0),
                                                         new byte[0]),
                selfLoop,
                DocumentPreviewFixtures.strippedGrey(64, 64, 64, OLD_JPEG, greyJpeg(64, 64, 64, 64)),
                DocumentPreviewFixtures.strippedGrey(64, 64, 64, JPEG, greyJpeg(64, 64, 64, 64)),
                DocumentPreviewFixtures.uncompressedWithJpegInterchange(greyJpeg(64, 64, 64, 64)),
                DocumentPreviewFixtures.withLeadingEntry(tiff, DocumentPreviewFixtures.entry(347, 7, 4, 0),
                                                         new byte[0]),
                DocumentPreviewFixtures.without(tiff, 256),
                DocumentPreviewFixtures.without(tiff, 257),
                DocumentPreviewFixtures.without(tiff, 277));
            byte[] tooManyFrames = DocumentPreviewFixtures.tiff(Collections.nCopies(
                PreviewProperties.MAX_PAGES + 1, new Frame(16, 16, GREEN, null, 1)));
            try (MockedStatic<ImageIO> imageIo = Mockito.mockStatic(ImageIO.class);
                 MockedConstruction<FileImageInputStream> streams = Mockito.mockConstruction(
                     FileImageInputStream.class)) {
                for (byte[] structure : refused) {
                    assertFailure(PreviewFormat.TIFF, structure, PreviewFailure.INVALID_DOCUMENT);
                }
                assertFailure(PreviewFormat.TIFF, tooManyFrames, PreviewFailure.LIMIT_EXCEEDED);
                imageIo.verifyNoInteractions();
                assertThat(streams.constructed()).isEmpty();

                // The seam is live: a TIFF the check passes goes on to ImageIO, which (mocked) has no reader for it.
                assertFailure(PreviewFormat.TIFF, tiff, PreviewFailure.INVALID_DOCUMENT);
                imageIo.verify(() -> ImageIO.getImageReadersByFormatName("tiff"));
                assertThat(streams.constructed()).isNotEmpty();
            }
        }

        @Test
        @DisplayName("a directory at the entry cap, and a frame with an EXIF directory, still render")
        void entryCapAndExif() {
            byte[] tiff = DocumentPreviewFixtures.strippedGrey(16, 16, null, 0x80);
            List<byte[]> atTheCap = java.util.stream.IntStream.range(0, TiffStructure.MAX_ENTRIES - 8)
                .mapToObj(index -> DocumentPreviewFixtures.entry(65_000 + index, 3, 1, index)).toList();
            // One EXIF directory of one inline entry, after the frame's own entries, with no next directory.
            byte[] exif = ByteBuffer.allocate(18).order(ByteOrder.LITTLE_ENDIAN).putShort((short) 1)
                .put(DocumentPreviewFixtures.entry(0x9000, 7, 4, 0x3033_3230)).putInt(0).array();
            for (byte[] served : List.of(DocumentPreviewFixtures.withLeadingEntries(tiff, atTheCap, new byte[0]),
                                         DocumentPreviewFixtures.withLeadingEntry(
                                             tiff, DocumentPreviewFixtures.entry(34_665, 4, 1, tiff.length + 12L),
                                             exif))) {
                try (RenderedPreview preview = render(PreviewFormat.TIFF, served)) {
                    assertThat(preview.pages()).hasValue(1);
                }
            }
        }

        @Test
        @DisplayName("a TIFF cut off half way, or with a broken header, is an invalid document")
        void damaged() {
            byte[] tiff = DocumentPreviewFixtures.read("two-frames.tiff");
            assertFailure(PreviewFormat.TIFF, Arrays.copyOf(tiff, 16), PreviewFailure.INVALID_DOCUMENT);
            assertFailure(PreviewFormat.TIFF, Arrays.copyOf(tiff, tiff.length / 2), PreviewFailure.INVALID_DOCUMENT);
            byte[] header = tiff.clone();
            header[2] = 7;
            header[3] = 7;
            assertFailure(PreviewFormat.TIFF, header, PreviewFailure.INVALID_DOCUMENT);
        }
    }

    @Nested
    @DisplayName("every format")
    class Boundaries {

        @Test
        @DisplayName("a stored type outside the upload allowlist has no preview")
        void unsupportedType() {
            assertThatThrownBy(() -> PreviewFormat.require("application/msword"))
                .isInstanceOfSatisfying(PreviewError.class,
                    error -> assertThat(error.failure()).isEqualTo(PreviewFailure.INVALID_DOCUMENT));
        }

        @Test
        @DisplayName("DOCX answers unavailable while its converter is unconfigured, only once its source is read")
        void docx() {
            TrackedSource source = new TrackedSource(new byte[] {'P', 'K', 3, 4});
            assertFailure(renderer(), PreviewFormat.DOCX, source, PreviewFailure.UNAVAILABLE);
            // The read is the store's check of the caller, so only a caller it would serve hears "unavailable".
            assertThat(source.opened).isTrue();
            assertThat(source.closed).isTrue();
        }

        @Test
        @DisplayName("a source over the upload ceiling is refused once the ceiling is passed, without reading on")
        void overInput() {
            long[] served = new long[1];
            InputStream endless = new InputStream() {
                @Override
                public int read() {
                    served[0]++;
                    return 0;
                }

                @Override
                public int read(byte[] bytes, int offset, int length) {
                    Arrays.fill(bytes, offset, offset + length, (byte) 0);
                    served[0] += length;
                    return length;
                }
            };
            assertThat(failure(renderer(), PreviewFormat.PNG, () -> endless))
                .isEqualTo(PreviewFailure.LIMIT_EXCEEDED);
            assertThat(served[0]).isBetween(UploadPolicy.MAX_UPLOAD_BYTES, UploadPolicy.MAX_UPLOAD_BYTES + 64 * 1024);
            assertThat(capacity.available()).isEqualTo(3);
            assertScratchEmpty();
        }

        @Test
        @DisplayName("a busy pod refuses once the source is read, keeping nothing, and a PDF still passes")
        void busy() throws IOException {
            capacity = new ConversionCapacity(1, Duration.ofSeconds(5));
            try (ConversionCapacity.Permit held = capacity.acquire(PreviewFormat.TIFF)) {
                TrackedSource source = new TrackedSource(DocumentPreviewFixtures.read("two-frames.tiff"));
                Throwable thrown = catchThrowable(() -> renderer().render(PreviewFormat.TIFF, source, budget()));
                assertThat(thrown).isInstanceOfSatisfying(PreviewError.class, error -> {
                    assertThat(error.failure()).isEqualTo(PreviewFailure.BUSY);
                    assertThat(error.retryAfter()).contains(Duration.ofSeconds(5));
                });
                assertThat(source.opened).isTrue();
                assertThat(source.closed).isTrue();
                assertScratchEmpty();
                assertThat(capacity.available()).isZero();
                render(PreviewFormat.PDF, DocumentPreviewFixtures.pdf()).close();
            }
            assertThat(capacity.available()).isEqualTo(1);
        }

        @Test
        @DisplayName("with every workspace held, any format, a PDF included, is busy once the source is opened, "
            + "spools nothing, and a refused source keeps its own exception")
        void everyWorkspaceHeld() throws IOException {
            capacity = new ConversionCapacity(3, 1, Duration.ofSeconds(5));
            try (RenderedPreview held = render(PreviewFormat.PDF, DocumentPreviewFixtures.pdf())) {
                assertThat(capacity.availableWorkspaces()).isZero();
                TrackedSource source = new TrackedSource(DocumentPreviewFixtures.pdf());
                Throwable thrown = catchThrowable(() -> renderer().render(PreviewFormat.PDF, source, budget()));
                assertThat(thrown).isInstanceOfSatisfying(PreviewError.class, error -> {
                    assertThat(error.failure()).isEqualTo(PreviewFailure.BUSY);
                    assertThat(error.retryAfter()).contains(Duration.ofSeconds(5));
                });
                assertThat(source.opened).isTrue();
                assertThat(source.closed).isTrue();
                assertThat(Files.list(scratch).count()).isEqualTo(1);

                NotFoundError missing = new NotFoundError("Document not found");
                assertThat(catchThrowable(() -> renderer().render(PreviewFormat.TIFF, () -> {
                    throw missing;
                }, budget()))).isSameAs(missing);
                assertThat(capacity.available()).isEqualTo(3);
            }
            // The slot comes back only once the response is closed and its file deleted.
            assertThat(capacity.availableWorkspaces()).isEqualTo(1);
            assertScratchEmpty();
            render(PreviewFormat.PDF, DocumentPreviewFixtures.pdf()).close();
            assertThat(capacity.availableWorkspaces()).isEqualTo(1);
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"TIFF", "DOCX"})
        @DisplayName("a refused source keeps its own exception with every permit held or the converter unconfigured")
        void sourceRefusalOutranksBusyAndUnavailable(PreviewFormat format) {
            capacity = new ConversionCapacity(1, Duration.ofSeconds(5));
            ForbiddenError refused = new ForbiddenError("You do not have permission to open this document's file");
            try (ConversionCapacity.Permit held = capacity.acquire(PreviewFormat.TIFF)) {
                Throwable thrown = catchThrowable(() -> renderer().render(format, () -> {
                    throw refused;
                }, budget()));
                assertThat(thrown).isSameAs(refused);
                assertScratchEmpty();
            }
            assertThat(capacity.available()).isEqualTo(1);
        }

        @Test
        @DisplayName("a refused or missing source keeps its own exception, and the permit comes back")
        void sourceRefusal() {
            NotFoundError missing = new NotFoundError("Document not found");
            Throwable thrown = catchThrowable(() -> renderer().render(PreviewFormat.TIFF, () -> {
                throw missing;
            }, budget()));
            assertThat(thrown).isSameAs(missing);
            assertThat(capacity.available()).isEqualTo(3);
            assertScratchEmpty();
        }

        @Test
        @DisplayName("a source that fails half way is unavailable, not the document's fault, and is closed")
        void sourceFailsMidway() {
            TrackedSource source = new TrackedSource(DocumentPreviewFixtures.read("two-frames.tiff"), 1_000);
            assertFailure(renderer(), PreviewFormat.TIFF, source, PreviewFailure.UNAVAILABLE);
            assertThat(source.closed).isTrue();
        }

        @Test
        @DisplayName("a source whose stream cannot be closed is unavailable, holds no permit and leaves nothing")
        void sourceCannotClose() {
            TrackedSource source = new TrackedSource(DocumentPreviewFixtures.read("two-frames.tiff"));
            source.closeFails = true;
            assertFailure(renderer(), PreviewFormat.TIFF, source, PreviewFailure.UNAVAILABLE);
            assertThat(source.closed).isTrue();
        }

        @Test
        @DisplayName("an expired budget is a limit, and starts nothing")
        void expired() {
            MutableClock clock = new MutableClock();
            PreviewBudget budget = PreviewBudget.start(clock, Duration.ofSeconds(25));
            clock.advance(Duration.ofSeconds(26));
            TrackedSource source = new TrackedSource(DocumentPreviewFixtures.pdf());
            Throwable thrown = catchThrowable(() -> renderer().render(PreviewFormat.PDF, source, budget));
            assertThat(thrown).isInstanceOfSatisfying(PreviewError.class,
                error -> assertThat(error.failure()).isEqualTo(PreviewFailure.LIMIT_EXCEEDED));
            assertThat(source.opened).isFalse();
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"PDF", "JPEG", "PNG", "TIFF"})
        @DisplayName("every success and failure closes the source and leaves no scratch file behind")
        void cleanup(PreviewFormat format) throws IOException {
            byte[] good = switch (format) {
                case PDF -> DocumentPreviewFixtures.pdf();
                case JPEG -> DocumentPreviewFixtures.read("photo.jpeg");
                case PNG -> DocumentPreviewFixtures.read("scan.png");
                case TIFF -> DocumentPreviewFixtures.read("three-frames.tiff");
                case DOCX -> throw new IllegalStateException();
            };
            TrackedSource success = new TrackedSource(good);
            try (RenderedPreview preview = renderer().render(format, success, budget())) {
                assertThat(success.closed).isTrue();
                assertThat(Files.list(scratch).count()).isEqualTo(1);
            }
            assertScratchEmpty();

            TrackedSource damaged = new TrackedSource(Arrays.copyOf(good, 12));
            assertFailure(renderer(), format, damaged, PreviewFailure.INVALID_DOCUMENT);
            assertThat(damaged.closed).isTrue();
            assertThat(capacity.available()).isEqualTo(3);
            assertThat(capacity.availableWorkspaces()).isEqualTo(PreviewProperties.MAX_CONCURRENT_PREVIEWS);
        }

        @Test
        @DisplayName("a preview closed half way through reading its PDF leaves nothing behind")
        void disconnect() throws IOException {
            RenderedPreview preview = render(PreviewFormat.TIFF, DocumentPreviewFixtures.read("two-frames.tiff"));
            try (InputStream body = preview.open()) {
                assertThat(body.readNBytes(100)).hasSize(100);
                preview.close();
            }
            preview.close();
            assertScratchEmpty();
        }

        @Test
        @DisplayName("a slow source read holds no permit: another preview converts meanwhile, and the slow one "
            + "still runs out of budget cleanly")
        void slowReadHoldsNoPermit() throws Exception {
            capacity = new ConversionCapacity(1, Duration.ofSeconds(5));
            CountDownLatch reading = new CountDownLatch(1);
            CountDownLatch resume = new CountDownLatch(1);
            byte[] tiff = DocumentPreviewFixtures.read("two-frames.tiff");
            AtomicBoolean closed = new AtomicBoolean();
            PreviewSource stalled = () -> new FilterInputStream(new ByteArrayInputStream(tiff)) {
                private boolean first = true;

                @Override
                public int read(byte[] bytes, int offset, int length) throws IOException {
                    if (first) {
                        first = false;
                        reading.countDown();
                        try {
                            resume.await(5, TimeUnit.SECONDS);
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                        }
                    }
                    return super.read(bytes, offset, length);
                }

                @Override
                public void close() throws IOException {
                    closed.set(true);
                    super.close();
                }
            };
            MutableClock clock = new MutableClock();
            PreviewBudget budget = PreviewBudget.start(clock, Duration.ofSeconds(25));
            PreviewRenderer renderer = renderer();
            final CompletableFuture<RenderedPreview> worker =
                CompletableFuture.supplyAsync(() -> renderer.render(PreviewFormat.TIFF, stalled, budget));
            try {
                assertThat(reading.await(5, TimeUnit.SECONDS)).isTrue();

                // The one permit is free while the slow read trickles in, so another conversion takes it and finishes.
                assertThat(capacity.available()).isEqualTo(1);
                try (RenderedPreview other = render(PreviewFormat.TIFF, tiff)) {
                    assertThat(other.pages()).hasValue(2);
                }
                clock.advance(Duration.ofSeconds(26));
            } finally {
                resume.countDown();
            }

            Throwable thrown = catchThrowable(() -> worker.get(5, TimeUnit.SECONDS));
            assertThat(thrown).cause().isInstanceOfSatisfying(PreviewError.class,
                error -> assertThat(error.failure()).isEqualTo(PreviewFailure.LIMIT_EXCEEDED));
            assertThat(capacity.available()).isEqualTo(1);
            assertThat(closed).isTrue();
            assertScratchEmpty();
        }

        @Test
        @DisplayName("the scratch directory is private to the service's own user")
        void privateScratch() throws IOException {
            try (RenderedPreview preview = render(PreviewFormat.PDF, DocumentPreviewFixtures.pdf())) {
                try (Stream<Path> listing = Files.list(scratch)) {
                    Path directory = listing.findFirst().orElseThrow();
                    if (Files.getFileStore(directory).supportsFileAttributeView("posix")) {
                        assertThat(java.nio.file.attribute.PosixFilePermissions.toString(
                            Files.getPosixFilePermissions(directory))).isEqualTo("rwx------");
                    }
                    assertThat(directory.getFileName().toString()).startsWith("preview-");
                }
            }
        }
    }

    enum Corner {
        TOP_LEFT(0.1, 0.1), TOP_RIGHT(0.9, 0.1), BOTTOM_LEFT(0.1, 0.9), BOTTOM_RIGHT(0.9, 0.9);

        private final double across;
        private final double down;

        Corner(double across, double down) {
            this.across = across;
            this.down = down;
        }
    }

    private RenderedPreview render(PreviewFormat format, byte[] source) {
        return renderer().render(format, () -> new ByteArrayInputStream(source), budget());
    }

    private PreviewRenderer renderer() {
        return renderer(limits(PreviewProperties.MAX_PAGES, PreviewProperties.MAX_FRAME_PIXELS,
                               PreviewProperties.MAX_OUTPUT_BYTES));
    }

    private PreviewRenderer renderer(PreviewProperties properties) {
        return new PreviewRenderer(properties, capacity, scratch);
    }

    private static PreviewProperties limits(int pages, long pixels, long output) {
        return new PreviewProperties(null, output, pages, pixels, null, null, null, null);
    }

    /**
     * Runs {@code work} with PDFBox's loader replaced, and fails if anything asked it to parse.
     */
    private static <T> T unparsed(Supplier<T> work) {
        try (MockedStatic<Loader> loader = Mockito.mockStatic(Loader.class)) {
            T result = work.get();
            loader.verifyNoInteractions();
            return result;
        }
    }

    private static byte[] concat(byte[] first, byte[] second) {
        byte[] both = Arrays.copyOf(first, first.length + second.length);
        System.arraycopy(second, 0, both, first.length, second.length);
        return both;
    }

    private static byte[] encodedPng(BufferedImage image) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            assertThat(ImageIO.write(image, "png", out)).isTrue();
        } catch (IOException unwritable) {
            throw new AssertionError(unwritable);
        }
        return out.toByteArray();
    }

    /**
     * A black 8-bit grey JPEG of {@code width} by {@code height}, its frame header then made to declare
     * {@code declaredWidth} by {@code declaredHeight}.
     */
    private static byte[] greyJpeg(int width, int height, int declaredWidth, int declaredHeight) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try {
            ImageIO.write(new BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY), "jpeg", bytes);
        } catch (IOException impossible) {
            throw new IllegalStateException(impossible);
        }
        byte[] jpeg = bytes.toByteArray();
        for (int at = 2; at + 1 < jpeg.length; at++) {
            if ((jpeg[at] & 0xFF) == 0xFF && (jpeg[at + 1] & 0xFF) == 0xC0) {
                ByteBuffer.wrap(jpeg, at + 5, 4).putShort((short) declaredHeight).putShort((short) declaredWidth);
                return jpeg;
            }
        }
        throw new IllegalStateException("no baseline frame header");
    }

    private static PreviewBudget budget() {
        return PreviewBudget.start(Clock.systemUTC(), PreviewProperties.REQUEST_BUDGET);
    }

    private void assertFailure(PreviewFormat format, byte[] source, PreviewFailure expected) {
        assertFailure(renderer(), format, source, expected);
    }

    private void assertFailure(PreviewRenderer renderer, PreviewFormat format, byte[] source,
                               PreviewFailure expected) {
        assertFailure(renderer, format, new TrackedSource(source), expected);
    }

    private void assertFailure(PreviewRenderer renderer, PreviewFormat format, PreviewSource source,
                               PreviewFailure expected) {
        assertThat(failure(renderer, format, source)).isEqualTo(expected);
        assertThat(capacity.available()).isEqualTo(3);
        assertThat(capacity.availableWorkspaces()).isEqualTo(PreviewProperties.MAX_CONCURRENT_PREVIEWS);
        assertScratchEmpty();
    }

    private PreviewFailure failure(PreviewRenderer renderer, PreviewFormat format, PreviewSource source) {
        try (RenderedPreview unexpected = renderer.render(format, source, budget())) {
            return fail("expected a refusal, got a preview of %s pages", unexpected.pages());
        } catch (PreviewError refused) {
            // The fixed sentence, never the parser's.
            assertThat(refused.getMessage()).isEqualTo(refused.failure().message());
            assertThat(refused.getCause()).isNull();
            return refused.failure();
        }
    }

    private void assertScratchEmpty() {
        try (Stream<Path> listing = Files.list(scratch)) {
            assertThat(listing.toList()).isEmpty();
        } catch (IOException unreadable) {
            throw new IllegalStateException(unreadable);
        }
    }

    private static byte[] bytes(RenderedPreview preview) throws IOException {
        try (InputStream in = preview.open()) {
            return in.readAllBytes();
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static PDImageXObject onlyImage(PDPage page) throws IOException {
        List<COSName> names = new java.util.ArrayList<>();
        page.getResources().getXObjectNames().forEach(names::add);
        assertThat(names).hasSize(1);
        return (PDImageXObject) page.getResources().getXObject(names.getFirst());
    }

    private static void assertPortrait(PDPage page) {
        assertThat(page.getMediaBox().getWidth()).isEqualTo(A4_SHORT);
        assertThat(page.getMediaBox().getHeight()).isEqualTo(A4_LONG);
    }

    private static void assertLandscape(PDPage page) {
        assertThat(page.getMediaBox().getWidth()).isEqualTo(A4_LONG);
        assertThat(page.getMediaBox().getHeight()).isEqualTo(A4_SHORT);
    }

    /**
     * The black marker is in {@code corner} of the drawn image, and the image's colour is in the opposite corner.
     */
    private static void assertMarker(PDDocument document, int page, Color colour, Corner corner) throws IOException {
        BufferedImage raster = raster(document, page);
        Box drawn = drawnBox(raster);
        assertNear(colourAt(raster, drawn.column(corner.across), drawn.row(corner.down)), Color.BLACK);
        assertNear(colourAt(raster, drawn.column(1 - corner.across), drawn.row(1 - corner.down)), colour);
    }

    private record Box(int left, int top, int right, int bottom) {
        int column(double fraction) {
            return (int) (left + (right - left) * fraction);
        }

        int row(double fraction) {
            return (int) (top + (bottom - top) * fraction);
        }
    }

    /** The image's own rectangle on the page: everything that is not the page's white margin. */
    private static Box drawnBox(BufferedImage raster) {
        int left = raster.getWidth();
        int top = raster.getHeight();
        int right = -1;
        int bottom = -1;
        for (int y = 0; y < raster.getHeight(); y++) {
            for (int x = 0; x < raster.getWidth(); x++) {
                if ((raster.getRGB(x, y) & 0xFFFFFF) != 0xFFFFFF) {
                    left = Math.min(left, x);
                    right = Math.max(right, x);
                    top = Math.min(top, y);
                    bottom = Math.max(bottom, y);
                }
            }
        }
        assertThat(right).as("something was drawn").isGreaterThanOrEqualTo(0);
        // A white frame (the bilevel scans) has only its marker to find, which is a quarter of each side.
        boolean onlyMarker = right - left < raster.getWidth() / 2 && bottom - top < raster.getHeight() / 2;
        return onlyMarker ? markerOnly(raster, left, top, right, bottom) : new Box(left, top, right, bottom);
    }

    private static Box markerOnly(BufferedImage raster, int left, int top, int right, int bottom) {
        // The marker is a quarter of the image each way, so the image extends three more marker-widths from it,
        // away from whichever page edge the marker sits nearest.
        int width = right - left + 1;
        int height = bottom - top + 1;
        boolean onLeft = left < raster.getWidth() - right;
        boolean onTop = top < raster.getHeight() - bottom;
        int imageLeft = onLeft ? left : right - 4 * width + 1;
        int imageTop = onTop ? top : bottom - 4 * height + 1;
        return new Box(imageLeft, imageTop, imageLeft + 4 * width - 1, imageTop + 4 * height - 1);
    }

    private static BufferedImage raster(PDDocument document, int page) throws IOException {
        return new PDFRenderer(document).renderImageWithDPI(page, 24);
    }

    private static Color colourAt(BufferedImage raster, double x, double y) {
        return colourAt(raster, (int) (raster.getWidth() * x), (int) (raster.getHeight() * y));
    }

    private static Color colourAt(BufferedImage raster, int x, int y) {
        return new Color(raster.getRGB(x, y));
    }

    private static void assertNear(Color actual, Color expected) {
        int distance = Math.abs(actual.getRed() - expected.getRed()) + Math.abs(actual.getGreen() - expected.getGreen())
            + Math.abs(actual.getBlue() - expected.getBlue());
        assertThat(distance).as("%s near %s", actual, expected).isLessThan(60);
    }

    /**
     * A PNG that is only a signature and an IHDR declaring {@code width} by {@code height}: nothing to decode.
     */
    private static byte[] pngHeader(int width, int height) {
        ByteBuffer chunk = ByteBuffer.allocate(4 + 13);
        chunk.put(new byte[] {'I', 'H', 'D', 'R'}).putInt(width).putInt(height)
            .put((byte) 8).put((byte) 6).put((byte) 0).put((byte) 0).put((byte) 0);
        CRC32 crc = new CRC32();
        crc.update(chunk.array());
        ByteBuffer png = ByteBuffer.allocate(8 + 4 + 17 + 4);
        png.put(new byte[] {(byte) 137, 'P', 'N', 'G', 13, 10, 26, 10}).putInt(13).put(chunk.array())
            .putInt((int) crc.getValue());
        return png.array();
    }

    /** A source that records whether it was opened and closed, and may fail after so many bytes. */
    private static final class TrackedSource implements PreviewSource {
        private final byte[] bytes;
        private final long failAfter;
        private volatile boolean opened;
        private volatile boolean closed;
        private volatile Runnable onClose = () -> { };
        private volatile boolean closeFails;

        TrackedSource(byte[] bytes) {
            this(bytes, Long.MAX_VALUE);
        }

        TrackedSource(byte[] bytes, long failAfter) {
            this.bytes = bytes;
            this.failAfter = failAfter;
        }

        @Override
        public InputStream open() {
            opened = true;
            return new FilterInputStream(new ByteArrayInputStream(bytes)) {
                private long served;

                @Override
                public int read(byte[] buffer, int offset, int length) throws IOException {
                    if (served >= failAfter) {
                        throw new IOException("/var/lib/documents/secret-name.tiff vanished");
                    }
                    int read = super.read(buffer, offset, (int) Math.min(length, failAfter - served));
                    served += Math.max(read, 0);
                    return read;
                }

                @Override
                public void close() throws IOException {
                    closed = true;
                    super.close();
                    onClose.run();
                    if (closeFails) {
                        throw new IOException("/var/lib/documents/secret-name.tiff could not be closed");
                    }
                }
            };
        }
    }

    private static final class MutableClock extends Clock {
        private volatile Instant now = Instant.parse("2026-10-23T09:00:00Z");

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
