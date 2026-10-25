package uk.gov.hmcts.reform.deedpoll.config;

import io.swagger.v3.core.converter.AnnotatedType;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.SpecVersion;
import io.swagger.v3.oas.models.headers.Header;
import io.swagger.v3.oas.models.media.BinarySchema;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import uk.gov.hmcts.reform.deedpoll.api.ApiError;
import uk.gov.hmcts.reform.deedpoll.api.DocumentFileResponses;
import uk.gov.hmcts.reform.deedpoll.api.DocumentPreviewError;
import uk.gov.hmcts.reform.deedpoll.domain.documents.preview.PreviewFailure;
import uk.gov.hmcts.reform.deedpoll.domain.documents.preview.PreviewFormat;
import uk.gov.hmcts.reform.deedpoll.domain.documents.preview.PreviewProperties;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Publishes the two document previews' responses: the PDF and its headers, the fixed refusals, and the limits.
 *
 * <p>The handlers write their response themselves (see {@link DocumentFileResponses#writePreview} for why), so
 * springdoc sees a {@code void} method and would publish a bodiless {@code 200}. Everything a client is generated
 * from is therefore stated here, from the same sources the service answers from:
 *
 * <ul>
 *   <li>the error codes from {@link PreviewFailure}, grouped by its own statuses, so a new failure is published with
 *       the status it is refused under and the document cannot list a code the service never sends;</li>
 *   <li>{@code x-document-preview-limits} from the running {@link PreviewProperties}, so the published budget is the
 *       configured one rather than a copy of the defaults. The producer artifact is generated under the defaults.</li>
 * </ul>
 *
 * <p>The 403 is {@code OpenAPIForbiddenResponses}'s, as on every staff path; the 404 is {@code ApiError}'s, because
 * a document or referral the caller cannot reach is refused before any preview starts. Nothing here names the
 * converter, its address or a document identifier beyond the path's own.
 */
@Configuration
public class OpenAPIDocumentPreviews {

    /** The two preview routes, as the controllers write them. */
    public static final List<String> PATHS = List.of(
        "/caseworker/cases/{id}/documents/{documentId}/preview",
        "/judicial/referrals/{referralId}/documents/{documentId}/preview");

    static final String LIMITS_EXTENSION = "x-document-preview-limits";
    static final String ERROR_SCHEMA = DocumentPreviewError.class.getSimpleName();

    private static final String ERROR_REF = "#/components/schemas/" + ERROR_SCHEMA;
    private static final String API_ERROR_REF = "#/components/schemas/" + ApiError.class.getSimpleName();

    @Bean
    public OpenApiCustomizer documentPreviewCustomizer(PreviewProperties properties) {
        Objects.requireNonNull(properties, "properties");
        return openApi -> {
            boolean published = false;
            for (String path : PATHS) {
                Operation operation = get(openApi, path);
                if (operation != null) {
                    describe(operation, properties);
                    published = true;
                }
            }
            if (published) {
                register(openApi);
            }
        };
    }

    private static Operation get(OpenAPI openApi, String path) {
        PathItem item = openApi.getPaths() == null ? null : openApi.getPaths().get(path);
        return item == null ? null : item.getGet();
    }

    private static void describe(Operation operation, PreviewProperties properties) {
        ApiResponses responses = operation.getResponses() == null ? new ApiResponses() : operation.getResponses();
        responses.put("200", pdf());
        responses.put("404", new ApiResponse()
            .description("No such document in this scope, as /file answers: the usual error object.")
            .content(json(API_ERROR_REF)));
        responses.put("502", new ApiResponse()
            .description("The document store would not return the original, as /file answers: the usual error "
                + "object. Nothing was converted.")
            .content(json(API_ERROR_REF)));
        failuresByStatus().forEach((status, failures) -> responses.put(status, refusal(failures, properties)));
        operation.setResponses(responses);
        operation.addExtension(LIMITS_EXTENSION, limits(properties));
    }

    private static ApiResponse pdf() {
        return new ApiResponse()
            .description("The whole preview, a complete PDF. Never partial: a Range header is ignored.")
            .content(new Content().addMediaType(org.springframework.http.MediaType.APPLICATION_PDF_VALUE,
                new MediaType().schema(new BinarySchema())))
            .addHeaderObject(HttpHeaders.CONTENT_DISPOSITION, header(
                "inline, with the fixed filename " + DocumentFileResponses.PREVIEW_FILENAME + "."))
            .addHeaderObject(HttpHeaders.CONTENT_LENGTH, new Header()
                .description("The preview's measured length in bytes.").schema(new IntegerSchema().format("int64")))
            .addHeaderObject(HttpHeaders.CACHE_CONTROL, header("no-store."))
            .addHeaderObject("X-Content-Type-Options", header("nosniff."))
            .addHeaderObject("Content-Security-Policy", header("default-src 'none'; sandbox."))
            .addHeaderObject(HttpHeaders.ACCEPT_RANGES, header("none: there is no range support."));
    }

    private static Map<String, List<PreviewFailure>> failuresByStatus() {
        return Arrays.stream(PreviewFailure.values()).collect(Collectors.groupingBy(
            failure -> Integer.toString(failure.status()), LinkedHashMap::new, Collectors.toList()));
    }

    private static ApiResponse refusal(List<PreviewFailure> failures, PreviewProperties properties) {
        String codes = failures.stream()
            .map(failure -> failure.code() + ": " + failure.message())
            .collect(Collectors.joining(" "));
        ApiResponse response = new ApiResponse()
            .description("The preview was not produced and the original download still works. " + codes)
            .content(json(ERROR_REF))
            .addHeaderObject(HttpHeaders.CACHE_CONTROL, header("no-store."));
        if (failures.contains(PreviewFailure.BUSY)) {
            response.addHeaderObject(HttpHeaders.RETRY_AFTER, new Header()
                .description("Whole seconds to wait before trying again: "
                    + properties.busyRetryAfter().toSeconds() + " as configured, never more than 60.")
                .schema(new IntegerSchema()));
        }
        return response;
    }

    /**
     * The preview error's schema, and {@link ApiError}'s if no other customizer has registered it yet. Both are read
     * by the model converter for the document's own version, as {@code OpenAPIForbiddenResponses} explains.
     */
    @SuppressWarnings("rawtypes")
    private static void register(OpenAPI openApi) {
        if (openApi.getComponents() == null) {
            openApi.setComponents(new Components());
        }
        Components components = openApi.getComponents();
        Map<String, Schema> models = ModelConverters.getInstance(openApi.getSpecVersion() == SpecVersion.V31)
            .readAll(new AnnotatedType(ApiError.class));
        models.forEach((name, schema) -> {
            if (components.getSchemas() == null || !components.getSchemas().containsKey(name)) {
                components.addSchemas(name, schema);
            }
        });
        components.addSchemas(ERROR_SCHEMA, errorSchema(openApi));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Schema errorSchema(OpenAPI openApi) {
        Schema error = ModelConverters.getInstance(openApi.getSpecVersion() == SpecVersion.V31)
            .read(new AnnotatedType(DocumentPreviewError.class)).get(ERROR_SCHEMA);
        Schema code = (Schema) error.getProperties().get("code");
        code.setEnum(Arrays.stream(PreviewFailure.values()).map(PreviewFailure::code).toList());
        code.setDescription("The stable code to switch on.");
        ((Schema) error.getProperties().get("error"))
            .setDescription("A fixed sentence, safe to show. It never names the document or the converter.");
        error.setDescription("A document preview that was not produced. The original download is unaffected.");
        error.setRequired(List.of("error", "code"));
        return error;
    }

    private static Map<String, Object> limits(PreviewProperties properties) {
        Map<String, Object> limits = new LinkedHashMap<>();
        limits.put("requestBudgetSeconds", properties.requestBudget().toSeconds());
        limits.put("maxInputBytes", properties.maxInputBytes());
        limits.put("maxOutputBytes", properties.maxOutputBytes());
        limits.put("maxPages", properties.maxPages());
        limits.put("maxPagesEnforcedFor", Arrays.stream(PreviewFormat.values()).filter(PreviewFormat::countsPages)
            .map(PreviewFormat::mimeType).toList());
        limits.put("maxPagesEnforcedByViewerFor", Arrays.stream(PreviewFormat.values())
            .filter(format -> !format.countsPages()).map(PreviewFormat::mimeType).toList());
        limits.put("maxFramePixels", properties.maxFramePixels());
        limits.put("maxConcurrentConversionsPerInstance", properties.maxConcurrentConversions());
        limits.put("maxConcurrentPreviewsPerInstance", properties.maxConcurrentPreviews());
        limits.put("busyRetryAfterSeconds", properties.busyRetryAfter().toSeconds());
        limits.put("rangeRequests", "unsupported");
        limits.put("description", "Preview limits only: none changes upload acceptance or the original download. "
            + "The budget covers conversion and rendering, but not a stalled read of the original, which keeps "
            + "the document store's own timeouts as the download does. Conversions beyond the per-instance "
            + "limit are refused with PREVIEW_BUSY, not queued; a PDF takes no conversion slot. Previews of any "
            + "format beyond maxConcurrentPreviewsPerInstance, counted from the original's read until the response "
            + "is written, are PREVIEW_BUSY too. Reaching any other "
            + "limit is PREVIEW_LIMIT_EXCEEDED, never a truncated PDF. maxPages is enforced by the service only for "
            + "the previews it builds (maxPagesEnforcedFor); a PDF or converted DOCX is served unparsed and "
            + "uncounted, so the viewer must count its pages and refuse one over maxPages before rendering.");
        return limits;
    }

    private static Content json(String ref) {
        return new Content().addMediaType(org.springframework.http.MediaType.APPLICATION_JSON_VALUE,
            new MediaType().schema(new Schema<>().$ref(ref)));
    }

    private static Header header(String description) {
        return new Header().description(description).schema(new StringSchema());
    }
}
