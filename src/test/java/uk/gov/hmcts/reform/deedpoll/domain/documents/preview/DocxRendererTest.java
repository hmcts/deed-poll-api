package uk.gov.hmcts.reform.deedpoll.domain.documents.preview;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.slf4j.LoggerFactory;
import uk.gov.hmcts.reform.deedpoll.domain.NotFoundError;
import uk.gov.hmcts.reform.deedpoll.domain.documents.UploadContentPolicy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static com.github.tomakehurst.wiremock.client.WireMock.aMultipart;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.binaryEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.Assertions.fail;

/**
 * The DOCX preview against a stub Docmosis Tornado: the request it sends, and every answer it must refuse.
 *
 * <p>The stub's PDFs are made here, so these tests prove the protocol, the bounds and the page count read back from
 * whatever the converter returned. They do not prove Word layout fidelity: that a real Tornado, with the platform's
 * fonts, lays out {@code three-pages.docx} as three pages is an acceptance check against AAT, not a local fact.
 */
class DocxRendererTest {

    private static final String ACCESS_KEY = "synthetic-docmosis-access-key";
    private static final String CONVERT = "/rs/convert";
    private static final String PROVIDER_BODY = "Tornado says: template synthetic-secret-name.docx failed";
    private static final String DOCX = PreviewFormat.DOCX.mimeType();

    private final WireMockServer converter = new WireMockServer(WireMockConfiguration.options().dynamicPort());
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private final List<Logger> loggers = Stream.of(DocxRenderer.class, PdfRenderer.class, UploadContentPolicy.class)
        .map(type -> (Logger) LoggerFactory.getLogger(type)).toList();

    @TempDir
    Path scratch;

    private ConversionCapacity capacity = new ConversionCapacity(3, Duration.ofSeconds(5));

    @BeforeEach
    void start() {
        converter.start();
        logs.start();
        loggers.forEach(logger -> logger.addAppender(logs));
    }

    @AfterEach
    void stop() {
        loggers.forEach(logger -> logger.detachAppender(logs));
        converter.stop();
    }

    @Nested
    @DisplayName("the request")
    class Protocol {

        @Test
        @DisplayName("is Tornado's multipart convert: the key, a fixed output name and the bytes under a generic name")
        void contract() throws IOException {
            byte[] original = DocumentPreviewFixtures.read(DocumentPreviewFixtures.MULTI_PAGE_DOCX);
            byte[] copy = original.clone();
            converter.stubFor(post(urlPathEqualTo(CONVERT))
                .withHeader("Accept", equalTo("application/pdf"))
                .withHeader("Content-Type", containing("multipart/form-data"))
                .withMultipartRequestBody(aMultipart("accessKey").withBody(equalTo(ACCESS_KEY)))
                .withMultipartRequestBody(aMultipart("outputName").withBody(equalTo("preview.pdf")))
                .withMultipartRequestBody(aMultipart("file")
                    .withHeader("Content-Disposition", containing("filename=\"document.docx\""))
                    .withHeader("Content-Type", equalTo(DOCX))
                    .withBody(binaryEqualTo(original)))
                .willReturn(pdf(DocumentPreviewFixtures.pdf(DocumentPreviewFixtures.MULTI_PAGE_DOCX_PAGES))));

            try (RenderedPreview preview = unparsed(() -> render(original))) {
                assertThat(preview.pages()).isEmpty();
                try (PDDocument document = Loader.loadPDF(bytes(preview))) {
                    assertThat(document.getNumberOfPages()).isEqualTo(DocumentPreviewFixtures.MULTI_PAGE_DOCX_PAGES);
                }
            }
            assertThat(original).isEqualTo(copy);
            assertThat(capacity.available()).isEqualTo(3);
            assertScratchEmpty();

            LoggedRequest sent = converter.findAll(postRequestedFor(urlPathEqualTo(CONVERT))).getFirst();
            assertThat(converter.findAll(anyRequestedFor(anyUrl()))).hasSize(1);
            assertThat(sent.getUrl()).isEqualTo(CONVERT);
            // The key travels in the body only, never as a header or in the query.
            assertThat(sent.getHeaders().all().toString()).doesNotContain(ACCESS_KEY);
            assertThat(sent.getQueryParams()).isEmpty();
        }

