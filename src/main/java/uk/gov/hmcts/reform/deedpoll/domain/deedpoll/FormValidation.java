package uk.gov.hmcts.reform.deedpoll.domain.deedpoll;

import uk.gov.hmcts.reform.deedpoll.domain.FieldError;

import java.time.YearMonth;
import java.time.temporal.ChronoField;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Validation for a tab save, run BEFORE the patch reaches the service.
 *
 * <p>Ported from {@code reference/ts/deed-poll/form-validation.ts}. Split from
 * {@link FormPayload#buildUpdatePatch} on purpose: that function's job is to shape a payload, and it
 * signals "no usable value" as null, which is also how it signals a deliberate clear. Those two cases
 * must be told apart here -- a blank date clears the field, whereas {@code 99/99/abcd} is a mistake the
 * caseworker needs told about. Both were silently stored as null until 2026-08-10, so a typo in a year
 * read back as "the date was never entered".
 *
 * <p>The reference returns its own {@code FormError {field, message}}; this returns {@link FieldError},
 * for the reason given on {@link CaseState}.
 */
public final class FormValidation {

    /** One or two digits, as a day or month part must be. */
    private static final Pattern ONE_OR_TWO_DIGITS = Pattern.compile("^\\d{1,2}$");

    /** Exactly four digits, as a year part must be. */
    private static final Pattern FOUR_DIGITS = Pattern.compile("^\\d{4}$");

    /**
     * The first year {@code Date.UTC} does not remap into the twentieth century.
     */
    private static final int YEAR_LOWER_BOUND = 100;

    private FormValidation() {
    }

    /**
     * Errors for date fields whose parts are PARTIALLY or badly filled.
     *
     * <p>Three outcomes per date field: all three parts blank (a clear -- no error), all three parts a
     * real date (fine), anything else (an error). The middle case is what
     * {@link DateParts#composeDateParts} cannot distinguish from the first.
     *
     * <p>Rejects out-of-range parts rather than letting the composition roll them over:
     * {@code 32/13/2026} is a typo, and silently storing 2027-02-01 is worse than refusing it.
     * Years below 100 are refused for the same reason -- {@code Date.UTC(26, …)} means 1926, which
     * nobody typing "26" or "0026" intends.
     */
    public static List<FieldError> collectDateErrors(Map<String, Object> body, List<FieldDef> fields) {
        List<FieldError> errors = new ArrayList<>();
        for (FieldDef field : fields) {
            if (field.kind() != FieldKind.DATE || field.readOnly()) {
                continue;
            }
            String day = FormValues.datePart(body, field.name(), "day");
            String month = FormValues.datePart(body, field.name(), "month");
            String year = FormValues.datePart(body, field.name(), "year");

            // All blank: the caseworker is clearing the field, which is legitimate.
            if (day.isEmpty() && month.isEmpty() && year.isEmpty()) {
                continue;
            }

            List<String> missing = new ArrayList<>();
            if (day.isEmpty()) {
                missing.add("day");
            }
            if (month.isEmpty()) {
                missing.add("month");
            }
            if (year.isEmpty()) {
                missing.add("year");
            }
            if (!missing.isEmpty()) {
                errors.add(new FieldError(
                    field.name(),
                    "%s must include a %s".formatted(field.label(), String.join(" and ", missing))));
                continue;
            }
            if (!isRealDate(day, month, year)) {
                errors.add(new FieldError(field.name(), "%s must be a real date".formatted(field.label())));
            }
        }
        return List.copyOf(errors);
    }

    /**
     * True when the three parts name a date that actually exists.
     *
     * <p>Checks the composed date agrees with its parts, which catches rollover (31 February becoming
     * 3 March) as well as out-of-range months. The year is required to be four digits so a two-digit
     * entry is a correction rather than a silent trip to the 1900s.
     *
     * <p>A year below 100 is refused even when it is written with four digits. The reference does this
     * through its identity check rather than a bound: {@code Date.UTC} remaps years 0-99 to
     * {@code 1900 + year}, so {@code Date.UTC(26, …)} is 1926 and {@code getUTCFullYear() === 26} then
     * fails. The four-digit pattern alone catches "26" but not "0026", which is the likelier
     * fat-finger of "2026" -- and {@link DateParts#composeDateParts} does not reproduce the 1900 remap,
     * so without this bound "0026" would be accepted here and then stored as year 26.
     */
    private static boolean isRealDate(String day, String month, String year) {
        if (!ONE_OR_TWO_DIGITS.matcher(day).matches()) {
            return false;
        }
        if (!ONE_OR_TWO_DIGITS.matcher(month).matches()) {
            return false;
        }
        if (!FOUR_DIGITS.matcher(year).matches()) {
            return false;
        }
        int dayOfMonth = Integer.parseInt(day);
        int monthValue = Integer.parseInt(month);
        int yearValue = Integer.parseInt(year);
        if (yearValue < YEAR_LOWER_BOUND) {
            return false;
        }
        // The reference composes the date and compares the parts back; asking the month whether it has the day
        // says the same thing about the same inputs, and says it without a rollover to undo.
        return ChronoField.MONTH_OF_YEAR.range().isValidIntValue(monthValue)
            && YearMonth.of(yearValue, monthValue).isValidDay(dayOfMonth);
    }
}
