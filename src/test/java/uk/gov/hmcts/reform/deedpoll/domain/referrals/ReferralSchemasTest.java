package uk.gov.hmcts.reform.deedpoll.domain.referrals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import uk.gov.hmcts.reform.deedpoll.domain.FieldError;
import uk.gov.hmcts.reform.deedpoll.domain.InvalidInputError;
import uk.gov.hmcts.reform.deedpoll.entity.enums.Urgency;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Validating a submitted referral.
 *
 * <p>No reference test to port -- referrals are new behaviour rather than something the source store had.
 *
 * <p>Plain unit tests with no container, deliberately: the Jenkins agent has no Docker and excludes every
 * container-backed class, so the rules asserted here are the ones CI actually runs. They are also the rules
 * most worth pinning -- two of them are conditional on another field of the same payload, which is the shape
 * a later refactor is most likely to get subtly wrong.
 */
class ReferralSchemasTest {

    private static Map<String, Object> payload(Object... keysAndValues) {
        Map<String, Object> raw = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            raw.put(String.valueOf(keysAndValues[i]), keysAndValues[i + 1]);
        }
        return raw;
    }

    /** The failures a rejection carries, each attributed to its field. */
    private static List<FieldError> errorsFrom(Runnable parse) {
        try {
            parse.run();
        } catch (InvalidInputError rejected) {
            return rejected.errors();
        }
        throw new AssertionError("expected the payload to be rejected");
    }

    private static CreateReferralInput parse(Map<String, Object> raw) {
        return ReferralSchemas.parseCreateReferral(raw);
    }

    private static String repeated(int length) {
        return "x".repeat(length);
    }

    @Nested
    @DisplayName("reasons")
    class Reasons {

        @Test
        @DisplayName("accepts a whole referral, as the enum members rather than the wire strings")
        void acceptsAWholeReferral() {
            CreateReferralInput created = parse(payload(
                "reasons", List.of("Minor", "DomesticAbuse"),
                "notes", "  The applicant is nine.  ",
                "urgency", "Urgent",
                "urgencyReason", "Hearing on Friday"));

            // The enum, not the strings: unlike a saved query's conditions, only the names are stored and
            // every later reader matches against the enum.
            assertThat(created.reasons())
                .containsExactly(ReferralReason.Minor, ReferralReason.DomesticAbuse);
            // Trimmed, and the trimmed value is what gets stored -- the rule every payload here applies.
            assertThat(created.notes()).contains("The applicant is nine.");
            assertThat(created.urgency()).isEqualTo(Urgency.Urgent);
            assertThat(created.urgencyReason()).contains("Hearing on Friday");
        }

        @Test
        @DisplayName("requires at least one reason, and says so for absent, null and empty alike")
        void requiresAtLeastOneReason() {
            String expected = "reasons must name at least one reason for the referral";

            assertThat(errorsFrom(() -> parse(payload())))
                .containsExactly(new FieldError("reasons", expected));
            assertThat(errorsFrom(() -> parse(payload("reasons", null))))
                .containsExactly(new FieldError("reasons", expected));
            // An empty list is the case a multi-select with nothing ticked actually posts, and it is the one
            // a check for `containsKey` alone would let through.
            assertThat(errorsFrom(() -> parse(payload("reasons", List.of()))))
                .containsExactly(new FieldError("reasons", expected));
        }

        @Test
        @DisplayName("refuses an unknown reason, naming the field and quoting what was sent")
        void refusesAnUnknownReason() {
            List<FieldError> errors = errorsFrom(() -> parse(payload("reasons", List.of("Whatever"))));

            assertThat(errors).hasSize(1);
            assertThat(errors.getFirst().field()).isEqualTo("reasons");
            assertThat(errors.getFirst().message())
                .contains("names no referral reason", "'Whatever'")
                // The acceptable values are listed, so a client does not have to hold the enum to recover.
                .contains("Minor", "Other");
        }

        @Test
        @DisplayName("refuses a reason whose case is wrong, because the name is the stored form")
        void refusesAReasonWhoseCaseIsWrong() {
            // Not a convenience worth having: the names are the wire form and the stored form at once, so
            // accepting "other" here would write a value nothing later matches against the enum.
            assertThat(errorsFrom(() -> parse(payload("reasons", List.of("other")))))
                .singleElement()
                .extracting(FieldError::field)
                .isEqualTo("reasons");
        }

        @Test
        @DisplayName("accepts every member of the enum, so none is unreachable")
        void acceptsEveryMember() {
            List<String> everyName = Arrays.stream(ReferralReason.values()).map(Enum::name).toList();

            CreateReferralInput created = parse(payload(
                "reasons", everyName,
                "notes", "Other is in there"));

            assertThat(created.reasons()).containsExactlyElementsOf(Arrays.asList(ReferralReason.values()));
        }

        @Test
        @DisplayName("de-duplicates rather than refusing, keeping the order the caller sent")
        void deDuplicates() {
            CreateReferralInput created =
                parse(payload("reasons", List.of("DomesticAbuse", "Minor", "DomesticAbuse")));

            // A multi-select posting the same box twice has one obvious correct reading, and the stored array
            // is a set in everything but type.
            assertThat(created.reasons())
                .containsExactly(ReferralReason.DomesticAbuse, ReferralReason.Minor);
        }

        @Test
        @DisplayName("refuses a bare string rather than reading it as a single reason")
        void refusesABareString() {
            // One shape, whether there is one reason or six: a client sending a string would otherwise store
            // something every reader has to handle separately.
            assertThat(errorsFrom(() -> parse(payload("reasons", "Minor"))))
                .containsExactly(new FieldError("reasons", "reasons must be a list of text"));
        }

        @Test
        @DisplayName("refuses a list holding something that is not text")
        void refusesANonTextElement() {
            assertThat(errorsFrom(() -> parse(payload("reasons", List.of("Minor", 7)))))
                .containsExactly(new FieldError("reasons", "reasons must be a list of text"));
        }
    }

    @Nested
    @DisplayName("notes, which Other requires")
    class Notes {

        @Test
        @DisplayName("requires notes when Other is among the reasons")
        void requiresNotesWhenOther() {
            List<FieldError> errors = errorsFrom(() -> parse(payload("reasons", List.of("Other"))));

            assertThat(errors).hasSize(1);
            assertThat(errors.getFirst().field()).isEqualTo("notes");
            assertThat(errors.getFirst().message()).contains("required when Other");
        }

        @Test
        @DisplayName("requires notes when Other arrives alongside a reason that does not need them")
        void requiresNotesWhenOtherIsOneOfSeveral() {
            // The rule is about the set containing Other, not about Other being the only selection -- which
            // is the reading an implementation checking `reasons.getFirst()` would get wrong.
            assertThat(errorsFrom(() -> parse(payload("reasons", List.of("Minor", "Other")))))
                .singleElement()
                .extracting(FieldError::field)
                .isEqualTo("notes");
        }

        @Test
        @DisplayName("does not accept whitespace as the explanation Other needs")
        void doesNotAcceptWhitespaceAsAnExplanation() {
            // Blank becomes absent, which is what makes the conditional rule real: a stored explanation of
            // nothing reads as an explanation until somebody opens it.
            assertThat(errorsFrom(() -> parse(payload("reasons", List.of("Other"), "notes", "   "))))
                .singleElement()
                .extracting(FieldError::field)
                .isEqualTo("notes");
        }

        @Test
        @DisplayName("leaves notes optional when Other is not among the reasons")
        void leavesNotesOptionalOtherwise() {
            CreateReferralInput created = parse(payload("reasons", List.of("NonPublication")));

            assertThat(created.notes()).isEmpty();
        }

        @Test
        @DisplayName("accepts notes on a referral that does not need them")
        void acceptsNotesWithoutOther() {
            assertThat(parse(payload("reasons", List.of("Minor"), "notes", "Born 2019")).notes())
                .contains("Born 2019");
        }

        @Test
        @DisplayName("caps the notes, because the case note the referral writes is bounded")
        void capsTheNotes() {
            assertThat(errorsFrom(() ->
                parse(payload("reasons", List.of("Minor"),
                    "notes", repeated(ReferralSchemas.MAX_NOTES_LENGTH + 1)))))
                .containsExactly(new FieldError(
                    "notes", "notes must be " + ReferralSchemas.MAX_NOTES_LENGTH + " characters or fewer"));

            assertThat(parse(payload("reasons", List.of("Minor"),
                "notes", repeated(ReferralSchemas.MAX_NOTES_LENGTH))).notes())
                .isPresent();
        }

        @Test
        @DisplayName("refuses notes that are not text")
        void refusesNotesThatAreNotText() {
            assertThat(errorsFrom(() -> parse(payload("reasons", List.of("Minor"), "notes", 7))))
                .containsExactly(new FieldError("notes", "notes must be text"));
        }
    }

    @Nested
    @DisplayName("urgency, and the reason Urgent requires")
    class UrgencyAndItsReason {

        @Test
        @DisplayName("defaults to Normal when the caller says nothing")
        void defaultsToNormal() {
            // Not demanded: most referrals are not urgent, and a required field whose usual answer is the
            // default is a field every caller fills in without reading.
            assertThat(parse(payload("reasons", List.of("Minor"))).urgency()).isEqualTo(Urgency.Normal);
            assertThat(parse(payload("reasons", List.of("Minor"), "urgency", "  ")).urgency())
                .isEqualTo(Urgency.Normal);
        }

        @Test
        @DisplayName("requires a reason when the referral is Urgent")
        void requiresAReasonWhenUrgent() {
            List<FieldError> errors =
                errorsFrom(() -> parse(payload("reasons", List.of("Minor"), "urgency", "Urgent")));

            assertThat(errors).hasSize(1);
            assertThat(errors.getFirst().field()).isEqualTo("urgencyReason");
            assertThat(errors.getFirst().message()).contains("required when urgency is Urgent");
        }

        @Test
        @DisplayName("does not require one when the referral is Normal")
        void doesNotRequireOneWhenNormal() {
            assertThat(parse(payload("reasons", List.of("Minor"), "urgency", "Normal")).urgencyReason())
                .isEmpty();
        }

        @Test
        @DisplayName("refuses an urgency it does not know rather than reading it as Normal")
        void refusesAnUnknownUrgency() {
            // Coercing would drop the one signal that gets a referral looked at today, so an unknown value is
            // refused the way a saved query's `shared` flag is.
            List<FieldError> errors =
                errorsFrom(() -> parse(payload("reasons", List.of("Minor"), "urgency", "Standard")));

            assertThat(errors).hasSize(1);
            assertThat(errors.getFirst().field()).isEqualTo("urgency");
            assertThat(errors.getFirst().message())
                .contains("names no urgency", "'Standard'", "Normal", "Urgent");
        }

        @Test
        @DisplayName("caps the urgency reason")
        void capsTheUrgencyReason() {
            assertThat(errorsFrom(() -> parse(payload(
                "reasons", List.of("Minor"),
                "urgency", "Urgent",
                "urgencyReason", repeated(ReferralSchemas.MAX_URGENCY_REASON_LENGTH + 1)))))
                .contains(new FieldError("urgencyReason",
                    "urgencyReason must be " + ReferralSchemas.MAX_URGENCY_REASON_LENGTH
                        + " characters or fewer"));
        }
    }

    @Nested
    @DisplayName("the payload as a whole")
    class ThePayloadAsAWhole {

        @Test
        @DisplayName("reports every failure at once rather than one per round trip")
        void reportsEveryFailureAtOnce() {
            List<FieldError> errors = errorsFrom(() -> parse(payload("urgency", "Urgent")));

            assertThat(errors).extracting(FieldError::field)
                .containsExactlyInAnyOrder("reasons", "urgencyReason");
        }

        @Test
        @DisplayName("does not demand an explanation for a reason it could not read")
        void doesNotDemandAnExplanationForAReasonItCouldNotRead() {
            // A misspelled "Other" is one mistake, not two: naming `notes` as well would send the caseworker
            // to fix a field that is only required because of the field they have yet to correct.
            assertThat(errorsFrom(() -> parse(payload("reasons", List.of("Otherr")))))
                .extracting(FieldError::field)
                .containsExactly("reasons");
        }

        @Test
        @DisplayName("carries the summary message a 400 renders as its banner")
        void carriesTheSummaryMessage() {
            assertThatThrownBy(() -> parse(payload()))
                .isInstanceOf(InvalidInputError.class)
                .hasMessage("Referral is not valid");
        }

        @Test
        @DisplayName("strips what the caller may not set, including the state and the migrated flag")
        void stripsWhatTheCallerMayNotSet() {
            // Structural rather than a check: CreateReferralInput has nowhere to put any of these, so a
            // caller cannot post a referral that is already Closed, one attributed to the backfill, or one
            // addressed to a chosen judge.
            CreateReferralInput created = parse(payload(
                "reasons", List.of("Minor"),
                "state", "Closed",
                "migrated", true,
                "masterName", "Master Someone",
                "createdBy", "Somebody Else"));

            assertThat(created).isEqualTo(new CreateReferralInput(
                List.of(ReferralReason.Minor), Optional.empty(), Urgency.Normal, Optional.empty()));
        }
    }
}
