package uk.gov.hmcts.reform.deedpoll.api.caseworker.dto;

import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import uk.gov.hmcts.reform.deedpoll.domain.mi.DurationFigures;
import uk.gov.hmcts.reform.deedpoll.domain.mi.StageDurationsReport;
import uk.gov.hmcts.reform.deedpoll.domain.mi.StageInterval;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * K2, stage durations: per milestone-to-milestone interval and period, how many there were and their median and
 * 95th percentile in calendar days. Counts and durations only; no case is identified.
 */
public record MiStageDurationsResponse(
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    MiParametersResponse parameters,
    @ArraySchema(arraySchema = @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
        description = "Every period of the range in order, empty ones included."))
    List<Period> periods,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    Excluded excluded) {

    /** One period, the first and last clipped to the range. */
    @Schema(name = "MiStageDurationsPeriod")
    public record Period(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "The period's first day, inclusive.")
        LocalDate start,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "The period's last day, inclusive.")
        LocalDate end,
        @ArraySchema(arraySchema = @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
            description = "Every interval in declaration order, by the period its end date falls in; an empty one "
                + "has count 0 and null percentiles."))
        List<IntervalFigures> intervals) {
    }

    /** One interval's figures in one period. */
    @Schema(name = "MiStageIntervalFigures")
    public record IntervalFigures(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        StageInterval interval,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        long count,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true,
            description = "The median in calendar days (percentile_cont), null when count is 0.")
        Double medianDays,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true,
            description = "The 95th percentile in calendar days (percentile_cont), null when count is 0.")
        Double p95Days) {
    }

    /** One interval's count of something left out. */
    @Schema(name = "MiStageIntervalCount")
    public record IntervalCount(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        StageInterval interval,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        long count) {
    }

    /** What was left out, and why, per interval ending in the range. */
    @Schema(name = "MiStageDurationsExcluded")
    public record Excluded(
        @ArraySchema(arraySchema = @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
            description = "Intervals whose end date is before their start date, a data-quality problem, never in the "
                + "figures; every interval, zeros included."))
        List<IntervalCount> negativeInterval,
        @ArraySchema(arraySchema = @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
            description = "Intervals of migrated cases, left out because includeMigrated is false; every interval, "
                + "zeros included."))
        List<IntervalCount> migrated) {
    }

    public static MiStageDurationsResponse from(StageDurationsReport report) {
        return new MiStageDurationsResponse(MiParametersResponse.from(report.parameters()),
            report.periods().stream().map(period -> new Period(period.period().start(), period.period().end(),
                period.intervals().entrySet().stream().map(entry -> figures(entry.getKey(), entry.getValue()))
                    .toList())).toList(),
            new Excluded(counts(report.excluded().negativeInterval()), counts(report.excluded().migrated())));
    }

    private static IntervalFigures figures(StageInterval interval, DurationFigures figures) {
        return new IntervalFigures(interval, figures.count(), figures.medianDays(), figures.p95Days());
    }

    private static List<IntervalCount> counts(Map<StageInterval, Long> counts) {
        return counts.entrySet().stream().map(entry -> new IntervalCount(entry.getKey(), entry.getValue())).toList();
    }
}
