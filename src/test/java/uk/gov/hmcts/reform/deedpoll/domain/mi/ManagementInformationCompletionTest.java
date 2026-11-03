package uk.gov.hmcts.reform.deedpoll.domain.mi;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import uk.gov.hmcts.reform.deedpoll.config.AbstractPostgresContainerTest;
import uk.gov.hmcts.reform.deedpoll.domain.mi.CompletionReport.Cohort;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static uk.gov.hmcts.reform.deedpoll.domain.mi.MiFixtures.at;

/** K4 against real Postgres, every figure worked out by hand from the cases below. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional
class ManagementInformationCompletionTest extends AbstractPostgresContainerTest {

    private static final double EXACT = 1e-9;

    @Autowired
    private ManagementInformationService service;

    @Autowired
    private JdbcTemplate jdbc;

    private CompletionReport baseline;

    @BeforeEach
    void seed() {
        baseline = service.completion(request(true));
        MiFixtures fixtures = new MiFixtures(jdbc);
        // January: 1 day (23:00 to 09:00), 10 days, one still open, one contradiction.
        fixtures.newCase().received(at(2190, 1, 5, 23, 0)).enroled(at(2190, 1, 6, 9, 0)).insert();
        fixtures.newCase().received(at(2190, 1, 10)).returned(at(2190, 1, 20)).insert();
        fixtures.newCase().received(at(2190, 1, 15)).insert();
        fixtures.newCase().received(at(2190, 1, 20)).enroled(at(2190, 2, 1)).returned(at(2190, 2, 2)).insert();
        // February: enroled before it was received (completed, but no duration), and a migrated case of 30 days.
        fixtures.newCase().received(at(2190, 2, 1)).enroled(at(2190, 1, 25)).insert();
        fixtures.newCase().migrated().received(at(2190, 2, 10)).enroled(at(2190, 3, 12)).insert();
        // March: returned after the range, 2 days later; it is the cohort that is in the range.
        fixtures.newCase().received(at(2190, 3, 31, 12, 0)).returned(at(2190, 4, 2)).insert();
        // March, migrated and negative: left out as migrated only, never also as negative.
        fixtures.newCase().migrated().received(at(2190, 3, 10)).enroled(at(2190, 3, 5)).insert();
        // The last minute of the range, still open; the first minute after it, not counted.
        fixtures.newCase().received(at(2190, 4, 30, 23, 59)).insert();
        fixtures.newCase().received(at(2190, 5, 1)).insert();
        // No dateReceived.
        fixtures.newCase().enroled(at(2190, 1, 9)).insert();
    }

    @Test
    void reportsEachCohortsOutcomesAndTimeToCompletion() {
        CompletionReport report = service.completion(request(true));

        assertThat(report.cohorts()).hasSize(4);
        assertCohort(report.cohorts().get(0), 4, 2, 1, 1, 1);
        // {1, 10}: 5.5, and 1 + 0.95 × 9.
        assertFigures(report.cohorts().get(0).daysToCompletion(), 2, 5.5, 9.55);
        assertCohort(report.cohorts().get(1), 2, 2, 2, 0, 0);
        assertFigures(report.cohorts().get(1).daysToCompletion(), 1, 30, 30);
        assertCohort(report.cohorts().get(2), 2, 2, 1, 1, 0);
        assertFigures(report.cohorts().get(2).daysToCompletion(), 1, 2, 2);
        // April: one received on its last day and still open, so nothing to compute.
        assertCohort(report.cohorts().get(3), 1, 0, 0, 0, 1);
        assertThat(report.cohorts().get(3).daysToCompletion()).isEqualTo(DurationFigures.NONE);
        assertThat(report.cohorts().get(3).cohort())
            .isEqualTo(new MiPeriod(LocalDate.of(2190, 4, 1), LocalDate.of(2190, 4, 30)));

        assertThat(report.excluded().noDateReceived() - baseline.excluded().noDateReceived()).isEqualTo(1);
        assertThat(report.excluded().contradiction()).isEqualTo(1);
        assertThat(report.excluded().negativeInterval()).isEqualTo(2);
        assertThat(report.excluded().migrated()).isZero();
    }

    @Test
    void leavesMigratedCasesOutAndCountsThem() {
        CompletionReport report = service.completion(request(false));

        assertCohort(report.cohorts().get(1), 1, 1, 1, 0, 0);
        assertThat(report.cohorts().get(1).daysToCompletion()).isEqualTo(DurationFigures.NONE);
        assertCohort(report.cohorts().get(2), 1, 1, 0, 1, 0);
        assertThat(report.excluded().negativeInterval()).isEqualTo(1);
        assertThat(report.excluded().migrated()).isEqualTo(2);
    }

    private static MiParameters request(boolean includeMigrated) {
        return MiParameters.of(LocalDate.of(2190, 1, 1), LocalDate.of(2190, 4, 30), MiGranularity.MONTH,
            includeMigrated);
    }

    private static void assertCohort(Cohort cohort, long received, long completed, long enroled, long returned,
                                     long stillOpen) {
        assertThat(new long[] {cohort.received(), cohort.completed(), cohort.enroled(), cohort.returned(),
            cohort.stillOpen()}).containsExactly(received, completed, enroled, returned, stillOpen);
    }

    private static void assertFigures(DurationFigures figures, long count, double median, double p95) {
        assertThat(figures.count()).isEqualTo(count);
        assertThat(figures.medianDays()).isCloseTo(median, within(EXACT));
        assertThat(figures.p95Days()).isCloseTo(p95, within(EXACT));
    }
}
