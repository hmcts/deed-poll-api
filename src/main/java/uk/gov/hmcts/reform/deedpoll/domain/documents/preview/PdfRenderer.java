package uk.gov.hmcts.reform.deedpoll.domain.documents.preview;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.OptionalInt;

/**
 * A PDF is its own preview: it is checked and then served byte for byte as it was stored, <strong>unparsed</strong>.
 *
 * <p>The checks are structural and read a bounded number of bytes: the file starts {@code %PDF-} as an upload's did,
 * and {@code %%EOF} sits within its last {@link #EOF_WINDOW} bytes, so a body cut short is refused. Its size is
 * already bounded by the input ceiling (or, for a converted DOCX, the output ceiling) before this runs.
 *
 * <p>Nothing parses it. A parser has to inflate compressed object streams into memory and collect the page tree
 * before any limit of ours could look at it, so a small hostile file could exhaust the heap or the stack; and PDF
 * passthrough takes no conversion permit, so concurrent requests would multiply that. Its pages are therefore not
 * counted here: the admin viewer reads {@code numPages} through PDF.js and holds it to the page limit before
 * rendering. An encrypted PDF (an upload refuses one, so only an old row can be) is not detected without a parse and
 * is served as stored. The viewer gives PDF.js no password, so the browser never asks for one: an empty user
 * password still opens, and any other fails in the viewer. Nothing is rewritten, so nothing is lost: what the
 * browser shows is what was uploaded.
 */
final class PdfRenderer implements SourceRenderer {

    private static final byte[] HEADER = "%PDF-".getBytes(StandardCharsets.US_ASCII);
    static final byte[] EOF_MARKER = "%%EOF".getBytes(StandardCharsets.US_ASCII);

    /**
     * How far from the end the whole {@code %%EOF} marker may sit; trailing whitespace or junk, generously.
     */
    static final int EOF_WINDOW = 1024;

    @Override
    public Rendition render(Path source, PreviewWorkspace workspace, PreviewBudget budget) {
        try (RandomAccessFile file = new RandomAccessFile(source.toFile(), "r")) {
            long length = file.length();
            byte[] start = new byte[HEADER.length];
            if (length < HEADER.length + EOF_MARKER.length) {
                throw new PreviewError(PreviewFailure.INVALID_DOCUMENT);
            }
            file.readFully(start);
            int window = (int) Math.min(length, EOF_WINDOW);
            byte[] tail = new byte[window];
            file.seek(length - window);
            file.readFully(tail);
            if (!Arrays.equals(start, HEADER) || !contains(tail, EOF_MARKER)) {
                throw new PreviewError(PreviewFailure.INVALID_DOCUMENT);
            }
        } catch (IOException scratch) {
            throw new PreviewError(PreviewFailure.UNAVAILABLE);
        }
        return new Rendition(source, OptionalInt.empty());
    }

    private static boolean contains(byte[] haystack, byte[] needle) {
        for (int at = 0; at <= haystack.length - needle.length; at++) {
            if (Arrays.equals(haystack, at, at + needle.length, needle, 0, needle.length)) {
                return true;
            }
        }
        return false;
    }
}
