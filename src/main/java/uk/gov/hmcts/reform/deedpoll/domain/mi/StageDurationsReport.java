package uk.gov.hmcts.reform.deedpoll.domain.mi;

import java.util.List;
import java.util.Map;

/**
 * K2: how long each milestone-to-milestone interval took, by the period its end falls in.
 *
 * <p>An interval with either end missing is not an interval, and is neither counted nor excluded.
 */
public record StageDurationsReport(MiParameters parameters, List<Period> periods, Excluded excluded) {

    /**
     * One period's figures, for every {@link StageInterval} in declaration order.
     */
    public record Period(MiPeriod period, Map<StageInterval, DurationFigures> intervals) {
    }

    /**
     * What was left out, and why, per interval ending in the range.
     *
     * @param negativeInterval intervals whose end is before their start, a data-quality problem
     * @param migrated         intervals of migrated cases, left out because {@code includeMigrated} is false
     */
    public record Excluded(Map<StageInterval, Long> negativeInterval, Map<StageInterval, Long> migrated) {
    }
}
