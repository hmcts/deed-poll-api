package uk.gov.hmcts.reform.deedpoll.api.caseworker.controller;

import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MultipartHttpServletRequest;
import uk.gov.hmcts.reform.deedpoll.api.caseworker.dto.DocumentResponse;
import uk.gov.hmcts.reform.deedpoll.auth.CurrentUsers;
import uk.gov.hmcts.reform.deedpoll.domain.documents.DocumentDownload;
import uk.gov.hmcts.reform.deedpoll.domain.documents.DocumentSchemas;
import uk.gov.hmcts.reform.deedpoll.domain.documents.DocumentService;
import uk.gov.hmcts.reform.deedpoll.domain.documents.DocumentStore;
import uk.gov.hmcts.reform.deedpoll.domain.documents.UploadFormInput;
import uk.gov.hmcts.reform.deedpoll.domain.documents.UploadPolicy;
import uk.gov.hmcts.reform.deedpoll.domain.documents.UploadedFile;
import uk.gov.hmcts.reform.deedpoll.entity.DeedPollSupportingDocument;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Supporting documents: the upload and the download.
 *
 * <p>Two of the caseworker operations, and the only two this service exposes on documents. There is deliberately
 * no metadata create, no patch, no delete and no document list -- {@code GET /caseworker/cases/&#123;id&#125;}
 * already carries a case's documents, which is where the detail page reads them, and the remaining domain methods
 * have no consumer. They are implemented and tested in {@code DocumentService}, so exposing one later is a route
 * rather than a design.
 *
 * <p><strong>These are the only two routes in the service that are not JSON in and JSON out</strong>, so they are
 * the only two that touch transport detail: multipart on the way in, and a stream with its own headers on the way
 * out. Everything that is a decision about the service rather than about HTTP is in
 * {@link UploadPolicy} -- the size limit, the accepted-type list and the wording of both refusals -- so that the
 * SSR form in the sibling web app enforces the same rules from the same constants.
 *
 * <p>Neither route is annotated as requiring authentication, and both do: {@code SecurityConfiguration}
 * authenticates {@code anyRequest()} that is not on a short infrastructure allowlist.
 */
@RestController
@RequestMapping("/caseworker/cases/{id}/documents")
public class CaseworkerDocumentController {

    /**
     * The types a browser is asked to render in place rather than save.
     *
     * <p>Steve's call, 2026-09-04, and the reasoning is in the API plan: the sibling web app hardcodes
     * {@code attachment}, so a caseworker checking a birth certificate has to download it, open it, and then
     * remember to delete it from their machine. PDF, JPEG and PNG are what a browser renders natively and what
     * the web app's own viewer handles; TIFF and the two Word types are download-only because nothing renders
     * them.
     *
     * <p><strong>The allowlist is what makes serving user-uploaded bytes inline defensible</strong>, and it is
     * narrower than {@link UploadPolicy#ALLOWED_UPLOAD_MIME_TYPES} on purpose rather than by coincidence. Serving
     * uploaded content inline is normally an XSS vector; it is safe here because the dangerous renderable types
     * -- SVG and HTML above all -- are on neither list, because the {@code Content-Type} is set from the stored
     * value rather than echoed from the request, and because {@code nosniff} stops a browser guessing past it.
     * Anything added to the upload policy is therefore download-only until it is added here as well, which is the
     * right way round for the two lists to fail.
     */
    private static final Set<String> INLINE_MIME_TYPES =
        Set.of("application/pdf", "image/jpeg", "image/png");

    /**
     * Characters kept in a downloaded file's name: letters, digits, and a few separators.
     *
     * <p>The name is whatever the uploading client called the file, so it reaches a response header as
     * caller-supplied text. The prototype stripped {@code "} and nothing else, and the reference records what
     * that leaves: a name containing a newline produces an invalid header, which the server rejects by throwing
     * mid-response -- a 500 on a download that was working.
     */
    private static final Pattern UNSAFE_FILENAME_CHARACTERS = Pattern.compile("[^A-Za-z0-9._ -]");

    /** What a name reduced to nothing by the sanitiser is called instead. */
    private static final String FALLBACK_FILENAME = "document";

    private static final String X_CONTENT_TYPE_OPTIONS = "X-Content-Type-Options";

    /**
     * The policy a served document is rendered under: it may load nothing and it runs as its own origin.
     *
     * <p>{@code default-src 'none'} denies every subresource and every script; {@code sandbox} with no
     * allowances puts the document in an opaque origin, so even script that a viewer runs anyway has no
     * same-origin anything to reach. This is what the {@link #INLINE_MIME_TYPES} argument needs to be
     * complete -- PDF is on that list and browser PDF viewers execute embedded JavaScript.
     */
    private static final String INERT_CONTENT_POLICY = "default-src 'none'; sandbox";

    private static final String CONTENT_SECURITY_POLICY = "Content-Security-Policy";

    private final DocumentService documents;

    public CaseworkerDocumentController(DocumentService documents) {
        this.documents = documents;
    }

    /**
     * Uploads a file and records the document row that carries it.
     *
     * <p>The bytes go to {@link DocumentStore} and the row records the key it returns, so nothing here knows where
     * a blob actually lives -- which is the seam plan 03 replaces with CDAM.
     *
     * <p>Three things are validated before the domain is called, in the order a caller would want them:
     *
     * <ol>
     *   <li><strong>The form fields</strong>, by {@link DocumentSchemas#parseUploadForm}. The reference records
     *       that the prototype read {@code documentTypeId} and {@code direction} as raw body primitives its
     *       validation pipe skipped, so a typo in the direction reached the database and a missing type id came
     *       back as a foreign-key failure. Both are 400s naming the field.</li>
     *   <li><strong>How many files were sent</strong>, because a request with two is ambiguous and picking one
     *       silently stores something the caller did not choose.</li>
     *   <li><strong>The declared type</strong>, against the upload policy.</li>
     * </ol>
     *
     * <p><strong>An absent file is deliberately not checked here.</strong> {@code uploadDocument} refuses a null
     * with "Select a file to upload", so the message stays with the rule and the SSR form gets the same one. The
     * reference makes the same point and has a test that the domain is reached with no file rather than being
     * pre-empted.
     *
     * <p>The case id comes from the path and never from a form field, so a caller cannot upload into somebody
     * else's case by adding {@code deedPollId} to the body. There is nowhere for such a field to land:
     * {@link UploadFormInput} has no component for it.
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public DocumentResponse upload(@PathVariable("id") String id, MultipartHttpServletRequest request) {
        UploadFormInput form = DocumentSchemas.parseUploadForm(formFields(request));
        UploadedFile file = uploadedFile(request);

        return DocumentResponse.from(documents.uploadDocument(id, form, file, CurrentUsers.required()));
    }

    /**
     * Streams a document's stored file back.
     *
     * <p>Scoped to both ids, so another case's document is not readable through this case's URL; the domain 404s
     * on a document that belongs elsewhere and on a metadata row with no file attached yet, which is a legitimate
     * state rather than an error.
     *
     * <p>Five response headers, and each is load-bearing:
     *
     * <ul>
     *   <li>{@code Content-Type} from the stored value, or {@code application/octet-stream} when the row has
     *       none. Set from what this service recorded, never echoed from the request.</li>
     *   <li>{@code Content-Disposition}, {@code inline} for {@link #INLINE_MIME_TYPES} and {@code attachment}
     *       otherwise, with a sanitised filename.</li>
     *   <li>{@code Content-Security-Policy: default-src 'none'; sandbox}, which is what makes the inline
     *       allowlist's argument actually true rather than nearly true. PDF is on that list and a PDF is an
     *       active-content format -- browser viewers run embedded JavaScript, and a PDF served inline runs it
     *       in this origin's context. The policy leaves a benign PDF or image rendering exactly as before and
     *       gives a malicious one nothing to reach.</li>
     *   <li>{@code X-Content-Type-Options: nosniff}, without which a browser may sniff past the declared type
     *       and render something as the thing it guessed. <strong>Spring Security's default header writers
     *       already add this to every response, so this line is deliberate redundancy rather than the only
     *       source</strong> -- checked by deleting it, which no test notices. It is kept because this is the one
     *       route that serves bytes a member of the public uploaded, and a later
     *       {@code headers(h -> h.contentTypeOptions(disable))} somewhere in the filter chain must not quietly
     *       take the protection off it. Security skips a header that is already present, so there is no
     *       duplicate, and a test pins that rather than pinning which of the two set it.</li>
     *   <li>{@code Cache-Control: no-store}, because a document is case data and a shared cache must not keep a
     *       copy.</li>
     * </ul>
     *
     * <p><strong>No {@code Content-Length}, deliberately.</strong> The obvious candidate is the row's
     * {@code sizeBytes}, and it is a recorded number rather than a measured one: a row whose blob was replaced
     * through the metadata path could disagree with it, and a wrong length truncates the download or hangs it.
     * The response is chunked instead, which costs nothing a caseworker notices.
     *
     * <p>A failure after the first byte cannot become a status code -- the headers are already sent -- so a
     * mid-stream error breaks the connection rather than answering 500. That is the correct outcome, and the
     * reference records it: a truncated download is visible to the client, whereas a 200 carrying half a file is
     * not.
     */
    @GetMapping("/{documentId}/file")
    public ResponseEntity<Resource> file(@PathVariable("id") String id,
                                         @PathVariable("documentId") String documentId) {

        DocumentDownload download = documents.downloadDocument(id, documentId);
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
     * The non-file parts of the multipart body, as the schema reads them.
     *
     * <p>Built from the parameter map rather than bound to a {@code Map<String, String>}, so that a parameter
     * sent twice arrives as a list and is refused as "must be text" instead of being silently reduced to its
     * first value. Task 13 records the same trap on {@code ?type=} and the reference records the prototype's
     * version of it: a caller who sent a field twice does not know which value took effect, and a service that
     * picks for them makes that unknowable rather than obvious.
     *
     * <p>Every value is a string, because multipart carries no types. That only shows in {@code direction},
     * whose members are strings anyway.
     */
    private static Map<String, Object> formFields(MultipartHttpServletRequest request) {
        Map<String, Object> fields = new LinkedHashMap<>();
        request.getParameterMap().forEach((name, values) ->
            fields.put(name, values.length == 1 ? values[0] : List.of(values)));
        return fields;
    }

    /**
     * The uploaded file mapped onto the four values the domain takes, or null if none was sent.
     *
     * <p>The mapping is the point of the whole method: {@code uploadDocument} takes an {@link UploadedFile}
     * rather than a {@code MultipartFile}, so the domain has no idea Spring's multipart support exists -- exactly
     * as the reference keeps Multer out of its own. Those four values are all it ever reads.
     *
     * <p>An empty part counts as no file. A form submitted with its file input untouched sends one, and storing a
     * zero-byte blob for it would leave a row that looks downloadable and is not; "Select a file to upload" is
     * the truer answer and is the one the domain gives.
     *
     * @throws uk.gov.hmcts.reform.deedpoll.domain.InvalidInputError if more than one file was sent, or the one
     *     that was is not an accepted type
     */
    private static UploadedFile uploadedFile(MultipartHttpServletRequest request) {
        List<MultipartFile> parts = request.getFiles(UploadPolicy.UPLOAD_FILE_FIELD);
        if (parts.size() > 1) {
            // The transport cannot express "at most one file" the way the reference's parser can, so the count
            // is checked rather than configured -- but the rejection is the policy's, by the reference's own
            // code, so both services say the same thing.
            throw UploadPolicy.uploadLimitRejection("LIMIT_FILE_COUNT");
        }
        MultipartFile part = parts.isEmpty() ? null : parts.get(0);
        if (part == null || part.isEmpty()) {
            return null;
        }

        // Parameters stripped before asking, which the policy's own comment says is the transport's job: a
        // browser may send "application/pdf; charset=binary", and the policy matches exactly so that
        // "application/" can never be read as a prefix.
        String mimeType = withoutParameters(part.getContentType());
        if (!UploadPolicy.isAllowedUploadType(mimeType)) {
            throw UploadPolicy.uploadTypeRejection(mimeType);
        }

        return UploadedFile.of(bytes(part), part.getOriginalFilename(), mimeType);
    }

    private static byte[] bytes(MultipartFile part) {
        try {
            return part.getBytes();
        } catch (IOException failed) {
            // Buffered in memory by the time a handler runs, so this is a genuinely exceptional read failure
            // and not something a caller can correct -- a 500 is the honest answer.
            throw new UncheckedIOException("Could not read the uploaded file", failed);
        }
    }

    /**
     * A {@code Content-Type} reduced to type and subtype, or null if there was none.
     */
    private static String withoutParameters(String contentType) {
        if (contentType == null) {
            return null;
        }
        int parameters = contentType.indexOf(';');
        return (parameters < 0 ? contentType : contentType.substring(0, parameters)).trim();
    }

    /**
     * The stored MIME type as a media type, defaulting when there is none and when it will not parse.
     *
     * <p>The unparseable case is not hypothetical: an upload's type is checked against the policy's allowlist,
     * but a row's {@code mimeType} can also be set through the metadata path, where the schema asks only that it
     * is text. Handing an unparseable value straight to a response header is how a header injection gets in, so
     * a value that is not a media type is treated as no value at all.
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
     * {@code Content-Disposition} supports RFC 5987 {@code filename*} for full Unicode; that is worth adding when
     * a real filename is shown to be mangled, not before.
     */
    private static String safeFilename(String name) {
        String cleaned = name == null ? "" : UNSAFE_FILENAME_CHARACTERS.matcher(name).replaceAll("").trim();
        return cleaned.isEmpty() ? FALLBACK_FILENAME : cleaned;
    }
}
