package uk.gov.hmcts.reform.deedpoll.domain.mi;

import java.util.List;

/**
 * K4: time from receipt to completion (Enroled or Returned) by intake cohort, the period {@code dateReceived} falls
 * in.
 *
 * <p>Recent cohorts look fast because their slow cases are still open, which is what {@code stillOpen} shows.
 */
public record CompletionReport(MiParameters parameters, List<Cohort> cohorts, Excluded excluded) {

    /**
     * One cohort.
     *
     * <p>{@code received} = {@code completed} + {@code stillOpen} + the cohort's contradictions, and
     * {@code completed} = {@code enroled} + {@code returned}.
     *
     * @param daysToCompletion from {@code dateReceived} to the completion date, over the completed cases whose
     *                         completion is not before their receipt
     */
    public record Cohort(MiPeriod cohort, long received, long completed, long enroled, long returned, long stillOpen,
                         DurationFigures daysToCompletion) {
    }

    /**
     * What was left out, and why.
     *
     * @param noDateReceived   cases with no {@code dateReceived}, which no cohort can hold
     * @param contradiction    cases received in the range with both {@code dateEnroled} and {@code dateReturned}
     * @param negativeInterval completed cases whose completion date is before their {@code dateReceived}, counted as
     *                         completed but not in {@code daysToCompletion}
     * @param migrated         migrated cases received in the range, left out because {@code includeMigrated} is false
     */
    public record Excluded(long noDateReceived, long contradiction, long negativeInterval, long migrated) {
    }
}
