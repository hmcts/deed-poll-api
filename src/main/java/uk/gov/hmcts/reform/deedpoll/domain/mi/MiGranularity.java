package uk.gov.hmcts.reform.deedpoll.domain.mi;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;

/**
 * How a management-information request buckets its dates (plan D7): ISO week (Monday start), calendar month or
 * calendar quarter.
 *
 * <p>{@link #field()} is the Postgres {@code date_trunc} field, bound as a parameter so no statement is assembled
 * at runtime, and {@link #start(LocalDate)} is the same truncation in Java. The two must agree, because the service
 * generates every period of the request in Java (so an empty one is still reported) and matches the database's
 * buckets to them by their start.
 */
public enum MiGranularity {
    WEEK("week"),
    MONTH("month"),
    QUARTER("quarter");

    private final String field;

    MiGranularity(String field) {
        this.field = field;
    }

    /**
     * The {@code date_trunc} field.
     */
    public String field() {
        return field;
    }

    /**
     * The first day of the calendar period {@code day} falls in.
     */
    public LocalDate start(LocalDate day) {
        return switch (this) {
            case WEEK -> day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            case MONTH -> day.withDayOfMonth(1);
            case QUARTER -> LocalDate.of(day.getYear(), (day.getMonthValue() - 1) / 3 * 3 + 1, 1);
        };
    }

    /**
     * The first day of the calendar period after the one starting on {@code start}.
     */
    public LocalDate next(LocalDate start) {
        return switch (this) {
            case WEEK -> start.plusWeeks(1);
            case MONTH -> start.plusMonths(1);
            case QUARTER -> start.plusMonths(3);
        };
    }
}
