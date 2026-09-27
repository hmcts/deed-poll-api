package uk.gov.hmcts.reform.deedpoll.ccd;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import uk.gov.hmcts.ccd.sdk.CCDDefinitionGenerator;
import uk.gov.hmcts.ccd.sdk.api.CCDConfig;
import uk.gov.hmcts.ccd.sdk.generator.JSONConfigGenerator;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The case definition, generated exactly as {@code ./gradlew generateCCDConfig} generates it and held against a pinned
 * copy in {@code src/test/resources/ccd-definition/}.
 *
 * <p>The definition is minimal on purpose (plan 02, Task 3), so the pin is the whole output: every file and every row.
 * A new field, event, state or grant fails {@link #matchesThePinnedDefinition} until the pinned copy changes with it,
 * which is the point -- growth should be a diff somebody reviews. The named tests say the same things in sentences, so
 * a failure explains which rule broke rather than only that two JSON trees differ.
 *
 * <p>Booted under {@code config-gen}, the profile the Gradle task uses, so generating needs no database here either.
 */
@SpringBootTest
@ActiveProfiles("config-gen")
class DeedPollDefinitionTest {

    private static final String PLACEHOLDER = "DEED_POLL_PLACEHOLDER";
    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    private CCDDefinitionGenerator generator;

    @Autowired
    private JSONConfigGenerator<?, ?, ?> writer;

    @TempDir
    private Path output;

    @Test
    @DisplayName("the generated definition is exactly the pinned one, file for file and row for row")
    void matchesThePinnedDefinition() throws IOException, URISyntaxException {
        generator.generateAllCaseTypesToJSON(output.toFile());

        Path pinned = Path.of(getClass().getResource("/ccd-definition").toURI());
        Map<String, JsonNode> expected = read(pinned);
        Map<String, JsonNode> actual = read(output);

        assertThat(actual.keySet())
            .as("the files generated -- one directory per case type, so a second case type fails here too")
            .containsExactlyElementsOf(expected.keySet());
        expected.forEach((file, rows) -> assertThat(actual.get(file)).as(file).isEqualTo(rows));
    }

    @Test
    @DisplayName("one case type, in one jurisdiction, both named by configuration's placeholder")
    void identifiersAreThePlaceholders() throws IOException {
        Path caseType = generate();

        assertThat(rows(caseType, "CaseType.json")).singleElement().satisfies(row -> {
            assertThat(row.get("ID").asText()).isEqualTo(PLACEHOLDER);
            assertThat(row.get("JurisdictionID").asText()).isEqualTo(PLACEHOLDER);
        });
        assertThat(rows(caseType, "Jurisdiction.json")).singleElement()
            .satisfies(row -> assertThat(row.get("ID").asText()).isEqualTo(PLACEHOLDER));
    }

    @Test
    @DisplayName("one state, Registered, and one event, linkDeedPoll, that creates a case into it")
    void oneStateAndOneEvent() throws IOException {
        Path caseType = generate();

        assertThat(rows(caseType, "State.json")).extracting(row -> row.get("ID").asText())
            .containsExactly("Registered");
        assertThat(rows(caseType, "CaseEvent/linkDeedPoll.json")).singleElement().satisfies(row -> {
            assertThat(row.get("ID").asText()).isEqualTo(DeedPollCaseType.LINK_DEED_POLL);
            assertThat(row.has("PreConditionStates")).as("a create event has no pre-condition state").isFalse();
            assertThat(row.get("PostConditionState").asText()).isEqualTo("Registered");
        });
        try (Stream<Path> events = Files.list(caseType.resolve("CaseEvent"))) {
            assertThat(events).extracting(path -> path.getFileName().toString())
                .containsExactly("linkDeedPoll.json");
        }
    }

    @Test
    @DisplayName("the two fields are the link and the display reference, and the event requires both")
    void twoFields() throws IOException {
        Path caseType = generate();

        assertThat(rows(caseType, "CaseField.json")).extracting(row -> row.get("ID").asText())
            // caseHistory is the SDK's own viewer field, which it adds to every case type; nobody is granted it.
            .containsExactly("caseHistory", "deedPollId", "deedPollReference");
        assertThat(rows(caseType, "CaseEventToFields/linkDeedPoll.json"))
            .extracting(row -> row.get("CaseFieldID").asText() + ":" + row.get("DisplayContext").asText())
            .containsExactly("deedPollId:MANDATORY", "deedPollReference:MANDATORY");
    }

    @Test
    @DisplayName("DEED_POLL_SYSTEM is granted create and read, and nothing else is granted anything")
    void onlyTheSystemProfileIsGrantedAndOnlyCreateAndRead() throws IOException {
        Path caseType = generate();

        List<JsonNode> grants = new ArrayList<>();
        grants.addAll(rows(caseType, "AuthorisationCaseType.json"));
        grants.addAll(rows(caseType, "AuthorisationCaseEvent/AuthorisationCaseEvent.json"));
        grants.addAll(rows(caseType, "AuthorisationCaseField/DEED_POLL_SYSTEM.json"));
        grants.addAll(rows(caseType, "AuthorisationCaseState.json"));

        assertThat(grants).isNotEmpty().allSatisfy(grant -> {
            assertThat(grant.get("UserRole").asText()).isEqualTo("DEED_POLL_SYSTEM");
            assertThat(grant.get("CRUD").asText()).isEqualTo("CR");
        });
        try (Stream<Path> fieldGrants = Files.list(caseType.resolve("AuthorisationCaseField"))) {
            assertThat(fieldGrants).extracting(path -> path.getFileName().toString())
                .as("one field-grant file per granted profile")
                .containsExactly("DEED_POLL_SYSTEM.json");
        }
        assertThat(rows(caseType, "CaseRoles.json")).as("no case roles").isEmpty();
    }

    @Test
    @DisplayName("the configured IDAM role maps onto DEED_POLL_SYSTEM, and nothing else maps onto anything")
    void systemRoleMapsOntoTheProfile() throws IOException {
        assertThat(rows(generate(), "RoleToAccessProfiles.json")).singleElement().satisfies(row -> {
            assertThat(row.get("RoleName").asText()).isEqualTo("idam:" + PLACEHOLDER);
            assertThat(row.get("AccessProfiles").asText()).isEqualTo("DEED_POLL_SYSTEM");
            assertThat(row.get("ReadOnly").asText()).isEqualTo("N");
            assertThat(row.get("Disabled").asText()).isEqualTo("N");
        });
    }

    @Test
    @DisplayName("confirmed identifiers are configuration: other values change the definition, not the code")
    void identifiersComeFromConfiguration() throws IOException {
        CcdProperties confirmed = new CcdProperties("CONFIRMED_TYPE", "CONFIRMED_JURISDICTION", "confirmed-role");
        // The generator reads the event and never invokes its handler, so the handler needs no repositories.
        DeedPollCaseType definition = new DeedPollCaseType(confirmed, new LinkDeedPollHandler(null, null));
        new CCDDefinitionGenerator(List.<CCDConfig<?, ?, ?>>of(definition), writer)
            .generateAllCaseTypesToJSON(output.toFile());

        Path caseType = output.resolve("CONFIRMED_TYPE");
        assertThat(rows(caseType, "CaseType.json")).singleElement()
            .satisfies(row -> assertThat(row.get("JurisdictionID").asText()).isEqualTo("CONFIRMED_JURISDICTION"));
        assertThat(rows(caseType, "RoleToAccessProfiles.json")).singleElement()
            .satisfies(row -> assertThat(row.get("RoleName").asText()).isEqualTo("idam:confirmed-role"));
    }

    @Test
    @DisplayName("a blank identifier refuses to start rather than generating an empty id")
    void blankIdentifierRefused() {
        assertThatThrownBy(() -> new CcdProperties(" ", PLACEHOLDER, PLACEHOLDER))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("deedpoll.ccd.case-type-id");
        assertThatThrownBy(() -> new CcdProperties(PLACEHOLDER, null, PLACEHOLDER))
            .hasMessageContaining("deedpoll.ccd.jurisdiction-id");
        assertThatThrownBy(() -> new CcdProperties(PLACEHOLDER, PLACEHOLDER, ""))
            .hasMessageContaining("deedpoll.ccd.system-role");
    }

    private Path generate() {
        generator.generateAllCaseTypesToJSON(output.toFile());
        return output.resolve(PLACEHOLDER);
    }

    private static List<JsonNode> rows(Path caseType, String file) throws IOException {
        List<JsonNode> rows = new ArrayList<>();
        JSON.readTree(caseType.resolve(file).toFile()).forEach(rows::add);
        return rows;
    }

    /**
     * Every JSON file under {@code root}, keyed on its path relative to it.
     */
    private static Map<String, JsonNode> read(Path root) throws IOException {
        Map<String, JsonNode> files = new TreeMap<>();
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                files.put(root.relativize(path).toString(), JSON.readTree(path.toFile()));
            }
        }
        return files;
    }
}
