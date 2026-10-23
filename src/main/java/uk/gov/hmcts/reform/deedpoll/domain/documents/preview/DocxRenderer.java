package uk.gov.hmcts.reform.deedpoll.domain.documents.preview;

import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.BufferedSink;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uk.gov.hmcts.reform.deedpoll.domain.InvalidInputError;
import uk.gov.hmcts.reform.deedpoll.domain.documents.UploadContentPolicy;
import uk.gov.hmcts.reform.deedpoll.domain.documents.UploadedFile;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

/**
 * A DOCX's preview is the PDF Docmosis Tornado's {@code /rs/convert} makes of it (decision 1).
 *
 * <p>The request is the one em-stitching-api's {@code DocmosisConverter} sends -- {@code multipart/form-data} with
 * {@code accessKey}, {@code outputName} and {@code file}, {@code Accept: application/pdf} -- with what that class
 * does not do. The file goes as {@code document.docx} under the DOCX type and the output is always
 * {@code preview.pdf}, so no original filename leaves the service. It is posted only to the configured endpoint,
 * once: no retry, because a conversion whose answer was lost is ambiguous (so the body is one-shot, and no answer
 * invites OkHttp to resend it), and no redirect, because a {@code Location} is not an endpoint anybody configured.
 * Connect, read and the whole call are each bounded by what is left of the budget.
 *
 * <p>Before anything is sent, the DOCX is held to {@link UploadContentPolicy} as an upload is, so a malformed,
 * encrypted or active package -- which can only be an old row -- is refused here as an invalid document and never
 * reaches the converter.
 *
 * <p>The answer is accepted only as a complete PDF: a 2xx, {@code application/pdf}, written into the workspace no
 * further than the output ceiling, ending in {@code %%EOF}, and then held to {@link PdfRenderer}'s structural checks.
 * It is never parsed, so its pages are counted by the viewer, not here. Anything else is
 * {@link PreviewFailure#UNAVAILABLE}, the converter's fault rather than the document's. Diagnostics carry the
 * status or an exception's class name, never the provider's body, the key, the endpoint or a name from the document.
 */
final class DocxRenderer implements SourceRenderer {

    private static final Logger LOG = LoggerFactory.getLogger(DocxRenderer.class);

    static final String SOURCE_NAME = "document.docx";
    static final String OUTPUT_NAME = "preview.pdf";

    private static final MediaType DOCX = MediaType.get(PreviewFormat.DOCX.mimeType());
    private static final String PDF = "application/pdf";
    private static final int CHUNK = 64 * 1024;
    private static final Duration SHORTEST = Duration.ofMillis(1);

    private final PreviewProperties properties;
    private final PdfRenderer pdf;
    private final OkHttpClient transport;

    DocxRenderer(PreviewProperties properties, PdfRenderer pdf) {
        this.properties = properties;
        this.pdf = pdf;
        this.transport = new OkHttpClient.Builder().retryOnConnectionFailure(false)
            .followRedirects(false).followSslRedirects(false).proxy(Proxy.NO_PROXY).build();
    }

    @Override
    public Rendition render(Path source, PreviewWorkspace workspace, PreviewBudget budget) {
        requireSafePackage(source);
        budget.checkpoint();
        Path converted = convert(source, workspace, budget);
        requireComplete(converted);
        SourceRenderer.Rendition rendition;
        try {
            rendition = pdf.render(converted, workspace, budget);
        } catch (PreviewError refused) {
            if (refused.failure() == PreviewFailure.INVALID_DOCUMENT) {
                // The DOCX passed; a PDF that does not is the converter's answer, not the applicant's document.
                LOG.warn("A DOCX preview conversion answered with something that does not look like a PDF");
                throw new PreviewError(PreviewFailure.UNAVAILABLE);
            }
            throw refused;
        }
        return rendition;
    }

