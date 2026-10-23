package uk.gov.hmcts.reform.deedpoll.domain.documents.preview;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import javax.imageio.IIOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowable;
import static uk.gov.hmcts.reform.deedpoll.domain.documents.preview.DocumentPreviewFixtures.BLUE;
import static uk.gov.hmcts.reform.deedpoll.domain.documents.preview.DocumentPreviewFixtures.Frame;
import static uk.gov.hmcts.reform.deedpoll.domain.documents.preview.DocumentPreviewFixtures.RED;
import static uk.gov.hmcts.reform.deedpoll.domain.documents.preview.DocumentPreviewFixtures.entry;
import static uk.gov.hmcts.reform.deedpoll.domain.documents.preview.DocumentPreviewFixtures.withLeadingEntries;
import static uk.gov.hmcts.reform.deedpoll.domain.documents.preview.DocumentPreviewFixtures.withLeadingEntry;

/**
 * The TIFF structure check, on real bytes: every payload its own bytes, inside the file, and a chain that ends.
 */
class TiffStructureTest {

    private static final int SHORT = 3;
    private static final int LONG = 4;
    private static final int UNDEFINED = 7;
    private static final int DOUBLE = 12;
    /** Private tags, which no reader gives a meaning to. */
    private static final int PRIVATE = 65_000;
    private static final int EXIF_IFD = 34_665;
    private static final int INTEROPERABILITY_IFD = 40_965;

    @TempDir
    Path scratch;

    /** A 16 by 16 grey TIFF of one 256-byte strip and eight inline entries. */
    private static byte[] base() {
        return DocumentPreviewFixtures.strippedGrey(16, 16, null, 0x80);
    }

    @Test
    @DisplayName("the committed aliasing probe is refused: its entries name one payload many times over")
    void aliasingProbe() {
        assertRefused(DocumentPreviewFixtures.read(DocumentPreviewFixtures.ALIASED_PAYLOADS),
                      "TIFF fields cover more than the file");
    }

    @Test
    @DisplayName("two payloads sharing bytes are refused even when together they fit inside the file")
    void overlappingPayloads() {
        byte[] tiff = base();
        long appendedAt = tiff.length + 24L;
        byte[] overlapping = withLeadingEntries(tiff, List.of(entry(PRIVATE, UNDEFINED, 100, appendedAt),
                                                              entry(PRIVATE + 1, UNDEFINED, 100, appendedAt + 50)),
                                                new byte[150]);
        assertThat(overlapping.length).isGreaterThan(400);
        assertRefused(overlapping, "TIFF fields overlap");
        byte[] apart = withLeadingEntries(tiff, List.of(entry(PRIVATE, UNDEFINED, 100, appendedAt),
                                                        entry(PRIVATE + 1, UNDEFINED, 100, appendedAt + 100)),
                                          new byte[200]);
        assertPasses(apart);
    }

    @Test
    @DisplayName("a payload sharing bytes with the header or a directory table is refused")
    void payloadOverTheStructure() {
        assertRefused(withLeadingEntry(base(), entry(PRIVATE, UNDEFINED, 20, 8), new byte[0]), "TIFF fields overlap");
        assertRefused(withLeadingEntry(base(), entry(PRIVATE, UNDEFINED, 8, 0), new byte[0]), "TIFF fields overlap");
    }

    @Test
    @DisplayName("a payload running past the end of the file is refused")
    void payloadPastTheEnd() {
        byte[] tiff = base();
        long end = tiff.length + 12L + 50;
        assertRefused(withLeadingEntry(tiff, entry(PRIVATE, UNDEFINED, 100, end - 50), new byte[50]),
                      "TIFF field value outside the file");
        assertRefused(withLeadingEntry(tiff, entry(PRIVATE, UNDEFINED, 5, 0xFFFF_FFF0L), new byte[0]),
                      "TIFF field value outside the file");
        assertPasses(withLeadingEntry(tiff, entry(PRIVATE, UNDEFINED, 50, end - 50), new byte[50]));
    }

