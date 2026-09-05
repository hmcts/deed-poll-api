package uk.gov.hmcts.reform.deedpoll.conformance;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import uk.gov.hmcts.reform.deedpoll.reference.DataverseArtifacts;
import uk.gov.hmcts.reform.deedpoll.reference.DataverseArtifacts.ColumnRow;
import uk.gov.hmcts.reform.deedpoll.reference.DataverseArtifacts.RelationshipRow;
import uk.gov.hmcts.reform.deedpoll.reference.DataverseArtifacts.TableDir;
import uk.gov.hmcts.reform.deedpoll.reference.PrismaSchema;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Layer 1 -- structural conformance.
 *
 * <p>Ported from {@code reference/ts/conformance/schema.conformance.test.ts}. Asserts the ported
 * schema covers the source Dataverse structure documented in the vendored
 * {@code reference/dataverse_tables/}.
 *
 * <p><strong>The Dataverse exports are alphabetically truncated at A--R.</strong> The
 * {@code deedpoll/columns.html} extract declares "Total columns: 70" but lists only as far as
 * "Refund Reference", so Type, State, Urgency, Section, Retain Permanently and every other post-R
 * column is absent from it. Everything here therefore asserts FORWARD coverage only -- every column
 * present in the artifact must map to a ported field -- and <em>must never be read as a complete
 * column set</em>. A green run proves nothing was silently dropped from what the export lists; it
 * says nothing about the columns the export does not reach.
 *
 * <p>Named {@code DataverseSchemaConformanceTest} rather than the reference's plain
 * {@code SchemaConformance} because {@code uk.gov.hmcts.reform.deedpoll.db.SchemaConformanceTest}
 * already holds that name for the other half of the same question: that one asserts the Flyway
 * schema against the vendored Prisma schema, this one asserts the Prisma schema against the
 * Dataverse exports it was derived from. Together they run from the source system to the live
 * database; separately, neither is enough.
 */
class DataverseSchemaConformanceTest {

    /**
     * Dynamics system columns present on every table -- audit, ownership, currency, import and
     * state plumbing. Not ported: the models use app-native equivalents ({@code createdOn} /
     * {@code modifiedOn}) and have no ownership or currency model.
     */
    private static final Set<String> SYSTEM_COLUMNS = Set.of(
        "CreatedBy",
        "CreatedOnBehalfBy",
        "CreatedOn",
        "ModifiedBy",
        "ModifiedOnBehalfBy",
        "ModifiedOn",
        "OwnerId",
        "OwningBusinessUnit",
        "OwningTeam",
        "OwningUser",
        "OverriddenCreatedOn",
        "ImportSequenceNumber",
        "TransactionCurrencyId",
        "ExchangeRate",
        "TimeZoneRuleVersionNumber",
        "UTCConversionTimeZoneCode",
        "VersionNumber",
        "statecode",
        "statuscode",
        "deedpoll_DateTimeZoneIndependent",
        "deedpoll_DateToday");

    /**
     * Source calculated columns the migration derives in the app layer instead of storing
     * ({@code Computed}): the two day-count fields. Not expected as stored fields.
     */
    private static final Set<String> DERIVED_COLUMNS =
        Set.of("deedpoll_DaysReceivedtoEnrolled", "deedpoll_DaysReceivedtoReturned");

    private static final Map<TableDir, TableMapping> TABLE_MAPS = tableMaps();

    private static PrismaSchema reference;

    /**
     * One source table's account of itself: the ported model it became, and its
     * source-column-to-field map.
     *
     * <p>The reference's {@code TABLE_MAPS} carries only the field maps, because in TypeScript
     * nothing checks the field names it names. Here the model is recorded too, so
     * {@link #everyMappedFieldExistsInTheReferenceModel()} can check them.
     */
    private record TableMapping(String model, Map<String, String> fields) {
    }

    @BeforeAll
    static void loadReference() {
        reference = PrismaSchema.loadReference();
    }