    private static void requireSafePackage(Path source) {
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(source);
        } catch (IOException scratch) {
            throw new PreviewError(PreviewFailure.UNAVAILABLE);
        }
        try {
            UploadContentPolicy.verify(UploadedFile.of(bytes, SOURCE_NAME, PreviewFormat.DOCX.mimeType()));
        } catch (InvalidInputError refused) {
            throw new PreviewError(PreviewFailure.INVALID_DOCUMENT);
        }
    }

    private Path convert(Path source, PreviewWorkspace workspace, PreviewBudget budget) {
        PreviewProperties.Converter converter = properties.converter();
        Request request = new Request.Builder()
            .url(converter.convertUri().toString())
            .header("Accept", PDF)
            .post(new OneShot(new MultipartBody.Builder().setType(MultipartBody.FORM)
                      .addFormDataPart("accessKey", converter.accessKey())
                      .addFormDataPart("outputName", OUTPUT_NAME)
                      .addFormDataPart("file", SOURCE_NAME, RequestBody.create(source.toFile(), DOCX))
                      .build()))
            .build();
        Duration read = atLeastOne(budget.deadlineFor(converter.readTimeout()));
        Call call = transport.newBuilder()
            .connectTimeout(atLeastOne(budget.deadlineFor(converter.connectTimeout())))
            .readTimeout(read)
            .writeTimeout(read)
            .callTimeout(atLeastOne(budget.remaining()))
            .build()
            .newCall(request);
        Path converted = workspace.newFile(".pdf");
        try (Response response = call.execute()) {
            requireAccepted(response);
            ResponseBody body = response.body();
            if (body.contentLength() > properties.maxOutputBytes()) {
                throw new PreviewError(PreviewFailure.LIMIT_EXCEEDED);
            }
            copy(body.byteStream(), workspace.boundedOutput(converted, properties.maxOutputBytes()), budget);
        } catch (PreviewError own) {
            throw own;
        } catch (IOException | RuntimeException failed) {
            budget.checkpoint();
            // The class name only: OkHttp's messages carry the endpoint, and nothing from the provider is trusted.
            LOG.warn("A DOCX preview conversion failed ({})", failed.getClass().getSimpleName());
            throw new PreviewError(PreviewFailure.UNAVAILABLE);
        }
        return converted;
    }

    private static void requireAccepted(Response response) {
        int status = response.code();
        if (status == 413) {
            LOG.warn("A DOCX preview conversion was refused as too large (status 413)");
            throw new PreviewError(PreviewFailure.LIMIT_EXCEEDED);
        }
        if (status < 200 || status > 299) {
            // The status only: a provider body may echo the document or the key.
            LOG.warn("A DOCX preview conversion failed (status {})", status);
            throw new PreviewError(PreviewFailure.UNAVAILABLE);
        }
        MediaType type = response.body().contentType();
        if (type == null || !PDF.equals(type.type() + "/" + type.subtype())) {
            LOG.warn("A DOCX preview conversion answered with something other than a PDF");
            throw new PreviewError(PreviewFailure.UNAVAILABLE);
        }
    }

    private static void copy(InputStream in, OutputStream bounded, PreviewBudget budget) throws IOException {
        try (InputStream body = in; OutputStream out = bounded) {
            byte[] chunk = new byte[CHUNK];
            int read;
            while ((read = body.read(chunk)) >= 0) {
                budget.checkpoint();
                out.write(chunk, 0, read);
            }
        }
    }

    /** A converter answer cut short can still parse, so it must end as a whole PDF does. */
    private static void requireComplete(Path converted) {
        try (RandomAccessFile file = new RandomAccessFile(converted.toFile(), "r")) {
            long length = file.length();
            int window = (int) Math.min(length, PdfRenderer.EOF_WINDOW);
            byte[] tail = new byte[window];
            file.seek(length - window);
            file.readFully(tail);
            int end = window;
            while (end > 0 && isWhitespace(tail[end - 1])) {
                end--;
            }
            if (end < PdfRenderer.EOF_MARKER.length) {
                throw incomplete();
            }
            for (int index = 0; index < PdfRenderer.EOF_MARKER.length; index++) {
                if (tail[end - PdfRenderer.EOF_MARKER.length + index] != PdfRenderer.EOF_MARKER[index]) {
                    throw incomplete();
                }
            }
        } catch (IOException scratch) {
            throw new PreviewError(PreviewFailure.UNAVAILABLE);
        }
    }

    private static PreviewError incomplete() {
        LOG.warn("A DOCX preview conversion answered with an incomplete PDF");
        return new PreviewError(PreviewFailure.UNAVAILABLE);
    }

    private static boolean isWhitespace(byte value) {
        return value == 0 || value == 9 || value == 10 || value == 12 || value == 13 || value == 32;
    }

    /**
     * The multipart body, marked as one that cannot be sent twice. {@code retryOnConnectionFailure(false)} stops
     * OkHttp replaying after a dropped connection, but not after a {@code 503} with {@code Retry-After: 0}, which its
     * follow-up interceptor resends for any replayable body. A one-shot body is never resent, whatever the answer.
     */
    private static final class OneShot extends RequestBody {

        private final RequestBody body;

        OneShot(RequestBody body) {
            this.body = body;
        }

        @Override
        public MediaType contentType() {
            return body.contentType();
        }

        @Override
        public long contentLength() throws IOException {
            return body.contentLength();
        }

        @Override
        public void writeTo(BufferedSink sink) throws IOException {
            body.writeTo(sink);
        }

        @Override
        public boolean isOneShot() {
            return true;
        }
    }

    /** OkHttp reads a zero timeout as none at all, so what is left of the budget is never rounded down to it. */
    private static Duration atLeastOne(Duration deadline) {
        return deadline.compareTo(SHORTEST) < 0 ? SHORTEST : deadline;
    }
}
