package uk.gov.hmcts.reform.deedpoll.domain.deedpoll;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uk.gov.hmcts.reform.deedpoll.domain.FieldError;
import uk.gov.hmcts.reform.deedpoll.domain.deedpoll.CardLayout.CardColumn;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ports every test block in {@code reference/ts/deed-poll/form-validation.test.ts}.
 */
class FormValidationTest {

    private static final List<FieldDef> DATES = List.of(
        FieldDef.of("signatureRequestedDate", "Signature requested date", FieldKind.DATE, null),
        FieldDef.of("signatureReceivedDate", "Signature received date", FieldKind.DATE, null));

    private static Map<String, Object> parts(String name, String day, String month, String year) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put(name + "-day", day);
        body.put(name + "-month", month);
        body.put(name + "-year", year);
        return body;
    }

    private static Map<String, Object> merge(Map<String, Object> first, Map<String, Object> second) {
        Map<String, Object> body = new LinkedHashMap<>(first);
        body.putAll(second);
        return body;
    }

    @Test
    @DisplayName("accepts a complete real date")
    void acceptsACompleteRealDate() {
        assertThat(FormValidation.collectDateErrors(parts("signatureRequestedDate", "3", "8", "2026"), DATES))
            .isEmpty();
    }

    @Test
    @DisplayName("accepts all three parts blank as a deliberate clear")
    void acceptsAllThreePartsBlankAsADeliberateClear() {
        // The clear path must stay open: blanking a signature date is how a caseworker walks a case back,
        // and it is what un-derives the state.
        assertThat(FormValidation.collectDateErrors(parts("signatureRequestedDate", "", "", ""), DATES))
            .isEmpty();
    }

    @Test
    @DisplayName("rejects a partial date and names the missing parts")
    void rejectsAPartialDateAndNamesTheMissingParts() {
        // Regression: a day with no month or year was silently stored as null, so the page read back "not
        // entered" and the caseworker had no idea the save dropped it.
        assertThat(FormValidation.collectDateErrors(parts("signatureRequestedDate", "3", "", ""), DATES))
            .containsExactly(new FieldError(
                "signatureRequestedDate",
                "Signature requested date must include a month and year"));
    }

    @Test
    @DisplayName("names a missing day, which is the half of the message the month-and-year case never reaches")
    void namesAMissingDay() {
        // Every other partial-date case supplies the day, so `missing.add("day")` and the wordings that come
        // out of joining it were never produced -- a defect in the assembly would have shipped silently.
        assertThat(FormValidation.collectDateErrors(parts("signatureRequestedDate", "", "8", "2026"), DATES))
            .containsExactly(new FieldError(
                "signatureRequestedDate",
                "Signature requested date must include a day"));

        assertThat(FormValidation.collectDateErrors(parts("signatureRequestedDate", "", "8", ""), DATES))
            .containsExactly(new FieldError(
                "signatureRequestedDate",
                "Signature requested date must include a day and year"));

        assertThat(FormValidation.collectDateErrors(parts("signatureRequestedDate", "", "", "2026"), DATES))
            .containsExactly(new FieldError(
                "signatureRequestedDate",
                "Signature requested date must include a day and month"));
    }

    @Test
    @DisplayName("rejects a non-numeric day, and a non-numeric month, each on its own")
    void rejectsNonNumericDayAndMonth() {
        // The day and month digit checks are unreachable through the existing cases, which fail on the year
        // regex first -- so each is given a complete date that is wrong in only that one part.
        assertThat(FormValidation.collectDateErrors(parts("signatureRequestedDate", "x", "8", "2026"), DATES))
            .containsExactly(new FieldError(
                "signatureRequestedDate", "Signature requested date must be a real date"));

        assertThat(FormValidation.collectDateErrors(parts("signatureRequestedDate", "3", "x", "2026"), DATES))
            .containsExactly(new FieldError(
                "signatureRequestedDate", "Signature requested date must be a real date"));
    }

    @Test
    @DisplayName("rejects garbage parts")
    void rejectsGarbageParts() {
        assertThat(FormValidation.collectDateErrors(parts("signatureRequestedDate", "99", "99", "abcd"), DATES))
            .containsExactly(new FieldError(
                "signatureRequestedDate",
                "Signature requested date must be a real date"));
    }

    @Test
    @DisplayName("rejects a date that would roll over rather than storing the rolled value")
    void rejectsADateThatWouldRollOver() {
        // Composing 32/13/2026 gives 2027-02-01. Storing that turns a typo into plausible-looking data on
        // the wrong side of a year boundary.
        List<FieldError> errors =
            FormValidation.collectDateErrors(parts("signatureRequestedDate", "32", "13", "2026"), DATES);
        assertThat(errors).hasSize(1);
        assertThat(errors.get(0).message()).contains("must be a real date");
    }

    @Test
    @DisplayName("rejects 31 February")
    void rejectsThirtyFirstOfFebruary() {
        assertThat(FormValidation.collectDateErrors(parts("signatureRequestedDate", "31", "2", "2026"), DATES))
            .hasSize(1);
    }

    @Test
    @DisplayName("rejects a two-digit year")
    void rejectsATwoDigitYear() {
        // A two-digit year composes as 1926 in the source. Nobody typing "26" means 1926, and a case dated
        // 1926 sorts and filters as garbage rather than announcing itself.
        List<FieldError> errors =
            FormValidation.collectDateErrors(parts("signatureRequestedDate", "1", "1", "26"), DATES);
        assertThat(errors).hasSize(1);
        assertThat(errors.get(0).message()).contains("must be a real date");
    }

    @Test
    @DisplayName("reports each bad date field separately")
    void reportsEachBadDateFieldSeparately() {
        List<FieldError> errors = FormValidation.collectDateErrors(
            merge(parts("signatureRequestedDate", "1", "", ""), parts("signatureReceivedDate", "99", "99", "9999")),
            DATES);

        assertThat(errors).extracting(FieldError::field)
            .containsExactly("signatureRequestedDate", "signatureReceivedDate");
    }

    @Test
    @DisplayName("ignores non-date and read-only fields")
    void ignoresNonDateAndReadOnlyFields() {
        List<FieldDef> fields = List.of(
            FieldDef.of("returnReason", "Return reason", FieldKind.TEXTAREA, CardColumn.FULL),
            FieldDef.of("dateOfApplication", "Date of application", FieldKind.DATE, null).asReadOnly());
        Map<String, Object> body = merge(
            Map.of("returnReason", "x"),
            parts("dateOfApplication", "99", "99", "x"));

        assertThat(FormValidation.collectDateErrors(body, fields)).isEmpty();
    }
}
