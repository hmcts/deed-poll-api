package uk.gov.hmcts.reform.deedpoll.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import uk.gov.hmcts.reform.deedpoll.domain.mi.DurationFigures;
import uk.gov.hmcts.reform.deedpoll.domain.mi.MiParameters;
import uk.gov.hmcts.reform.deedpoll.domain.mi.StageInterval;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.Collectors;

/**
 * The aggregate reads behind management information (plan 20261103-mi-dashboard). Every statement is a compile-time
 * constant; the request's values, the {@code date_trunc} field included, are bound once in a one-row {@code p} CTE.
 *
 * <p>Every date is cast to {@code date} before it is subtracted (plan D4), because some stored values carry a time
 * of day, and bucketed by {@code date_trunc} on the stored value, which ignores the time of day. A range is compared
 * as {@code value >= from AND value < to + 1} on the uncast column, which is the same set of days.
 *
 * <p>The rows hold counts and figures only: no case id, reference or text ever leaves here (plan D5).
 */
@Repository
public class ManagementInformationRepository {

    private static final String PARAMETERS = """
        WITH p AS (SELECT CAST(? AS text) AS field, CAST(? AS date) AS from_day, CAST(? AS date) + 1 AS end_day,
                          CAST(? AS boolean) AS include_migrated)
        """;

    private static final String RECEIVED = PARAMETERS + """
        SELECT date_trunc(p.field, d."dateReceived")::date AS period_start,
               coalesce(d."type"::text, 'Unknown') AS type,
               count(*) AS received
          FROM deedpoll_deedpoll d CROSS JOIN p
         WHERE d."dateReceived" >= p.from_day AND d."dateReceived" < p.end_day
           AND (p.include_migrated OR NOT d."migratedRecord")
         GROUP BY 1, 2
        """;

    // The ends are bound as one array literal, '{2026-01-31,2026-02-28}', because they are dates the service made.
    private static final String OPEN_AT_END = """
        WITH p AS (SELECT CAST(? AS boolean) AS include_migrated)
        SELECT e.period_end, count(d.id) AS open_at_end
          FROM unnest(CAST(? AS date[])) AS e(period_end)
         CROSS JOIN p
          LEFT JOIN deedpoll_deedpoll d
            ON d."dateReceived" < e.period_end + 1
           AND (d."dateEnroled" IS NULL OR d."dateEnroled" >= e.period_end + 1)
           AND (d."dateReturned" IS NULL OR d."dateReturned" >= e.period_end + 1)
           AND (p.include_migrated OR NOT d."migratedRecord")
         GROUP BY e.period_end
        """;

    // The migrated cases includeMigrated=false takes out of openAtEnd but CASES_EXCLUDED does not count, because they
    // were received before the range: those still open at the first period's end. A case open at a later end is open
    // at the first one too, since a closing date is the same at every end, so the first end alone decides.
    private static final String MIGRATED_CARRIED_IN = """
        SELECT count(*) FROM deedpoll_deedpoll d
         WHERE d."migratedRecord" AND d."dateReceived" < CAST(? AS date)
           AND (d."dateEnroled" IS NULL OR d."dateEnroled" >= CAST(? AS date) + 1)
           AND (d."dateReturned" IS NULL OR d."dateReturned" >= CAST(? AS date) + 1)
        """;

    // A case with no dateReceived is counted here whether or not it is migrated: migrated counts only cases inside
    // the range, and a case with no date is inside none, so filtering it here would leave it counted nowhere.
    private static final String CASES_EXCLUDED = PARAMETERS + """
        SELECT count(*) FILTER (WHERE d."dateReceived" IS NULL) AS no_date_received,
               count(*) FILTER (WHERE NOT p.include_migrated AND d."migratedRecord"
                                  AND d."dateReceived" >= p.from_day AND d."dateReceived" < p.end_day) AS migrated
          FROM deedpoll_deedpoll d CROSS JOIN p
        """;

