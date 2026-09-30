package uk.gov.hmcts.reform.deedpoll.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class StubDmStoreContractTest {

    private static final Map<String, List<String>> HEADER_DISCREPANCIES = Map.of(
        "/documents/{documentId}", List.of("user-roles"),
        "/documents/{documentId}/binary", List.of("user-roles", "classification"));

    @Test
    void cftlibVersionAndSpecRevisionAreRecordedTogether() throws Exception {
        assertThat(Files.readString(Path.of("build.gradle")))
            .contains("id 'com.github.hmcts.rse-cft-lib' version '" + StubDmStore.CFTLIB_VERSION + "'");
        assertThat(Files.readString(Path.of("src/test/resources/dm-store-contract.md")))
            .contains(StubDmStore.CFTLIB_VERSION, StubDmStore.SPEC_COMMIT);
    }

    @Test
    void everyMappingAndItsRequiredHeadersExistInThePinnedSpec() throws Exception {
        JsonNode spec = spec();
        for (StubDmStore.Operation operation : StubDmStore.OPERATIONS) {
            JsonNode endpoint = spec.path("paths").path(operation.path())
                .path(operation.method().toLowerCase(Locale.ROOT));
            assertThat(endpoint.isMissingNode()).as(operation.toString()).isFalse();
            List<String> required = new ArrayList<>();
            for (JsonNode parameter : endpoint.path("parameters")) {
                if (parameter.path("in").asText().equals("header") && parameter.path("required").asBoolean()) {
                    required.add(parameter.path("name").asText().toLowerCase(Locale.ROOT));
                }
            }
            List<String> accepted = new ArrayList<>(operation.headers());
            if (operation.method().equals("GET")) {
                accepted.addAll(HEADER_DISCREPANCIES.getOrDefault(operation.path(), List.of()));
            }
            assertThat(accepted).as(operation.toString()).containsExactlyInAnyOrderElementsOf(required);
        }
    }

    @Test
    void multipartFieldsAndRequiredPartsMatchThePinnedSpec() throws Exception {
        JsonNode spec = spec();
        String ref = spec.path("paths").path("/documents").path("post").path("requestBody")
            .path("content").path("multipart/form-data").path("schema").path("$ref").asText();
        JsonNode schema = spec.at(ref.substring(1));
        List<String> fields = StubDmStore.UPLOAD_PARTS.stream().map(part -> part.split("\\[")[0]).toList();
        for (String field : fields) {
            assertThat(schema.path("properties").has(field)).as(field).isTrue();
        }
        for (JsonNode required : schema.path("required")) {
            assertThat(fields).contains(required.asText());
        }
        assertThat(schema.path("properties").path("files").path("items").path("format").asText())
            .isEqualTo("binary");
    }

    private JsonNode spec() throws Exception {
        return new ObjectMapper().readTree(Path.of("src/test/resources/dm-store-openapi.json").toFile());
    }
}
