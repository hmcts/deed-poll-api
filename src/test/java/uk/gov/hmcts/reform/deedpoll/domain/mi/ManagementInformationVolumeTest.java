package uk.gov.hmcts.reform.deedpoll.domain.mi;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import uk.gov.hmcts.reform.deedpoll.config.AbstractPostgresContainerTest;
import uk.gov.hmcts.reform.deedpoll.entity.enums.DeedPollType;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static uk.gov.hmcts.reform.deedpoll.domain.mi.MiFixtures.at;
import static uk.gov.hmcts.reform.deedpoll.entity.enums.DeedPollType.Adult;
import static uk.gov.hmcts.reform.deedpoll.entity.enums.DeedPollType.AdultTransgender;
import static uk.gov.hmcts.reform.deedpoll.entity.enums.DeedPollType.DeedOfRelinquishment;
import static uk.gov.hmcts.reform.deedpoll.entity.enums.DeedPollType.Minor;
import static uk.gov.hmcts.reform.deedpoll.entity.enums.DeedPollType.MinorTransgender;
import static uk.gov.hmcts.reform.deedpoll.entity.enums.DeedPollType.Unknown;

/** K1 against real Postgres, every figure worked out by hand from the cases below. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional
class ManagementInformationVolumeTest extends AbstractPostgresContainerTest {

    private static final LocalDate DEC_1 = LocalDate.of(2189, 12, 1);
    private static final LocalDate APR_30 = LocalDate.of(2190, 4, 30);

    @Autowired
    private ManagementInformationService service;

    @Autowired
    private JdbcTemplate jdbc;

    private VolumeReport monthlyBaseline;
    private VolumeReport monthlyBaselineWithoutMigrated;
    private VolumeReport weeklyBaseline;

    @BeforeEach
    void seed() {
        monthlyBaseline = service.volume(monthly(true));
        monthlyBaselineWithoutMigrated = service.volume(monthly(false));
        weeklyBaseline = service.volume(weekly());

        MiFixtures fixtures = new MiFixtures(jdbc);
        // A: the last minute of 2189, enroled mid-January.
        fixtures.newCase().type(Adult).received(at(2189, 12, 31, 23, 59)).enroled(at(2190, 1, 15)).insert();
        // B: the first minute of 2190, still open.
        fixtures.newCase().type(AdultTransgender).received(at(2190, 1, 1)).insert();
        // S: the last minute of Sunday 3 January, enroled on the 5th.
        fixtures.newCase().type(Adult).received(at(2190, 1, 3, 23, 59)).enroled(at(2190, 1, 5)).insert();
        // M: the first minute of Monday 4 January, still open.
        fixtures.newCase().type(Adult).received(at(2190, 1, 4)).insert();
        // C: returned on 3 February.
        fixtures.newCase().type(Minor).received(at(2190, 1, 10, 15, 0)).returned(at(2190, 2, 3)).insert();
        // F: migrated, enroled on 5 March.
        fixtures.newCase().type(Unknown).migrated().received(at(2190, 1, 20)).enroled(at(2190, 3, 5)).insert();
        // G: no type at all, counted as Unknown; still open.
        fixtures.newCase().type(null).received(at(2190, 1, 25)).insert();
        // D: midday on the last day of Q1, still open.
        fixtures.newCase().type(MinorTransgender).received(at(2190, 3, 31, 12, 0)).insert();
        // E: the first day of Q2, still open.
        fixtures.newCase().type(DeedOfRelinquishment).received(at(2190, 4, 1)).insert();
        // H and I: no dateReceived, the second migrated.
        fixtures.newCase().insert();
        fixtures.newCase().migrated().insert();
    }

    @Test
    void countsEachMonthsReceiptsByTypeAndTheOpenCaseloadAtItsEnd() {
        VolumeReport report = service.volume(monthly(true));

        assertThat(report.periods()).extracting(VolumeReport.Period::period).containsExactly(
            new MiPeriod(DEC_1, LocalDate.of(2189, 12, 31)),
            new MiPeriod(LocalDate.of(2190, 1, 1), LocalDate.of(2190, 1, 31)),
            new MiPeriod(LocalDate.of(2190, 2, 1), LocalDate.of(2190, 2, 28)),
            new MiPeriod(LocalDate.of(2190, 3, 1), LocalDate.of(2190, 3, 31)),
            new MiPeriod(LocalDate.of(2190, 4, 1), APR_30));
        assertThat(report.periods()).extracting(VolumeReport.Period::received).containsExactly(1L, 6L, 0L, 1L, 1L);
        assertThat(report.periods().get(0).receivedByType()).isEqualTo(byType(1, 0, 0, 0, 0, 0));
        assertThat(report.periods().get(0).receivedByType().keySet()).containsExactly(DeedPollType.values());
        assertThat(report.periods().get(1).receivedByType()).isEqualTo(byType(2, 1, 1, 0, 0, 2));
        assertThat(report.periods().get(2).receivedByType()).isEqualTo(byType(0, 0, 0, 0, 0, 0));
        assertThat(report.periods().get(3).receivedByType()).isEqualTo(byType(0, 0, 0, 1, 0, 0));
        assertThat(report.periods().get(4).receivedByType()).isEqualTo(byType(0, 0, 0, 0, 1, 0));
        // Dec: A. Jan: B, C, F, G, M. Feb: B, F, G, M. Mar: B, G, D, M. Apr: B, G, D, E, M.
        assertThat(openAddedTo(monthlyBaseline, report)).containsExactly(1L, 5L, 4L, 4L, 5L);
        assertThat(report.excluded().noDateReceived() - monthlyBaseline.excluded().noDateReceived()).isEqualTo(2);
        assertThat(report.excluded().migrated()).isZero();
        assertThat(report.parameters()).isEqualTo(monthly(true));
    }

    @Test
    void leavesMigratedCasesOutAndCountsThem() {
        VolumeReport report = service.volume(monthly(false));

        assertThat(report.periods()).extracting(VolumeReport.Period::received).containsExactly(1L, 5L, 0L, 1L, 1L);
        assertThat(report.periods().get(1).receivedByType()).isEqualTo(byType(2, 1, 1, 0, 0, 1));
        // F, the migrated case, is open through January and February.
        assertThat(openAddedTo(monthlyBaselineWithoutMigrated, report)).containsExactly(1L, 4L, 3L, 4L, 5L);
        // H and I: I is migrated, but with no date it is in no range, so it is counted here and not as migrated.
        assertThat(report.excluded().noDateReceived() - monthlyBaselineWithoutMigrated.excluded().noDateReceived())
            .isEqualTo(2);
        assertThat(report.excluded().migrated()).isEqualTo(1);
    }

    @Test
    void weeksTurnBetweenSundayAndMondayAndTheFirstIsClippedToTheRange() {
        VolumeReport report = service.volume(weekly());

        assertThat(report.periods()).extracting(VolumeReport.Period::period).containsExactly(
            new MiPeriod(LocalDate.of(2190, 1, 1), LocalDate.of(2190, 1, 3)),
            new MiPeriod(LocalDate.of(2190, 1, 4), LocalDate.of(2190, 1, 10)),
            new MiPeriod(LocalDate.of(2190, 1, 11), LocalDate.of(2190, 1, 17)));
        // A, received on 31 December in the same ISO week, is before the range.
        assertThat(report.periods()).extracting(VolumeReport.Period::received).containsExactly(2L, 2L, 0L);
        // To 3 Jan: A, B, S. To 10 Jan: A, B, M, C. To 17 Jan: B, M, C.
        assertThat(openAddedTo(weeklyBaseline, report)).containsExactly(3L, 4L, 3L);
    }

    @Test
    void quartersTurnBetweenTheLastDayOfMarchAndTheFirstOfApril() {
        VolumeReport report = service.volume(MiParameters.of(DEC_1, APR_30, MiGranularity.QUARTER, true));

        assertThat(report.periods()).extracting(VolumeReport.Period::period).containsExactly(
            new MiPeriod(DEC_1, LocalDate.of(2189, 12, 31)),
            new MiPeriod(LocalDate.of(2190, 1, 1), LocalDate.of(2190, 3, 31)),
            new MiPeriod(LocalDate.of(2190, 4, 1), APR_30));
        assertThat(report.periods()).extracting(VolumeReport.Period::received).containsExactly(1L, 7L, 1L);
    }

    @Test
    void countsTheWholeOfTheLastDayAndTheCaseloadAsOfAMidPeriodTo() {
        MiParameters toMidFebruary =
            MiParameters.of(LocalDate.of(2190, 2, 1), LocalDate.of(2190, 2, 14), MiGranularity.MONTH, true);
        VolumeReport before = service.volume(toMidFebruary);

        MiFixtures fixtures = new MiFixtures(jdbc);
        // The last minute of the range: received, and open at its end.
        fixtures.newCase().received(at(2190, 2, 14, 23, 59)).insert();
        // The first minute after it: neither.
        fixtures.newCase().received(at(2190, 2, 15)).insert();
        // Enroled after the range but inside the month: open at the range's end, not the month's.
        fixtures.newCase().received(at(2190, 2, 2)).enroled(at(2190, 2, 20)).insert();
        // Enroled during the last day: no longer open at its end.
        fixtures.newCase().received(at(2190, 2, 2)).enroled(at(2190, 2, 14, 15, 0)).insert();
        VolumeReport after = service.volume(toMidFebruary);

        assertThat(after.periods()).extracting(VolumeReport.Period::period)
            .containsExactly(new MiPeriod(LocalDate.of(2190, 2, 1), LocalDate.of(2190, 2, 14)));
        assertThat(after.periods().get(0).received() - before.periods().get(0).received()).isEqualTo(3);
        assertThat(openAddedTo(before, after)).containsExactly(2L);
    }

    private static MiParameters monthly(boolean includeMigrated) {
        return MiParameters.of(DEC_1, APR_30, MiGranularity.MONTH, includeMigrated);
    }

    private static MiParameters weekly() {
        return MiParameters.of(LocalDate.of(2190, 1, 1), LocalDate.of(2190, 1, 17), MiGranularity.WEEK, true);
    }

    private static List<Long> openAddedTo(VolumeReport baseline, VolumeReport report) {
        return IntStream.range(0, report.periods().size())
            .mapToObj(i -> report.periods().get(i).openAtEnd() - baseline.periods().get(i).openAtEnd())
            .toList();
    }

    private static Map<DeedPollType, Long> byType(long adult, long adultTransgender, long minor,
                                                  long minorTransgender, long relinquishment, long unknown) {
        return Map.of(Adult, adult, AdultTransgender, adultTransgender, Minor, minor,
            MinorTransgender, minorTransgender, DeedOfRelinquishment, relinquishment, Unknown, unknown);
    }
}