    // A case's decision is its latest decided referral's, else its own masterDateOfDecision (plan K2).
    private static final String STAGE_DURATIONS = PARAMETERS + """
        , decided AS (
            SELECT DISTINCT ON (r."deedPollId") r."deedPollId", r."decisionDate"
              FROM deedpoll_referral r
             WHERE r."decisionDate" IS NOT NULL
             ORDER BY r."deedPollId", r."decisionDate" DESC
        ), spans AS (
            SELECT v.name, date_trunc(p.field, v.end_at)::date AS period_start,
                   (p.include_migrated OR NOT d."migratedRecord") AS included,
                   v.end_at::date - v.start_at::date AS days
              FROM deedpoll_deedpoll d
             CROSS JOIN p
              LEFT JOIN decided x ON x."deedPollId" = d.id
             CROSS JOIN LATERAL (VALUES
                 ('RECEIVED_TO_READY_FOR_MASTER', d."dateReceived", d."dateReadyForMaster"),
                 ('READY_TO_REFERRED', d."dateReadyForMaster", d."dateReferred"),
                 ('REFERRED_TO_DECISION', d."dateReferred", coalesce(x."decisionDate", d."masterDateOfDecision")),
                 ('SIGNATURE_REQUESTED_TO_RECEIVED', d."signatureRequestedDate", d."signaturerecieveddate"),
                 ('SIGNATURE_RECEIVED_TO_ENROLED', d."signaturerecieveddate", d."dateEnroled")
             ) AS v(name, start_at, end_at)
             WHERE v.start_at IS NOT NULL
               AND v.end_at >= p.from_day AND v.end_at < p.end_day
        )
        SELECT name, period_start,
               count(*) FILTER (WHERE included AND days >= 0) AS counted,
               percentile_cont(0.5) WITHIN GROUP (ORDER BY days) FILTER (WHERE included AND days >= 0) AS median_days,
               percentile_cont(0.95) WITHIN GROUP (ORDER BY days) FILTER (WHERE included AND days >= 0) AS p95_days,
               count(*) FILTER (WHERE included AND days < 0) AS negative,
               count(*) FILTER (WHERE NOT included) AS migrated
          FROM spans
         GROUP BY name, period_start
        """;

    private static final String DECISIONS = PARAMETERS + """
        SELECT date_trunc(p.field, r."decisionDate")::date AS period_start,
               count(*) FILTER (WHERE x.included AND r."decision" = 'Approved') AS approved,
               count(*) FILTER (WHERE x.included AND r."decision" = 'Approved' AND r."urgency" = 'Urgent')
                   AS approved_urgent,
               count(*) FILTER (WHERE x.included AND r."decision" = 'Rejected') AS rejected,
               count(*) FILTER (WHERE x.included AND r."decision" = 'Rejected' AND r."urgency" = 'Urgent')
                   AS rejected_urgent,
               count(*) FILTER (WHERE x.included AND r."decision" IS NULL) AS no_outcome,
               count(*) FILTER (WHERE NOT x.included) AS migrated
          FROM deedpoll_referral r
          JOIN deedpoll_deedpoll d ON d.id = r."deedPollId"
         CROSS JOIN p
         CROSS JOIN LATERAL (SELECT p.include_migrated OR NOT d."migratedRecord" AS included) x
         WHERE r."decisionDate" >= p.from_day AND r."decisionDate" < p.end_day
         GROUP BY 1
        """;

    private static final String CONFIRMATIONS = PARAMETERS + """
        SELECT date_trunc(p.field, r."confirmationReceivedAt")::date AS period_start,
               count(*) FILTER (WHERE x.included AND r."confirmationOutcome" = 'Confirmed') AS confirmed,
               count(*) FILTER (WHERE x.included AND r."confirmationOutcome" = 'Withdrawn') AS withdrawn,
               count(*) FILTER (WHERE x.included AND r."confirmationOutcome" IS NULL) AS no_outcome,
               count(*) FILTER (WHERE NOT x.included) AS migrated
          FROM deedpoll_referral r
          JOIN deedpoll_deedpoll d ON d.id = r."deedPollId"
         CROSS JOIN p
         CROSS JOIN LATERAL (SELECT p.include_migrated OR NOT d."migratedRecord" AS included) x
         WHERE r."confirmationReceivedAt" >= p.from_day AND r."confirmationReceivedAt" < p.end_day
         GROUP BY 1
        """;

    // least() ignores a null, so days is the one completion date's, or the earlier of a contradiction's two.
    private static final String COMPLETION = PARAMETERS + """
        , received AS (
            SELECT date_trunc(p.field, d."dateReceived")::date AS period_start,
                   (p.include_migrated OR NOT d."migratedRecord") AS included,
                   d."dateEnroled" IS NOT NULL AS enroled,
                   d."dateReturned" IS NOT NULL AS returned,
                   least(d."dateEnroled"::date, d."dateReturned"::date) - d."dateReceived"::date AS days
              FROM deedpoll_deedpoll d CROSS JOIN p
             WHERE d."dateReceived" >= p.from_day AND d."dateReceived" < p.end_day
        )
        SELECT period_start,
               count(*) FILTER (WHERE included) AS received,
               count(*) FILTER (WHERE included AND enroled AND NOT returned) AS enroled,
               count(*) FILTER (WHERE included AND returned AND NOT enroled) AS returned,
               count(*) FILTER (WHERE included AND NOT enroled AND NOT returned) AS still_open,
               count(*) FILTER (WHERE included AND enroled AND returned) AS contradiction,
               count(*) FILTER (WHERE included AND enroled <> returned AND days < 0) AS negative,
               count(*) FILTER (WHERE included AND enroled <> returned AND days >= 0) AS counted,
               percentile_cont(0.5) WITHIN GROUP (ORDER BY days)
                   FILTER (WHERE included AND enroled <> returned AND days >= 0) AS median_days,
               percentile_cont(0.95) WITHIN GROUP (ORDER BY days)
                   FILTER (WHERE included AND enroled <> returned AND days >= 0) AS p95_days
          FROM received
         GROUP BY period_start
        """;

