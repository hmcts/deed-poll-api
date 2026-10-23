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
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import java.util.zip.CRC32;
import javax.imageio.ImageIO;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.Assertions.fail;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static uk.gov.hmcts.reform.deedpoll.domain.documents.preview.DocumentPreviewFixtures.BLUE;
import static uk.gov.hmcts.reform.deedpoll.domain.documents.preview.DocumentPreviewFixtures.Frame;
import static uk.gov.hmcts.reform.deedpoll.domain.documents.preview.DocumentPreviewFixtures.GREEN;
import static uk.gov.hmcts.reform.deedpoll.domain.documents.preview.DocumentPreviewFixtures.RED;
import static uk.gov.hmcts.reform.deedpoll.domain.documents.preview.DocumentPreviewFixtures.YELLOW;

/**
 * PDF, JPEG, PNG and TIFF previews made from real bytes and read back with a real PDF parser and rasteriser.
 */
class PreviewRendererTest {

    private static final float A4_SHORT = PDRectangle.A4.getWidth();
    private static final float A4_LONG = PDRectangle.A4.getHeight();

    @TempDir
    Path scratch;

    private ConversionCapacity capacity = new ConversionCapacity(3, Duration.ofSeconds(5));

    @Nested
    @DisplayName("a PDF")
    class Pdf {

        @Test
        @DisplayName("passes through byte for byte, keeping its pages, and takes no permit")
        void passthrough() throws IOException {
            byte[] original = DocumentPreviewFixtures.read(DocumentPreviewFixtures.PDF);
            byte[] copy = original.clone();
            try (RenderedPreview preview = render(PreviewFormat.PDF, original)) {
                assertThat(capacity.available()).isEqualTo(3);
                assertThat(preview.pages()).isEqualTo(3);
                byte[] served = bytes(preview);
                assertThat(sha256(served)).isEqualTo(sha256(copy));
                assertThat(preview.size()).isEqualTo(original.length);
            }
            assertThat(original).isEqualTo(copy);
            assertScratchEmpty();
        }

        @Test
        @DisplayName("over the page limit is refused, whatever its /Count says")
        void overPages() {
            assertFailure(renderer(limits(2, PreviewProperties.MAX_FRAME_PIXELS, PreviewProperties.MAX_OUTPUT_BYTES)),
                          PreviewFormat.PDF, DocumentPreviewFixtures.pdf(3), PreviewFailure.LIMIT_EXCEEDED);
        }

        @Test
        @DisplayName("exactly the page limit is served")
        void atPageLimit() {
            PreviewRenderer exact = renderer(limits(3, PreviewProperties.MAX_FRAME_PIXELS,
                                                    PreviewProperties.MAX_OUTPUT_BYTES));
            try (RenderedPreview preview = exact.render(PreviewFormat.PDF, new TrackedSource(
                DocumentPreviewFixtures.pdf(3)), budget())) {
                assertThat(preview.pages()).isEqualTo(3);
            }
        }

        @Test
        @DisplayName("an encrypted PDF is an invalid document, as an upload would have been")
        void encrypted() throws IOException {
            byte[] encrypted;
            try (PDDocument document = Loader.loadPDF(DocumentPreviewFixtures.pdf())) {
                document.protect(new StandardProtectionPolicy("owner", "", new AccessPermission()));
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                document.save(out);
                encrypted = out.toByteArray();
            }
            assertFailure(PreviewFormat.PDF, encrypted, PreviewFailure.INVALID_DOCUMENT);
        }