        @Test
        @DisplayName("a table-and-image DOCX's preview is the converted PDF as answered, however many pages it has")
        void tableAndImage() throws IOException {
            byte[] original = DocumentPreviewFixtures.read(DocumentPreviewFixtures.TABLE_AND_IMAGE_DOCX);
            byte[] answer = DocumentPreviewFixtures.pdf(DocumentPreviewFixtures.TABLE_AND_IMAGE_DOCX_PAGES);
            converter.stubFor(post(urlPathEqualTo(CONVERT))
                .withMultipartRequestBody(aMultipart("file").withBody(binaryEqualTo(original)))
                .willReturn(pdf(answer)));
            try (RenderedPreview preview = unparsed(() -> render(original))) {
                assertThat(preview.pages()).isEmpty();
                assertThat(bytes(preview)).isEqualTo(answer);
            }
        }

        @Test
        @DisplayName("goes once, with no retry after a dropped connection")
        void noRetry() {
            converter.stubFor(post(urlPathEqualTo(CONVERT))
                .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));
            assertFailure(PreviewFailure.UNAVAILABLE);
            assertThat(converter.findAll(anyRequestedFor(anyUrl()))).hasSize(1);
        }

        @Test
        @DisplayName("does not follow a redirect to wherever its Location says")
        void noRedirect() {
            converter.stubFor(post(urlPathEqualTo(CONVERT))
                .willReturn(aResponse().withStatus(307).withHeader("Location", converter.url("/elsewhere"))));
            converter.stubFor(post(urlPathEqualTo("/elsewhere")).willReturn(pdf(DocumentPreviewFixtures.pdf())));
            assertFailure(PreviewFailure.UNAVAILABLE);
            assertThat(converter.findAll(postRequestedFor(urlPathEqualTo("/elsewhere")))).isEmpty();
        }

        @Test
        @DisplayName("goes once when the converter answers 503 with Retry-After: 0, which OkHttp would replay")
        void noReplayOfAnImmediateRetry() {
            converter.stubFor(post(urlPathEqualTo(CONVERT))
                .willReturn(aResponse().withStatus(503).withHeader("Retry-After", "0")));
            assertFailure(PreviewFailure.UNAVAILABLE);
            assertThat(converter.findAll(anyRequestedFor(anyUrl()))).hasSize(1);
        }

