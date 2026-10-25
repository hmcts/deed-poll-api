package uk.gov.hmcts.reform.deedpoll.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link RateLimitBucket}'s routes against the controllers and the published document, from the whole context.
 *
 * <p>A bucket naming a route no controller maps would leave the renamed route unlimited, and nothing else would fail.
 * The same routes decide where the document publishes 429, so this also holds the document to exactly those six --
 * and the two document previews, whose 429 is their own {@code PREVIEW_BUSY} for a pod with no conversion permit
 * free, and not a rate limit.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RateLimitRoutesTest extends AbstractPostgresContainerTest {

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping mappings;

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private RateLimitProperties properties;

    @Test
    @DisplayName("every bucket's route is a method and pattern some controller maps")
    void everyRouteIsMapped() {
        Set<String> mapped = new TreeSet<>();
        for (RequestMappingInfo info : mappings.getHandlerMethods().keySet()) {
            for (RequestMethod method : info.getMethodsCondition().getMethods()) {
                info.getPatternValues().forEach(pattern -> mapped.add(method.name() + " " + pattern));
            }
        }

        assertThat(routes()).hasSize(6).allSatisfy(route -> assertThat(mapped).contains(route));
    }

    @Test
    @DisplayName("the document publishes 429 on the six limited operations and the two previews, and on no other")
    void theDocumentPublishes429OnTheSixOnly() throws Exception {
        JsonNode document = json.readTree(mvc.perform(get("/v3/api-docs"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());

        List<String> with429 = new ArrayList<>();
        for (Map.Entry<String, JsonNode> path : document.path("paths").properties()) {
            for (Map.Entry<String, JsonNode> operation : path.getValue().properties()) {
                JsonNode response = operation.getValue().path("responses").path("429");
                if (!response.isMissingNode()) {
                    with429.add(operation.getKey().toUpperCase(Locale.ROOT) + " " + path.getKey());
                    assertThat(response.path("headers").has("Retry-After")).isTrue();
                    boolean upload = "/caseworker/cases/{id}/documents".equals(path.getKey());
                    assertThat(response.path("description").asText().contains("whatever the mode"))
                        .as("only the upload's 429 says it is refused in every mode: %s", path.getKey())
                        .isEqualTo(upload);
                    assertThat(response.path("description").asText().contains("PREVIEW_BUSY"))
                        .as("only a preview's 429 is PREVIEW_BUSY: %s", path.getKey())
                        .isEqualTo(OpenAPIDocumentPreviews.PATHS.contains(path.getKey()));
                }
            }
        }

        List<String> expected = new ArrayList<>(routes());
        OpenAPIDocumentPreviews.PATHS.forEach(path -> expected.add("GET " + path));
        assertThat(with429).containsExactlyInAnyOrderElementsOf(expected);
    }

    @Test
    @DisplayName("ships in observe mode, with decision 1's numbers and four upload permits")
    void shipsObserving() {
        assertThat(properties.mode()).isEqualTo(RateLimitProperties.Mode.OBSERVE);
        assertThat(properties.buckets()).allSatisfy(
            (bucket, budget) -> assertThat(budget).isEqualTo(bucket.defaultBudget()));
        assertThat(properties.uploadInFlight()).as("decision 7's four permits").isEqualTo(4);
    }

    private static List<String> routes() {
        return Arrays.stream(RateLimitBucket.values())
            .flatMap(bucket -> bucket.routes().stream())
            .map(route -> route.method().name() + " " + route.pattern())
            .toList();
    }
}
