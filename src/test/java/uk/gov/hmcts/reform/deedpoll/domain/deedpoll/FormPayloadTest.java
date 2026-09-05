package uk.gov.hmcts.reform.deedpoll.domain.deedpoll;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ports every test block in {@code reference/ts/deed-poll/form-payload.test.ts}.
 */
class FormPayloadTest {

    private static Map<String, Object> body(Object... keysAndValues) {
        Map<String, Object> body = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            body.put(String.valueOf(keysAndValues[i]), keysAndValues[i + 1]);
        }
        return body;
    }

    @Test
    @DisplayName("composes GOV.UK date parts into an ISO string")
    void composesGovUkDatePartsIntoAnIsoString() {
        Map<String, Object> patch = FormPayload.buildUpdatePatch(body(
            "dateReceived-day", "15",
            "dateReceived-month", "3",
            "dateReceived-year", "2026"));

        assertThat(patch).containsEntry("dateReceived", "2026-03-15T00:00:00.000Z");
    }

    @Test
    @DisplayName("nulls an incomplete or empty date")
    void nullsAnIncompleteOrEmptyDate() {
        assertThat(FormPayload.buildUpdatePatch(body("dateReceived-day", "15")))
            .containsEntry("dateReceived", null);
    }

    @Test
    @DisplayName("treats a present checkbox as true and an absent one as false")
    void treatsAPresentCheckboxAsTrue() {
        Map<String, Object> patch = FormPayload.buildUpdatePatch(body("inPerson", "true"));
        assertThat(patch).containsEntry("inPerson", true);
        assertThat(patch).containsEntry("retainPermanently", false);
    }

    @Test
    @DisplayName("parses number fields and nulls blanks")
    void parsesNumberFieldsAndNullsBlanks() {
        Map<String, Object> patch = FormPayload.buildUpdatePatch(body("ageOfMinor", "7", "ageOfMinorMonths", ""));
        assertThat(patch).containsEntry("ageOfMinor", 7);
        assertThat(patch).containsEntry("ageOfMinorMonths", null);
    }

    @Test
    @DisplayName("collects multi-checkbox values into an int list")
    void collectsMultiCheckboxValuesIntoAnIntList() {
        assertThat(FormPayload.buildUpdatePatch(body("iShall", List.of("1", "4"))))
            .containsEntry("iShall", List.of(1, 4));
    }

    @Test
    @DisplayName("trims text and nulls empty strings")
    void trimsTextAndNullsEmptyStrings() {
        Map<String, Object> patch = FormPayload.buildUpdatePatch(body("newForename", "  Alex  ", "newSurname", ""));
        assertThat(patch).containsEntry("newForename", "Alex");
        assertThat(patch).containsEntry("newSurname", null);
    }

    @Test
    @DisplayName("omits a non-nullable enum that is absent from the body")
    void omitsANonNullableEnumThatIsAbsent() {
        // Regression: `privacy` renders disabled for a non-admin, and browsers do not submit a disabled
        // control. Nulling it wrote null to a NOT NULL column, so a non-admin saving the Application tab got
        // a 500. Absent must mean "leave alone", NOT "clear" -- the blank-means-clear rule only holds for
        // nullable columns.
        Map<String, Object> patch = FormPayload.buildUpdatePatch(body("newForename", "Ada"));
        assertThat(patch).doesNotContainKey("privacy");
        assertThat(patch).doesNotContainKey("urgency");
    }

    @Test
    @DisplayName("still writes a non-nullable enum that IS submitted")
    void stillWritesANonNullableEnumThatIsSubmitted() {
        assertThat(FormPayload.buildUpdatePatch(body("privacy", "Private")))
            .containsEntry("privacy", "Private");
    }

    @Test
    @DisplayName("still clears a nullable field submitted blank")
    void stillClearsANullableFieldSubmittedBlank() {
        // The counterpart: the blank-means-clear behaviour must survive for nullable columns, or clearing
        // dateEnroled would stop un-enrolling a case.
        assertThat(FormPayload.buildUpdatePatch(body("urgencyreason", "")))
            .containsEntry("urgencyreason", null);
    }

    @Test
    @DisplayName("ignores read-only/derived fields (state, referencenumber)")
    void ignoresReadOnlyAndDerivedFields() {
        Map<String, Object> patch = FormPayload.buildUpdatePatch(body("state", "Enroled", "referencenumber", "9"));
        assertThat(patch).doesNotContainKey("state");
        assertThat(patch).doesNotContainKey("referencenumber");
    }
}
