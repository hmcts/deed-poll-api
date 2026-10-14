package uk.gov.hmcts.reform.deedpoll.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import uk.gov.hmcts.reform.deedpoll.domain.auth.Capability;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code GET /me} as the published document describes it, from the whole context.
 *
 * <p>The admin app generates its client from this document, so the capability enum here is the drift guard: a new
 * {@link Capability} that the document failed to list would reach admin as a value its types do not know.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CallerAccessOpenApiTest extends AbstractPostgresContainerTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper json;

    @Test
    @DisplayName("documents GET /me as getCallerAccess, with three required properties and every capability")
    void documentsTheOperation() throws Exception {
        JsonNode document = json.readTree(mvc.perform(get("/v3/api-docs"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());

        JsonNode operation = document.path("paths").path("/me").path("get");
        assertThat(operation.path("operationId").asText()).isEqualTo("getCallerAccess");

        JsonNode response = resolve(document,
            operation.path("responses").path("200").path("content").path("*/*").path("schema"));
        assertThat(texts(response.path("required")))
            .containsExactlyInAnyOrder("staff", "judicial", "capabilities");
        assertThat(response.path("properties").properties()).extracting(Map.Entry::getKey)
            .containsExactlyInAnyOrder("staff", "judicial", "capabilities");

        JsonNode capabilities = response.path("properties").path("capabilities");
        assertThat(capabilities.path("type").asText()).isEqualTo("array");
        JsonNode item = resolve(document, capabilities.path("items"));
        assertThat(item.path("type").asText()).isEqualTo("string");
        assertThat(texts(item.path("enum")))
            .containsExactlyElementsOf(Arrays.stream(Capability.values()).map(Enum::name).toList());
    }

    private static JsonNode resolve(JsonNode document, JsonNode schema) {
        String ref = schema.path("$ref").asText("");
        if (ref.isEmpty()) {
            return schema;
        }
        return document.path("components").path("schemas").path(ref.substring(ref.lastIndexOf('/') + 1));
    }

    private static List<String> texts(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(value -> values.add(value.asText()));
        return values;
    }
}
