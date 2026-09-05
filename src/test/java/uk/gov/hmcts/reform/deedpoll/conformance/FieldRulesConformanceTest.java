package uk.gov.hmcts.reform.deedpoll.conformance;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import uk.gov.hmcts.reform.deedpoll.domain.deedpoll.DeedPollValidation;
import uk.gov.hmcts.reform.deedpoll.domain.deedpoll.FieldRule;
import uk.gov.hmcts.reform.deedpoll.entity.enums.DeedPollType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Layer 2b -- field-rules conformance.
 *
 * <p>Ported from {@code reference/ts/conformance/field-rules.conformance.test.ts}. Encodes the "Make
 * fields required or non-required depending on Type and Migrated Record" rule from the vendored
 * {@code dataverse_tables/deedpoll/business_rules.md}, plus the "Minors" and "Deed of
 * Relinquishment" show / hide rules, as assertions over
 * {@link DeedPollValidation#getFieldRules(DeedPollType, boolean)}.
 *
 * <p>The rule keys required-ness on BOTH Type and Migrated Record: when Migrated Record = Yes, the
 * whole per-type required set relaxes to optional. The reference recorded that as "gap #1", failing
 * until the service was extended to take {@code migratedRecord}; this port has the parameter, so the
 * migrated cases assert behaviour rather than describing a hole.
 */
class FieldRulesConformanceTest {

    /**
     * The eight fields the required-set rule names, as {@code business_rules.md} words them, mapped
     * to the field names the port uses.
     *
     * <p>Hand-written because it is the only place the rules file's vocabulary meets the port's. It
     * is small and it is checked: {@link RequiredMatrixReadOutOfTheRulesFile} fails on any
     * {@code Set …} line whose display name is not in here, so the rule gaining a ninth field
     * cannot pass unnoticed.
     */
    private static final Map<String, String> FIELD_BY_DISPLAY_NAME = fieldByDisplayName();

    @Nested
    @DisplayName("new (non-migrated) records -- per-type required set")
    class NewRecords {

        @Test
        @DisplayName("Adult requires inPerson, maritalStatus, urgency, nationality, section")
        void adultRequiresTheIdentityAndClassificationSet() {
            Map<String, FieldRule> rules = DeedPollValidation.getFieldRules(DeedPollType.Adult, false);

            for (String field : List.of("inPerson", "maritalStatus", "urgency", "nationality",
                "nationalitySection")) {
                assertThat(rules.get(field).required()).as(field).isTrue();
            }
        }

        @Test
        @DisplayName("Minor requires the Adult set plus age (years+months) and parents")
        void minorRequiresTheAdultSetPlusTheMinorFields() {
            Map<String, FieldRule> rules = DeedPollValidation.getFieldRules(DeedPollType.Minor, false);

            for (String field : List.of("inPerson", "maritalStatus", "urgency", "nationality",
                "nationalitySection", "ageOfMinor", "ageOfMinorMonths", "parents")) {
                assertThat(rules.get(field).required()).as(field).isTrue();
            }
        }

        @Test
        @DisplayName("Deed of Relinquishment requires only inPerson + urgency")
        void relinquishmentRequiresOnlyInPersonAndUrgency() {
            Map<String, FieldRule> rules =
                DeedPollValidation.getFieldRules(DeedPollType.DeedOfRelinquishment, false);

            assertThat(rules.get("inPerson").required()).isTrue();
            assertThat(rules.get("urgency").required()).isTrue();
            assertThat(rules.get("maritalStatus").required()).isFalse();
            assertThat(rules.get("nationality").required()).isFalse();
        }
    }

    @Nested
    @DisplayName("migrated records -- every per-type required field relaxes to optional")
    class MigratedRecords {

        @ParameterizedTest(name = "{0}: no field is required when Migrated Record = Yes")
        @EnumSource(value = DeedPollType.class, names = {"Adult", "Minor", "DeedOfRelinquishment"})
        void noFieldIsRequiredWhenMigrated(DeedPollType type) {
            Map<String, FieldRule> rules = DeedPollValidation.getFieldRules(type, true);

            for (String field : List.of("inPerson", "maritalStatus", "ageOfMinor", "ageOfMinorMonths",
                "parents", "urgency", "nationality", "nationalitySection")) {
                assertThat(rules.get(field).required()).as(field).isFalse();
            }
        }
    }

    @Nested
    @DisplayName("show/hide rules are unaffected by Migrated Record")
    class ShowHideRules {

        @Test
        @DisplayName("minor fields stay hidden for Adult even when migrated")
        void minorFieldsStayHiddenForAdultEvenWhenMigrated() {
            Map<String, FieldRule> rules = DeedPollValidation.getFieldRules(DeedPollType.Adult, true);

            assertThat(rules.get("ageOfMinor").visible()).isFalse();
            assertThat(rules.get("parents").visible()).isFalse();
        }

        @Test
        @DisplayName("relinquishment hides old-name/marital/nationality/section/gazette when migrated")
        void relinquishmentHidesItsFieldsEvenWhenMigrated() {
            Map<String, FieldRule> rules =
                DeedPollValidation.getFieldRules(DeedPollType.DeedOfRelinquishment, true);

            for (String field : List.of("oldForename", "oldMiddleName", "oldSurname", "maritalStatus",
                "nationality", "nationalitySection", "dateNoticeSentToLondonGazette")) {
                assertThat(rules.get(field).visible()).as(field).isFalse();
            }
        }
    }

    /**
     * Not a port -- an addition.
     *
     * <p>The reference states each branch's required set by hand, one {@code it} per branch with the
     * fields listed in the test. That is a transcription of a rule the export states in full, and a
     * transcription only ever agrees with its source on the day it is written. This reads the rule's
     * four IF / ELSE-IF branches out of {@code business_rules.md} and asserts the whole matrix from
     * them -- every field the rule names, in every branch, with the required-ness the rule gives it.
     *
     * <p>{@link BusinessRule#parseRequiredMatrix} is strict: an unrecognised line inside the rule
     * block, or a display name with no ported field, fails rather than being skipped. A rule that
     * grows a branch or a field therefore stops the build instead of quietly widening the gap
     * between the two systems.
     */
    @Nested
    @DisplayName("required matrix, read out of business_rules.md (addition)")
    class RequiredMatrixReadOutOfTheRulesFile {

        @TestFactory
        List<DynamicTest> everyBranchOfTheRuleHolds() {
            List<DynamicTest> tests = new ArrayList<>();

            for (BusinessRule.RequiredBranch branch : BusinessRule.parseRequiredMatrix()) {
                Map<String, Boolean> expected = byFieldName(branch.required());
                for (DeedPollType type : branch.types()) {
                    tests.add(DynamicTest.dynamicTest(
                        "%s, migrated=%s: the rule's required set".formatted(type, branch.migrated()),
                        () -> assertThat(requiredSet(type, branch.migrated(), expected.keySet()))
                            .containsExactlyInAnyOrderEntriesOf(expected)));
                }
            }

            return tests;
        }

        /** The rule's required-ness, re-keyed from its display names to the port's field names. */
        private Map<String, Boolean> byFieldName(Map<String, Boolean> byDisplayName) {
            Map<String, Boolean> expected = new LinkedHashMap<>();
            byDisplayName.forEach((displayName, required) -> {
                String field = FIELD_BY_DISPLAY_NAME.get(displayName);
                assertThat(field)
                    .as("business_rules.md names a field this test cannot resolve, '%s' -- add it "
                        + "to FIELD_BY_DISPLAY_NAME", displayName)
                    .isNotNull();
                expected.put(field, required);
            });
            return expected;
        }

        /** What the port makes required, for the fields the rule names. */
        private Map<String, Boolean> requiredSet(DeedPollType type, boolean migrated,
                                                 Iterable<String> fields) {
            Map<String, FieldRule> rules = DeedPollValidation.getFieldRules(type, migrated);
            Map<String, Boolean> actual = new LinkedHashMap<>();
            for (String field : fields) {
                assertThat(rules).as("the port has no rule for field %s", field).containsKey(field);
                actual.put(field, rules.get(field).required());
            }
            return actual;
        }
    }

    private static Map<String, String> fieldByDisplayName() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("In Person?", "inPerson");
        map.put("Marital Status", "maritalStatus");
        map.put("Age of Minor Years", "ageOfMinor");
        map.put("Age of Minor Months", "ageOfMinorMonths");
        map.put("Parents", "parents");
        map.put("Urgency", "urgency");
        map.put("Nationality", "nationality");
        map.put("Section", "nationalitySection");
        return Map.copyOf(map);
    }
}