    @ParameterizedTest(name = "type {0}")
    @ValueSource(ints = {LONG, DOUBLE})
    @DisplayName("a count whose payload overflows an int is refused from the arithmetic, allocating nothing")
    void overflowingCount(int type) {
        // 2^31 LONGs is 8 GiB and 2^32 - 1 DOUBLEs 32 GiB: either would be an OutOfMemoryError if allocated.
        long count = type == LONG ? 0x8000_0000L : 0xFFFF_FFFFL;
        assertRefused(withLeadingEntry(base(), entry(PRIVATE, type, count, 8), new byte[0]),
                      "TIFF field value outside the file");
    }

    @Test
    @DisplayName("a directory chain that comes back to itself, or to an earlier directory, is refused")
    void loops() {
        byte[] self = base();
        ByteBuffer.wrap(self).order(ByteOrder.LITTLE_ENDIAN).putInt(nextOffsetAt(self, 8), 8);
        assertRefused(self, "TIFF directory chain loops");

        byte[] two = DocumentPreviewFixtures.tiff(List.of(new Frame(16, 16, RED, "LZW", 1),
                                                          new Frame(16, 16, BLUE, "LZW", 1)));
        ByteBuffer buffer = ByteBuffer.wrap(two).order(byteOrder(two));
        long first = Integer.toUnsignedLong(buffer.getInt(4));
        long second = Integer.toUnsignedLong(buffer.getInt(nextOffsetAt(two, first)));
        buffer.putInt(nextOffsetAt(two, second), (int) first);
        assertRefused(two, "TIFF directory chain loops");
    }

    @Test
    @DisplayName("more directories than the page limit is a limit; exactly the limit passes")
    void tooManyDirectories() throws IOException {
        byte[] three = DocumentPreviewFixtures.read("three-frames.tiff");
        assertThat(catchThrowable(() -> require(three, 2))).isInstanceOfSatisfying(PreviewError.class,
            error -> assertThat(error.failure()).isEqualTo(PreviewFailure.LIMIT_EXCEEDED));
        require(three, 3);
    }

    @Test
    @DisplayName("a directory of more than the entry cap is refused; one at the cap passes")
    void tooManyEntries() {
        byte[] tiff = base();
        int room = TiffStructure.MAX_ENTRIES - 8;
        assertPasses(withLeadingEntries(tiff, inlineEntries(room), new byte[0]));
        assertRefused(withLeadingEntries(tiff, inlineEntries(room + 1), new byte[0]),
                      "TIFF directory with too many entries");
    }

