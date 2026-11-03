package uk.gov.hmcts.reform.deedpoll.domain.mi;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import uk.gov.hmcts.reform.deedpoll.config.AbstractPostgresContainerTest;

import java.time.Duration;
import java.time.LocalDate;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Plan 20261103-mi-dashboard, Task 4: each of the four KPIs answers a two-year monthly request in under 2 s over
 * 10,000 cases, ten times the expected prod volume, on the Testcontainers Postgres and with no index of its own (D9).
 *
 * <p>The cases are written by one {@code generate_series} statement each, spread over three years (2180 to 2182,
 * where no other test dates anything) with every milestone, null dates, migrated cases, contradictions and a second
 * referral on some cases, so every branch of every query has rows to read. The class is {@code @Transactional}, so
 * the seed is rolled back; {@code ANALYZE} runs inside the transaction, as prod's statistics would already exist.
 * Each operation is run once to warm the connection and plan caches and then timed as the slowest of three runs.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional
class ManagementInformationPerformanceTest extends AbstractPostgresContainerTest {

    private static final Logger LOG = LoggerFactory.getLogger(ManagementInformationPerformanceTest.class);

    private static final int CASES = 10_000;

    private static final Duration LIMIT = Duration.ofSeconds(2);

    private static final int TIMED_RUNS = 3;

    private static final MiParameters TWO_YEARS_MONTHLY =
        MiParameters.of(LocalDate.of(2181, 1, 1), LocalDate.of(2182, 12, 31), MiGranularity.MONTH, true);

    @Autowired
    private ManagementInformationService service;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void seed() {
        jdbc.update("""
            INSERT INTO deedpoll_deedpoll (id, "type", "migratedRecord", "dateReceived", "dateReadyForMaster",
                "dateReferred", "masterDateOfDecision", "signatureRequestedDate", "signaturerecieveddate",
                "dateEnroled", "dateReturned", "modifiedOn")
            SELECT 'mi-perf-' || i,
                   CAST((ARRAY['Adult', 'AdultTransgender', 'Minor', 'MinorTransgender', 'DeedOfRelinquishment',
                       'Unknown'])[1 + i % 6] AS "DeedPollType"),
                   i % 10 = 0,
                   received,
                   CASE WHEN i % 10 < 9 THEN received + make_interval(days => i % 20) END,
                   CASE WHEN i % 10 < 7 THEN received + make_interval(days => i % 20 + i % 7) END,
                   CASE WHEN i % 100 = 1 THEN received + make_interval(days => 40) END,
                   CASE WHEN i % 10 < 5 THEN received + make_interval(days => 40) END,
                   CASE WHEN i % 10 < 5 THEN received + make_interval(days => 40 + i % 15) END,
                   CASE WHEN i % 10 < 4 OR i % 250 = 9 THEN received + make_interval(days => 60 + i % 15) END,
                   CASE WHEN i % 10 = 8 OR i % 250 = 9 THEN received + make_interval(days => 30 + i % 9) END,
                   now()
              FROM (SELECT i,
                           CASE WHEN i % 500 <> 0 THEN TIMESTAMP '2180-01-01'
                               + make_interval(days => i % 1095, hours => i % 24) END AS received
                      FROM generate_series(1, ?) AS g(i)) AS c
            """, CASES);
        jdbc.update("""
            INSERT INTO deedpoll_referral (id, "deedPollId", "state", "urgency", "decision", "decisionDate",
                "confirmationOutcome", "confirmationReceivedAt", "modifiedOn")
            SELECT gen_random_uuid(), d.id,
                   CAST(CASE WHEN n % 3 = 0 THEN 'Open' ELSE 'Closed' END AS "ReferralState"),
                   CAST(CASE WHEN n % 4 < 2 THEN 'Urgent' ELSE 'Normal' END AS "Urgency"),
                   CAST(CASE WHEN n % 3 = 0 THEN NULL WHEN n % 2 = 0 THEN 'Approved' ELSE 'Rejected' END
                       AS "MasterDecision"),
                   CASE WHEN n % 3 <> 0 THEN d."dateReferred" + make_interval(days => n % 12) END,
                   CAST(CASE WHEN n % 3 = 1 THEN 'Confirmed' WHEN n % 3 = 2 AND n % 5 = 0 THEN 'Withdrawn' END
                       AS "ConfirmationOutcome"),
                   CASE WHEN n % 3 = 1 OR (n % 3 = 2 AND n % 5 = 0)
                       THEN d."dateReferred" + make_interval(days => n % 12 + 5) END,
                   now()
              FROM (SELECT p.id, p."dateReferred", CAST(substr(p.id, 9) AS integer) + copy AS n
                      FROM deedpoll_deedpoll p
                      CROSS JOIN generate_series(0, 1) AS s(copy)
                     WHERE p.id LIKE 'mi-perf-%' AND p."dateReferred" IS NOT NULL
                       AND (copy = 0 OR CAST(substr(p.id, 9) AS integer) % 50 = 0)) AS d
            """);
        jdbc.execute("ANALYZE deedpoll_deedpoll");
        jdbc.execute("ANALYZE deedpoll_referral");
    }