    @TestFactory
    @DisplayName("every artifact column is accounted for")
    List<DynamicTest> everyArtifactColumnIsAccountedFor() {
        List<DynamicTest> tests = new ArrayList<>();
        for (Map.Entry<TableDir, TableMapping> entry : TABLE_MAPS.entrySet()) {
            Map<String, String> map = entry.getValue().fields();
            for (ColumnRow column : DataverseArtifacts.loadColumns(entry.getKey())) {
                if (SYSTEM_COLUMNS.contains(column.logicalName())
                    || DERIVED_COLUMNS.contains(column.logicalName())) {
                    continue;
                }
                tests.add(DynamicTest.dynamicTest(
                    "%s: accounts for business column %s (%s)".formatted(
                        entry.getKey(), column.logicalName(), column.displayName()),
                    () -> assertThat(map)
                        .as("no entry for source column %s -- account for it or record why it was "
                            + "deliberately not ported", column.logicalName())
                        .containsKey(column.logicalName())));
            }
        }
        return tests;
    }

    /**
     * Not a port -- an addition. The reference's map claims a field name per source column and
     * nothing verifies the field exists, so a typo or a later rename would leave a source column
     * looking accounted for while nothing carries it. Checked against the vendored Prisma schema,
     * which {@code db.SchemaConformanceTest} in turn checks against the live tables.
     */
    @TestFactory
    @DisplayName("every mapped field exists in the reference model")
    List<DynamicTest> everyMappedFieldExistsInTheReferenceModel() {
        List<DynamicTest> tests = new ArrayList<>();
        for (Map.Entry<TableDir, TableMapping> entry : TABLE_MAPS.entrySet()) {
            TableMapping mapping = entry.getValue();
            List<String> fieldNames = reference.model(mapping.model()).fields().stream()
                .map(PrismaSchema.Field::name)
                .toList();
            mapping.fields().forEach((column, field) -> {
                if (field == null) {
                    return;
                }
                tests.add(DynamicTest.dynamicTest(
                    "%s: %s -> %s.%s".formatted(entry.getKey(), column, mapping.model(), field),
                    () -> assertThat(fieldNames)
                        .as("the map sends source column %s to %s.%s, which the reference schema "
                            + "does not declare", column, mapping.model(), field)
                        .contains(field)));
            });
        }
        return tests;
    }

    @Test
    @DisplayName("models the three business child relations as one-to-many")
    void modelsTheThreeBusinessChildRelationsAsOneToMany() {
        List<String> related = DataverseArtifacts.loadRelationships(TableDir.DEEDPOLL).stream()
            .filter(relationship -> "One-to-many".equals(relationship.relationshipType()))
            .map(RelationshipRow::relatedTable)
            .filter(table -> table.matches(".*(Supporting Document|Witness|Note).*"))
            .sorted()
            .toList();

        assertThat(related).containsExactly(
            "Deed Poll Note", "Deed Poll Supporting Document", "Deed Poll Witness");
    }

    @Test
    @DisplayName("records that the deedpoll columns extract is alphabetically truncated")
    void recordsThatTheDeedPollColumnsExtractIsAlphabeticallyTruncated() {
        // The extract lists 70 rows, ending alphabetically at "Refund Reference" -- post-R
        // rule-driving columns (Type, State, Urgency, Section, Retain Permanently) are absent, so
        // coverage here is FORWARD-only. This asserts that absence directly: if a fuller extract
        // adds those columns, this trips and prompts extending the forward maps above.
        List<String> logicalNames = DataverseArtifacts.loadColumns(TableDir.DEEDPOLL).stream()
            .map(ColumnRow::logicalName)
            .toList();

        assertThat(logicalNames).doesNotContain("deedpoll_Type", "deedpoll_State", "deedpoll_Urgency");
        // Sanity: the extract really does stop in the R's.
        assertThat(logicalNames).contains("deedpoll_RefundReference");
    }

    @TestFactory
    @DisplayName("child tables carry no business rules")
    List<DynamicTest> childTablesCarryNoBusinessRules() {
        List<TableDir> childTables = List.of(
            TableDir.NOTE,
            TableDir.WITNESS,
            TableDir.SUPPORTING_DOCUMENT,
            TableDir.SUPPORTING_DOCUMENT_TYPE);

        return childTables.stream()
            .map(table -> DynamicTest.dynamicTest(
                "%s business_rules.md is empty (nothing to port)".formatted(table),
                () -> assertThat(DataverseArtifacts.hasNoBusinessRules(table))
                    .as("%s now records a business rule -- port it", table)
                    .isTrue()))
            .toList();
    }

