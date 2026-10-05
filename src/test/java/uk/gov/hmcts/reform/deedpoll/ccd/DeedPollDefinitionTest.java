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
    @DisplayName("one state, Registered; linkDeedPoll creates a case into it, and the document event runs on it")
    void oneStateAndTwoEvents() throws IOException {
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
                .containsExactlyInAnyOrder("linkDeedPoll.json", CaseDocumentsEvent.EVENT_ID + ".json");
        }
        assertThat(rows(caseType, "CaseEvent/" + CaseDocumentsEvent.EVENT_ID + ".json")).singleElement()
            .satisfies(row -> assertThat(row.get("PostConditionState").asText()).isEqualTo("Registered"));
    }

    @Test
    @DisplayName("three fields: the link and the display reference, which linking requires, and the documents")
    void threeFields() throws IOException {
        Path caseType = generate();

        assertThat(rows(caseType, "CaseField.json")).extracting(row -> row.get("ID").asText())
            // caseHistory is the SDK's own viewer field, which it adds to every case type; nobody is granted it.
            .containsExactly("caseHistory", "deedPollId", "deedPollReference", CaseDocumentsEvent.FIELD);
        assertThat(rows(caseType, "CaseEventToFields/linkDeedPoll.json"))
            .extracting(row -> row.get("CaseFieldID").asText() + ":" + row.get("DisplayContext").asText())
            .containsExactly("deedPollId:MANDATORY", "deedPollReference:MANDATORY");
        assertThat(rows(caseType, "CaseEventToFields/" + CaseDocumentsEvent.EVENT_ID + ".json"))
            .extracting(row -> row.get("CaseFieldID").asText() + ":" + row.get("DisplayContext").asText())
            .containsExactly(CaseDocumentsEvent.FIELD + ":OPTIONAL");
        assertThat(rows(caseType, "CaseField.json"))
            .filteredOn(row -> row.get("ID").asText().equals(CaseDocumentsEvent.FIELD)).singleElement()
            .satisfies(row -> {
                assertThat(row.get("FieldType").asText()).isEqualTo("Collection");
                assertThat(row.get("FieldTypeParameter").asText()).isEqualTo("Document");
            });
    }

    @Test
    @DisplayName("DEED_POLL_SYSTEM holds what its two events need, D only on the documents field")
    void systemProfileHoldsOnlyWhatItsEventsNeed() throws IOException {
        Path caseType = generate();

        List<JsonNode> grants = new ArrayList<>();
        grants.addAll(rows(caseType, "AuthorisationCaseType.json"));
        grants.addAll(rows(caseType, "AuthorisationCaseEvent/AuthorisationCaseEvent.json"));
        grants.addAll(rows(caseType, "AuthorisationCaseField/DEED_POLL_SYSTEM.json"));
        grants.addAll(rows(caseType, "AuthorisationCaseState.json"));

        // A creating event leaves its destination state ungranted in the SDK, and data-store then refuses every read.
        assertThat(rows(caseType, "AuthorisationCaseState.json")).as("the one state is granted")
            .filteredOn(row -> row.get("UserRole").asText().equals("DEED_POLL_SYSTEM"))
            .singleElement().satisfies(row -> assertThat(row.get("CaseStateID").asText()).isEqualTo("Registered"));
        assertThat(grants).extracting(grant -> grant.get("UserRole").asText())
            .containsOnly("DEED_POLL_SYSTEM", "DEED_POLL_DOCUMENT_READER");
        // Update on the case type and the state: data-store checks both before any event on an existing case.
        assertThat(crud(caseType, "AuthorisationCaseType.json", "CaseTypeID"))
            .containsOnly(Map.entry(PLACEHOLDER, "CRU"));
        assertThat(crud(caseType, "AuthorisationCaseState.json", "CaseStateID"))
            .containsOnly(Map.entry("Registered", "CRU"));
        assertThat(crud(caseType, "AuthorisationCaseEvent/AuthorisationCaseEvent.json", "CaseEventID")).containsOnly(
            Map.entry(DeedPollCaseType.LINK_DEED_POLL, "CR"), Map.entry(CaseDocumentsEvent.EVENT_ID, "CRU"));
        // Delete only where a collection item is taken away, and not on the event, which would carry it to the state.
        assertThat(crud(caseType, "AuthorisationCaseField/DEED_POLL_SYSTEM.json", "CaseFieldID")).containsOnly(
            Map.entry("deedPollId", "CR"), Map.entry("deedPollReference", "CR"),
            Map.entry(CaseDocumentsEvent.FIELD, "CRUD"));
        try (Stream<Path> fieldGrants = Files.list(caseType.resolve("AuthorisationCaseField"))) {
            assertThat(fieldGrants).extracting(path -> path.getFileName().toString())
                .as("one field-grant file per granted profile")
                .containsExactlyInAnyOrder("DEED_POLL_SYSTEM.json", "DEED_POLL_DOCUMENT_READER.json");
        }
        assertThat(rows(caseType, "CaseRoles.json")).as("no case roles").isEmpty();
    }

    @Test
    @DisplayName("the three layouts definition store refuses empty hold the case reference, and nothing else")
    void layoutsDefinitionStoreRequires() throws IOException {
        Path caseType = generate();

        for (String layout : List.of("SearchInputFields.json", "SearchResultFields.json",
            "WorkBasketResultFields.json")) {
            assertThat(rows(caseType, layout)).as(layout).singleElement()
                .satisfies(row -> assertThat(row.get("CaseFieldID").asText()).isEqualTo("[CASE_REFERENCE]"));
        }
        assertThat(rows(caseType, "WorkBasketInputFields.json")).as("the one layout that may be empty").isEmpty();
    }

    @Test
    @DisplayName("admin staff can read the case and documents but have no event, history or other field grants")
    void documentReaderHasOnlyReadAccess() throws IOException {
        Path caseType = generate();
        String reader = "DEED_POLL_DOCUMENT_READER";
        assertThat(crud(caseType, "AuthorisationCaseType.json", "CaseTypeID", reader))
            .containsOnly(Map.entry(PLACEHOLDER, "R"));
        assertThat(crud(caseType, "AuthorisationCaseState.json", "CaseStateID", reader))
            .containsOnly(Map.entry("Registered", "R"));
        assertThat(crud(caseType, "AuthorisationCaseField/" + reader + ".json", "CaseFieldID", reader))
            .containsOnly(Map.entry(CaseDocumentsEvent.FIELD, "R"));
        assertThat(crud(caseType, "AuthorisationCaseEvent/AuthorisationCaseEvent.json", "CaseEventID", reader))
            .isEmpty();
    }

    @Test
    @DisplayName("system and admin staff roles map to their own profiles, with no generic or judicial mapping")
    void systemAndAdminRolesMapOntoSeparateProfiles() throws IOException {
        assertThat(rows(generate(), "RoleToAccessProfiles.json"))
            .extracting(row -> row.get("RoleName").asText() + ":" + row.get("AccessProfiles").asText())
            .containsExactlyInAnyOrder("idam:" + PLACEHOLDER + ":DEED_POLL_SYSTEM",
                "idam:caseworker-deedpoll-admin:DEED_POLL_DOCUMENT_READER");
    }

    @Test
    @DisplayName("confirmed identifiers are configuration: other values change the definition, not the code")
    void identifiersComeFromConfiguration() throws IOException {
        CcdProperties confirmed = new CcdProperties("CONFIRMED_TYPE", "CONFIRMED_JURISDICTION", "confirmed-role");
        // The generator reads the event and never invokes its handler, so the handler needs no repositories.
        DeedPollCaseType definition = new DeedPollCaseType(confirmed, new LinkDeedPollHandler(null, null),
            new SupportingDocumentsHandler(null, null));
        new CCDDefinitionGenerator(List.<CCDConfig<?, ?, ?>>of(definition), writer)
            .generateAllCaseTypesToJSON(output.toFile());

        Path caseType = output.resolve("CONFIRMED_TYPE");
        assertThat(rows(caseType, "CaseType.json")).singleElement()
            .satisfies(row -> assertThat(row.get("JurisdictionID").asText()).isEqualTo("CONFIRMED_JURISDICTION"));
        assertThat(rows(caseType, "RoleToAccessProfiles.json"))
            .filteredOn(row -> row.get("AccessProfiles").asText().equals("DEED_POLL_SYSTEM")).singleElement()
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

    /** Each row's CRUD, keyed on the column that names what it grants. */
    private static Map<String, String> crud(Path caseType, String file, String key) throws IOException {
        return crud(caseType, file, key, "DEED_POLL_SYSTEM");
    }

    private static Map<String, String> crud(Path caseType, String file, String key, String profile) throws IOException {
        Map<String, String> grants = new TreeMap<>();
        for (JsonNode row : rows(caseType, file)) {
            if (row.get("UserRole").asText().equals(profile)) {
                grants.put(row.get(key).asText(), row.get("CRUD").asText());
            }
        }
        return grants;
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