        @ParameterizedTest(name = "{0} {1}: {2}")
        @CsvSource(delimiter = '|', value = {
            "408 | Retry-After        | 0",
            "421 | Content-Type       | text/plain",
            "401 | WWW-Authenticate   | Basic realm=\"tornado\"",
            "407 | Proxy-Authenticate | Basic realm=\"proxy\"",
            "300 | Location           | /rs/convert",
            "301 | Location           | /rs/convert",
            "302 | Location           | /rs/convert",
            "303 | Location           | /rs/convert",
            "307 | Location           | /rs/convert",
            "308 | Location           | /rs/convert",
        })
        @DisplayName("goes once whatever follow-up the answer invites: a timeout, a misdirect, a challenge, a redirect")
        void noFollowUp(int status, String header, String value) {
            converter.stubFor(post(urlPathEqualTo(CONVERT))
                .willReturn(aResponse().withStatus(status).withHeader(header, value)));
            assertFailure(PreviewFailure.UNAVAILABLE);
            assertThat(converter.findAll(anyRequestedFor(anyUrl()))).hasSize(1);
        }
    }

    @Nested
    @DisplayName("nothing is sent")
    class NothingSent {

        @ParameterizedTest(name = "with the key \"{0}\"")
        @ValueSource(strings = {"", "   "})
        @DisplayName("while the access key is blank, once the source is read, and nothing is held")
        void blankCredentials(String key) {
            TrackedSource source = source();
            assertFailure(renderer(properties(converter.baseUrl(), key, null, null, null)), source,
                          budget(), PreviewFailure.UNAVAILABLE);
            assertThat(source.opened).isTrue();
            assertNothingSent();
        }

        @Test
        @DisplayName("while the endpoint is unset")
        void unsetEndpoint() {
            TrackedSource source = source();
            assertFailure(renderer(properties(null, ACCESS_KEY, null, null, null)), source, budget(),
                          PreviewFailure.UNAVAILABLE);
            assertThat(source.opened).isTrue();
        }

        @Test
        @DisplayName("when the source read is refused or missing: its own exception, and the permit comes back")
        void refusedSource() {
            NotFoundError missing = new NotFoundError("Document not found");
            Throwable thrown = catchThrowable(() -> renderer().render(PreviewFormat.DOCX, () -> {
                throw missing;
            }, budget()));
            assertThat(thrown).isSameAs(missing);
            assertThat(capacity.available()).isEqualTo(3);
            assertScratchEmpty();
            assertNothingSent();
        }

        @Test
        @DisplayName("for a malformed, macro-enabled, encrypted or legacy package: an invalid document")
        void unsafePackages() {
            byte[] docx = DocumentPreviewFixtures.read(DocumentPreviewFixtures.MULTI_PAGE_DOCX);
            for (byte[] refused : List.of(Arrays.copyOf(docx, docx.length / 2),
                                          DocumentPreviewFixtures.withMacros(docx),
                                          DocumentPreviewFixtures.encrypted(docx),
                                          DocumentPreviewFixtures.pdf(),
                                          new byte[] {'P', 'K', 3, 4})) {
                assertFailure(refused, PreviewFailure.INVALID_DOCUMENT);
            }
            assertNothingSent();
        }

        @Test
        @DisplayName("when the pod is busy, once the source is read and closed")
        void busy() {
            capacity = new ConversionCapacity(1, Duration.ofSeconds(5));
            try (ConversionCapacity.Permit held = capacity.acquire(PreviewFormat.TIFF)) {
                TrackedSource source = new TrackedSource(
                    DocumentPreviewFixtures.read(DocumentPreviewFixtures.MULTI_PAGE_DOCX));
                Throwable thrown = catchThrowable(() -> renderer().render(PreviewFormat.DOCX, source, budget()));
                assertThat(thrown).isInstanceOfSatisfying(PreviewError.class,
                    error -> assertThat(error.failure()).isEqualTo(PreviewFailure.BUSY));
                assertThat(source.opened).isTrue();
                assertThat(source.closed).isTrue();
                assertScratchEmpty();
            }
            assertNothingSent();
        }
    }

    @Nested
    @DisplayName("the converter's answer")
    class Answers {

        @ParameterizedTest(name = "status {0}")
        @ValueSource(ints = {400, 401, 403, 404, 415, 500, 502, 503, 504})
        @DisplayName("a refusal, including rejected credentials, is unavailable and its body goes nowhere")
        void refusals(int status) {
            converter.stubFor(post(urlPathEqualTo(CONVERT))
                .willReturn(aResponse().withStatus(status).withHeader("Content-Type", "text/plain")
                                .withBody(PROVIDER_BODY + " " + ACCESS_KEY)));
            assertFailure(PreviewFailure.UNAVAILABLE);
            assertThat(converter.findAll(anyRequestedFor(anyUrl()))).hasSize(1);
        }

        @Test
        @DisplayName("413 is the document being too large for the converter")
        void tooLargeForTheConverter() {
            converter.stubFor(post(urlPathEqualTo(CONVERT)).willReturn(aResponse().withStatus(413)));
            assertFailure(PreviewFailure.LIMIT_EXCEEDED);
        }

        @Test
        @DisplayName("a 200 that is not application/pdf is unavailable, even when its bytes are a PDF")
        void notPdfType() {
            converter.stubFor(post(urlPathEqualTo(CONVERT)).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "text/html").withBody(DocumentPreviewFixtures.pdf())));
            assertFailure(PreviewFailure.UNAVAILABLE);
            converter.resetAll();
            converter.stubFor(post(urlPathEqualTo(CONVERT)).willReturn(aResponse().withStatus(200)
                .withBody(DocumentPreviewFixtures.pdf())));
            assertFailure(PreviewFailure.UNAVAILABLE);
        }

        @Test
        @DisplayName("an application/pdf answer that is empty, not a PDF or cut short is unavailable")
        void notACompletePdf() {
            byte[] whole = DocumentPreviewFixtures.pdf();
            for (byte[] answer : List.of(new byte[0], PROVIDER_BODY.getBytes(StandardCharsets.UTF_8),
                                         Arrays.copyOf(whole, whole.length - 200), Arrays.copyOf(whole, 40),
                                         ("%PDF-1.7 nothing\n%%EOF\n" + " ".repeat(2048))
                                             .getBytes(StandardCharsets.US_ASCII),
                                         "not a pdf\n%%EOF\n".getBytes(StandardCharsets.US_ASCII))) {
                converter.resetAll();
                converter.stubFor(post(urlPathEqualTo(CONVERT)).willReturn(pdf(answer)));
                unparsed(() -> {
                    assertFailure(PreviewFailure.UNAVAILABLE);
                    return null;
                });
            }
        }

        @Test
        @DisplayName("a whole-looking answer is served unparsed, whatever is inside it: the viewer reads it")
        void servedUnparsed() throws IOException {
            byte[] whole = DocumentPreviewFixtures.pdf();
            byte[] encrypted;
            try (PDDocument document = Loader.loadPDF(whole)) {
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
            byte[] bomb = DocumentPreviewFixtures.objectStreamBomb(64 * 1024 * 1024);
            for (byte[] answer : List.of("%PDF-1.7 nothing\n%%EOF\n".getBytes(StandardCharsets.US_ASCII),
                                         encrypted, empty, bomb)) {
                converter.resetAll();
                converter.stubFor(post(urlPathEqualTo(CONVERT)).willReturn(pdf(answer)));
                try (RenderedPreview preview = unparsed(() -> render())) {
                    assertThat(preview.pages()).isEmpty();
                    assertThat(bytes(preview)).isEqualTo(answer);
                }
                assertThat(capacity.available()).isEqualTo(3);
            }
            assertScratchEmpty();
        }

        @Test
        @DisplayName("a body cut off by the connection is unavailable")
        void brokenTransfer() {
            converter.stubFor(post(urlPathEqualTo(CONVERT))
                .willReturn(pdf(DocumentPreviewFixtures.pdf()).withFault(Fault.MALFORMED_RESPONSE_CHUNK)));
            assertFailure(PreviewFailure.UNAVAILABLE);
        }

        @Test
        @DisplayName("an answer over the output ceiling is a limit, declared or streamed")
        void excessOutput() {
            byte[] answer = DocumentPreviewFixtures.pdf(3);
            PreviewRenderer small = renderer(new PreviewProperties(null, (long) answer.length - 1, null, null, null,
                                                                   null, null, converterAt(converter.baseUrl())));
            converter.stubFor(post(urlPathEqualTo(CONVERT)).willReturn(pdf(answer)));
            assertFailure(small, PreviewFailure.LIMIT_EXCEEDED);
            converter.resetAll();
            converter.stubFor(post(urlPathEqualTo(CONVERT))
                .willReturn(pdf(answer).withChunkedDribbleDelay(4, 10)));
            assertFailure(small, PreviewFailure.LIMIT_EXCEEDED);
        }

        @Test
        @DisplayName("a converted PDF over the page limit is served: the viewer counts its pages, not this service")
        void pagesAreTheViewers() {
            converter.stubFor(post(urlPathEqualTo(CONVERT)).willReturn(pdf(DocumentPreviewFixtures.pdf(3))));
            PreviewRenderer two = renderer(new PreviewProperties(null, null, 2, null, null, null, null,
                                                                 converterAt(converter.baseUrl())));
            try (RenderedPreview preview = unparsed(() -> two.render(PreviewFormat.DOCX, source(), budget()))) {
                assertThat(preview.pages()).isEmpty();
            }
        }

        @Test
        @DisplayName("no answer within the read timeout is unavailable")
        void readTimeout() {
            converter.stubFor(post(urlPathEqualTo(CONVERT))
                .willReturn(pdf(DocumentPreviewFixtures.pdf()).withFixedDelay(2_000)));
            assertFailure(renderer(properties(converter.baseUrl(), ACCESS_KEY, null, Duration.ofMillis(300), null)),
                          PreviewFailure.UNAVAILABLE);
        }

        @Test
        @DisplayName("no answer before the request budget ends is a limit, and the call stops there")
        void budgetEnds() {
            converter.stubFor(post(urlPathEqualTo(CONVERT))
                .willReturn(pdf(DocumentPreviewFixtures.pdf()).withFixedDelay(5_000)));
            long started = System.nanoTime();
            assertFailure(renderer(), source(), PreviewBudget.start(Clock.systemUTC(), Duration.ofMillis(700)),
                          PreviewFailure.LIMIT_EXCEEDED);
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(3));
        }

        @Test
        @DisplayName("a conversion holds its permit while the converter works, and gives it back once it stops")
        void permitHeldWhileConverting() throws Exception {
            converter.stubFor(post(urlPathEqualTo(CONVERT))
                .willReturn(pdf(DocumentPreviewFixtures.pdf()).withFixedDelay(5_000)));
            PreviewRenderer renderer = renderer();
            TrackedSource source = source();
            PreviewBudget budget = PreviewBudget.start(Clock.systemUTC(), Duration.ofMillis(1_500));
            CompletableFuture<RenderedPreview> worker =
                CompletableFuture.supplyAsync(() -> renderer.render(PreviewFormat.DOCX, source, budget));

            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (capacity.available() == 3 && !worker.isDone() && System.nanoTime() < until) {
                Thread.sleep(10);
            }
            // The source is read by now; what holds the permit is the conversion, still waiting on the converter.
            assertThat(capacity.available()).isEqualTo(2);
            assertThat(source.closed).isTrue();

            Throwable thrown = catchThrowable(() -> worker.get(5, TimeUnit.SECONDS));
            assertThat(thrown).cause().isInstanceOfSatisfying(PreviewError.class,
                error -> assertThat(error.failure()).isEqualTo(PreviewFailure.LIMIT_EXCEEDED));
            assertThat(capacity.available()).isEqualTo(3);
            assertThat(source.closed).isTrue();
            assertScratchEmpty();
        }

        @Test
        @DisplayName("a converter nobody is listening on is unavailable")
        void unreachable() {
            String address = converter.baseUrl();
            converter.stop();
            assertFailure(renderer(properties(address, ACCESS_KEY, null, null, null)), source(), budget(),
                          PreviewFailure.UNAVAILABLE);
        }
    }

    @Test
    @DisplayName("no diagnostic carries the provider's body, the key, the endpoint or a stack trace")
    void diagnostics() {
        converter.stubFor(post(urlPathEqualTo(CONVERT)).willReturn(aResponse().withStatus(500)
            .withBody(PROVIDER_BODY)));
        assertFailure(PreviewFailure.UNAVAILABLE);
        converter.resetAll();
        converter.stubFor(post(urlPathEqualTo(CONVERT))
            .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));
        assertFailure(PreviewFailure.UNAVAILABLE);
        converter.resetAll();
        converter.stubFor(post(urlPathEqualTo(CONVERT))
            .willReturn(pdf(PROVIDER_BODY.getBytes(StandardCharsets.UTF_8))));
        assertFailure(PreviewFailure.UNAVAILABLE);

        assertThat(logs.list).isNotEmpty();
        for (ILoggingEvent event : logs.list) {
            assertThat(event.getFormattedMessage())
                .doesNotContain(PROVIDER_BODY, "Tornado", ACCESS_KEY, converter.baseUrl(), "localhost",
                                "document.docx");
            assertThat(event.getThrowableProxy()).isNull();
        }
    }

    private static ResponseDefinitionBuilder pdf(byte[] body) {
        return aResponse().withStatus(200).withHeader("Content-Type", "application/pdf").withBody(body);
    }

    private TrackedSource source() {
        return new TrackedSource(DocumentPreviewFixtures.read(DocumentPreviewFixtures.MULTI_PAGE_DOCX));
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

    private RenderedPreview render() {
        return render(DocumentPreviewFixtures.read(DocumentPreviewFixtures.MULTI_PAGE_DOCX));
    }

    private RenderedPreview render(byte[] docx) {
        return renderer().render(PreviewFormat.DOCX, () -> new ByteArrayInputStream(docx), budget());
    }

    private PreviewRenderer renderer() {
        return renderer(properties(converter.baseUrl(), ACCESS_KEY, null, null, null));
    }

    private PreviewRenderer renderer(PreviewProperties properties) {
        return new PreviewRenderer(properties, capacity, scratch);
    }

    private PreviewProperties.Converter converterAt(String url) {
        return new PreviewProperties.Converter(url, ACCESS_KEY, null, null);
    }

    private static PreviewProperties properties(String url, String key, Duration connect, Duration read,
                                                Duration budget) {
        return new PreviewProperties(budget, null, null, null, null, null, null,
                                     new PreviewProperties.Converter(url, key, connect, read));
    }

    private static PreviewBudget budget() {
        return PreviewBudget.start(Clock.systemUTC(), PreviewProperties.REQUEST_BUDGET);
    }

    private void assertFailure(PreviewFailure expected) {
        assertFailure(renderer(), expected);
    }

    private void assertFailure(byte[] docx, PreviewFailure expected) {
        assertFailure(renderer(), new TrackedSource(docx), budget(), expected);
    }

    private void assertFailure(PreviewRenderer renderer, PreviewFailure expected) {
        assertFailure(renderer, source(), budget(), expected);
    }

    private void assertFailure(PreviewRenderer renderer, TrackedSource source, PreviewBudget budget,
                               PreviewFailure expected) {
        try (RenderedPreview unexpected = renderer.render(PreviewFormat.DOCX, source, budget)) {
            fail("expected %s, got a preview of %s pages", expected, unexpected.pages());
        } catch (PreviewError refused) {
            assertThat(refused.failure()).isEqualTo(expected);
            assertThat(refused.getMessage()).isEqualTo(expected.message());
            assertThat(refused.getCause()).isNull();
        }
        if (source.opened) {
            assertThat(source.closed).isTrue();
        }
        assertThat(capacity.available()).isEqualTo(3);
        assertScratchEmpty();
    }

    private void assertNothingSent() {
        if (converter.isRunning()) {
            assertThat(converter.findAll(anyRequestedFor(anyUrl()))).isEmpty();
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

    /** A source that remembers whether it was opened and closed. */
    private static final class TrackedSource implements PreviewSource {
        private final byte[] bytes;
        private volatile boolean opened;
        private volatile boolean closed;

        TrackedSource(byte[] bytes) {
            this.bytes = bytes;
        }

        @Override
        public InputStream open() {
            opened = true;
            return new ByteArrayInputStream(bytes) {
                @Override
                public void close() throws IOException {
                    closed = true;
                    super.close();
                }
            };
        }
    }
}
