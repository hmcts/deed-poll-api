package uk.gov.hmcts.reform.deedpoll.domain.mi;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import uk.gov.hmcts.reform.deedpoll.config.AbstractPostgresContainerTest;
import uk.gov.hmcts.reform.deedpoll.entity.enums.MasterDecision;
import uk.gov.hmcts.reform.deedpoll.entity.enums.Urgency;

import java.time.LocalDate;
import java.util.EnumMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static uk.gov.hmcts.reform.deedpoll.domain.mi.MiFixtures.at;
import static uk.gov.hmcts.reform.deedpoll.domain.mi.StageInterval.READY_TO_REFERRED;
import static uk.gov.hmcts.reform.deedpoll.domain.mi.StageInterval.RECEIVED_TO_READY_FOR_MASTER;
import static uk.gov.hmcts.reform.deedpoll.domain.mi.StageInterval.REFERRED_TO_DECISION;
import static uk.gov.hmcts.reform.deedpoll.domain.mi.StageInterval.SIGNATURE_RECEIVED_TO_ENROLED;
import static uk.gov.hmcts.reform.deedpoll.domain.mi.StageInterval.SIGNATURE_REQUESTED_TO_RECEIVED;

/**
 * K2 against real Postgres, every figure worked out by hand from the cases below.
 *
 * <p>{@code percentile_cont(f)} over n sorted values interpolates at position f × (n − 1): over {1, 10} the median
 * is 5.5 and the 95th percentile 1 + 0.95 × 9 = 9.55.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional
class ManagementInformationStageDurationsTest extends AbstractPostgresContainerTest {

    private static final double EXACT = 1e-9;

    @Autowired
    private ManagementInformationService service;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void seed() {
        MiFixtures fixtures = new MiFixtures(jdbc);
        // 23:00 to 09:00 the next morning is one calendar day (plan D4).
        fixtures.newCase().received(at(2190, 1, 1, 23, 0)).ready(at(2190, 1, 2, 9, 0)).insert();
        // Received to ready 10 days (Jan); ready to referred 21 days (Feb); two decided referrals and an undecided
        // one: the latest decision, 11 Feb, is 10 days after referral, and the case's own decision date is ignored.
        String twoReferrals = fixtures.newCase().received(at(2190, 1, 1)).ready(at(2190, 1, 11))
            .referred(at(2190, 2, 1)).masterDateOfDecision(at(2190, 2, 20)).insert();
        fixtures.referral(twoReferrals, MasterDecision.Rejected, at(2190, 2, 5), Urgency.Normal, null, null);
        fixtures.referral(twoReferrals, MasterDecision.Approved, at(2190, 2, 11), Urgency.Normal, null, null);
        fixtures.referral(twoReferrals, null, null, Urgency.Normal, null, null);
        // No decided referral, so its own masterDateOfDecision: 3 days (Feb).
        fixtures.newCase().referred(at(2190, 2, 1)).masterDateOfDecision(at(2190, 2, 4)).insert();
        // Referred earlier the same day it was ready: 0 days, not negative (Jan).
        fixtures.newCase().ready(at(2190, 1, 20, 15, 0)).referred(at(2190, 1, 20, 9, 0)).insert();
        // Signature received before it was requested: negative, excluded; then 20 days to enrolment (Mar).
        fixtures.newCase().signatureRequested(at(2190, 3, 10)).signatureReceived(at(2190, 3, 5))
            .enroled(at(2190, 3, 25)).insert();
        // Migrated: 3 days to signature, then 6 to enrolment (Mar).
        fixtures.newCase().migrated().signatureRequested(at(2190, 3, 1)).signatureReceived(at(2190, 3, 4))
            .enroled(at(2190, 3, 10)).insert();
        // Migrated and negative: left out as migrated only, never also as negative.
        fixtures.newCase().migrated().signatureRequested(at(2190, 3, 20)).signatureReceived(at(2190, 3, 15)).insert();
        // Ends in the last minute of the range: 10 days (Mar). Ends in the first minute after it: not counted.
        fixtures.newCase().received(at(2190, 3, 21)).ready(at(2190, 3, 31, 23, 59)).insert();
        fixtures.newCase().received(at(2190, 3, 25)).ready(at(2190, 4, 1)).insert();
        // Ends before the range: not counted.
        fixtures.newCase().received(at(2189, 12, 1)).ready(at(2189, 12, 20)).insert();
        // One end only: not an interval, and not excluded either.
        fixtures.newCase().ready(at(2190, 1, 5)).insert();
    }

    @Test
    void bucketsEachIntervalByItsEndAndPublishesEveryBucketsPercentiles() {
        StageDurationsReport report = service.stageDurations(request(true));

        assertThat(report.periods()).extracting(period -> period.period().start())
            .containsExactly(LocalDate.of(2190, 1, 1), LocalDate.of(2190, 2, 1), LocalDate.of(2190, 3, 1));
        Map<StageInterval, DurationFigures> january = report.periods().get(0).intervals();
        assertThat(january.keySet()).containsExactly(StageInterval.values());
        assertFigures(january.get(RECEIVED_TO_READY_FOR_MASTER), 2, 5.5, 9.55);
        assertFigures(january.get(READY_TO_REFERRED), 1, 0, 0);
        assertThat(january.get(REFERRED_TO_DECISION)).isEqualTo(DurationFigures.NONE);

        Map<StageInterval, DurationFigures> february = report.periods().get(1).intervals();
        assertThat(february.get(RECEIVED_TO_READY_FOR_MASTER)).isEqualTo(DurationFigures.NONE);
        assertFigures(february.get(READY_TO_REFERRED), 1, 21, 21);
        // {3, 10}: 6.5, and 3 + 0.95 × 7.
        assertFigures(february.get(REFERRED_TO_DECISION), 2, 6.5, 9.65);

        Map<StageInterval, DurationFigures> march = report.periods().get(2).intervals();
        assertFigures(march.get(RECEIVED_TO_READY_FOR_MASTER), 1, 10, 10);
        assertFigures(march.get(SIGNATURE_REQUESTED_TO_RECEIVED), 1, 3, 3);
        // {6, 20}: 13, and 6 + 0.95 × 14.
        assertFigures(march.get(SIGNATURE_RECEIVED_TO_ENROLED), 2, 13, 19.3);

        assertThat(report.excluded().negativeInterval()).isEqualTo(counts(0, 0, 0, 2, 0));
        assertThat(report.excluded().migrated()).isEqualTo(counts(0, 0, 0, 0, 0));
    }

    @Test
    void leavesMigratedCasesIntervalsOutAndCountsThem() {
        StageDurationsReport report = service.stageDurations(request(false));

        Map<StageInterval, DurationFigures> march = report.periods().get(2).intervals();
        assertThat(march.get(SIGNATURE_REQUESTED_TO_RECEIVED)).isEqualTo(DurationFigures.NONE);
        assertFigures(march.get(SIGNATURE_RECEIVED_TO_ENROLED), 1, 20, 20);
        assertThat(report.excluded().migrated()).isEqualTo(counts(0, 0, 0, 2, 1));
        assertThat(report.excluded().negativeInterval()).isEqualTo(counts(0, 0, 0, 1, 0));
    }

    @Test
    void bucketsByWeekAndQuarterOnThePeriodEdges() {
        MiFixtures fixtures = new MiFixtures(jdbc);
        // Wednesday 1 July 2189 to Sunday 12 July: a clipped first week, then Monday 6 July's.
        fixtures.newCase().received(at(2189, 7, 1)).ready(at(2189, 7, 5, 23, 59)).insert();
        fixtures.newCase().received(at(2189, 7, 1)).ready(at(2189, 7, 6)).insert();
        // 15 March to 30 June: a clipped first quarter, then the second.
        fixtures.newCase().received(at(2189, 3, 15)).ready(at(2189, 3, 31, 23, 59)).insert();
        fixtures.newCase().received(at(2189, 3, 20)).ready(at(2189, 4, 1)).insert();

        StageDurationsReport weeks = service.stageDurations(MiParameters.of(LocalDate.of(2189, 7, 1),
            LocalDate.of(2189, 7, 12), MiGranularity.WEEK, true));
        assertThat(weeks.periods()).extracting(StageDurationsReport.Period::period).containsExactly(
            new MiPeriod(LocalDate.of(2189, 7, 1), LocalDate.of(2189, 7, 5)),
            new MiPeriod(LocalDate.of(2189, 7, 6), LocalDate.of(2189, 7, 12)));
        assertFigures(weeks.periods().get(0).intervals().get(RECEIVED_TO_READY_FOR_MASTER), 1, 4, 4);
        assertFigures(weeks.periods().get(1).intervals().get(RECEIVED_TO_READY_FOR_MASTER), 1, 5, 5);

        StageDurationsReport quarters = service.stageDurations(MiParameters.of(LocalDate.of(2189, 3, 15),
            LocalDate.of(2189, 6, 30), MiGranularity.QUARTER, true));
        assertThat(quarters.periods()).hasSize(2);
        assertFigures(quarters.periods().get(0).intervals().get(RECEIVED_TO_READY_FOR_MASTER), 1, 16, 16);
        assertFigures(quarters.periods().get(1).intervals().get(RECEIVED_TO_READY_FOR_MASTER), 1, 12, 12);
    }

    @Test
    void reReferralAwaitingItsDecisionHasNoIntervalRatherThanANegativeOne() {
        MiFixtures fixtures = new MiFixtures(jdbc);
        // Decided on 10 January, then referred again on 1 February and not yet decided. The old decision belongs to
        // the earlier referral; pairing it with the new dateReferred would be -22 days. Its own masterDateOfDecision
        // still says 10 January, and is not a fallback, because the case has a decided referral.
        String reReferred = fixtures.newCase().referred(at(2188, 2, 1)).masterDateOfDecision(at(2188, 1, 10))
            .insert();
        fixtures.referral(reReferred, MasterDecision.Approved, at(2188, 1, 10), Urgency.Normal, null, null);
        fixtures.referral(reReferred, null, null, Urgency.Normal, null, null);
        // Re-referred on 1 February and decided again on 9 February: 8 days, from the decision since the referral.
        String reDecided = fixtures.newCase().referred(at(2188, 2, 1)).insert();
        fixtures.referral(reDecided, MasterDecision.Rejected, at(2188, 1, 10), Urgency.Normal, null, null);
        fixtures.referral(reDecided, MasterDecision.Approved, at(2188, 2, 9, 16, 0), Urgency.Normal, null, null);
        // Decided the same day it was referred, earlier in the day: 0 days, not excluded (plan D4).
        String sameDay = fixtures.newCase().referred(at(2188, 2, 3, 15, 0)).insert();
        fixtures.referral(sameDay, MasterDecision.Approved, at(2188, 2, 3, 9, 0), Urgency.Normal, null, null);
        // No referral row, and a masterDateOfDecision before dateReferred: nothing explains it, so it is negative.
        fixtures.newCase().referred(at(2188, 2, 10)).masterDateOfDecision(at(2188, 2, 2)).insert();

        StageDurationsReport report = service.stageDurations(MiParameters.of(LocalDate.of(2188, 1, 1),
            LocalDate.of(2188, 2, 29), MiGranularity.MONTH, true));

        assertThat(report.periods().get(0).intervals().get(REFERRED_TO_DECISION)).isEqualTo(DurationFigures.NONE);
        // {0, 8}: 4, and 0 + 0.95 × 8.
        assertFigures(report.periods().get(1).intervals().get(REFERRED_TO_DECISION), 2, 4, 7.6);
        assertThat(report.excluded().negativeInterval()).isEqualTo(counts(0, 0, 1, 0, 0));
    }

    private static MiParameters request(boolean includeMigrated) {
        return MiParameters.of(LocalDate.of(2190, 1, 1), LocalDate.of(2190, 3, 31), MiGranularity.MONTH,
            includeMigrated);
    }

    private static void assertFigures(DurationFigures figures, long count, double median, double p95) {
        assertThat(figures.count()).isEqualTo(count);
        assertThat(figures.medianDays()).isCloseTo(median, within(EXACT));
        assertThat(figures.p95Days()).isCloseTo(p95, within(EXACT));
    }

    private static Map<StageInterval, Long> counts(long... values) {
        Map<StageInterval, Long> counts = new EnumMap<>(StageInterval.class);
        for (StageInterval interval : StageInterval.values()) {
            counts.put(interval, values[interval.ordinal()]);
        }
        return counts;
    }
}