    @Test
    @DisplayName("each KPI answers two years by month over 10,000 cases in under 2 s")
    void everyKpiAnswersWithinTheLimit() {
        assertThat(jdbc.queryForObject(
            "SELECT count(*) FROM deedpoll_deedpoll WHERE id LIKE 'mi-perf-%'", Long.class)).isEqualTo(CASES);

        Duration volume = slowest("volume", service::volume);
        Duration stageDurations = slowest("stage-durations", service::stageDurations);
        Duration referralOutcomes = slowest("referral-outcomes", service::referralOutcomes);
        Duration completion = slowest("completion", service::completion);

        assertThat(volume).isLessThan(LIMIT);
        assertThat(stageDurations).isLessThan(LIMIT);
        assertThat(referralOutcomes).isLessThan(LIMIT);
        assertThat(completion).isLessThan(LIMIT);
    }

    @Test
    @DisplayName("the seed reaches every figure, so the timings are of real work")
    void theSeedReachesEveryFigure() {
        VolumeReport volume = service.volume(TWO_YEARS_MONTHLY);
        assertThat(volume.periods()).hasSize(24);
        assertThat(volume.periods()).allSatisfy(period -> assertThat(period.received()).isPositive());

        StageDurationsReport stages = service.stageDurations(TWO_YEARS_MONTHLY);
        assertThat(stages.periods().get(12).intervals().values())
            .allSatisfy(figures -> assertThat(figures.count()).isPositive());

        ReferralOutcomesReport outcomes = service.referralOutcomes(TWO_YEARS_MONTHLY);
        assertThat(outcomes.periods()).hasSize(24);
        assertThat(outcomes.periods().get(12).decisions().approved().count()).isPositive();
        assertThat(outcomes.periods().get(12).decisions().rejected().urgent()).isPositive();
        assertThat(outcomes.periods().get(12).confirmations().confirmed()).isPositive();

        CompletionReport completion = service.completion(TWO_YEARS_MONTHLY);
        assertThat(completion.cohorts().get(0).daysToCompletion().count()).isPositive();
        assertThat(completion.excluded().contradiction()).isPositive();
    }

    private <T> Duration slowest(String operation, Function<MiParameters, T> call) {
        call.apply(TWO_YEARS_MONTHLY);
        Duration slowest = Duration.ZERO;
        for (int run = 0; run < TIMED_RUNS; run++) {
            long start = System.nanoTime();
            call.apply(TWO_YEARS_MONTHLY);
            Duration took = Duration.ofNanos(System.nanoTime() - start);
            if (took.compareTo(slowest) > 0) {
                slowest = took;
            }
        }
        LOG.info("MI performance: {} took at most {} ms over {} runs", operation, slowest.toMillis(), TIMED_RUNS);
        return slowest;
    }
}
