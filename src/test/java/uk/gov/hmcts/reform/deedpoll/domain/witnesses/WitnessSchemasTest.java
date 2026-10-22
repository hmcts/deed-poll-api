package uk.gov.hmcts.reform.deedpoll.domain.witnesses;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import uk.gov.hmcts.reform.deedpoll.domain.FieldError;
import uk.gov.hmcts.reform.deedpoll.domain.InvalidInputError;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Ports every test block in {@code reference/ts/witnesses/schemas.test.ts}, as re-vendored from admin 7567b60.
 *
 * <p>{@code safeParse(...).success === false} becomes "throws {@link InvalidInputError}", and where the
 * reference only checks that flag this asserts the {@link FieldError} as well -- the field name is what
 * lets a form render the failure against the right input rather than as a banner, and it is the part a
 * boolean success flag cannot check.
 */
class WitnessSchemasTest {

    /** The reference's `valid` fixture, field for field. */
    private static final Map<String, Object> VALID = payload(
        "witnessName", "Sam Doe",
        "witnessAddress", "1 High St",
        "witnessOccupation", "Teacher",
        "declarant", false);

    private static Map<String, Object> payload(Object... keysAndValues) {
        Map<String, Object> raw = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            raw.put(String.valueOf(keysAndValues[i]), keysAndValues[i + 1]);
        }
        return raw;
    }

    /** The reference's `{ ...valid, field: value }`. */
    private static Map<String, Object> validWith(String field, Object value) {
        Map<String, Object> raw = new LinkedHashMap<>(VALID);
        raw.put(field, value);
        return raw;
    }

    /** The reference's `delete partial[field]`. */
    private static Map<String, Object> validWithout(String field) {
        Map<String, Object> raw = new LinkedHashMap<>(VALID);
        raw.remove(field);
        return raw;
    }

    /** The failures the rejection carries, each attributed to its field. */
    private static List<FieldError> errorsFrom(ThrowingParse parse) {
        try {
            parse.run();
        } catch (InvalidInputError rejected) {
            return rejected.errors();
        }
        throw new AssertionError("expected the payload to be rejected");
    }

    private interface ThrowingParse {
        void run();
    }

    @Nested
    @DisplayName("createWitnessSchema")
    class CreateWitnessSchema {

        @Test
        @DisplayName("accepts a complete witness")
        void acceptsACompleteWitness() {
            // `expect(parse(valid)).toEqual(valid)`: a value with nothing to trim comes back as submitted.
            assertThat(WitnessSchemas.parseCreateWitness(VALID))
                .isEqualTo(new CreateWitnessInput("Sam Doe", "1 High St", "Teacher", false));
        }

        @Test
        @DisplayName("requires every field, because the source does")
        void requiresEveryField() {
            for (String field : VALID.keySet()) {
                assertThat(errorsFrom(() -> WitnessSchemas.parseCreateWitness(validWithout(field))))
                    .as("%s should be required", field)
                    .containsExactly(new FieldError(field, field + " is required"));
            }
        }

        @Test
        @DisplayName("enforces the source column lengths")
        void enforcesTheSourceColumnLengths() {
            // Each field at its limit and one over: only the pair pins the boundary.
            assertThat(WitnessSchemas.parseCreateWitness(validWith("witnessName", "a".repeat(100))).witnessName())
                .hasSize(100);
            assertThat(errorsFrom(() -> WitnessSchemas.parseCreateWitness(validWith("witnessName", "a".repeat(101)))))
                .containsExactly(new FieldError("witnessName", "witnessName must be 100 characters or fewer"));
            assertThat(WitnessSchemas.parseCreateWitness(validWith("witnessAddress", "a".repeat(250))).witnessAddress())
                .hasSize(250);
            assertThat(errorsFrom(() ->
                WitnessSchemas.parseCreateWitness(validWith("witnessAddress", "a".repeat(251)))))
                .containsExactly(new FieldError("witnessAddress", "witnessAddress must be 250 characters or fewer"));
            assertThat(WitnessSchemas.parseCreateWitness(validWith("witnessOccupation", "a".repeat(100)))
                .witnessOccupation()).hasSize(100);
            assertThat(errorsFrom(() ->
                WitnessSchemas.parseCreateWitness(validWith("witnessOccupation", "a".repeat(101)))))
                .containsExactly(
                    new FieldError("witnessOccupation", "witnessOccupation must be 100 characters or fewer"));
        }

        @ParameterizedTest(name = "refuses a blank or whitespace-only {0}")
        @ValueSource(strings = {"witnessName", "witnessAddress", "witnessOccupation"})
        void refusesABlankOrWhitespaceOnlyField(String field) {
            FieldError blank = new FieldError(field, field + " must not be blank");
            assertThat(errorsFrom(() -> WitnessSchemas.parseCreateWitness(validWith(field, ""))))
                .containsExactly(blank);
            assertThat(errorsFrom(() -> WitnessSchemas.parseCreateWitness(validWith(field, " \t\n "))))
                .containsExactly(blank);
        }

        @Test
        @DisplayName("sends the trimmed values")
        void sendsTheTrimmedValues() {
            Map<String, Object> raw = validWith("witnessName", "  Sam Doe  ");
            raw.put("witnessAddress", "\t1 High St\n");
            raw.put("witnessOccupation", " Teacher");

            assertThat(WitnessSchemas.parseCreateWitness(raw))
                .isEqualTo(new CreateWitnessInput("Sam Doe", "1 High St", "Teacher", false));
        }

        @Test
        @DisplayName("measures the maximum after trimming")
        void measuresTheMaximumAfterTrimming() {
            Map<String, Object> raw = validWith("witnessName", " " + "a".repeat(100) + " ");
            raw.put("witnessAddress", " " + "a".repeat(250) + " ");
            raw.put("witnessOccupation", " " + "a".repeat(100) + " ");

            CreateWitnessInput result = WitnessSchemas.parseCreateWitness(raw);

            assertThat(result.witnessName()).isEqualTo("a".repeat(100));
            assertThat(result.witnessAddress()).isEqualTo("a".repeat(250));
            assertThat(result.witnessOccupation()).isEqualTo("a".repeat(100));
        }

        @Test
        @DisplayName("trims what JavaScript's trim trims, and nothing else")
        void trimsWhatJavaScriptTrims() {
            // Not a reference block: zod's .trim() is String.prototype.trim, whose set differs from Java's trim()
            // (which also strips every C0 control and not the no-break space) and strip() (which keeps the
            // no-break space). Admin trims with the former before it sends, so the port must agree with it.
            assertThat(WitnessSchemas.trim("\u00A0\uFEFF\u2028\u3000\u000B\fSam\u2029\u202F\r"))
                .isEqualTo("Sam");
            assertThat(WitnessSchemas.trim("\u0001Sam\u001F")).isEqualTo("\u0001Sam\u001F");
            assertThat(errorsFrom(() -> WitnessSchemas.parseCreateWitness(validWith("witnessName", "\u00A0\u2003"))))
                .containsExactly(new FieldError("witnessName", "witnessName must not be blank"));
        }

        @Test
        @DisplayName("requires declarant to be a boolean, not a truthy string")
        void requiresDeclarantToBeABoolean() {
            // The string that would coerce wrongly is "false", which is truthy in JavaScript -- so a
            // declarant recorded as an ordinary witness is the failure this refusal prevents. Both strings
            // are checked; the reference checks "true".
            assertThat(errorsFrom(() -> WitnessSchemas.parseCreateWitness(validWith("declarant", "true"))))
                .containsExactly(new FieldError("declarant", "declarant must be true or false"));
            assertThatThrownBy(() -> WitnessSchemas.parseCreateWitness(validWith("declarant", "false")))
                .isInstanceOf(InvalidInputError.class);
        }

        @Test
        @DisplayName("refuses a field present as null, as the source columns are NOT NULL")
        void refusesAFieldPresentAsNull() {
            // Not a reference block: in TypeScript a required `z.string()` rejects null as a type error the
            // tests do not restate. Here it is worth pinning that an explicit null is refused with the same
            // message as an omission rather than reaching a NOT NULL column.
            assertThat(errorsFrom(() -> WitnessSchemas.parseCreateWitness(validWith("witnessName", null))))
                .containsExactly(new FieldError("witnessName", "witnessName is required"));
        }

        @Test
        @DisplayName("reports every offending field at once, not just the first")
        void reportsEveryOffendingFieldAtOnce() {
            // Not a reference block, but zod collects issues and InvalidInputError carries a list, so the
            // behaviour is the reference's. A caseworker retyping a declaration should see all of it.
            Map<String, Object> raw = payload("witnessAddress", 42, "declarant", "yes");

            assertThat(errorsFrom(() -> WitnessSchemas.parseCreateWitness(raw)))
                .containsExactly(
                    new FieldError("witnessName", "witnessName is required"),
                    new FieldError("witnessAddress", "witnessAddress must be text"),
                    new FieldError("witnessOccupation", "witnessOccupation is required"),
                    new FieldError("declarant", "declarant must be true or false"));
        }

        @Test
        @DisplayName("strips unknown keys, so a caller cannot set the parent or the timestamps")
        void stripsUnknownKeys() {
            // Not a reference block: there the DTO type makes it a compile error. Here it matters more,
            // because a Map payload could carry anything -- and deedPollId is an argument to
            // createWitness, so a witness cannot be posted onto a case other than the one in the URL.
            Map<String, Object> raw = validWith("deedPollId", "somebody-elses-case");
            raw.put("createdOn", "1999-01-01T00:00:00");

            assertThat(WitnessSchemas.parseCreateWitness(raw))
                .isEqualTo(new CreateWitnessInput("Sam Doe", "1 High St", "Teacher", false));
        }
    }

    @Nested
    @DisplayName("updateWitnessSchema")
    class UpdateWitnessSchema {

        @Test
        @DisplayName("accepts a partial patch")
        void acceptsAPartialPatch() {
            UpdateWitnessInput result = WitnessSchemas.parseUpdateWitness(payload("witnessOccupation", "Nurse"));

            assertThat(result).isEqualTo(new UpdateWitnessInput(
                Optional.empty(), Optional.empty(), Optional.of("Nurse"), Optional.empty()));
        }

        @Test
        @DisplayName("leaves declarant optional, unlike create, so a patch need not restate it")
        void leavesDeclarantOptional() {
            assertThat(errorsFrom(() -> WitnessSchemas.parseCreateWitness(validWithout("declarant"))))
                .containsExactly(new FieldError("declarant", "declarant is required"));
            assertThat(WitnessSchemas.parseUpdateWitness(payload("witnessName", "Sam Doe")))
                .isEqualTo(new UpdateWitnessInput(
                    Optional.of("Sam Doe"), Optional.empty(), Optional.empty(), Optional.empty()));
        }

        @Test
        @DisplayName("still refuses a truthy string for declarant when the patch does carry it")
        void stillRefusesATruthyStringForDeclarant() {
            assertThat(errorsFrom(() -> WitnessSchemas.parseUpdateWitness(payload("declarant", "true"))))
                .containsExactly(new FieldError("declarant", "declarant must be true or false"));
            assertThat(WitnessSchemas.parseUpdateWitness(payload("declarant", false)))
                .isEqualTo(new UpdateWitnessInput(
                    Optional.empty(), Optional.empty(), Optional.empty(), Optional.of(false)));
        }

        @Test
        @DisplayName("refuses a blank present field and trims a padded one")
        void refusesABlankPresentFieldAndTrimsAPaddedOne() {
            assertThat(errorsFrom(() -> WitnessSchemas.parseUpdateWitness(payload("witnessName", "   "))))
                .containsExactly(new FieldError("witnessName", "witnessName must not be blank"));
            assertThat(WitnessSchemas.parseUpdateWitness(payload("witnessAddress", " 2 Low St ")))
                .isEqualTo(new UpdateWitnessInput(
                    Optional.empty(), Optional.of("2 Low St"), Optional.empty(), Optional.empty()));
        }

        @Test
        @DisplayName("leaves an omitted field out of the patch, so it is unchanged at api")
        void leavesAnOmittedFieldOutOfThePatch() {
            assertThat(WitnessSchemas.parseUpdateWitness(payload("declarant", true)))
                .isEqualTo(new UpdateWitnessInput(
                    Optional.empty(), Optional.empty(), Optional.empty(), Optional.of(true)));
        }

        @Test
        @DisplayName("still enforces lengths on present fields")
        void stillEnforcesLengthsOnPresentFields() {
            assertThat(errorsFrom(() -> WitnessSchemas.parseUpdateWitness(payload("witnessName", "a".repeat(101)))))
                .containsExactly(new FieldError("witnessName", "witnessName must be 100 characters or fewer"));
        }

        @Test
        @DisplayName("accepts an empty patch")
        void acceptsAnEmptyPatch() {
            // Not a reference block, but `.partial()` makes every field optional and an empty object is the
            // limit of that. The service then writes nothing, which WitnessServiceTest asserts.
            assertThat(WitnessSchemas.parseUpdateWitness(Map.of())).isEqualTo(UpdateWitnessInput.EMPTY);
            assertThat(UpdateWitnessInput.EMPTY.isEmpty()).isTrue();
        }

        @Test
        @DisplayName("keeps absent and null distinct, refusing the null rather than clearing the column")
        void keepsAbsentAndNullDistinct() {
            // Not a reference block. It is the distinction the plan singles out: a patch omitting a field
            // and a patch clearing it must stay different. Every witness column is NOT NULL, so "clear it"
            // has no legal outcome and is refused -- better a validation error naming the field than a
            // constraint violation naming the column.
            assertThat(WitnessSchemas.parseUpdateWitness(Map.of()).declarant()).isEmpty();
            assertThat(errorsFrom(() -> WitnessSchemas.parseUpdateWitness(payload("declarant", null))))
                .containsExactly(new FieldError("declarant", "declarant cannot be null"));
            assertThat(errorsFrom(() -> WitnessSchemas.parseUpdateWitness(payload("witnessAddress", null))))
                .containsExactly(new FieldError("witnessAddress", "witnessAddress cannot be null"));
        }

        @Test
        @DisplayName("strips unknown keys, as create does")
        void stripsUnknownKeys() {
            // Not a reference block, and the same guard as on create: a patch must not be able to move a
            // witness to another case.
            Map<String, Object> raw = payload("declarant", true, "deedPollId", "somebody-elses-case");

            assertThat(WitnessSchemas.parseUpdateWitness(raw)).isEqualTo(new UpdateWitnessInput(
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.of(true)));
        }
    }
}
