package uk.gov.hmcts.reform.deedpoll.conformance;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import uk.gov.hmcts.reform.deedpoll.domain.FieldError;
import uk.gov.hmcts.reform.deedpoll.domain.deedpoll.DeedPollValidation;
import uk.gov.hmcts.reform.deedpoll.domain.deedpoll.FieldRule;
import uk.gov.hmcts.reform.deedpoll.domain.deedpoll.ValidationInput;
import uk.gov.hmcts.reform.deedpoll.entity.enums.DeedPollType;
import uk.gov.hmcts.reform.deedpoll.entity.enums.PostCounter;
import uk.gov.hmcts.reform.deedpoll.entity.enums.Urgency;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Layer 2c -- conditional-gate conformance.
 *
 * <p>Ported from {@code reference/ts/conformance/conditional-gates.conformance.test.ts}. Encodes the
 * three field-level conditional rules from the vendored
 * {@code dataverse_tables/deedpoll/business_rules.md}:
 *
 * <ul>
 *   <li>"Show and mandate Urgency Reason" -- {@code urgencyreason} required when Urgent</li>
 *   <li>"Show Online Application reference and mandate" -- intentionally retired following AAT feedback</li>
 *   <li>"Show or hide Date Notice Sent to London Gazette" -- hide AND CLEAR the date when Publish to
 *       London Gazette = No, for every type. The reference recorded the general case as "gap #2":
 *       the source applies it to every type while the port at the time only handled it inside the
 *       relinquishment branch.</li>
 * </ul>
 *
 * <p>Plus the "Show hide and mandate fields for Minors" gate, whose else branch has four Clear steps
 * alongside its four Hide steps -- so a Minor case converted to Adult must not keep orphaned minor
 * data in the row.
 */
class ConditionalGatesConformanceTest {

    private static final LocalDateTime SOME_DATE = LocalDateTime.of(2026, 1, 1, 0, 0);

    /** The four fields the Minors rule shows for a minor and clears for everyone else. */
    private static final List<String> MINOR_FIELDS =
        List.of("minorDateOfBirth", "ageOfMinor", "ageOfMinorMonths", "parents");

    private static boolean hasError(List<FieldError> errors, String field) {
        return errors.stream().anyMatch(error -> field.equals(error.field()));
    }

