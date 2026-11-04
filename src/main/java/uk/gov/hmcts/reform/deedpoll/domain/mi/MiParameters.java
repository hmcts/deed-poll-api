package uk.gov.hmcts.reform.deedpoll.domain.mi;

import uk.gov.hmcts.reform.deedpoll.domain.FieldError;
import uk.gov.hmcts.reform.deedpoll.domain.InvalidInputError;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * The resolved parameters every management-information operation takes and echoes: an inclusive date range of at
 * most {@link #MAX_SPAN_YEARS} years, a granularity, and whether migrated cases count (plan D2, default true).
 */
public record MiParameters(LocalDate from, LocalDate to, MiGranularity granularity, boolean includeMigrated) {

    /** The longest request, so a weekly request is at most about 105 periods. */
    public static final int MAX_SPAN_YEARS = 2;

    /** The query parameters, as the HTTP operations name them and as every refusal attributes them. */
    public static final String FROM = "from";
    public static final String TO = "to";
    public static final String GRANULARITY = "granularity";
    public static final String INCLUDE_MIGRATED = "includeMigrated";

    private static final int MIN_YEAR = 1;
    private static final int MAX_YEAR = 9999;

    private static final String NO_FROM = "Enter the first day of the range.";
    private static final String NO_TO = "Enter the last day of the range.";
    private static final String CHOOSE_GRANULARITY = "Choose WEEK, MONTH or QUARTER.";

    /**
     * Parses and validates a request's query parameters as the caller sent them: {@code from} and {@code to} as
     * {@code yyyy-MM-dd}, {@code granularity} as one of {@link MiGranularity}'s names, and {@code includeMigrated}
     * as {@code true} or {@code false} (absent means true). Text that does not parse is refused on its own field,
     * without echoing it, and only well-formed values go on to {@link #of}'s checks.
     *
     * @throws InvalidInputError naming every field at fault
     */
    public static MiParameters parse(String from, String to, String granularity, String includeMigrated) {
        List<FieldError> errors = new ArrayList<>();
        final LocalDate first = date(from, FROM, NO_FROM, "Enter the first day of the range as yyyy-MM-dd.", errors);
        final LocalDate last = date(to, TO, NO_TO, "Enter the last day of the range as yyyy-MM-dd.", errors);
        MiGranularity bucket = null;
        if (granularity == null || granularity.isBlank()) {
            errors.add(new FieldError(GRANULARITY, CHOOSE_GRANULARITY));
        } else {
            try {
                bucket = MiGranularity.valueOf(granularity);
            } catch (IllegalArgumentException unknown) {
                errors.add(new FieldError(GRANULARITY, CHOOSE_GRANULARITY));
            }
        }
        if (includeMigrated != null && !"true".equals(includeMigrated) && !"false".equals(includeMigrated)) {
            errors.add(new FieldError(INCLUDE_MIGRATED, "includeMigrated is true or false."));
        }
        if (!errors.isEmpty()) {
            throw new InvalidInputError(errors.get(0).message(), errors);
        }
        return of(first, last, bucket, !"false".equals(includeMigrated));
    }

    private static LocalDate date(String text, String field, String missing, String malformed,
                                  List<FieldError> errors) {
        if (text == null || text.isBlank()) {
            errors.add(new FieldError(field, missing));
            return null;
        }
        try {
            LocalDate date = LocalDate.parse(text);
            // ISO also parses signed years such as +999999999; near LocalDate.MAX the span check overflows, and past
            // Postgres's date range the query fails, so a 500 either way. Four-digit years only.
            if (date.getYear() >= MIN_YEAR && date.getYear() <= MAX_YEAR) {
                return date;
            }
        } catch (DateTimeParseException unparsable) {
            // refused below, without echoing the text
        }
        errors.add(new FieldError(field, malformed));
        return null;
    }

    /**
     * Applies the range checks to well-formed values: {@code to} not before {@code from}, and at most
     * {@link #MAX_SPAN_YEARS} years apart. Missing and unparsable values are {@link #parse}'s to refuse.
     *
     * @throws InvalidInputError on {@code to}
     */
    public static MiParameters of(LocalDate from, LocalDate to, MiGranularity granularity, boolean includeMigrated) {
        if (to.isBefore(from)) {
            throw refusedTo("The last day of the range cannot be before the first.");
        }
        if (to.isAfter(from.plusYears(MAX_SPAN_YEARS))) {
            throw refusedTo("The range cannot be longer than " + MAX_SPAN_YEARS + " years.");
        }
        return new MiParameters(from, to, granularity, includeMigrated);
    }

    private static InvalidInputError refusedTo(String message) {
        return new InvalidInputError(message, List.of(new FieldError(TO, message)));
    }

    /**
     * Every period of the range in order, the first and last clipped to it (see {@link MiPeriod}).
     */
    public List<MiPeriod> periods() {
        List<MiPeriod> periods = new ArrayList<>();
        for (LocalDate start = granularity.start(from); !start.isAfter(to); start = granularity.next(start)) {
            LocalDate end = granularity.next(start).minusDays(1);
            periods.add(new MiPeriod(start.isBefore(from) ? from : start, end.isAfter(to) ? to : end));
        }
        return List.copyOf(periods);
    }
}
