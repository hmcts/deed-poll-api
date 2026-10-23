package uk.gov.hmcts.reform.deedpoll.domain.documents.preview;

import org.springframework.boot.context.properties.ConfigurationProperties;
import uk.gov.hmcts.reform.deedpoll.domain.documents.UploadPolicy;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.Locale;
import java.util.Set;

/**
 * The preview's resource limits and the DOCX converter's settings: {@code deedpoll.documents.preview}.
 *
 * <p><strong>These are preview limits only.</strong> They do not change what an upload accepts, what an original
 * download serves or the generated-deed staging cap: the input ceiling is read from
 * {@link UploadPolicy#MAX_UPLOAD_BYTES} and never the other way round, so a preview can never refuse a document
 * the service accepted for a reason of size it did not already state at upload.
 *
 * <p>Each limit defaults to the plan's engineering figure when unset, and a value that would switch a limit off
 * (zero, negative) refuses to start. Reaching one at run time is {@link PreviewFailure#LIMIT_EXCEEDED}, never a
 * truncated PDF.
 *
 * @param requestBudget conversion and rendering, checked between spooled chunks (decision 2); under the gateways'
 *     30 s. A stalled store read is bounded by the store client's own timeouts, as a download is
 * @param maxOutputBytes the largest preview PDF served
 * @param maxPages the most pages a preview this service builds may have: a TIFF's frames, an image's one page. A
 *     PDF or a converted DOCX is served unparsed, so the viewer holds it to this limit, not the service
 * @param maxFramePixels the most decoded pixels in one image or TIFF frame, checked before it is decoded
 * @param maxConcurrentConversions non-PDF previews at once on one pod (decision 5); there is no queue
 * @param busyRetryAfter the {@code Retry-After} on {@link PreviewFailure#BUSY}
 * @param converter the DOCX converter; unconfigured is a DOCX preview answering {@link PreviewFailure#UNAVAILABLE}
 */
