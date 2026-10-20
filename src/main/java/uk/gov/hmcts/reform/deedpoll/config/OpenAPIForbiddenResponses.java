package uk.gov.hmcts.reform.deedpoll.config;

import io.swagger.v3.core.converter.AnnotatedType;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.SpecVersion;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import uk.gov.hmcts.reform.deedpoll.api.ApiError;

import java.util.Map;

/**
 * Publishes the 403 every staff operation can answer, with the {@link ApiError} body it answers with.
 *
 * <p>springdoc sees neither refusal: {@link StaffAccessDeniedHandler} answers from the filter chain when a
 * {@link StaffArea} does not admit the caller, and {@code DomainErrorHandler} answers when a member lacks a
 * capability or role. Both write the same envelope as JSON. Operations are selected by {@link StaffArea#of}, the
 * table the chain builds its rules from, so a new staff route is documented by being where it belongs.
 *
 * <p>Nothing outside an area gains one: {@code GET /} is public and {@code GET /me} admits any authenticated
 * caller so that one with no role is told so rather than refused. An operation whose own security override is
 * empty is public whatever its path, and one that already declares a 403 keeps its own.
 */
@Configuration
public class OpenAPIForbiddenResponses {

    static final String FORBIDDEN = "403";

    /** The reusable response component every staff operation references. */
    static final String RESPONSE_NAME = "Forbidden";

    static final String DESCRIPTION = "The caller is not staff of this area, or lacks the role or capability "
        + "this operation requires. The body is the usual error object, with a fixed summary in error.";

    @Bean
    public OpenApiCustomizer forbiddenResponseCustomizer() {
        return openApi -> {
            if (openApi.getPaths() == null) {
                return;
            }
            boolean referenced = false;
            for (Map.Entry<String, PathItem> path : openApi.getPaths().entrySet()) {
                if (StaffArea.of(path.getKey()).isEmpty()) {
                    continue;
                }
                for (Operation operation : path.getValue().readOperations()) {
                    if (isPublic(operation)) {
                        continue;
                    }
                    if (operation.getResponses() == null) {
                        operation.setResponses(new ApiResponses());
                    }
                    if (!operation.getResponses().containsKey(FORBIDDEN)) {
                        operation.getResponses().addApiResponse(FORBIDDEN,
                            new ApiResponse().$ref("#/components/responses/" + RESPONSE_NAME));
                        referenced = true;
                    }
                }
            }
            if (referenced) {
                register(openApi);
            }
        };
    }

    /** An explicit empty requirement opts an operation out of the global bearer requirement. */
    private static boolean isPublic(Operation operation) {
        return operation.getSecurity() != null && operation.getSecurity().isEmpty();
    }

    private static void register(OpenAPI openApi) {
        if (openApi.getComponents() == null) {
            openApi.setComponents(new Components());
        }
        Components components = openApi.getComponents();
        // The converter for the document's own version: the 3.0 one writes no type into a 3.1 document.
        @SuppressWarnings("rawtypes")
        Map<String, Schema> models = ModelConverters.getInstance(openApi.getSpecVersion() == SpecVersion.V31)
            .readAll(new AnnotatedType(ApiError.class));
        models.forEach((name, schema) -> {
            if (components.getSchemas() == null || !components.getSchemas().containsKey(name)) {
                components.addSchemas(name, schema);
            }
        });
        if (components.getResponses() == null || !components.getResponses().containsKey(RESPONSE_NAME)) {
            components.addResponses(RESPONSE_NAME, new ApiResponse()
                .description(DESCRIPTION)
                .content(new Content().addMediaType(org.springframework.http.MediaType.APPLICATION_JSON_VALUE,
                    new MediaType().schema(new Schema<>().$ref("#/components/schemas/"
                        + ApiError.class.getSimpleName())))));
        }
    }
}
