package uk.gov.hmcts.reform.deedpoll.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.headers.Header;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;

/**
 * Publishes {@link RateLimitFilter}'s 429 on the six operations it can refuse, and on no other.
 *
 * <p>springdoc cannot see a refusal made by a filter, so without this the document would describe operations that
 * never answer 429. Generated from {@link RateLimitBucket}'s routes, so the document and the filter name the same
 * operations; {@code RateLimitRoutesTest} holds those routes to real controller mappings. Like the 409 and
 * 502 the email customizer adds, it is described rather than given a schema: no error body is published as one.
 */
@Configuration
public class OpenAPIRateLimits {

    static final String TOO_MANY_REQUESTS = "429";

    @Bean
    public OpenApiCustomizer rateLimitResponseCustomizer() {
        return openApi -> {
            for (RateLimitBucket bucket : RateLimitBucket.values()) {
                for (RateLimitBucket.Route route : bucket.routes()) {
                    Operation operation = operation(openApi, route);
                    if (operation != null) {
                        operation.getResponses().computeIfAbsent(TOO_MANY_REQUESTS, key -> tooManyRequests(bucket));
                    }
                }
            }
        };
    }

    private static Operation operation(OpenAPI openApi, RateLimitBucket.Route route) {
        PathItem item = openApi.getPaths() == null ? null : openApi.getPaths().get(route.pattern());
        if (item == null) {
            return null;
        }
        return item.readOperationsMap().get(PathItem.HttpMethod.valueOf(route.method().name()));
    }

    private static ApiResponse tooManyRequests(RateLimitBucket bucket) {
        String description = bucket == RateLimitBucket.UPLOAD
            ? "This caller has used this operation's allowance for the current window, which is refused only "
                + "when rate limiting is enforced; or too many uploads are already in progress on this instance, "
                + "which is refused whatever the mode, with Retry-After 5. Wait the number of seconds in "
                + "Retry-After before trying again. The body is the usual error object, in fixed wording."
            : "This caller has used this operation's allowance for the current window. Wait the "
                + "number of seconds in Retry-After before trying again. Only refused when rate limiting is "
                + "enforced. The body is the usual error object, in fixed wording.";
        return new ApiResponse()
            .description(description)
            .addHeaderObject(HttpHeaders.RETRY_AFTER, new Header()
                .description(bucket == RateLimitBucket.UPLOAD
                    ? "Whole seconds until the window ends, or 5 when too many uploads are in progress."
                    : "Whole seconds until the window ends.")
                .schema(new IntegerSchema()));
    }
}
