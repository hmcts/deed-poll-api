package uk.gov.hmcts.reform.deedpoll.domain.deedpoll;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The patch's own guards, tested directly rather than through a controller.
 *
 * <p>Every one of them is defence in depth, which is exactly why they need their own test: on the HTTP path
 * {@code DeedPollSchemas} strips unknown keys first, so deleting the {@code isWritable} check in {@link
 * DeedPollPatch#of} breaks nothing the controller tests would notice -- and it is the check that keeps
 * {@code referencenumber}, {@code displayReference} and the audit columns out of reach of the form path and of
 * any future producer that is not the JSON schema.
 */
class DeedPollPatchTest {

    private static Map<String, Object> fields(Object... keysAndValues) {
        Map<String, Object> raw = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            raw.put(String.valueOf(keysAndValues[i]), keysAndValues[i + 1]);
        }
        return raw;
    }

    @Nested
    @DisplayName("of")
    class Of {

        @Test
        @DisplayName("drops a field a caller may not write, silently, as the schema does")
        void dropsANonWritableField() {
            DeedPollPatch patch = DeedPollPatch.of(
                fields("newForename", "Ada", "displayReference", "KBDP2026-00001", "createdBy", "Someone Else"));

            assertThat(patch.values()).containsExactly(Map.entry("newForename", "Ada"));
        }

        @Test
        @DisplayName("keeps a null value, because that is a deliberate clear and not an unknown key")
        void keepsANullValue() {
            DeedPollPatch patch = DeedPollPatch.of(fields("dateEnroled", null));

            assertThat(patch.has("dateEnroled")).isTrue();
            assertThat(patch.get("dateEnroled")).isNull();
        }

        @Test
        @DisplayName("an empty map gives a patch that writes nothing")
        void anEmptyMapWritesNothing() {
            assertThat(DeedPollPatch.of(Map.of()).isEmpty()).isTrue();
            assertThat(DeedPollPatch.empty().isEmpty()).isTrue();
        }
    }

    @Nested
    @DisplayName("set")
    class Set {

        @Test
        @DisplayName("refuses a field that is not writable, where of() would have dropped it")
        void refusesANonWritableField() {
            // Naming a field in code is a statement of intent, so a typo there must fail rather than be
            // dropped -- which is the one place the two entry points deliberately disagree.
            assertThatThrownBy(() -> DeedPollPatch.empty().set("displayReference", "KBDP2026-00001"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("displayReference");
        }

        @Test
        @DisplayName("records the fields in the order they were added, and remove takes one back out")
        void recordsAndRemovesFields() {
            DeedPollPatch patch = DeedPollPatch.empty()
                .set("newForename", "Ada")
                .set("newSurname", "Lovelace")
                .set("privacy", null);

            assertThat(patch.fields()).containsExactly("newForename", "newSurname", "privacy");

            patch.remove("privacy");

            assertThat(patch.has("privacy")).isFalse();
            assertThat(patch.fields()).containsExactly("newForename", "newSurname");
        }
    }

    @Nested
    @DisplayName("coerceDates")
    class CoerceDates {

        @Test
        @DisplayName("turns an ISO date string into a date-time")
        void coercesAnIsoDateString() {
            DeedPollPatch patch = DeedPollPatch.of(fields("dateReceived", "2026-07-01")).coerceDates();

            assertThat(patch.get("dateReceived")).isEqualTo(LocalDateTime.parse("2026-07-01T00:00:00"));
        }

        @Test
        @DisplayName("leaves an empty string alone rather than making it a silent 1970")
        void leavesAnEmptyStringAlone() {
            // The reference's `v.length > 0` guard. The schema has already refused an empty date, so reaching
            // here with one means validation was bypassed -- and the write failing is better than the record
            // quietly claiming it was received at the epoch.
            DeedPollPatch patch = DeedPollPatch.of(fields("dateReceived", "")).coerceDates();

            assertThat(patch.get("dateReceived")).isEqualTo("");
        }

        @Test
        @DisplayName("leaves a null and a non-date field untouched")
        void leavesNullsAndNonDatesUntouched() {
            DeedPollPatch patch = DeedPollPatch
                .of(fields("dateEnroled", null, "newForename", "2026-07-01"))
                .coerceDates();

            assertThat(patch.has("dateEnroled")).isTrue();
            assertThat(patch.get("dateEnroled")).isNull();
            // newForename is not a date field, so text that happens to look like one stays text.
            assertThat(patch.get("newForename")).isEqualTo("2026-07-01");
        }

        @Test
        @DisplayName("dateOf reads a coerced date, and null for anything that is not one")
        void dateOfReadsACoercedDate() {
            DeedPollPatch patch = DeedPollPatch.of(fields("dateReceived", "2026-07-01")).coerceDates();

            assertThat(patch.dateOf("dateReceived")).isEqualTo(LocalDateTime.parse("2026-07-01T00:00:00"));
            assertThat(patch.dateOf("dateEnroled")).isNull();
        }
    }

    @Nested
    @DisplayName("copy and merge")
    class CopyAndMerge {

        @Test
        @DisplayName("a copy is independent, so a write never mutates the caller's patch")
        void copyIsIndependentOfTheOriginal() {
            DeedPollPatch original = DeedPollPatch.of(fields("newForename", "Ada"));

            original.copy().set("newSurname", "Lovelace");

            assertThat(original.has("newSurname")).isFalse();
        }

        @Test
        @DisplayName("merge overwrites on collision, which is what the gates rely on")
        void mergeOverwritesOnCollision() {
            DeedPollPatch patch = DeedPollPatch.of(fields("masterName", "Original"));

            patch.merge(Map.of("masterName", "Replaced"));

            assertThat(patch.get("masterName")).isEqualTo("Replaced");
        }

        @Test
        @DisplayName("merge refuses a field that is not writable, as set does")
        void mergeRefusesANonWritableField() {
            assertThatThrownBy(() -> DeedPollPatch.empty().merge(Map.of("referencenumber", 1)))
                .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
