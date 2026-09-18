package uk.gov.hmcts.reform.deedpoll.notify;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import uk.gov.hmcts.reform.deedpoll.domain.FieldError;
import uk.gov.hmcts.reform.deedpoll.domain.InvalidInputError;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

/**
 * Validating what a caseworker composes: the slots, and the refusal of everything else.
 *
 * <p>Container-free, for the reason {@code ReferralSchemasTest} is: CI has no Docker and excludes every
 * container-backed class, so these are the rules CI actually runs -- and they are the rules most worth pinning,
 * because two of them are the difference between a caseworker adding a sentence to approved wording and a
 * caseworker rewriting the reference number an applicant is told.
 */
class EmailSlotsTest {

    private static final EmailTemplate TEMPLATE = EmailTemplate.APPLICATION_REJECTED;

    /** The judge's own words, as a direction records them: quoted, multi-line, not tidied. */
    private static final String DIRECTION = "The declarant does not meet the requirements of\n"
        + "section 4, and the applicant has not addressed the point raised on 2 September.";

    private static Map<String, Object> body(Object... keysAndValues) {
        Map<String, Object> raw = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            raw.put(String.valueOf(keysAndValues[i]), keysAndValues[i + 1]);
        }
        return raw;
    }

    private static Map<EmailSlot, String> parse(Map<String, Object> raw, String directionsText) {
        return EmailSlots.parse(TEMPLATE, raw, directionsText);
    }

    /** The failures a refusal carries, each attributed to its field. */
    private static List<FieldError> errorsFrom(Runnable parse) {
        try {
            parse.run();
        } catch (InvalidInputError refused) {
            return refused.errors();
        }
        throw new AssertionError("expected the payload to be refused");
    }

    private static String repeated(int length) {
        return "x".repeat(length);
    }

    @Nested
    @DisplayName("what is accepted")
    class Accepted {

        @Test
        @DisplayName("takes both slots, trimmed, and nothing else")
        void takesBothSlots() {
            Map<EmailSlot, String> slots = parse(body(
                "judges_reason", "  The declarant is not qualified.  ",
                "additional_notes", "  Please call us if this is unclear.  "), null);

            assertThat(slots).containsOnlyKeys(EmailSlot.JUDGES_REASON, EmailSlot.ADDITIONAL_NOTES);
            assertThat(slots.get(EmailSlot.JUDGES_REASON)).isEqualTo("The declarant is not qualified.");
            assertThat(slots.get(EmailSlot.ADDITIONAL_NOTES))
                .isEqualTo("Please call us if this is unclear.");
        }

        @Test
        @DisplayName("notes are optional, and absent rather than empty when not sent")
        void notesAreOptional() {
            // Absent rather than "", so the resolver decides what a missing note renders as in one place.
            assertThat(parse(body("judges_reason", "Refused."), null))
                .containsOnlyKeys(EmailSlot.JUDGES_REASON);
            assertThat(parse(body("judges_reason", "Refused.", "additional_notes", "   "), null))
                .containsOnlyKeys(EmailSlot.JUDGES_REASON);
        }

        @Test
        @DisplayName("a slot at exactly its cap is accepted, one character over is not")
        void theCapIsInclusive() {
            assertThat(parse(body("judges_reason", repeated(EmailSlot.JUDGES_REASON.maxLength())), null))
                .containsEntry(EmailSlot.JUDGES_REASON, repeated(EmailSlot.JUDGES_REASON.maxLength()));
            assertThat(errorsFrom(() ->
                parse(body("judges_reason", repeated(EmailSlot.JUDGES_REASON.maxLength() + 1)), null)))
                .extracting(FieldError::field).containsExactly("judges_reason");
        }
    }

    @Nested
    @DisplayName("the judge's reason is pre-populated from the direction")
    class PrePopulation {

        @Test
        @DisplayName("the direction reaches the slot unaltered when the caseworker sent nothing")
        void theDirectionReachesTheSlotUnaltered() {
            // Unaltered, newlines and all: it was trimmed and bounded when the judge recorded it, and this is
            // not a second place that decides what a direction says.
            assertThat(parse(body(), DIRECTION)).containsEntry(EmailSlot.JUDGES_REASON, DIRECTION);
            assertThat(parse(null, DIRECTION)).containsEntry(EmailSlot.JUDGES_REASON, DIRECTION);
        }

        @Test
        @DisplayName("a blank slot falls back to the direction rather than sending a quotation of nothing")
        void blankFallsBackToTheDirection() {
            assertThat(parse(body("judges_reason", "   "), DIRECTION))
                .containsEntry(EmailSlot.JUDGES_REASON, DIRECTION);
        }

        @Test
        @DisplayName("what the caseworker typed wins, because editing the judge's words is theirs to do")
        void theCaseworkersTextWins() {
            assertThat(parse(body("judges_reason", "The judge refused the application."), DIRECTION))
                .containsEntry(EmailSlot.JUDGES_REASON, "The judge refused the application.");
        }

        @Test
        @DisplayName("no direction recorded leaves the slot to the caseworker, and it is still required")
        void noDirectionLeavesTheSlotRequired() {
            // The nullable case: api-3 stores directionsText as optional, so an approval with no comment -- or a
            // referral backfilled by V7 -- has none to pre-populate from.
            assertThat(errorsFrom(() -> parse(body(), null)))
                .extracting(FieldError::field).containsExactly("judges_reason");
            assertThat(errorsFrom(() -> parse(body(), "   ")))
                .extracting(FieldError::field).containsExactly("judges_reason");
            assertThat(parse(body("judges_reason", "Refused, for the reasons given orally."), null))
                .containsEntry(EmailSlot.JUDGES_REASON, "Refused, for the reasons given orally.");
        }

        @Test
        @DisplayName("an over-long direction is refused rather than truncated, and says to shorten it")
        void anOverLongDirectionIsRefused() {
            // A migrated referral's direction is not bounded by ReferralSchemas -- V7 wrote it, not the
            // validator. Truncating a quotation of a judge is worse than asking a caseworker to edit it.
            List<FieldError> refused = errorsFrom(() ->
                parse(body(), repeated(EmailSlot.JUDGES_REASON.maxLength() + 1)));

            assertThat(refused).extracting(FieldError::field).containsExactly("judges_reason");
            assertThat(refused.getFirst().message())
                .contains("pre-populated from the judge's direction")
                .contains("Shorten it");
        }
    }

    @Nested
    @DisplayName("resolved values are not client input")
    class ResolvedValuesAreRefused {

        @Test
        @DisplayName("a request supplying reference_number is refused rather than honoured")
        void referenceNumberIsRefused() {
            // Decision 12. A caseworker altering the reference an applicant is told is a correctness and a
            // fraud problem, so the key is named back rather than quietly dropped.
            List<FieldError> refused = errorsFrom(() ->
                parse(body("judges_reason", "Refused.", "reference_number", "DP2026-9999"), null));

            assertThat(refused).extracting(FieldError::field).containsExactly("reference_number");
            assertThat(refused.getFirst().message())
                .contains("resolved from the case")
                .contains("judges_reason");
        }

        @Test
        @DisplayName("every resolved key is refused, not only the interesting one")
        void everyResolvedKeyIsRefused() {
            for (String resolved : TEMPLATE.resolvedKeys()) {
                Map<String, Object> raw = body("judges_reason", "Refused.");
                raw.put(resolved, "anything at all");

                assertThat(errorsFrom(() -> parse(raw, null)))
                    .as(resolved)
                    .extracting(FieldError::field).containsExactly(resolved);
            }
        }

        @Test
        @DisplayName("flipping the refund branch is refused, which is the point of the rule")
        void theRefundBranchCannotBeFlipped() {
            assertThat(errorsFrom(() -> parse(body("judges_reason", "Refused.", "applied_online", true), null)))
                .extracting(FieldError::field).containsExactly("applied_online");
        }
    }

    @Nested
    @DisplayName("what is refused, and how it is reported")
    class Refusals {

        @Test
        @DisplayName("an unknown key is refused and quoted back")
        void anUnknownKeyIsRefused() {
            List<FieldError> refused = errorsFrom(() ->
                parse(body("judges_reason", "Refused.", "sign_off", "Kind regards"), null));

            assertThat(refused).extracting(FieldError::field).containsExactly("sign_off");
            assertThat(refused.getFirst().message())
                .contains("'sign_off'")
                .contains("is not a slot of application-rejected")
                .contains("judges_reason, additional_notes");
        }

        @Test
        @DisplayName("an absurdly long unknown key is truncated in the message rather than echoed whole")
        void anUnknownKeyIsBoundedInTheMessage() {
            String key = repeated(500);

            assertThat(errorsFrom(() -> parse(body("judges_reason", "Refused.", key, "x"), null)).getFirst()
                .message()).hasSizeLessThan(200);
        }

        @Test
        @DisplayName("a non-text slot is refused rather than coerced")
        void nonTextIsRefusedRatherThanCoerced() {
            assertThat(errorsFrom(() -> parse(body("judges_reason", 42), null)))
                .extracting(FieldError::field, FieldError::message)
                .containsExactly(tuple("judges_reason", "judges_reason must be text"));
            assertThat(errorsFrom(() -> parse(body("judges_reason", "Refused.",
                                                   "additional_notes", List.of("a", "b")), null)))
                .extracting(FieldError::field).containsExactly("additional_notes");
        }

        @Test
        @DisplayName("every offending field is named at once, not one per round trip")
        void everyFailureIsReportedAtOnce() {
            // Three different mistakes in one payload: a key that may not be set, a key that does not exist,
            // an over-long optional slot, and a required slot with nothing to fall back on.
            List<FieldError> refused = errorsFrom(() -> parse(body(
                "reference_number", "DP2026-9999",
                "sign_off", "Kind regards",
                "additional_notes", repeated(EmailSlot.ADDITIONAL_NOTES.maxLength() + 1)), null));

            assertThat(refused).extracting(FieldError::field)
                .containsExactlyInAnyOrder("reference_number", "sign_off", "additional_notes",
                                           "judges_reason");
        }

        @Test
        @DisplayName("the summary names the payload rather than the field, as every schema here does")
        void theSummaryNamesThePayload() {
            assertThatThrownBy(() -> parse(body(), null))
                .isInstanceOf(InvalidInputError.class)
                .hasMessage("Email content is not valid");
        }
    }
}