    /**
     * Not a port -- an addition, and the check that gives the four above their meaning. An emptiness
     * test that could only ever answer "empty" would pass every child table while proving nothing;
     * the case table does carry rules, so this is the negative case that shows the answer is read
     * rather than assumed.
     */
    @Test
    @DisplayName("the case table's own rules file is not empty")
    void theCaseTablesOwnRulesFileIsNotEmpty() {
        assertThat(DataverseArtifacts.hasNoBusinessRules(TableDir.DEEDPOLL)).isFalse();
    }

    /**
     * Not a port -- an addition. The reference exports {@code declaredColumnTotal} "for the
     * truncation sanity-check" but never calls it, so nothing pinned the parser against the
     * export's own count. A regex that quietly stopped matching some rows would otherwise shrink
     * every coverage assertion above to a green nothing.
     */
    @TestFactory
    @DisplayName("every extract lists as many columns as it declares")
    List<DynamicTest> everyExtractListsAsManyColumnsAsItDeclares() {
        return List.of(TableDir.values()).stream()
            .map(table -> DynamicTest.dynamicTest(
                "%s lists its declared column total".formatted(table),
                () -> assertThat(DataverseArtifacts.declaredColumnTotal(table))
                    .as("%s declares a different number of columns from the rows parsed out of it",
                        table)
                    .isEqualTo(DataverseArtifacts.loadColumns(table).size())))
            .toList();
    }

    /**
     * Source-column (logical name) to ported field, per table. Explicit rather than fuzzy: a source
     * column the migration has NOT accounted for has no entry here and fails the coverage test by
     * name, rather than being silently matched by a loose heuristic.
     *
     * <p>{@code null} marks a business column deliberately NOT ported, with the reason -- these are
     * conscious omissions, distinct from system plumbing.
     */
    private static Map<TableDir, TableMapping> tableMaps() {
        Map<TableDir, TableMapping> maps = new LinkedHashMap<>();
        maps.put(TableDir.DEEDPOLL, new TableMapping("DeedPoll", deedPollFields()));
        maps.put(TableDir.NOTE, new TableMapping("DeedPollNote", noteFields()));
        maps.put(TableDir.WITNESS, new TableMapping("DeedPollWitness", witnessFields()));
        maps.put(TableDir.SUPPORTING_DOCUMENT,
            new TableMapping("DeedPollSupportingDocument", documentFields()));
        maps.put(TableDir.SUPPORTING_DOCUMENT_TYPE,
            new TableMapping("DeedPollSupportingDocumentType", documentTypeFields()));
        return Collections.unmodifiableMap(maps);
    }

