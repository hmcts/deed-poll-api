package uk.gov.hmcts.reform.deedpoll.domain.witnesses;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import uk.gov.hmcts.reform.deedpoll.domain.FieldError;
import uk.gov.hmcts.reform.deedpoll.domain.InvalidInputError;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Ports every test block in {@code reference/ts/witnesses/schemas.test.ts}.
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
            // `expect(parse(valid)).toEqual(valid)`: the values come back exactly as submitted, which is
            // also what pins that this port does not trim -- see WitnessSchemas for why it must not.
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
            assertThatThrownBy(() -> WitnessSchemas.parseCreateWitness(validWith("witnessName", "a".repeat(101))))
                .isInstanceOf(InvalidInputError.class);
            assertThatThrownBy(() -> WitnessSchemas.parseCreateWitness(validWith("witnessAddress", "a".repeat(251))))
                .isInstanceOf(InvalidInputError.class);
            assertThatThrownBy(() -> WitnessSchemas.parseCreateWitness(validWith("witnessOccupation", "a".repeat(101))))
                .isInstanceOf(InvalidInputError.class);
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
        @DisplayName("accepts a value exactly at each column's cap")
        void acceptsAValueExactlyAtEachCap() {
            // Not a reference block: the reference asserts one past each cap and leaves the boundary to
            // zod. Here the comparison is written out, so off-by-one would go unnoticed without this.
            Map<String, Object> raw = validWith("witnessName", "a".repeat(100));
            raw.put("witnessAddress", "a".repeat(250));
            raw.put("witnessOccupation", "a".repeat(100));

            CreateWitnessInput result = WitnessSchemas.parseCreateWitness(raw);

            assertThat(result.witnessName()).hasSize(100);
            assertThat(result.witnessAddress()).hasSize(250);
            assertThat(result.witnessOccupation()).hasSize(100);
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
        @DisplayName("still enforces lengths on present fields")
        void stillEnforcesLengthsOnPresentFields() {
            assertThatThrownBy(() -> WitnessSchemas.parseUpdateWitness(payload("witnessName", "a".repeat(101))))
                .isInstanceOf(InvalidInputError.class);
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
        @DisplayName("still refuses a truthy string for declarant")
        void stillRefusesATruthyStringForDeclarant() {
            // Not a reference block: the reference only re-checks the length rule on the partial schema.
            // The type rule is the one with a wrong-data outcome rather than a rejected-write one, so it is
            // worth asserting that `.partial()` kept it.
            assertThatThrownBy(() -> WitnessSchemas.parseUpdateWitness(payload("declarant", "true")))
                .isInstanceOf(InvalidInputError.class);
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
