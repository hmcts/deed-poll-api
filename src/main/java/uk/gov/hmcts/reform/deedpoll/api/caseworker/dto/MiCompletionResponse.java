package uk.gov.hmcts.reform.deedpoll.api.caseworker.dto;

import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import uk.gov.hmcts.reform.deedpoll.domain.mi.CompletionReport;

import java.time.LocalDate;
import java.util.List;

/**
 * K4, time to completion by intake cohort: per period of dateReceived, how many cases completed and how long they
 * took, and how many are still open. Counts and durations only; no case is identified.
 */
public record MiCompletionResponse(
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    MiParametersResponse parameters,
    @ArraySchema(arraySchema = @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
        description = "Every cohort of the range in order, empty ones included."))
    List<Cohort> cohorts,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    Excluded excluded) {

    /**
     * One cohort, the first and last clipped to the range. received = completed + stillOpen + the cohort's
     * contradictions, and completed = enroled + returned.
     */
    @Schema(name = "MiCompletionCohort")
    public record Cohort(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "The cohort's first day, inclusive.")
        LocalDate start,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "The cohort's last day, inclusive.")
        LocalDate end,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
            description = "Cases whose dateReceived falls in the cohort.")
        long received,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
            description = "Of those, cases with dateEnroled or dateReturned (not both).")
        long completed,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        long enroled,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        long returned,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
            description = "Cases with neither completion date. Show it beside the percentiles: a recent cohort "
                + "looks fast because its slow cases are still open.")
        long stillOpen,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
            description = "From dateReceived to the completion date, over the completed cases whose completion is "
                + "not before their receipt.")
        MiDurationResponse daysToCompletion) {
    }

    /** What was left out, and why. */
    @Schema(name = "MiCompletionExcluded")
    public record Excluded(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
            description = "Cases with no dateReceived, which no cohort can hold, whatever their dates.")
        long noDateReceived,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
            description = "Cases received in the range with both dateEnroled and dateReturned, counted in none of "
                + "completed, enroled, returned or stillOpen.")
        long contradiction,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
            description = "Completed cases whose completion date is before their dateReceived: counted as completed, "
                + "but not in daysToCompletion.")
        long negativeInterval,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
            description = "Migrated cases received in the range, left out because includeMigrated is false; 0 "
                + "otherwise.")
        long migrated) {
    }

    public static MiCompletionResponse from(CompletionReport report) {
        CompletionReport.Excluded excluded = report.excluded();
        return new MiCompletionResponse(MiParametersResponse.from(report.parameters()),
            report.cohorts().stream().map(cohort -> new Cohort(cohort.cohort().start(), cohort.cohort().end(),
                cohort.received(), cohort.completed(), cohort.enroled(), cohort.returned(), cohort.stillOpen(),
                MiDurationResponse.from(cohort.daysToCompletion()))).toList(),
            new Excluded(excluded.noDateReceived(), excluded.contradiction(), excluded.negativeInterval(),
                         excluded.migrated()));
    }
}
