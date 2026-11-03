package uk.gov.hmcts.reform.deedpoll.domain.mi;

import uk.gov.hmcts.reform.deedpoll.domain.FieldError;
import uk.gov.hmcts.reform.deedpoll.domain.InvalidInputError;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * The resolved parameters every management-information operation takes and echoes: an inclusive date range of at
 * most {@link #MAX_SPAN_YEARS} years, a granularity, and whether migrated cases count (plan D2, default true).
 */
public record MiParameters(LocalDate from, LocalDate to, MiGranularity granularity, boolean includeMigrated) {

    /** The longest request, so a weekly request is at most about 105 periods. */
    public static final int MAX_SPAN_YEARS = 2;

    /**
     * Validates a request's raw values.
     *
     * @param includeMigrated null for the default, true
     * @throws InvalidInputError naming every field at fault
     */
    public static MiParameters of(LocalDate from, LocalDate to, MiGranularity granularity, Boolean includeMigrated) {
        List<FieldError> errors = new ArrayList<>();
        if (from == null) {
            errors.add(new FieldError("from", "Enter the first day of the range."));
        }
        if (to == null) {
            errors.add(new FieldError("to", "Enter the last day of the range."));
        }
        if (granularity == null) {
            errors.add(new FieldError("granularity", "Choose WEEK, MONTH or QUARTER."));
        }
        if (from != null && to != null) {
            if (to.isBefore(from)) {
                errors.add(new FieldError("to", "The last day of the range cannot be before the first."));
            } else if (to.isAfter(from.plusYears(MAX_SPAN_YEARS))) {
                errors.add(new FieldError("to", "The range cannot be longer than " + MAX_SPAN_YEARS + " years."));
            }
        }
        if (!errors.isEmpty()) {
            throw new InvalidInputError(errors.get(0).message(), errors);
        }
        return new MiParameters(from, to, granularity, includeMigrated == null || includeMigrated);
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
