package uk.gov.hmcts.reform.deedpoll.api;

import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import uk.gov.hmcts.reform.deedpoll.domain.documents.DocumentDownload;
import uk.gov.hmcts.reform.deedpoll.domain.documents.UploadPolicy;
import uk.gov.hmcts.reform.deedpoll.entity.DeedPollSupportingDocument;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * The one way a supporting document's bytes become an HTTP response, shared by the caseworker and judicial downloads.
 *
 * <p>Here rather than in either namespace, as {@code DomainErrorHandler} is, because the headers below are what make
 * serving user-uploaded bytes safe, and a second copy would be a second place to forget one. It takes the domain's
 * {@link DocumentDownload} and returns a response; it publishes no DTO, so neither surface reuses the other's types.
 *
 * <p>Five response headers, and each is load-bearing:
 *
 * <ul>
 *   <li>{@code Content-Type} from the stored value, or {@code application/octet-stream} when the row has none. Set
 *       from what this service recorded, never echoed from the request.</li>
 *   <li>{@code Content-Disposition}, {@code inline} for {@link #INLINE_MIME_TYPES} and {@code attachment} otherwise,
 *       with a sanitised filename.</li>
 *   <li>{@code Content-Security-Policy: default-src 'none'; sandbox}, which is what makes the inline allowlist's
 *       argument actually true rather than nearly true. PDF is on that list and a PDF is an active-content format --
 *       browser viewers run embedded JavaScript, and a PDF served inline runs it in this origin's context. The
 *       policy leaves a benign PDF or image rendering exactly as before and gives a malicious one nothing to
 *       reach.</li>
 *   <li>{@code X-Content-Type-Options: nosniff}, without which a browser may sniff past the declared type and render
 *       something as the thing it guessed. <strong>Spring Security's default header writers already add this to
 *       every response, so this line is deliberate redundancy rather than the only source</strong> -- checked by
 *       deleting it, which no test notices. It is kept because these are the routes that serve bytes a member of the
 *       public uploaded, and a later {@code headers(h -> h.contentTypeOptions(disable))} somewhere in the filter
 *       chain must not quietly take the protection off them. Security skips a header that is already present, so
 *       there is no duplicate, and a test pins that rather than pinning which of the two set it.</li>
 *   <li>{@code Cache-Control: no-store}, because a document is case data and a shared cache must not keep a
 *       copy.</li>
 * </ul>
 *
 * <p><strong>No {@code Content-Length}, deliberately.</strong> The obvious candidate is the row's {@code sizeBytes},
 * and it is a recorded number rather than a measured one: a row whose blob was replaced through the metadata path
 * could disagree with it, and a wrong length truncates the download or hangs it. The response is chunked instead.
 *
 * <p>A failure after the first byte cannot become a status code -- the headers are already sent -- so a mid-stream
 * error breaks the connection rather than answering 500. That is the correct outcome: a truncated download is
 * visible to the client, whereas a 200 carrying half a file is not.
 */
public final class DocumentFileResponses {

    /**
     * The types a browser is asked to render in place rather than save.
     *
     * <p>Steve's call, 2026-09-04, and the reasoning is in the API plan: the sibling web app hardcodes
     * {@code attachment}, so a caseworker checking a birth certificate has to download it, open it, and then
     * remember to delete it from their machine. PDF, JPEG and PNG are what a browser renders natively and what the
     * web app's own viewer handles; TIFF and the two Word types are download-only because nothing renders them.
     *
     * <p><strong>The allowlist is what makes serving user-uploaded bytes inline defensible</strong>, and it is
     * narrower than {@link UploadPolicy#ALLOWED_UPLOAD_MIME_TYPES} on purpose rather than by coincidence. Serving
     * uploaded content inline is normally an XSS vector; it is safe here because the dangerous renderable types --
     * SVG and HTML above all -- are on neither list, because the {@code Content-Type} is set from the stored value
     * rather than echoed from the request, and because {@code nosniff} stops a browser guessing past it. Anything
     * added to the upload policy is therefore download-only until it is added here as well, which is the right way
     * round for the two lists to fail.
     */
    private static final Set<String> INLINE_MIME_TYPES =
        Set.of("application/pdf", "image/jpeg", "image/png");

    /**
     * Characters kept in a downloaded file's name: letters, digits, and a few separators.
     *
     * <p>The name is whatever the uploading client called the file, so it reaches a response header as
     * caller-supplied text. The prototype stripped {@code "} and nothing else, and the reference records what that
     * leaves: a name containing a newline produces an invalid header, which the server rejects by throwing
     * mid-response -- a 500 on a download that was working.
     */
    private static final Pattern UNSAFE_FILENAME_CHARACTERS = Pattern.compile("[^A-Za-z0-9._ -]");

    /** What a name reduced to nothing by the sanitiser is called instead. */
    private static final String FALLBACK_FILENAME = "document";

    private static final String X_CONTENT_TYPE_OPTIONS = "X-Content-Type-Options";

    /**
     * The policy a served document is rendered under: it may load nothing and it runs as its own origin.
     *
     * <p>{@code default-src 'none'} denies every subresource and every script; {@code sandbox} with no allowances
     * puts the document in an opaque origin, so even script that a viewer runs anyway has no same-origin anything to
     * reach. This is what the {@link #INLINE_MIME_TYPES} argument needs to be complete -- PDF is on that list and
     * browser PDF viewers execute embedded JavaScript.
     */
    private static final String INERT_CONTENT_POLICY = "default-src 'none'; sandbox";

    private static final String CONTENT_SECURITY_POLICY = "Content-Security-Policy";

    private DocumentFileResponses() {
    }

    /**
     * The download as a streamed response, under the five headers above.
     */
    public static ResponseEntity<Resource> of(DocumentDownload download) {
        DeedPollSupportingDocument document = download.document();
        MediaType contentType = contentType(document.getMimeType());

        return ResponseEntity.ok()
            .contentType(contentType)
            .header(HttpHeaders.CONTENT_DISPOSITION, disposition(contentType, document).toString())
            .header(X_CONTENT_TYPE_OPTIONS, "nosniff")
            .header(CONTENT_SECURITY_POLICY, INERT_CONTENT_POLICY)
            .cacheControl(CacheControl.noStore())
            // The message converter closes the stream once it is written, which is what DocumentDownload's
            // "the caller closes it" means here.
            .body(new InputStreamResource(download.stream()));
    }

    /**
     * The stored MIME type as a media type, defaulting when there is none and when it will not parse.
     *
     * <p>The unparseable case is not hypothetical: an upload's type is checked against the policy's allowlist, but a
     * row's {@code mimeType} can also be set through the metadata path, where the schema asks only that it is text.
     * Handing an unparseable value straight to a response header is how a header injection gets in, so a value that
     * is not a media type is treated as no value at all.
     */
    private static MediaType contentType(String stored) {
        if (stored == null || stored.isBlank()) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
        try {
            return MediaType.parseMediaType(stored);
        } catch (InvalidMediaTypeException notAMediaType) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
    }

    /**
     * Whether to render in place or save, and under what name.
     */
    private static ContentDisposition disposition(MediaType contentType, DeedPollSupportingDocument document) {
        String bareType = contentType.getType() + "/" + contentType.getSubtype();
        String name = document.getOriginalName() == null ? document.getName() : document.getOriginalName();

        return ContentDisposition
            .builder(INLINE_MIME_TYPES.contains(bareType) ? "inline" : "attachment")
            .filename(safeFilename(name))
            .build();
    }

    /**
     * A filename reduced to characters that are safe in a quoted header value.
     *
     * <p>Anything else -- quotes, backslashes, control characters, non-ASCII -- is dropped rather than escaped,
     * because the name is a convenience for the browser's save dialog and is not load-bearing.
     * {@code Content-Disposition} supports RFC 5987 {@code filename*} for full Unicode; that is worth adding when a
     * real filename is shown to be mangled, not before.
     */
    private static String safeFilename(String name) {
        String cleaned = name == null ? "" : UNSAFE_FILENAME_CHARACTERS.matcher(name).replaceAll("").trim();
        return cleaned.isEmpty() ? FALLBACK_FILENAME : cleaned;
    }
}
