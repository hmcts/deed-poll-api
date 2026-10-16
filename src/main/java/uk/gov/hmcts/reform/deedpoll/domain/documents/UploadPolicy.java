package uk.gov.hmcts.reform.deedpoll.domain.documents;

import uk.gov.hmcts.reform.deedpoll.domain.FieldError;
import uk.gov.hmcts.reform.deedpoll.domain.InvalidInputError;

import java.util.List;
import java.util.Set;

/**
 * What the service will accept as an uploaded document, and what it says when it will not.
 *
 * <p>Ported from {@code reference/ts/documents/upload-policy.ts}. The multipart wiring itself belongs to
 * whatever serves the request -- Spring's multipart resolver here, Multer in the reference's Express apps.
 * The <em>policy</em> does not: a size limit and an accepted-type list are decisions about the service, and
 * holding them in one place means the figure someone confirms with the service team is the figure every
 * caller enforces. Two copies of a number the handover already flags as a guess would drift the moment one
 * was corrected.
 *
 * <p>{@link #uploadLimitRejection} takes a plain {@code code} string rather than a framework exception, for
 * the same reason the reference does: it keeps the domain from importing a transport library. Task 15's
 * controller is what maps Spring's {@code MaxUploadSizeExceededException} and its siblings onto these
 * codes, and the codes are the reference's own so that mapping reads the same in both services.
 */
public final class UploadPolicy {

    /** The multipart field name every caller posts the file under. */
    public static final String UPLOAD_FILE_FIELD = "file";

    /**
     * A multipart request that could not be parsed at all -- no {@code boundary}, a truncated body, a malformed
     * part header.
     *
     * <p><strong>Deliberately not one of the reference's Multer codes</strong>, because Multer never raises this:
     * Express refuses the request before the interceptor sees it. It exists so the handler for Spring's generic
     * {@code MultipartException} names what it is passing rather than passing a bare string, and so lands on
     * {@link #messageForLimit}'s vague message on purpose rather than by falling off the end of a lookup. That
     * fallback is the point -- see its comment: a vague message beats an unmapped failure becoming a 500.
     */
    public static final String UNREADABLE_UPLOAD = "UNREADABLE_MULTIPART";

    /**
     * 25 MB. Raised from 10 MB on Steve's call, 2026-09-03.
     *
     * <p>These are scanned certificates and multi-page deeds, and a colour scan of a multi-page document at
     * 300dpi runs well past 10 MB -- so the original figure was likely to reject legitimate documents, which
     * a caseworker experiences as the service being broken rather than as a limit.
     *
     * <p><strong>Still not a researched number; confirm against real file sizes with the service team.</strong>
     * The message shown to the user is derived from this constant, so correcting it here corrects everything
     * -- and {@code UploadPolicyTest} pins that pairing so the two cannot drift.
     *
     * <p><strong>Why 25 and not 50.</strong> The file is buffered in memory, so the real ceiling is this
     * figure multiplied by concurrent uploads, against the pod's memory limit -- and the charts set no
     * memory limits at all, so that ceiling is whatever the subchart defaults to and is not visible from
     * this repository. 25 MB roughly doubles the headroom for genuine documents while keeping the worst
     * case moderate. Going higher wants explicit {@code resources.limits.memory} in the charts first, and
     * ideally streaming to storage rather than buffering -- which is what CDAM brings in plan 03.
     */
    public static final long MAX_UPLOAD_BYTES = 25L * 1024 * 1024;

    /**
     * PDF, the common scan formats, and Word for documents produced by the court.
     *
     * <p>This is the {@code Content-Type} the client puts on the
     * multipart part, which a client can simply lie about. It stops an accidental upload of the wrong thing
     * and keeps obvious executables out. {@link UploadContentPolicy} additionally checks signatures and
     * rejects encrypted/active documents at upload and staging; malware scanning still belongs with CDAM.
     * Legacy binary Word (.doc) is not accepted (Steve's decision, 2026-10-16).
     */
    public static final Set<String> ALLOWED_UPLOAD_MIME_TYPES = Set.of(
        "application/pdf",
        "image/jpeg",
        "image/png",
        "image/tiff",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document");

    private UploadPolicy() {
    }

    /**
     * Whether the declared type is on the list.
     *
     * <p>An exact match, never a prefix: matching on {@code application/} would let every executable
     * through, and the reference asserts that even {@code "application/pdf; charset=binary"} is refused. A
     * caller with a parameterised content type has to strip the parameters before asking, which is the
     * transport's job and not the policy's.
     *
     * <p>A null is refused rather than passed through. {@link Set#of} refuses to be asked about one -- it
     * throws rather than answering false -- and a client may legitimately send a multipart part with no
     * {@code Content-Type}, which arrives here as null. An exception at that point would be a 500 for a
     * file that should have been a 400.
     */
    public static boolean isAllowedUploadType(String mimeType) {
        return mimeType != null && ALLOWED_UPLOAD_MIME_TYPES.contains(mimeType);
    }

    /** The rejection for a type not on the list, attributed to the file input a form renders. */
    public static InvalidInputError uploadTypeRejection(String mimeType) {
        return new InvalidInputError(mimeType + " is not an accepted file type",
            List.of(new FieldError(UPLOAD_FILE_FIELD, "Upload a PDF, image or Word document")));
    }

    /**
     * The rejection for one of the transport's own limits, by its error code.
     *
     * <p>An {@link InvalidInputError} rather than the raw failure, because the transport's is not a domain
     * error: unwrapped it reaches the error handler unrecognised and becomes a 500, telling a caseworker the
     * server has broken when in fact their file was too big. The reference records three defects that fell
     * into exactly that trap.
     */
    public static InvalidInputError uploadLimitRejection(String code) {
        String message = messageForLimit(code);
        return new InvalidInputError(message, List.of(new FieldError(UPLOAD_FILE_FIELD, message)));
    }

    private static String messageForLimit(String code) {
        if ("LIMIT_FILE_SIZE".equals(code)) {
            return "The file must be smaller than " + megabytes() + "MB";
        }
        if ("LIMIT_FILE_COUNT".equals(code) || "LIMIT_UNEXPECTED_FILE".equals(code)) {
            return "Upload one file at a time";
        }
        // There are codes this does not enumerate. A vague message beats an unmapped failure becoming a
        // 500, which is the whole reason this method exists.
        return "The file could not be read";
    }

    /**
     * {@link #MAX_UPLOAD_BYTES} in megabytes, derived rather than restated -- see the constant's note.
     */
    private static long megabytes() {
        return Math.round((double) MAX_UPLOAD_BYTES / (1024 * 1024));
    }
}