    private final JdbcTemplate jdbc;

    public ManagementInformationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * One period's and one type's receipts. {@code type} is a {@code DeedPollType} name.
     */
    public record ReceivedRow(LocalDate periodStart, String type, long received) {
    }

    /** The open caseload at the end of one period. */
    public record OpenRow(LocalDate periodEnd, long openAtEnd) {
    }

    /** The cases the range leaves out. */
    public record CasesExcludedRow(long noDateReceived, long migrated) {
    }

    /** One interval's figures in one period. */
    public record StageRow(StageInterval interval, LocalDate periodStart, DurationFigures figures, long negative,
                           long migrated) {
    }

    /** One period's decisions. */
    public record DecisionRow(LocalDate periodStart, long approved, long approvedUrgent, long rejected,
                              long rejectedUrgent, long noOutcome, long migrated) {
    }

    /** One period's confirmations. */
    public record ConfirmationRow(LocalDate periodStart, long confirmed, long withdrawn, long noOutcome,
                                  long migrated) {
    }

    /** One cohort's completion. */
    public record CompletionRow(LocalDate periodStart, long received, long enroled, long returned, long stillOpen,
                                long contradiction, long negative, DurationFigures figures) {
    }

    public List<ReceivedRow> received(MiParameters request) {
        return jdbc.query(RECEIVED, (row, index) -> new ReceivedRow(row.getObject("period_start", LocalDate.class),
            row.getString("type"), row.getLong("received")), parameters(request));
    }

    public List<OpenRow> openAtEnd(boolean includeMigrated, List<LocalDate> periodEnds) {
        String ends = periodEnds.stream().map(LocalDate::toString).collect(Collectors.joining(",", "{", "}"));
        return jdbc.query(OPEN_AT_END, (row, index) -> new OpenRow(row.getObject("period_end", LocalDate.class),
            row.getLong("open_at_end")), includeMigrated, ends);
    }

    public CasesExcludedRow casesExcluded(MiParameters request) {
        return jdbc.queryForObject(CASES_EXCLUDED, (row, index) -> new CasesExcludedRow(
            row.getLong("no_date_received"), row.getLong("migrated")), parameters(request));
    }

    /**
     * Migrated cases received before {@code from} and still open at {@code firstPeriodEnd}: the open caseload that
     * {@code includeMigrated=false} leaves out of K1 beyond what {@link #casesExcluded} counts.
     */
    public long migratedCarriedIn(LocalDate from, LocalDate firstPeriodEnd) {
        Long count = jdbc.queryForObject(MIGRATED_CARRIED_IN, Long.class, from, firstPeriodEnd, firstPeriodEnd);
        return count == null ? 0 : count;
    }

    public List<StageRow> stageDurations(MiParameters request) {
        return jdbc.query(STAGE_DURATIONS, (row, index) -> new StageRow(StageInterval.valueOf(row.getString("name")),
            row.getObject("period_start", LocalDate.class), figures(row), row.getLong("negative"),
            row.getLong("migrated")), parameters(request));
    }

    public List<DecisionRow> decisions(MiParameters request) {
        return jdbc.query(DECISIONS, (row, index) -> new DecisionRow(row.getObject("period_start", LocalDate.class),
            row.getLong("approved"), row.getLong("approved_urgent"), row.getLong("rejected"),
            row.getLong("rejected_urgent"), row.getLong("no_outcome"), row.getLong("migrated")), parameters(request));
    }

    public List<ConfirmationRow> confirmations(MiParameters request) {
        return jdbc.query(CONFIRMATIONS, (row, index) -> new ConfirmationRow(
            row.getObject("period_start", LocalDate.class), row.getLong("confirmed"), row.getLong("withdrawn"),
            row.getLong("no_outcome"), row.getLong("migrated")), parameters(request));
    }

    public List<CompletionRow> completion(MiParameters request) {
        return jdbc.query(COMPLETION, (row, index) -> new CompletionRow(row.getObject("period_start", LocalDate.class),
            row.getLong("received"), row.getLong("enroled"), row.getLong("returned"), row.getLong("still_open"),
            row.getLong("contradiction"), row.getLong("negative"), figures(row)),
            parameters(request));
    }

    private static Object[] parameters(MiParameters request) {
        return new Object[] {request.granularity().field(), request.from(), request.to(), request.includeMigrated()};
    }

    private static DurationFigures figures(ResultSet row) throws SQLException {
        long counted = row.getLong("counted");
        return counted == 0 ? DurationFigures.NONE
            : new DurationFigures(counted, row.getObject("median_days", Double.class),
                row.getObject("p95_days", Double.class));
    }
}