    /**
     * A validation input from name / value pairs.
     *
     * <p>Built through a {@link LinkedHashMap} rather than {@code Map.of}, which refuses a null
     * value -- and "the patch settles the type to null" is one of the cases being tested.
     */
    private static ValidationInput input(Object... nameThenValue) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (int i = 0; i < nameThenValue.length; i += 2) {
            values.put((String) nameThenValue[i], nameThenValue[i + 1]);
        }
        return ValidationInput.of(values);
    }

    @Nested
    @DisplayName("Urgency Reason gate")
    class UrgencyReasonGate {

        @Test
        @DisplayName("requires urgencyreason when urgency = Urgent and it is empty")
        void requiresUrgencyReasonWhenUrgent() {
            List<FieldError> errors = DeedPollValidation.validate(input(
                "type", DeedPollType.Adult,
                "urgency", Urgency.Urgent,
                "urgencyreason", ""));

            assertThat(hasError(errors, "urgencyreason")).isTrue();
        }

        @Test
        @DisplayName("does not require urgencyreason when urgency = Normal")
        void doesNotRequireUrgencyReasonWhenNormal() {
            List<FieldError> errors = DeedPollValidation.validate(input(
                "type", DeedPollType.Adult,
                "urgency", Urgency.Normal));

            assertThat(hasError(errors, "urgencyreason")).isFalse();
        }
    }

    @Nested
    @DisplayName("Retired Online Application Reference gate")
    class OnlineApplicationReferenceGate {

        @ParameterizedTest
        @EnumSource(value = DeedPollType.class, names = {"Adult", "Minor"})
        @DisplayName("does not require the retired online reference for online adult and minor applications")
        void doesNotRequireOnlineApplicationReferenceWhenOnline(DeedPollType type) {
            List<FieldError> errors = DeedPollValidation.validate(input(
                "type", type,
                "postCounter", PostCounter.Online,
                "onlineApplicationReference", ""));

            assertThat(hasError(errors, "onlineApplicationReference")).isFalse();
        }

        @Test
        @DisplayName("does not require it for Post/Counter")
        void doesNotRequireItForPostOrCounter() {
            List<FieldError> errors = DeedPollValidation.validate(input(
                "type", DeedPollType.Adult,
                "postCounter", PostCounter.Post));

            assertThat(hasError(errors, "onlineApplicationReference")).isFalse();
        }
    }

    @Nested
    @DisplayName("Gazette date gate -- GENERAL, all types")
    class GazetteDateGate {

        @Test
        @DisplayName("hides dateNoticeSentToLondonGazette when publish = No for a normal Adult case")
        void hidesTheNoticeDateWhenPublishIsNo() {
            Map<String, FieldRule> rules =
                DeedPollValidation.getFieldRules(DeedPollType.Adult, false, false);

            assertThat(rules.get("dateNoticeSentToLondonGazette").visible()).isFalse();
        }

        @Test
        @DisplayName("shows dateNoticeSentToLondonGazette when publish = Yes")
        void showsTheNoticeDateWhenPublishIsYes() {
            Map<String, FieldRule> rules =
                DeedPollValidation.getFieldRules(DeedPollType.Adult, false, true);

            assertThat(rules.get("dateNoticeSentToLondonGazette").visible()).isTrue();
        }

        @Test
        @DisplayName("clears dateNoticeSentToLondonGazette on write when publish = No")
        void clearsTheNoticeDateOnWriteWhenPublishIsNo() {
            Map<String, Object> patch = DeedPollValidation.applyGazetteGate(input(
                "publishToLondonGazette", false,
                "dateNoticeSentToLondonGazette", SOME_DATE));

            assertThat(patch).containsOnlyKeys("dateNoticeSentToLondonGazette");
            assertThat(patch.get("dateNoticeSentToLondonGazette")).isNull();
        }

        @Test
        @DisplayName("leaves the date untouched when publish = Yes")
        void leavesTheDateUntouchedWhenPublishIsYes() {
            Map<String, Object> patch = DeedPollValidation.applyGazetteGate(input(
                "publishToLondonGazette", true,
                "dateNoticeSentToLondonGazette", SOME_DATE));

            // The reference asserts the key is `undefined`; a patch that does not carry the key at
            // all is the Java equivalent, and is what the write path reads as "leave it alone".
            assertThat(patch).doesNotContainKey("dateNoticeSentToLondonGazette");
        }
    }

    @Nested
    @DisplayName("Minor fields gate -- hide AND clear")
    class MinorFieldsGate {

        @ParameterizedTest(name = "hides nothing and clears nothing for {0}")
        @EnumSource(value = DeedPollType.class, names = {"Minor", "MinorTransgender"})
        void hidesNothingAndClearsNothingForAMinor(DeedPollType type) {
            Map<String, FieldRule> rules = DeedPollValidation.getFieldRules(type);

            for (String field : MINOR_FIELDS) {
                assertThat(rules.get(field).visible()).as(field).isTrue();
            }
            assertThat(DeedPollValidation.applyMinorGate(input("type", type))).isEmpty();
        }

        @ParameterizedTest(name = "hides and clears all four minor fields for {0}")
        @EnumSource(value = DeedPollType.class,
            names = {"Adult", "AdultTransgender", "DeedOfRelinquishment"})
        void hidesAndClearsAllFourMinorFields(DeedPollType type) {
            Map<String, FieldRule> rules = DeedPollValidation.getFieldRules(type);

            for (String field : MINOR_FIELDS) {
                assertThat(rules.get(field).visible()).as(field).isFalse();
            }

            Map<String, Object> patch = DeedPollValidation.applyMinorGate(input("type", type));
            assertThat(patch).containsOnlyKeys(MINOR_FIELDS.toArray(String[]::new));
            assertThat(patch.values()).containsOnlyNulls();
        }

        @Test
        @DisplayName("clears every one of the four, not just the three the port knew about")
        void clearsEveryOneOfTheFour() {
            // minorDateOfBirth was the omission: it stayed visible on an Adult case and rendered as
            // a stranded row. Asserted by name so a partial list fails here.
            Map<String, Object> patch =
                DeedPollValidation.applyMinorGate(input("type", DeedPollType.Adult));

            assertThat(patch).containsOnlyKeys(
                "minorDateOfBirth", "ageOfMinor", "ageOfMinorMonths", "parents");
            assertThat(patch.values()).containsOnlyNulls();
        }

        @Test
        @DisplayName("clears nothing when no type is resolved")
        void clearsNothingWhenNoTypeIsResolved() {
            // A patch that does not settle the type must not be read as "not a minor". Unknown is
            // the source's untouched type, so it clears nothing either.
            assertThat(DeedPollValidation.applyMinorGate(ValidationInput.empty())).isEmpty();
            assertThat(DeedPollValidation.applyMinorGate(input("type", null))).isEmpty();
            assertThat(DeedPollValidation.applyMinorGate(input("type", DeedPollType.Unknown)))
                .isEmpty();
        }
    }
}
