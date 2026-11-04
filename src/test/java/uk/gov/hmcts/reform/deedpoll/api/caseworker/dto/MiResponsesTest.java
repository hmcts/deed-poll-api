package uk.gov.hmcts.reform.deedpoll.api.caseworker.dto;

import org.junit.jupiter.api.Test;
import uk.gov.hmcts.reform.deedpoll.domain.mi.CompletionReport;
import uk.gov.hmcts.reform.deedpoll.domain.mi.DurationFigures;
import uk.gov.hmcts.reform.deedpoll.domain.mi.MiGranularity;
import uk.gov.hmcts.reform.deedpoll.domain.mi.MiParameters;
import uk.gov.hmcts.reform.deedpoll.domain.mi.MiPeriod;
import uk.gov.hmcts.reform.deedpoll.domain.mi.ReferralOutcomesReport;
import uk.gov.hmcts.reform.deedpoll.domain.mi.StageDurationsReport;
import uk.gov.hmcts.reform.deedpoll.domain.mi.StageInterval;
import uk.gov.hmcts.reform.deedpoll.domain.mi.VolumeReport;
import uk.gov.hmcts.reform.deedpoll.entity.enums.DeedPollType;

import java.time.LocalDate;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Each management-information response built from a report whose every figure is different, so a DTO that put one
 * same-typed field in another's place fails here rather than shipping. The figures themselves are the domain tests'.
 */
class MiResponsesTest {

    private static final MiParameters PARAMETERS =
        MiParameters.of(LocalDate.of(2191, 1, 1), LocalDate.of(2191, 1, 31), MiGranularity.MONTH, false);
    private static final MiPeriod PERIOD = new MiPeriod(LocalDate.of(2191, 1, 1), LocalDate.of(2191, 1, 31));

    @Test
    void echoesTheParameters() {
        assertThat(MiParametersResponse.from(PARAMETERS)).isEqualTo(new MiParametersResponse(
            LocalDate.of(2191, 1, 1), LocalDate.of(2191, 1, 31), MiGranularity.MONTH, false));
    }

    @Test
    void volumeKeepsEveryFigureInItsOwnField() {
        Map<DeedPollType, Long> byType = new EnumMap<>(DeedPollType.class);
        long count = 10;
        for (DeedPollType type : DeedPollType.values()) {
            byType.put(type, count++);
        }
        MiVolumeResponse response = MiVolumeResponse.from(new VolumeReport(PARAMETERS,
            List.of(new VolumeReport.Period(PERIOD, 2, byType, 3)), new VolumeReport.Excluded(4, 5)));

        MiVolumeResponse.Period period = response.periods().get(0);
        assertThat(period.start()).isEqualTo(PERIOD.start());
        assertThat(period.end()).isEqualTo(PERIOD.end());
        assertThat(period.received()).isEqualTo(2);
        assertThat(period.openAtEnd()).isEqualTo(3);
        assertThat(period.receivedByType()).extracting(MiVolumeResponse.TypeCount::type)
            .containsExactly(DeedPollType.values());
        assertThat(period.receivedByType()).allSatisfy(entry ->
            assertThat(entry.count()).isEqualTo(byType.get(entry.type())));
        assertThat(response.excluded()).isEqualTo(new MiVolumeResponse.Excluded(4, 5));
    }

    @Test
    void stageDurationsKeepEveryFigureInItsOwnField() {
        Map<StageInterval, DurationFigures> intervals = new EnumMap<>(StageInterval.class);
        Map<StageInterval, Long> negative = new EnumMap<>(StageInterval.class);
        Map<StageInterval, Long> migrated = new EnumMap<>(StageInterval.class);
        for (StageInterval interval : StageInterval.values()) {
            int n = interval.ordinal();
            intervals.put(interval, new DurationFigures(n + 1, n + 0.5, n + 0.75));
            negative.put(interval, 100L + n);
            migrated.put(interval, 200L + n);
        }
        MiStageDurationsResponse response = MiStageDurationsResponse.from(new StageDurationsReport(PARAMETERS,
            List.of(new StageDurationsReport.Period(PERIOD, intervals)),
            new StageDurationsReport.Excluded(negative, migrated)));

        assertThat(response.periods().get(0).intervals()).allSatisfy(figures -> {
            int n = figures.interval().ordinal();
            assertThat(figures).isEqualTo(new MiStageDurationsResponse.IntervalFigures(figures.interval(), n + 1,
                n + 0.5, n + 0.75));
        });
        assertThat(response.periods().get(0).intervals()).hasSize(StageInterval.values().length);
        assertThat(response.excluded().negativeInterval()).allSatisfy(entry ->
            assertThat(entry.count()).isEqualTo(100L + entry.interval().ordinal()));
        assertThat(response.excluded().migrated()).allSatisfy(entry ->
            assertThat(entry.count()).isEqualTo(200L + entry.interval().ordinal()));
    }

    @Test
    void referralOutcomesKeepEveryFigureInItsOwnField() {
        MiReferralOutcomesResponse response = MiReferralOutcomesResponse.from(new ReferralOutcomesReport(PARAMETERS,
            List.of(new ReferralOutcomesReport.Period(PERIOD,
                new ReferralOutcomesReport.Decisions(new ReferralOutcomesReport.DecisionCount(1, 2),
                    new ReferralOutcomesReport.DecisionCount(3, 4)),
                new ReferralOutcomesReport.Confirmations(5, 6))),
            new ReferralOutcomesReport.Excluded(7, 8, 9, 10)));

        assertThat(response.periods().get(0)).isEqualTo(new MiReferralOutcomesResponse.Period(PERIOD.start(),
            PERIOD.end(), new MiReferralOutcomesResponse.Decisions(new MiReferralOutcomesResponse.DecisionCount(1, 2),
                new MiReferralOutcomesResponse.DecisionCount(3, 4)),
            new MiReferralOutcomesResponse.Confirmations(5, 6)));
        assertThat(response.excluded()).isEqualTo(new MiReferralOutcomesResponse.Excluded(7, 8, 9, 10));
    }

    @Test
    void completionKeepsEveryFigureInItsOwnField() {
        MiCompletionResponse response = MiCompletionResponse.from(new CompletionReport(PARAMETERS,
            List.of(new CompletionReport.Cohort(PERIOD, 1, 2, 3, 4, 5, new DurationFigures(6, 7.5, 8.5))),
            new CompletionReport.Excluded(9, 10, 11, 12)));

        assertThat(response.cohorts().get(0)).isEqualTo(new MiCompletionResponse.Cohort(PERIOD.start(),
            PERIOD.end(), 1, 2, 3, 4, 5, new MiDurationResponse(6, 7.5, 8.5)));
        assertThat(response.excluded()).isEqualTo(new MiCompletionResponse.Excluded(9, 10, 11, 12));
    }
}
