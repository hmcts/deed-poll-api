package uk.gov.hmcts.reform.deedpoll.domain.deedpoll;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import uk.gov.hmcts.reform.deedpoll.domain.FieldError;
import uk.gov.hmcts.reform.deedpoll.entity.enums.DeedPollType;
import uk.gov.hmcts.reform.deedpoll.entity.enums.MaritalStatus;
import uk.gov.hmcts.reform.deedpoll.entity.enums.Nationality;
import uk.gov.hmcts.reform.deedpoll.entity.enums.NationalitySection;
import uk.gov.hmcts.reform.deedpoll.entity.enums.PostCounter;
import uk.gov.hmcts.reform.deedpoll.entity.enums.ReferToMaster;
import uk.gov.hmcts.reform.deedpoll.entity.enums.Urgency;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ports every test block in {@code reference/ts/deed-poll/deed-poll-validation.test.ts} -- the per-type
 * field-rule matrix, the create-time defaults and the conditional gates.
 *
 * <p>The reference passes plain objects; here the same inputs are {@link ValidationInput}s over a field
 * map, which is that type's two halves (named fields plus lookup by name) in one place.
 */
class DeedPollValidationTest {

    private static ValidationInput input(Object... keysAndValues) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            values.put(String.valueOf(keysAndValues[i]), keysAndValues[i + 1]);
        }
        return ValidationInput.of(values);
    }

    private static List<String> fieldsOf(List<FieldError> errors) {
        return errors.stream().map(FieldError::field).toList();
    }

    @Nested
    @DisplayName("getFieldRules -- per-type required/visible matrix")
    class GetFieldRules {

        @Test
        @DisplayName("Adult requires inPerson/maritalStatus/urgency/nationality/section, hides minor fields")
        void adultRequiresIdentityAndHidesMinorFields() {
            Map<String, FieldRule> rules = DeedPollValidation.getFieldRules(DeedPollType.Adult);
            assertThat(rules.get("inPerson").required()).isTrue();
            assertThat(rules.get("maritalStatus").required()).isTrue();
            assertThat(rules.get("urgency").required()).isTrue();
            assertThat(rules.get("nationality").required()).isTrue();
            assertThat(rules.get("nationalitySection").required()).isTrue();
            assertThat(rules.get("ageOfMinor").visible()).isFalse();
            assertThat(rules.get("ageOfMinor").required()).isFalse();
            assertThat(rules.get("parents").visible()).isFalse();
        }

        @Test
        @DisplayName("Adult Transgender behaves like Adult")
        void adultTransgenderBehavesLikeAdult() {
            Map<String, FieldRule> rules = DeedPollValidation.getFieldRules(DeedPollType.AdultTransgender);
            assertThat(rules.get("maritalStatus").required()).isTrue();
            assertThat(rules.get("ageOfMinor").visible()).isFalse();
        }

        @Test
        @DisplayName("Minor additionally requires age (years+months) and parents")
        void minorAdditionallyRequiresAgeAndParents() {
            Map<String, FieldRule> rules = DeedPollValidation.getFieldRules(DeedPollType.Minor);
            assertThat(rules.get("ageOfMinor").required()).isTrue();
            assertThat(rules.get("ageOfMinorMonths").required()).isTrue();
            assertThat(rules.get("parents").required()).isTrue();
            assertThat(rules.get("maritalStatus").required()).isTrue();
            assertThat(rules.get("ageOfMinor").visible()).isTrue();
        }

        @Test
        @DisplayName("Minor Transgender behaves like Minor")
        void minorTransgenderBehavesLikeMinor() {
            assertThat(DeedPollValidation.getFieldRules(DeedPollType.MinorTransgender).get("parents").required())
                .isTrue();
        }

        @Test
        @DisplayName("Relinquishment requires only inPerson+urgency, and hides old name and nationality")
        void relinquishmentRequiresOnlyInPersonAndUrgency() {
            Map<String, FieldRule> rules = DeedPollValidation.getFieldRules(DeedPollType.DeedOfRelinquishment);
            assertThat(rules.get("inPerson").required()).isTrue();
            assertThat(rules.get("urgency").required()).isTrue();
            assertThat(rules.get("maritalStatus").required()).isFalse();
            assertThat(rules.get("oldForename").visible()).isFalse();
            assertThat(rules.get("oldMiddleName").visible()).isFalse();
            assertThat(rules.get("oldSurname").visible()).isFalse();
            assertThat(rules.get("maritalStatus").visible()).isFalse();
            assertThat(rules.get("nationality").visible()).isFalse();
            assertThat(rules.get("nationalitySection").visible()).isFalse();
            assertThat(rules.get("dateNoticeSentToLondonGazette").visible()).isFalse();
        }

        @Test
        @DisplayName("Unknown / null impose nothing (all optional, all visible)")
        void unknownAndNullImposeNothing() {
            for (DeedPollType type : Arrays.asList(DeedPollType.Unknown, null)) {
                Map<String, FieldRule> rules = DeedPollValidation.getFieldRules(type);
                assertThat(rules.values()).noneMatch(FieldRule::required);
                assertThat(rules.values()).allMatch(FieldRule::visible);
            }
        }
    }

    @Nested
    @DisplayName("applyCreateDefaults")
    class ApplyCreateDefaults {

        @Test
        @DisplayName("defaults minor marital status to Single when absent")
        void defaultsMinorMaritalStatusToSingle() {
            assertThat(DeedPollValidation.applyCreateDefaults(input("type", DeedPollType.Minor)))
                .containsEntry("maritalStatus", MaritalStatus.Single);
        }

        @Test
        @DisplayName("does not override an explicit minor marital status")
        void doesNotOverrideAnExplicitMinorMaritalStatus() {
            assertThat(DeedPollValidation.applyCreateDefaults(input(
                "type", DeedPollType.Minor,
                "maritalStatus", MaritalStatus.Widowed)))
                .doesNotContainKey("maritalStatus");
        }

        @Test
        @DisplayName("does not default marital status for adults")
        void doesNotDefaultMaritalStatusForAdults() {
            assertThat(DeedPollValidation.applyCreateDefaults(input("type", DeedPollType.Adult)))
                .doesNotContainKey("maritalStatus");
        }

        @Test
        @DisplayName("relinquishment defaults refer-to-master Yes and publish-to-Gazette false")
        void relinquishmentDefaultsReferToMasterAndGazette() {
            Map<String, Object> patch =
                DeedPollValidation.applyCreateDefaults(input("type", DeedPollType.DeedOfRelinquishment));
            assertThat(patch).containsEntry("referToMasterChoice", ReferToMaster.Yes);
            assertThat(patch).containsEntry("publishToLondonGazette", false);
        }

        @Test
        @DisplayName("relinquishment does not override explicit refer-to-master")
        void relinquishmentDoesNotOverrideExplicitReferToMaster() {
            assertThat(DeedPollValidation.applyCreateDefaults(input(
                "type", DeedPollType.DeedOfRelinquishment,
                "referToMasterChoice", ReferToMaster.No)))
                .doesNotContainKey("referToMasterChoice");
        }
    }

    @Nested
    @DisplayName("validate -- per-type required set")
    class ValidatePerTypeRequiredSet {

        @Test
        @DisplayName("flags every missing required field for an Adult")
        void flagsEveryMissingRequiredFieldForAnAdult() {
            List<String> fields = fieldsOf(DeedPollValidation.validate(input("type", DeedPollType.Adult)));
            assertThat(fields).contains("inPerson", "maritalStatus", "urgency", "nationality", "nationalitySection");
            // Hidden minor fields are never demanded.
            assertThat(fields).doesNotContain("ageOfMinor", "parents");
        }

        @Test
        @DisplayName("passes for a fully-populated Adult")
        void passesForAFullyPopulatedAdult() {
            assertThat(DeedPollValidation.validate(input(
                "type", DeedPollType.Adult,
                "inPerson", true,
                "maritalStatus", MaritalStatus.Single,
                "urgency", Urgency.Normal,
                "nationality", Nationality.BritishCitizen,
                "nationalitySection", NationalitySection.Section1))).isEmpty();
        }

        @Test
        @DisplayName("demands age + parents for a Minor")
        void demandsAgeAndParentsForAMinor() {
            assertThat(fieldsOf(DeedPollValidation.validate(input("type", DeedPollType.Minor))))
                .contains("ageOfMinor", "ageOfMinorMonths", "parents");
        }

        @Test
        @DisplayName("imposes nothing type-driven when type is Unknown/null")
        void imposesNothingWhenTypeIsUnknownOrNull() {
            assertThat(DeedPollValidation.validate(input("type", DeedPollType.Unknown))).isEmpty();
            assertThat(DeedPollValidation.validate(ValidationInput.empty())).isEmpty();
        }
    }

    @Nested
    @DisplayName("validate -- conditional gates")
    class ValidateConditionalGates {

        @Test
        @DisplayName("requires urgencyreason when urgency = Urgent")
        void requiresUrgencyReasonWhenUrgent() {
            assertThat(fieldsOf(DeedPollValidation.validate(input("urgency", Urgency.Urgent))))
                .contains("urgencyreason");
        }

        @Test
        @DisplayName("accepts urgency = Urgent with a reason")
        void acceptsUrgentWithAReason() {
            assertThat(fieldsOf(DeedPollValidation.validate(input(
                "urgency", Urgency.Urgent,
                "urgencyreason", "Court deadline"))))
                .doesNotContain("urgencyreason");
        }

        @Test
        @DisplayName("requires onlineApplicationReference when postcounter = Online")
        void requiresOnlineApplicationReferenceWhenOnline() {
            assertThat(fieldsOf(DeedPollValidation.validate(input("postCounter", PostCounter.Online))))
                .contains("onlineApplicationReference");
        }

        @Test
        @DisplayName("accepts Online with a reference present")
        void acceptsOnlineWithAReferencePresent() {
            assertThat(fieldsOf(DeedPollValidation.validate(input(
                "postCounter", PostCounter.Online,
                "onlineApplicationReference", "ONL-123"))))
                .doesNotContain("onlineApplicationReference");
        }
    }
}
