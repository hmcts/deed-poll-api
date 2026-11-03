package uk.gov.hmcts.reform.deedpoll.domain.mi;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import uk.gov.hmcts.reform.deedpoll.config.AbstractPostgresContainerTest;
import uk.gov.hmcts.reform.deedpoll.domain.mi.ReferralOutcomesReport.Confirmations;
import uk.gov.hmcts.reform.deedpoll.domain.mi.ReferralOutcomesReport.DecisionCount;
import uk.gov.hmcts.reform.deedpoll.domain.mi.ReferralOutcomesReport.Decisions;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static uk.gov.hmcts.reform.deedpoll.domain.mi.MiFixtures.at;
import static uk.gov.hmcts.reform.deedpoll.entity.enums.ConfirmationOutcome.Confirmed;
import static uk.gov.hmcts.reform.deedpoll.entity.enums.ConfirmationOutcome.Withdrawn;
import static uk.gov.hmcts.reform.deedpoll.entity.enums.MasterDecision.Approved;
import static uk.gov.hmcts.reform.deedpoll.entity.enums.MasterDecision.Rejected;
import static uk.gov.hmcts.reform.deedpoll.entity.enums.Urgency.Normal;
import static uk.gov.hmcts.reform.deedpoll.entity.enums.Urgency.Urgent;

/** K3 against real Postgres, every figure worked out by hand from the referrals below. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional
class ManagementInformationReferralOutcomesTest extends AbstractPostgresContainerTest {

    @Autowired
    private ManagementInformationService service;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void seed() {
        MiFixtures fixtures = new MiFixtures(jdbc);
        String live = fixtures.newCase().insert();
        String migrated = fixtures.newCase().migrated().insert();
        fixtures.referral(live, Approved, at(2190, 1, 10), Normal, Confirmed, at(2190, 2, 2));
        fixtures.referral(live, Approved, at(2190, 1, 31, 23, 59), Urgent, Withdrawn, at(2190, 2, 28));
        fixtures.referral(live, Rejected, at(2190, 2, 1), Urgent, null, null);
        fixtures.referral(migrated, Rejected, at(2190, 2, 15), Normal, Confirmed, at(2190, 3, 1));
        // A decision date with no decision, and a confirmation date with no outcome.
        fixtures.referral(live, null, at(2190, 3, 3), Normal, null, null);
        fixtures.referral(live, Approved, at(2189, 12, 31), Normal, null, at(2190, 3, 4));
        // The last minute of the range is counted; the first minute after it is not.
        fixtures.referral(live, Approved, at(2190, 3, 31, 23, 59), Normal, Confirmed, at(2190, 3, 31, 23, 59));
        fixtures.referral(live, Approved, at(2190, 4, 1), Normal, Confirmed, at(2190, 4, 1));
        // Before the range, and undecided.
        fixtures.referral(live, Approved, at(2189, 12, 30), Urgent, null, null);
        fixtures.referral(live, null, null, Normal, null, null);
    }

    @Test
    void countsDecisionsWithTheirUrgentShareAndConfirmationsByOutcome() {
        ReferralOutcomesReport report = service.referralOutcomes(request(true));

        assertThat(report.periods()).extracting(ReferralOutcomesReport.Period::decisions).containsExactly(
            new Decisions(new DecisionCount(2, 1), new DecisionCount(0, 0)),
            new Decisions(new DecisionCount(0, 0), new DecisionCount(2, 1)),
            new Decisions(new DecisionCount(1, 0), new DecisionCount(0, 0)));
        assertThat(report.periods()).extracting(ReferralOutcomesReport.Period::confirmations).containsExactly(
            new Confirmations(0, 0), new Confirmations(1, 1), new Confirmations(2, 0));
        assertThat(report.excluded()).isEqualTo(new ReferralOutcomesReport.Excluded(0, 0, 1, 1));
    }

    @Test
    void leavesMigratedCasesReferralsOutAndCountsThem() {
        ReferralOutcomesReport report = service.referralOutcomes(request(false));

        assertThat(report.periods().get(1).decisions())
            .isEqualTo(new Decisions(new DecisionCount(0, 0), new DecisionCount(1, 1)));
        assertThat(report.periods().get(2).confirmations()).isEqualTo(new Confirmations(1, 0));
        assertThat(report.excluded()).isEqualTo(new ReferralOutcomesReport.Excluded(1, 1, 1, 1));
    }

    private static MiParameters request(boolean includeMigrated) {
        return MiParameters.of(LocalDate.of(2190, 1, 1), LocalDate.of(2190, 3, 31), MiGranularity.MONTH,
            includeMigrated);
    }
}
