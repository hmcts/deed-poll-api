package uk.gov.hmcts.reform.deedpoll.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.SpecVersion;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OpenAPIForbiddenResponsesTest {

    private static final String REF = "#/components/responses/" + OpenAPIForbiddenResponses.RESPONSE_NAME;

    private final OpenAPIForbiddenResponses underTest = new OpenAPIForbiddenResponses();

    @Test
    void referencesTheForbiddenResponseOnStaffOperationsAndRegistersTheErrorModel() {
        Operation caseworker = operation();
        Operation judicial = operation();
        OpenAPI openApi = openApi(new Paths()
            .addPathItem("/caseworker/cases", new PathItem().get(caseworker).post(operation()))
            .addPathItem("/judicial/referrals/{referralId}", new PathItem().get(judicial)));

        underTest.forbiddenResponseCustomizer().customise(openApi);

        assertThat(caseworker.getResponses().get("403").get$ref()).isEqualTo(REF);
        assertThat(judicial.getResponses().get("403").get$ref()).isEqualTo(REF);
        assertThat(caseworker.getResponses()).containsKey("200");

        ApiResponse forbidden = openApi.getComponents().getResponses()
            .get(OpenAPIForbiddenResponses.RESPONSE_NAME);
        assertThat(forbidden.getDescription()).isEqualTo(OpenAPIForbiddenResponses.DESCRIPTION);
        assertThat(forbidden.getContent()).containsOnlyKeys("application/json");
        assertThat(forbidden.getContent().get("application/json").getSchema().get$ref())
            .isEqualTo("#/components/schemas/ApiError");

        Schema<?> apiError = openApi.getComponents().getSchemas().get("ApiError");
        assertThat(apiError.getTypes()).containsExactly("object");
        assertThat(apiError.getProperties()).containsOnlyKeys("error", "errors");
        Schema<?> errors = apiError.getProperties().get("errors");
        assertThat(errors.getItems().get$ref()).isEqualTo("#/components/schemas/FieldError");
        Schema<?> fieldError = openApi.getComponents().getSchemas().get("FieldError");
        assertThat(fieldError.getProperties()).containsOnlyKeys("field", "message");
    }

    @Test
    void keepsAnExistingForbiddenResponse() {
        ApiResponse own = new ApiResponse().description("Only a Delivery Manager may do this.");
        Operation operation = operation();
        operation.getResponses().addApiResponse("403", own);
        OpenAPI openApi = openApi(new Paths()
            .addPathItem("/caseworker/gazette-settings", new PathItem().get(operation)));

        underTest.forbiddenResponseCustomizer().customise(openApi);

        assertThat(operation.getResponses().get("403")).isSameAs(own);
        assertThat(openApi.getComponents().getResponses()).as("nothing references the shared one").isNull();
    }

    @Test
    void honoursAnExplicitPublicOverrideAndLeavesOtherPathsAlone() {
        Operation publicStaff = operation().security(List.of());
        Operation root = operation().security(List.of());
        Operation me = operation();
        OpenAPI openApi = openApi(new Paths()
            .addPathItem("/caseworker/public", new PathItem().get(publicStaff))
            .addPathItem("/", new PathItem().get(root))
            .addPathItem("/me", new PathItem().get(me))
            .addPathItem("/caseworkers", new PathItem().get(operation())));

        underTest.forbiddenResponseCustomizer().customise(openApi);

        assertThat(publicStaff.getResponses()).doesNotContainKey("403");
        assertThat(publicStaff.getSecurity()).isEmpty();
        assertThat(root.getResponses()).doesNotContainKey("403");
        assertThat(root.getSecurity()).isEmpty();
        assertThat(me.getResponses()).doesNotContainKey("403");
        assertThat(me.getSecurity()).isNull();
        assertThat(openApi.getPaths().get("/caseworkers").getGet().getResponses()).doesNotContainKey("403");
        assertThat(openApi.getComponents().getResponses()).isNull();
        assertThat(openApi.getComponents().getSchemas()).isNull();
    }

    private static Operation operation() {
        return new Operation()
            .responses(new ApiResponses().addApiResponse("200", new ApiResponse().description("OK")));
    }

    private static OpenAPI openApi(Paths paths) {
        return new OpenAPI(SpecVersion.V31).components(new Components()).paths(paths);
    }
}
