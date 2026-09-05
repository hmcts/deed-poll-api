package uk.gov.hmcts.reform.deedpoll.domain.deedpoll;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Conversions between a stored date and the three parts a GOV.UK date input renders.
 *
 * <p>Ported from {@code reference/ts/deed-poll/date-parts.ts}. The forms submit {@code <name>-day},
 * {@code <name>-month} and {@code <name>-year} separately, so every page that shows or accepts a date
 * needs to move between that shape and a single value. Collected here rather than left as private
 * helpers on the page, which is where the prototype had them.
 *
 * <p>The record itself is the reference's {@code DateParts} interface: three strings, because that is
 * what a form submits.
 */
public record DateParts(String day, String month, String year) {

    /** All three parts blank -- an unset or unreadable stored value. */
    private static final DateParts EMPTY = new DateParts("", "", "");

    /**
     * A part that {@code Number()} would read as an integer rather than as NaN.
     */
    private static final Pattern INTEGER = Pattern.compile("^-?\\d+$");

    /** All three parts blank. */
    public static DateParts empty() {
        return EMPTY;
    }

    /**
     * GOV.UK date parts to an ISO string, or empty when incomplete or not a number.
     *
     * <p>UTC deliberately. These are calendar dates -- a date received, a date enroled -- with no time
     * component, so constructing them in local time would shift them a day either side of midnight
     * depending on where the server happens to be.
     *
     * <p>Out-of-range parts ROLL OVER rather than being rejected, because {@code Date.UTC} does:
     * 31 February composes as 3 March. That is documented rather than endorsed --
     * {@link FormValidation#collectDateErrors} is what refuses it before the form path reaches here,
     * which is why it is not a defect in this method.
     *
     * <p>The reference returns {@code string | null} and relies on the compiler to force the caller to
     * handle the null. Java has no such check, so this returns an {@link Optional}, as
     * {@code ReferenceNumbers.parse} does and for the same reason.
     */
    public static Optional<String> composeDateParts(String day, String month, String year) {
        if (isBlank(day) || isBlank(month) || isBlank(year)) {
            return Optional.empty();
        }
        if (!INTEGER.matcher(day).matches()
            || !INTEGER.matcher(month).matches()
            || !INTEGER.matcher(year).matches()) {
            return Optional.empty();
        }
        try {
            // Date.UTC(year, month - 1, day) semantics: start at 1 January of the year, then add the
            // months and days as offsets, so an out-of-range part rolls forward exactly as it does in
            // the reference instead of throwing.
            LocalDate composed = LocalDate.of(Integer.parseInt(year), 1, 1)
                .plusMonths(Integer.parseInt(month) - 1L)
                .plusDays(Integer.parseInt(day) - 1L);
            return Optional.of("%04d-%02d-%02dT00:00:00.000Z".formatted(
                composed.getYear(), composed.getMonthValue(), composed.getDayOfMonth()));
        } catch (ArithmeticException | java.time.DateTimeException outOfRange) {
            // A year beyond the supported range. `new Date(Date.UTC(...))` gives an Invalid Date for
            // the same inputs, which the reference reports as null.
            return Optional.empty();
        }
    }

    /**
     * A stored value to parts, for pre-filling an edit form.
     *
     * <p>An unset or unparseable value yields empty strings rather than throwing, because a
     * half-populated record is normal here: a case is logged incrementally and most of its dates are
     * null for most of its life.
     */
    public static DateParts splitDateParts(Object value) {
        LocalDateTime date = Dates.toDateTime(value);
        if (date == null) {
            return EMPTY;
        }
        return new DateParts(
            String.valueOf(date.getDayOfMonth()),
            String.valueOf(date.getMonthValue()),
            String.valueOf(date.getYear()));
    }

    /**
     * The parts exactly as submitted, for re-rendering a form that failed validation.
     *
     * <p>Deliberately not routed through {@link #composeDateParts} and back: the whole reason the form
     * is being re-rendered is that the parts did not compose, so the offending {@code 99} has to stay
     * on screen next to the error explaining it. Reading the stored value instead would erase what the
     * caseworker typed and leave them looking at an error against a field that now appears fine.
     *
     * <p>Nothing is trimmed and nothing is coerced: a part that is not text reads as blank, matching
     * the reference's {@code typeof value === "string"} guard.
     */
    public static DateParts submittedDateParts(String name, Map<String, Object> body) {
        return new DateParts(part(name, "day", body), part(name, "month", body), part(name, "year", body));
    }

    private static String part(String name, String suffix, Map<String, Object> body) {
        Object value = body.get(name + "-" + suffix);
        return value instanceof String text ? text : "";
    }

    private static boolean isBlank(String part) {
        return part == null || part.isEmpty();
    }
}