    @Test
    @DisplayName("a directory outside the file, inside the header, or with no entries is refused")
    void misplacedDirectories() {
        byte[] beyond = base();
        ByteBuffer.wrap(beyond).order(ByteOrder.LITTLE_ENDIAN).putInt(4, beyond.length);
        assertRefused(beyond, "TIFF directory outside the file");
        byte[] header = base();
        ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN).putInt(4, 4);
        assertRefused(header, "TIFF directory outside the file");
        byte[] next = base();
        ByteBuffer.wrap(next).order(ByteOrder.LITTLE_ENDIAN).putInt(nextOffsetAt(next, 8), next.length - 1);
        assertRefused(next, "TIFF directory outside the file");
        // Two entries' worth of table where only four bytes of file are left.
        byte[] cut = base();
        ByteBuffer.wrap(cut).order(ByteOrder.LITTLE_ENDIAN).putInt(nextOffsetAt(cut, 8), cut.length - 4)
            .putShort(cut.length - 4, (short) 2);
        assertRefused(cut, "TIFF directory outside the file");
        byte[] empty = base();
        ByteBuffer.wrap(empty).order(ByteOrder.LITTLE_ENDIAN).putShort(8, (short) 0);
        assertRefused(empty, "TIFF directory with no entries");
        byte[] none = base();
        ByteBuffer.wrap(none).order(ByteOrder.LITTLE_ENDIAN).putInt(4, 0);
        assertRefused(none, "TIFF with no directory");
    }

    @ParameterizedTest(name = "type {0}")
    @ValueSource(ints = {0, 14, 16, 0xFFFF})
    @DisplayName("an entry of a type TIFF 6.0 does not define, BigTIFF's LONG8 (16) included, is refused")
    void unknownTypes(int type) {
        assertRefused(withLeadingEntry(base(), entry(PRIVATE, type, 1, 0), new byte[0]),
                      "TIFF field of an unknown type");
    }

    @Test
    @DisplayName("BigTIFF, a bad byte order and a file shorter than its header are refused")
    void headers() {
        byte[] big = base();
        big[2] = 43;
        assertRefused(big, "TIFF magic number");
        byte[] order = base();
        order[0] = 'X';
        order[1] = 'X';
        assertRefused(order, "TIFF byte order");
        assertRefused(new byte[] {'I', 'I', 42, 0}, "TIFF header outside the file");
    }

    @Test
    @DisplayName("SubIFDs are refused")
    void subIfds() {
        byte[] tiff = base();
        assertRefused(withLeadingEntry(tiff, entry(330, LONG, 1, tiff.length + 12L), directory(inlineEntries(1))),
                      "TIFF SubIFDs");
    }

    @Test
    @DisplayName("an EXIF directory and its Interoperability directory pass, held to the same rules")
    void exifDirectories() {
        byte[] tiff = base();
        long exifAt = tiff.length + 12L;
        assertPasses(withLeadingEntry(tiff, entry(EXIF_IFD, LONG, 1, exifAt), directory(inlineEntries(3))));
        // The EXIF directory holds one Interoperability pointer, to the directory straight after its own 18 bytes.
        byte[] nested = concat(directory(List.of(entry(INTEROPERABILITY_IFD, LONG, 1, exifAt + 18))),
                               directory(inlineEntries(1)));
        assertPasses(withLeadingEntry(tiff, entry(EXIF_IFD, 13, 1, exifAt), nested));
    }

    @Test
    @DisplayName("an EXIF pointer aliasing a directory, outside the file, mistyped, repeated or too deep is refused")
    void exifPointersRefused() {
        byte[] tiff = base();
        long exifAt = tiff.length + 12L;
        assertRefused(withLeadingEntry(tiff, entry(EXIF_IFD, LONG, 1, 8), new byte[0]), "TIFF directory chain loops");
        // A private payload and the EXIF directory both claiming the same 42 bytes.
        assertRefused(withLeadingEntries(tiff, List.of(entry(PRIVATE, UNDEFINED, 42, exifAt + 12),
                                                       entry(EXIF_IFD, LONG, 1, exifAt + 12)),
                                         directory(inlineEntries(3))),
                      "TIFF fields overlap");
        assertRefused(withLeadingEntry(tiff, entry(EXIF_IFD, LONG, 1, exifAt + 100), new byte[0]),
                      "TIFF directory outside the file");
        assertRefused(withLeadingEntry(tiff, entry(EXIF_IFD, SHORT, 1, exifAt), directory(inlineEntries(1))),
                      "TIFF directory pointer of an unexpected type or count");
        assertRefused(withLeadingEntries(tiff, List.of(entry(EXIF_IFD, LONG, 1, exifAt + 12),
                                                       entry(EXIF_IFD, LONG, 1, exifAt + 12)),
                                         directory(inlineEntries(1))),
                      "TIFF directory pointers too deep or repeated");
        // Frame, EXIF, Interoperability, and then a pointer from the Interoperability directory.
        byte[] deep = concat(directory(List.of(entry(INTEROPERABILITY_IFD, LONG, 1, exifAt + 18))),
                             directory(List.of(entry(EXIF_IFD, LONG, 1, exifAt + 36))),
                             directory(inlineEntries(1)));
        assertRefused(withLeadingEntry(tiff, entry(EXIF_IFD, LONG, 1, exifAt), deep),
                      "TIFF directory pointers too deep or repeated");
    }

    @Test
    @DisplayName("every committed TIFF, and JDK-written TIFFs of every compression, single and multi-frame, pass")
    void ordinaryTiffs() throws IOException {
        for (String name : List.of("two-frames.tiff", "three-frames.tiff", DocumentPreviewFixtures.OVERSIZED_TILE)) {
            require(DocumentPreviewFixtures.read(name), PreviewProperties.MAX_PAGES);
        }
        for (String compression : new String[] {null, "LZW", "Deflate", "ZLib", "PackBits", "CCITT RLE",
                                                "CCITT T.4", "CCITT T.6"}) {
            Frame frame = new Frame(64, 48, RED, compression, 1);
            require(DocumentPreviewFixtures.tiff(List.of(frame)), PreviewProperties.MAX_PAGES);
            require(DocumentPreviewFixtures.tiff(Collections.nCopies(3, frame)), PreviewProperties.MAX_PAGES);
        }
        require(DocumentPreviewFixtures.tiledGrey(300, 200, 64, 64, 0), PreviewProperties.MAX_PAGES);
    }

    @Test
    @DisplayName("an expired budget stops the walk")
    void budget() {
        PreviewBudget spent = PreviewBudget.start(Clock.systemUTC(), Duration.ZERO);
        Throwable thrown = catchThrowable(() -> {
            Path file = Files.write(scratch.resolve("spent.tiff"), base());
            TiffStructure.require(file, PreviewProperties.MAX_PAGES, spent);
        });
        assertThat(thrown).isInstanceOfSatisfying(PreviewError.class,
            error -> assertThat(error.failure()).isEqualTo(PreviewFailure.LIMIT_EXCEEDED));
    }

    private void assertRefused(byte[] tiff, String reason) {
        assertThat(catchThrowable(() -> require(tiff, PreviewProperties.MAX_PAGES)))
            .isInstanceOf(IIOException.class).hasMessage(reason);
    }

    private void assertPasses(byte[] tiff) {
        assertThatCode(() -> require(tiff, PreviewProperties.MAX_PAGES)).doesNotThrowAnyException();
    }

    private void require(byte[] tiff, int maxPages) throws IOException {
        Path file = Files.write(Files.createTempFile(scratch, "structure-", ".tiff"), tiff);
        TiffStructure.require(file, maxPages, PreviewBudget.start(Clock.systemUTC(), Duration.ofSeconds(25)));
    }

    /**
     * {@code count} distinct private SHORT entries, each one value held inline.
     */
    private static List<byte[]> inlineEntries(int count) {
        return java.util.stream.IntStream.range(0, count).mapToObj(index -> entry(PRIVATE + index, SHORT, 1, index))
            .toList();
    }

    /**
     * A little-endian directory of {@code entries}, with no next directory.
     */
    private static byte[] directory(List<byte[]> entries) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort((short) entries.size())
                           .array());
        entries.forEach(out::writeBytes);
        out.writeBytes(new byte[4]);
        return out.toByteArray();
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            out.writeBytes(part);
        }
        return out.toByteArray();
    }

    private static ByteOrder byteOrder(byte[] tiff) {
        return tiff[0] == 'M' ? ByteOrder.BIG_ENDIAN : ByteOrder.LITTLE_ENDIAN;
    }

    /**
     * Where the directory at {@code directory} keeps its next-directory offset.
     */
    private static int nextOffsetAt(byte[] tiff, long directory) {
        int entries = ByteBuffer.wrap(tiff).order(byteOrder(tiff)).getShort((int) directory) & 0xFFFF;
        return (int) directory + 2 + entries * 12;
    }
}
