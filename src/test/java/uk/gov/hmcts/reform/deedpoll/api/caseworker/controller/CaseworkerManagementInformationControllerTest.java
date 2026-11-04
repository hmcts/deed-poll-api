package uk.gov.hmcts.reform.deedpoll.api.caseworker.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;
import uk.gov.hmcts.reform.deedpoll.auth.StubIdam;
import uk.gov.hmcts.reform.deedpoll.config.AbstractPostgresContainerTest;
import uk.gov.hmcts.reform.deedpoll.domain.mi.MiFixtures;
import uk.gov.hmcts.reform.deedpoll.domain.mi.MiGranularity;
import uk.gov.hmcts.reform.deedpoll.entity.enums.ConfirmationOutcome;
import uk.gov.hmcts.reform.deedpoll.entity.enums.DeedPollType;
import uk.gov.hmcts.reform.deedpoll.entity.enums.MasterDecision;
import uk.gov.hmcts.reform.deedpoll.entity.enums.Urgency;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static uk.gov.hmcts.reform.deedpoll.domain.mi.MiFixtures.at;

/**
 * {@code /caseworker/management-information} through the full security context, a stub IDAM and real Postgres: the
 * {@code VIEW_MANAGEMENT_INFORMATION} gate before any parameter is read, the 400s, each response against its
 * published schema, and no property anywhere in those schemas that could carry a case's identity. The figures
 * themselves are the domain tests'; one case here proves they reach the wire.
 *
 * <p>{@code @Transactional}, so the fixture (dated 2191, where nothing else is) is rolled back.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class CaseworkerManagementInformationControllerTest extends AbstractPostgresContainerTest {

    private static final String ROOT = "/caseworker/management-information";
    private static final List<String> OPERATIONS = List.of("volume", "stage-durations", "referral-outcomes",
                                                           "completion");
    private static final Map<String, String> OPERATION_IDS = Map.of("volume", "getMiVolume",
        "stage-durations", "getMiStageDurations", "referral-outcomes", "getMiReferralOutcomes",
        "completion", "getMiCompletion");
    private static final String RANGE = "?from=2191-01-01&to=2191-02-28&granularity=MONTH";

    private static final StubIdam IDAM = new StubIdam();

    static {
        IDAM.start();
    }

    @AfterAll
    static void stopIdam() {
        IDAM.stop();
    }

    @DynamicPropertySource
    static void idamProperties(DynamicPropertyRegistry registry) {
        registry.add("idam.api.issuer-url", IDAM::issuerUrl);
        registry.add("idam.api.audiences", () -> StubIdam.AUDIENCE);
    }

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ObjectMapper json;

    @BeforeEach
    void setUp() {
        IDAM.stubDefaults();
        as("caseworker-deedpoll-team-leader");
        MiFixtures fixtures = new MiFixtures(jdbc);
        String caseId = fixtures.newCase().type(DeedPollType.Minor).received(at(2191, 1, 5))
            .ready(at(2191, 1, 7)).enroled(at(2191, 1, 20)).insert();
        fixtures.referral(caseId, MasterDecision.Approved, at(2191, 1, 10), Urgency.Urgent,
                          ConfirmationOutcome.Confirmed, at(2191, 1, 12));
    }

    @Test
    @DisplayName("no token is 401 on every operation")
    void unauthenticated() throws Exception {
        for (String operation : OPERATIONS) {
            mvc.perform(get(ROOT + "/" + operation + RANGE)).andExpect(status().isUnauthorized());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"caseworker-deedpoll-team-leader", "caseworker-deedpoll-delivery-manager"})
    @DisplayName("a Team leader and a Delivery Manager are admitted to every operation")
    void admitsTeamLeadersAndDeliveryManagers(String role) throws Exception {
        as(role);
        for (String operation : OPERATIONS) {
            authorised(ROOT + "/" + operation + RANGE).andExpect(status().isOk())
                .andExpect(jsonPath("$.parameters.from").value("2191-01-01"))
                .andExpect(jsonPath("$.parameters.to").value("2191-02-28"))
                .andExpect(jsonPath("$.parameters.granularity").value("MONTH"))
                .andExpect(jsonPath("$.parameters.includeMigrated").value(true));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"caseworker-deedpoll", "caseworker-deedpoll-admin", "caseworker-deedpoll-judicial",
        "caseworker-civil"})
    @DisplayName("everyone else is 403 on every operation, before any parameter is read")
    void refusesEveryoneElse(String role) throws Exception {
        as(role);
        for (String operation : OPERATIONS) {
            authorised(ROOT + "/" + operation + RANGE).andExpect(status().isForbidden());
            authorised(ROOT + "/" + operation + "?granularity=DAY&from=nope").andExpect(status().isForbidden());
        }
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "?to=2191-02-28&granularity=MONTH                         | from",
        "?from=2191-01-01&granularity=MONTH                       | to",
        "?from=2191-01-01&to=2191-02-28                           | granularity",
        "?from=2191-01-02&to=2191-01-01&granularity=MONTH         | to",
        "?from=2191-01-01&to=2193-01-02&granularity=MONTH         | to",
        "?from=2191-01-01&to=2191-02-28&granularity=DAY           | granularity",
        "?from=2191-01-01&to=2191-02-28&granularity=month         | granularity",
        "?from=01/01/2191&to=2191-02-28&granularity=MONTH         | from",
        "?from=2191-01-01&to=2191-02-28&granularity=MONTH&includeMigrated=yes | includeMigrated"})
    @DisplayName("a missing, malformed or out-of-range parameter is 400 on that parameter, on every operation")
    void refusesBadParameters(String query, String field) throws Exception {
        for (String operation : OPERATIONS) {
            authorised(ROOT + "/" + operation + query).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value(field));
        }
    }

    @Test
    @DisplayName("exactly two years is admitted, and includeMigrated=false is echoed")
    void admitsTheLongestRange() throws Exception {
        for (String operation : OPERATIONS) {
            authorised(ROOT + "/" + operation + "?from=2191-01-01&to=2193-01-01&granularity=QUARTER"
                       + "&includeMigrated=false").andExpect(status().isOk())
                .andExpect(jsonPath("$.parameters.includeMigrated").value(false));
        }
    }

    @Test
    @DisplayName("the fixture's figures reach the wire, and an empty period is zeros and null percentiles")
    void figuresReachTheWire() throws Exception {
        authorised(ROOT + "/volume" + RANGE).andExpect(status().isOk())
            .andExpect(jsonPath("$.periods.length()").value(2))
            .andExpect(jsonPath("$.periods[0].start").value("2191-01-01"))
            .andExpect(jsonPath("$.periods[0].end").value("2191-01-31"))
            .andExpect(jsonPath("$.periods[0].received").value(1))
            .andExpect(jsonPath("$.periods[0].receivedByType.length()").value(DeedPollType.values().length))
            .andExpect(jsonPath("$.periods[0].receivedByType[2].type").value("Minor"))
            .andExpect(jsonPath("$.periods[0].receivedByType[2].count").value(1))
            .andExpect(jsonPath("$.periods[1].received").value(0));
        authorised(ROOT + "/stage-durations" + RANGE).andExpect(status().isOk())
            .andExpect(jsonPath("$.periods[0].intervals[0].interval").value("RECEIVED_TO_READY_FOR_MASTER"))
            .andExpect(jsonPath("$.periods[0].intervals[0].count").value(1))
            .andExpect(jsonPath("$.periods[0].intervals[0].medianDays").value(2.0))
            .andExpect(jsonPath("$.periods[0].intervals[0].p95Days").value(2.0))
            .andExpect(jsonPath("$.periods[1].intervals[0].count").value(0))
            .andExpect(jsonPath("$.periods[1].intervals[0].medianDays").isEmpty())
            .andExpect(jsonPath("$.periods[1].intervals[0].p95Days").isEmpty())
            .andExpect(jsonPath("$.excluded.negativeInterval[0].count").value(0));
        authorised(ROOT + "/referral-outcomes" + RANGE).andExpect(status().isOk())
            .andExpect(jsonPath("$.periods[0].decisions.approved.count").value(1))
            .andExpect(jsonPath("$.periods[0].decisions.approved.urgent").value(1))
            .andExpect(jsonPath("$.periods[0].decisions.rejected.count").value(0))
            .andExpect(jsonPath("$.periods[0].confirmations.confirmed").value(1))
            .andExpect(jsonPath("$.periods[0].confirmations.withdrawn").value(0));
        authorised(ROOT + "/completion" + RANGE).andExpect(status().isOk())
            .andExpect(jsonPath("$.cohorts[0].received").value(1))
            .andExpect(jsonPath("$.cohorts[0].completed").value(1))
            .andExpect(jsonPath("$.cohorts[0].enroled").value(1))
            .andExpect(jsonPath("$.cohorts[0].stillOpen").value(0))
            .andExpect(jsonPath("$.cohorts[0].daysToCompletion.count").value(1))
            .andExpect(jsonPath("$.cohorts[0].daysToCompletion.medianDays").value(15.0))
            .andExpect(jsonPath("$.cohorts[1].daysToCompletion.count").value(0))
            .andExpect(jsonPath("$.cohorts[1].daysToCompletion.medianDays").isEmpty());
    }

    @Test
    @DisplayName("each operation is published under its id, its parameters as they bind, and its 200 as it answers")
    void responsesMatchThePublishedSchema() throws Exception {
        JsonNode document = document();
        for (String operation : OPERATIONS) {
            JsonNode published = document.path("paths").path(ROOT + "/" + operation);
            assertThat(published.properties()).extracting(Map.Entry::getKey).as(operation).containsExactly("get");
            JsonNode get = published.path("get");
            assertThat(get.path("operationId").asText()).isEqualTo(OPERATION_IDS.get(operation));
            assertThat(get.path("description").asText()).contains("VIEW_MANAGEMENT_INFORMATION", "Not counted");
            assertParameters(document, get.path("parameters"));

            JsonNode schema = get.path("responses").path("200").path("content").path("*/*").path("schema");
            JsonNode body = json.readTree(authorised(ROOT + "/" + operation + RANGE).andExpect(status().isOk())
                                              .andReturn().getResponse().getContentAsString());
            assertMatches(document, schema, body, operation);
        }
    }

    @Test
    @DisplayName("no property of any response schema, however deep, could carry a case's or a person's identity")
    void publishesNoIdentifier() throws Exception {
        JsonNode document = document();
        Set<String> names = new TreeSet<>();
        Set<String> components = new TreeSet<>();
        for (String operation : OPERATIONS) {
            collectPropertyNames(document, document.path("paths").path(ROOT + "/" + operation).path("get")
                .path("responses").path("200").path("content").path("*/*").path("schema"), names, components);
        }
        // A nested record's simple name (Period, Excluded) would otherwise share a component with another's.
        assertThat(components).as("every component these responses use is their own")
            .allSatisfy(ref -> assertThat(ref.substring(ref.lastIndexOf('/') + 1))
                .matches("Mi[A-Z].*|DeedPollType|StageInterval|MiGranularity"));
        assertThat(names).isNotEmpty().contains("received", "medianDays", "excluded");
        assertThat(names).allSatisfy(name -> {
            String lower = name.toLowerCase();
            assertThat(lower).as(name).doesNotEndWith("id").doesNotContain("reference", "name", "subject", "email",
                "note", "text", "case");
        });
    }

    private void assertParameters(JsonNode document, JsonNode parameters) {
        List<String> names = new ArrayList<>();
        for (JsonNode parameter : parameters) {
            names.add(parameter.path("name").asText());
            assertThat(parameter.path("in").asText()).isEqualTo("query");
            JsonNode schema = resolve(document, parameter.path("schema"));
            switch (parameter.path("name").asText()) {
                case "from", "to" -> {
                    assertThat(parameter.path("required").asBoolean()).isTrue();
                    assertThat(schema.path("format").asText()).isEqualTo("date");
                }
                case "granularity" -> {
                    assertThat(parameter.path("required").asBoolean()).isTrue();
                    assertThat(texts(schema.path("enum"))).containsExactlyElementsOf(
                        Arrays.stream(MiGranularity.values()).map(Enum::name).toList());
                }
                case "includeMigrated" -> {
                    assertThat(parameter.path("required").asBoolean()).isFalse();
                    assertThat(schema.path("type").asText()).isEqualTo("boolean");
                }
                default -> throw new AssertionError("Unexpected parameter " + parameter.path("name").asText());
            }
        }
        assertThat(names).containsExactlyInAnyOrder("from", "to", "granularity", "includeMigrated");
    }

    /** The body has exactly the schema's properties, all required, each value of the published type. */
    private void assertMatches(JsonNode document, JsonNode schemaRef, JsonNode value, String path) {
        JsonNode schema = resolve(document, schemaRef);
        if (value.isNull()) {
            assertThat(nullable(schema)).as(path + " is null but not nullable").isTrue();
            return;
        }
        if (schema.has("properties")) {
            assertThat(value.isObject()).as(path).isTrue();
            List<String> declared = new ArrayList<>();
            schema.path("properties").properties().forEach(entry -> declared.add(entry.getKey()));
            List<String> present = new ArrayList<>();
            value.properties().forEach(entry -> present.add(entry.getKey()));
            assertThat(present).as(path).containsExactlyInAnyOrderElementsOf(declared);
            assertThat(texts(schema.path("required"))).as(path + " required").containsExactlyInAnyOrderElementsOf(
                declared);
            schema.path("properties").properties().forEach(entry ->
                assertMatches(document, entry.getValue(), value.path(entry.getKey()), path + "." + entry.getKey()));
            return;
        }
        String type = type(schema);
        switch (type) {
            case "array" -> {
                assertThat(value.isArray()).as(path).isTrue();
                for (int i = 0; i < value.size(); i++) {
                    assertMatches(document, schema.path("items"), value.get(i), path + "[" + i + "]");
                }
            }
            case "integer" -> assertThat(value.isIntegralNumber()).as(path).isTrue();
            case "number" -> assertThat(value.isNumber()).as(path).isTrue();
            case "boolean" -> assertThat(value.isBoolean()).as(path).isTrue();
            case "string" -> {
                assertThat(value.isTextual()).as(path).isTrue();
                if (schema.has("enum")) {
                    assertThat(texts(schema.path("enum"))).as(path).contains(value.asText());
                }
                if ("date".equals(schema.path("format").asText())) {
                    LocalDate.parse(value.asText());
                }
            }
            default -> throw new AssertionError(path + " has an unexpected schema " + schema);
        }
    }

    private void collectPropertyNames(JsonNode document, JsonNode schemaRef, Set<String> names, Set<String> seen) {
        String ref = schemaRef.path("$ref").asText("");
        if (!ref.isEmpty() && !seen.add(ref)) {
            return;
        }
        JsonNode schema = resolve(document, schemaRef);
        schema.path("properties").properties().forEach(entry -> {
            names.add(entry.getKey());
            collectPropertyNames(document, entry.getValue(), names, seen);
        });
        if (schema.has("items")) {
            collectPropertyNames(document, schema.path("items"), names, seen);
        }
    }

    private static JsonNode resolve(JsonNode document, JsonNode schema) {
        if (schema.has("allOf") && schema.path("allOf").size() == 1) {
            return resolve(document, schema.path("allOf").get(0));
        }
        String ref = schema.path("$ref").asText("");
        if (ref.isEmpty()) {
            return schema;
        }
        return document.path("components").path("schemas").path(ref.substring(ref.lastIndexOf('/') + 1));
    }

    private static boolean nullable(JsonNode schema) {
        return schema.path("nullable").asBoolean(false) || texts(schema.path("type")).contains("null");
    }

    private static String type(JsonNode schema) {
        JsonNode type = schema.path("type");
        if (type.isArray()) {
            return texts(type).stream().filter(name -> !"null".equals(name)).findFirst().orElse("");
        }
        return type.asText();
    }

    private static List<String> texts(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(value -> values.add(value.asText()));
        return values;
    }

    private JsonNode document() throws Exception {
        return json.readTree(mvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
                                 .andReturn().getResponse().getContentAsString());
    }

    private void as(String role) {
        IDAM.stubUserInfo(StubIdam.SUBJECT, "Management Information Reader", List.of(role));
    }

    private ResultActions authorised(String uri) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(IDAM.accessToken());
        return mvc.perform(get(uri).headers(headers));
    }
}