@ConfigurationProperties(prefix = "deedpoll.documents.preview")
public record PreviewProperties(Duration requestBudget,
                                Long maxOutputBytes,
                                Integer maxPages,
                                Long maxFramePixels,
                                Integer maxConcurrentConversions,
                                Duration busyRetryAfter,
                                Converter converter) {

    public static final Duration REQUEST_BUDGET = Duration.ofSeconds(25);
    public static final long MAX_OUTPUT_BYTES = 50L * 1024 * 1024;
    public static final int MAX_PAGES = 100;
    public static final long MAX_FRAME_PIXELS = 25_000_000L;
    public static final int MAX_CONCURRENT_CONVERSIONS = 3;
    public static final Duration BUSY_RETRY_AFTER = Duration.ofSeconds(5);

    /** Both Application Gateways end a request here, so a budget must finish before it. */
    static final Duration GATEWAY_TIMEOUT = Duration.ofSeconds(30);

    /**
     * The longest {@code Retry-After} published; a bounded wait, not an outage notice.
     */
    static final Duration MAX_RETRY_AFTER = Duration.ofSeconds(60);

    public PreviewProperties {
        requestBudget = requestBudget == null ? REQUEST_BUDGET : requestBudget;
        maxOutputBytes = maxOutputBytes == null ? MAX_OUTPUT_BYTES : maxOutputBytes;
        maxPages = maxPages == null ? MAX_PAGES : maxPages;
        maxFramePixels = maxFramePixels == null ? MAX_FRAME_PIXELS : maxFramePixels;
        maxConcurrentConversions = maxConcurrentConversions == null
            ? MAX_CONCURRENT_CONVERSIONS : maxConcurrentConversions;
        busyRetryAfter = busyRetryAfter == null ? BUSY_RETRY_AFTER : busyRetryAfter;
        converter = converter == null ? new Converter(null, null, null, null) : converter;

        if (!positive(requestBudget) || requestBudget.compareTo(GATEWAY_TIMEOUT) >= 0) {
            throw new IllegalArgumentException("the preview request budget must be positive and under 30 seconds");
        }
        if (maxOutputBytes < 1 || maxPages < 1 || maxFramePixels < 1 || maxConcurrentConversions < 1) {
            throw new IllegalArgumentException("every preview limit must be at least 1");
        }
        if (busyRetryAfter.compareTo(Duration.ofSeconds(1)) < 0 || busyRetryAfter.compareTo(MAX_RETRY_AFTER) > 0) {
            throw new IllegalArgumentException("the preview busy Retry-After must be between 1 and 60 seconds");
        }
        if (converter.connectTimeout().plus(converter.readTimeout()).compareTo(requestBudget) > 0) {
            throw new IllegalArgumentException("the converter's connect and read timeouts must fit the request budget");
        }
    }

    /** The largest source previewed: the upload ceiling, which this does not change. */
    public long maxInputBytes() {
        return UploadPolicy.MAX_UPLOAD_BYTES;
    }

    /**
     * Refuses a preview over the output ceiling.
     *
     * @throws PreviewError {@link PreviewFailure#LIMIT_EXCEEDED} over {@link #maxOutputBytes}
     */
    public void requireOutputBytes(long bytes) {
        if (bytes > maxOutputBytes) {
            throw new PreviewError(PreviewFailure.LIMIT_EXCEEDED);
        }
    }

    /**
     * A document must have at least one page, and no more than {@link #maxPages}.
     *
     * @throws PreviewError {@link PreviewFailure#INVALID_DOCUMENT} for none, {@link PreviewFailure#LIMIT_EXCEEDED}
     *     for too many
     */
    public void requirePages(long pages) {
        if (pages < 1) {
            throw new PreviewError(PreviewFailure.INVALID_DOCUMENT);
        }
        if (pages > maxPages) {
            throw new PreviewError(PreviewFailure.LIMIT_EXCEEDED);
        }
    }

    /**
     * A frame's declared dimensions, checked before anything is decoded.
     *
     * @throws PreviewError {@link PreviewFailure#INVALID_DOCUMENT} for a dimension under one,
     *     {@link PreviewFailure#LIMIT_EXCEEDED} for more than {@link #maxFramePixels} pixels
     */
    public void requireFrame(long width, long height) {
        if (width < 1 || height < 1) {
            throw new PreviewError(PreviewFailure.INVALID_DOCUMENT);
        }
        // Divided rather than multiplied, so two huge declared dimensions cannot overflow past the check.
        if (width > maxFramePixels || height > maxFramePixels / width) {
            throw new PreviewError(PreviewFailure.LIMIT_EXCEEDED);
        }
    }

    /**
     * The DOCX converter: Docmosis Tornado's {@code /rs/convert} (decision 1), a trusted operator setting.
     *
     * <p>An unset {@code url} or {@code accessKey} leaves the converter unconfigured: the service starts, every
     * other preview and every download works, and a DOCX preview answers {@link PreviewFailure#UNAVAILABLE} once
     * its caller has been authorised. A {@code url} that <em>is</em> set but is not an absolute {@code https} base
     * with no credentials, query or fragment refuses to start, because a mistyped endpoint should fail the deploy
     * and not each preview. Plain {@code http} is accepted for a loopback host only, which is WireMock's.
     *
     * <p>The key is never printed: {@link #toString} redacts it.
     *
     * @param url the converter's base, e.g. {@code https://docmosis.aat.platform.hmcts.net}; {@code /rs/convert}
     *     is appended
     * @param accessKey the Tornado access key, sent only as the request body's {@code accessKey} part
     * @param connectTimeout the longest a connection may take; never longer than what is left of the budget
     * @param readTimeout the longest the converter may take to answer; never longer than what is left of the budget
     */
    public record Converter(String url, String accessKey, Duration connectTimeout, Duration readTimeout) {

        public static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
        public static final Duration READ_TIMEOUT = Duration.ofSeconds(20);

        static final String CONVERT_PATH = "/rs/convert";

        private static final Set<String> LOOPBACK = Set.of("localhost", "127.0.0.1", "[::1]", "::1");

        public Converter {
            url = normalise(url);
            accessKey = accessKey == null || accessKey.isBlank() ? null : accessKey.trim();
            connectTimeout = connectTimeout == null ? CONNECT_TIMEOUT : connectTimeout;
            readTimeout = readTimeout == null ? READ_TIMEOUT : readTimeout;
            if (!positive(connectTimeout) || !positive(readTimeout)) {
                throw new IllegalArgumentException("the converter's timeouts must be positive");
            }
        }

        /** Whether both the endpoint and the key are set. */
        public boolean configured() {
            return url != null && accessKey != null;
        }

        /**
         * Where a conversion is posted.
         *
         * @throws PreviewError {@link PreviewFailure#UNAVAILABLE} if the converter is not configured
         */
        public URI convertUri() {
            if (!configured()) {
                throw new PreviewError(PreviewFailure.UNAVAILABLE);
            }
            return URI.create(url + CONVERT_PATH);
        }

        @Override
        public String toString() {
            return "Converter[url=" + url + ", accessKey=" + (accessKey == null ? "unset" : "redacted")
                + ", connectTimeout=" + connectTimeout + ", readTimeout=" + readTimeout + "]";
        }

        private static String normalise(String configured) {
            if (configured == null || configured.isBlank()) {
                return null;
            }
            String trimmed = configured.trim();
            String base = trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
            URI uri;
            try {
                uri = new URI(base);
            } catch (URISyntaxException notAUri) {
                throw new IllegalArgumentException("the converter url is not a URL");
            }
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            String host = uri.getHost();
            boolean secure = "https".equals(scheme);
            boolean loopback = "http".equals(scheme) && host != null
                && LOOPBACK.contains(host.toLowerCase(Locale.ROOT));
            if (!uri.isAbsolute() || host == null || !(secure || loopback) || uri.getRawUserInfo() != null
                || uri.getRawQuery() != null || uri.getRawFragment() != null) {
                throw new IllegalArgumentException(
                    "the converter url must be an absolute https base with no credentials, query or fragment");
            }
            return base;
        }
    }

    private static boolean positive(Duration value) {
        return value != null && !value.isNegative() && !value.isZero();
    }
}