        @Test
        @DisplayName("a truncated PDF, a PDF with no pages and bytes that are not a PDF are invalid documents")
        void damaged() throws IOException {
            byte[] whole = DocumentPreviewFixtures.pdf();
            assertFailure(PreviewFormat.PDF, Arrays.copyOf(whole, 40), PreviewFailure.INVALID_DOCUMENT);
            assertFailure(PreviewFormat.PDF, DocumentPreviewFixtures.read("scan.png"),
                          PreviewFailure.INVALID_DOCUMENT);
            assertFailure(PreviewFormat.PDF, "%PDF-1.7 and then nothing".getBytes(),
                          PreviewFailure.INVALID_DOCUMENT);
            try (PDDocument empty = new PDDocument()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                empty.save(out);
                assertFailure(PreviewFormat.PDF, out.toByteArray(), PreviewFailure.INVALID_DOCUMENT);
            }
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
                assertThat(preview.pages()).isEqualTo(1);
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
                assertThat(preview.pages()).isEqualTo(1);
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
        @DisplayName("two frames become two pages, in order, each its own way round")
        void twoFrames() throws IOException {
            byte[] original = DocumentPreviewFixtures.read("two-frames.tiff");
            byte[] copy = original.clone();
            try (RenderedPreview preview = render(PreviewFormat.TIFF, original);
                 PDDocument document = Loader.loadPDF(bytes(preview))) {
                assertThat(preview.pages()).isEqualTo(2);
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
        @ValueSource(strings = {"None", "LZW", "Deflate", "ZLib", "PackBits", "JPEG", "CCITT RLE", "CCITT T.4",
                                "CCITT T.6"})
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
                assertThat(preview.pages()).isEqualTo(3);
            }
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
        @DisplayName("DOCX answers unavailable while its converter is unconfigured, and opens nothing")
        void docx() {
            TrackedSource source = new TrackedSource(new byte[] {'P', 'K', 3, 4});
            assertFailure(renderer(), PreviewFormat.DOCX, source, PreviewFailure.UNAVAILABLE);
            assertThat(source.opened).isFalse();
            assertThat(capacity.available()).isEqualTo(3);
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
        @DisplayName("a busy pod refuses before the source is opened, and a PDF still passes")
        void busy() throws IOException {
            capacity = new ConversionCapacity(1, Duration.ofSeconds(5));
            try (ConversionCapacity.Permit held = capacity.acquire(PreviewFormat.TIFF)) {
                TrackedSource source = new TrackedSource(DocumentPreviewFixtures.read("two-frames.tiff"));
                Throwable thrown = catchThrowable(() -> renderer().render(PreviewFormat.TIFF, source, budget()));
                assertThat(thrown).isInstanceOfSatisfying(PreviewError.class, error -> {
                    assertThat(error.failure()).isEqualTo(PreviewFailure.BUSY);
                    assertThat(error.retryAfter()).contains(Duration.ofSeconds(5));
                });
                assertThat(source.opened).isFalse();
                render(PreviewFormat.PDF, DocumentPreviewFixtures.pdf()).close();
            }
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
        @DisplayName("a worker whose budget runs out keeps its permit until it has actually stopped, then cleans up")
        void expiryMidRead() throws Exception {
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
            assertThat(reading.await(5, TimeUnit.SECONDS)).isTrue();

            clock.advance(Duration.ofSeconds(26));
            // The time is up, but the worker is still inside the source read: its permit is still its own.
            assertThat(capacity.available()).isEqualTo(2);
            resume.countDown();

            Throwable thrown = catchThrowable(() -> worker.get(5, TimeUnit.SECONDS));
            assertThat(thrown).cause().isInstanceOfSatisfying(PreviewError.class,
                error -> assertThat(error.failure()).isEqualTo(PreviewFailure.LIMIT_EXCEEDED));
            assertThat(capacity.available()).isEqualTo(3);
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
        return new PreviewProperties(null, output, pages, pixels, null, null, null);
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
        assertScratchEmpty();
    }

    private PreviewFailure failure(PreviewRenderer renderer, PreviewFormat format, PreviewSource source) {
        try (RenderedPreview unexpected = renderer.render(format, source, budget())) {
            return fail("expected a refusal, got a preview of %d pages", unexpected.pages());
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
