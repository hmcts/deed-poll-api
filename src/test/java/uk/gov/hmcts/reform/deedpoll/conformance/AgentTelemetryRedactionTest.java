package uk.gov.hmcts.reform.deedpoll.conformance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Asserts the Application Insights agent masks query strings before telemetry leaves the pod.
 *
 * <p>Admin's case search runs over GET, so applicants' names and dates of birth reach this service as {@code ?q=}
 * and {@code ?where=}. The agent records request and dependency URLs, so without this they persist in App Insights,
 * outside this service's access and retention controls (OWASP assessment M4). Only agent configuration reaches
 * that telemetry, because the agent records it, not this service's code.
 *
 * <p>The shape asserted here is the one agent 3.7.9's own {@code Configuration$ProcessorConfig} and
 * {@code $MaskAttribute} accept. It was read from that agent's classes, not from documentation:
 * <ul>
 *   <li>types and actions are lowercase;</li>
 *   <li>{@code mask} needs both {@code pattern} and {@code replace};</li>
 *   <li>{@code replace} names its groups as {@code ${name}}, and the agent only recognises names of letters and
 *       the digits 1 to 9.</li>
 * </ul>
 * A malformed processor makes the agent refuse the whole file, so a typo here would stop telemetry entirely, not
 * just fail to mask. After any change, confirm in AAT that requests still arrive.
 */
class AgentTelemetryRedactionTest {

    private static final Path AGENT_CONFIG = Path.of("lib/applicationinsights.json");

    /** The group-name syntax agent 3.7.9's MaskAttribute recognises in a replace string. */
    private static final Pattern AGENT_GROUP_REFERENCE = Pattern.compile("\\$\\{([A-Za-z1-9]*)}");

    private static final String SEARCH = "http://deed-poll-api.internal/caseworker/cases?q=Testperson&where=x";

    @Test
    void deletesTheQueryAttributeOutright() throws IOException {
        assertThat(actions()).anySatisfy(action -> {
            assertThat(action.path("key").asText()).isEqualTo("url.query");
            assertThat(action.path("action").asText()).isEqualTo("delete");
        });
    }

    @Test
    void masksEveryFullUrlAttributeUnderEitherHttpConvention() throws IOException {
        for (String key : List.of("url.full", "http.url")) {
            JsonNode mask = actions().stream()
                .filter(action -> key.equals(action.path("key").asText()))
                .findFirst().orElseThrow(() -> new AssertionError("no action for " + key));

            assertThat(mask.path("action").asText()).as(key).isEqualTo("mask");
            assertThat(masked(mask, SEARCH)).as(key).isEqualTo("http://deed-poll-api.internal/caseworker/cases?REDACTED");
            assertThat(masked(mask, "http://deed-poll-api.internal/caseworker/cases/a-case-id"))
                .as(key + " leaves a URL without a query alone")
                .isEqualTo("http://deed-poll-api.internal/caseworker/cases/a-case-id");
        }
    }

    @Test
    void usesOnlyGroupNamesTheAgentRecognises() throws IOException {
        for (JsonNode action : actions()) {
            if (!"mask".equals(action.path("action").asText())) {
                continue;
            }
            Matcher reference = AGENT_GROUP_REFERENCE.matcher(action.path("replace").asText());
            assertThat(reference.find()).as("replace for " + action.path("key").asText() + " names a group").isTrue();
            assertThat(Pattern.compile(action.path("pattern").asText()).pattern())
                .contains("(?<" + reference.group(1) + ">");
        }
    }

    @Test
    void keepsTheHealthProbeSamplingOverride() throws IOException {
        JsonNode overrides = config().path("sampling").path("overrides");

        assertThat(overrides).hasSize(1);
        assertThat(overrides.get(0).path("percentage").asInt()).isEqualTo(1);
    }

    private static List<JsonNode> actions() throws IOException {
        List<JsonNode> actions = new ArrayList<>();
        for (JsonNode processor : config().path("preview").path("processors")) {
            if ("attribute".equals(processor.path("type").asText())) {
                processor.path("actions").forEach(actions::add);
            }
        }
        return actions;
    }

    /** What the agent writes for a masked value: the pattern's whole match replaced, groups substituted. */
    private static String masked(JsonNode mask, String value) {
        Matcher matcher = Pattern.compile(mask.path("pattern").asText()).matcher(value);
        return matcher.replaceAll(mask.path("replace").asText());
    }

    private static JsonNode config() throws IOException {
        return new ObjectMapper().readTree(AGENT_CONFIG.toFile());
    }
}