    private static Map<String, String> deedPollFields() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("deedpoll_DeedPollId", "id");
        map.put("deedpoll_ReferenceNumber", "displayReference");
        map.put("deedpoll_AgeofMinorMonths", "ageOfMinorMonths");
        map.put("deedpoll_AgeofMinor", "ageOfMinor");
        map.put("deedpoll_Andalsomyold", "andAlsoMyOld");
        map.put("deedpoll_ApplicantAddress1", "applicantAddress1");
        map.put("deedpoll_ApplicantAddress2", "applicantAddress2");
        map.put("deedpoll_ApplicantBuildingNoName", "applicantBuildingNoName");
        map.put("deedpoll_ApplicantEmail", "applicantEmail");
        map.put("deedpoll_ApplicantPostcode", "applicantPostcode");
        map.put("deedpoll_ApplicantTelephone", "applicantTelephone");
        map.put("deedpoll_ApplicantTownCity", "applicantTownCity");
        map.put("deedpoll_Awaitingfurtherdocsinfo", "awaitingFurtherDocsInfo");
        map.put("deedpoll_DateDecisionDirectionsReceived", "dateDecisionDirectionsReceived");
        map.put("deedpoll_DateEnrolled", "dateEnroled");
        map.put("deedpoll_DateNoticeSentToLondonGazette", "dateNoticeSentToLondonGazette");
        map.put("deedpoll_Date", "dateOfApplication");
        map.put("deedpoll_DateofDeclaration", "dateOfDeclaration");
        map.put("deedpoll_DateofRefund", "dateOfRefund");
        map.put("deedpoll_DateReadyforMaster", "dateReadyForMaster");
        map.put("deedpoll_DateReceived", "dateReceived");
        map.put("deedpoll_DateReferred", "dateReferred");
        map.put("deedpoll_DateReturned", "dateReturned");
        map.put("deedpoll_FeeReference", "feeReference");
        map.put("deedpoll_Ihavesubscribed", "iHaveSubscribed");
        map.put("deedpoll_Ishall", "iShall");
        map.put("deedpoll_InPerson", "inPerson");
        map.put("deedpoll_Status", "maritalStatus");
        map.put("deedpoll_MasterDateofDecision", "masterDateOfDecision");
        map.put("deedpoll_MasterDecision", "masterDecision");
        map.put("deedpoll_MasterDirections", "masterDirections");
        map.put("deedpoll_MasterName", "masterName");
        map.put("deedpoll_MigratedRecord", "migratedRecord");
        // migration-provenance id; not needed in the rebuilt store
        map.put("deedpoll_MigratedRecordID", null);
        map.put("deedpoll_MinorDateofBirth", "minorDateOfBirth");
        map.put("deedpoll_Nationality", "nationality");
        map.put("deedpoll_NewForename", "newForename");
        map.put("deedpoll_NewMiddleName", "newMiddleName");
        map.put("deedpoll_NewSurname", "newSurname");
        map.put("deedpoll_OldForename", "oldForename");
        map.put("deedpoll_OldMiddleName", "oldMiddleName");
        map.put("deedpoll_OldSurname", "oldSurname");
        map.put("deedpoll_OnlineApplicationReference", "onlineApplicationReference");
        map.put("deedpoll_Parents", "parents");
        map.put("deedpoll_PostCounter", "postCounter");
        map.put("deedpoll_Privacy", "privacy");
        map.put("deedpoll_PublishtoLondonGazette", "publishToLondonGazette");
        map.put("deedpoll_RefertoMasterChoice", "referToMasterChoice");
        map.put("deedpoll_ReferralNotes", "referralNotes");
        map.put("deedpoll_RefundAmount", "refundAmount");
        // currency base-amount shadow; single-currency rebuild has no base
        map.put("deedpoll_refundamount_Base", null);
        map.put("deedpoll_RefundReference", "refundReference");
        return Collections.unmodifiableMap(map);
    }

    private static Map<String, String> noteFields() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("deedpoll_NotesId", "id");
        map.put("deedpoll_Note", "note");
        // Autonumber display code, deliberately not ported (2026-08-10). It exists in the source
        // only because Dataverse forces a GUID primary key, so a short human-readable handle needed
        // a second column; `id` is the only identity the rebuild needs. The ported column was
        // dropped having never held a value -- see the reference schema.
        map.put("deedpoll_NoteID", null);
        map.put("deedpoll_DeedPoll", "deedPollId");
        return Collections.unmodifiableMap(map);
    }

    private static Map<String, String> witnessFields() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("deedpoll_WitnessId", "id");
        map.put("deedpoll_WitnessName", "witnessName");
        map.put("deedpoll_WitnessAddress", "witnessAddress");
        map.put("deedpoll_WitnessOccupation", "witnessOccupation");
        map.put("deedpoll_Declarant", "declarant");
        map.put("deedpoll_DeedPoll", "deedPollId");
        return Collections.unmodifiableMap(map);
    }

    private static Map<String, String> documentFields() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("deedpoll_SupportingDocumentId", "id");
        map.put("deedpoll_Direction", "direction");
        // File column -> blob metadata (storageKey / originalName / mimeType / sizeBytes)
        map.put("deedpoll_Document", "storageKey");
        map.put("deedpoll_DocumentType", "documentTypeId");
        map.put("deedpoll_Name", "name");
        map.put("deedpoll_DeedPoll", "deedPollId");
        return Collections.unmodifiableMap(map);
    }

    private static Map<String, String> documentTypeFields() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("deedpoll_SupportingDocumentTypeId", "id");
        map.put("deedpoll_SupportingDocumentType", "name");
        map.put("deedpoll_DeedPollType", "applicableTypes");
        map.put("deedpoll_DeedPollMaritalStatus", "applicableMaritalStatuses");
        return Collections.unmodifiableMap(map);
    }
}
