package uk.gov.hmcts.reform.deedpoll.domain.mi;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import uk.gov.hmcts.reform.deedpoll.domain.InvalidInputError;
import uk.gov.hmcts.reform.deedpoll.domain.auth.Capability;
import uk.gov.hmcts.reform.deedpoll.domain.auth.CurrentUser;
import uk.gov.hmcts.reform.deedpoll.entity.enums.DeedPollType;
import uk.gov.hmcts.reform.deedpoll.repository.ManagementInformationRepository;
import uk.gov.hmcts.reform.deedpoll.repository.ManagementInformationRepository.CasesExcludedRow;
import uk.gov.hmcts.reform.deedpoll.repository.ManagementInformationRepository.CompletionRow;
import uk.gov.hmcts.reform.deedpoll.repository.ManagementInformationRepository.ConfirmationRow;
import uk.gov.hmcts.reform.deedpoll.repository.ManagementInformationRepository.DecisionRow;
import uk.gov.hmcts.reform.deedpoll.repository.ManagementInformationRepository.OpenRow;
import uk.gov.hmcts.reform.deedpoll.repository.ManagementInformationRepository.ReceivedRow;
import uk.gov.hmcts.reform.deedpoll.repository.ManagementInformationRepository.StageRow;

import java.time.LocalDate;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Management information (plan 20261103-mi-dashboard): four read-only, aggregate-only KPIs computed from the
 * cases' and referrals' own dates, never the audit trail, so migrated cases count too.
 *
 * <p>Every operation validates its parameters ({@link MiParameters#of}), reports every period of the request,
 * empty ones included (plan D6: {@code count} 0, null percentiles), and says in an {@code excluded} block what it
 * left out and why. The figures reflect the dates as they stand now, which staff can edit; they are not a history.
 *
 * <p>Each operation's reads share one repeatable-read snapshot, so the figures of one response agree with each
 * other. The capability check is the caller's ({@code VIEW_MANAGEMENT_INFORMATION}); nothing here is per-case, so
 * there is no read audit (plan D5).
 */
@Service
public class ManagementInformationService {

    private final ManagementInformationRepository repository;

    public ManagementInformationService(ManagementInformationRepository repository) {
        this.repository = repository;
    }

    /**
     * The gate every HTTP operation passes first: {@link Capability#VIEW_MANAGEMENT_INFORMATION} (403), and only then
     * the query parameters ({@link MiParameters#parse}, 400), so a caller without the capability learns nothing from
     * how their parameters are refused. Reads nothing.
     *
     * @throws uk.gov.hmcts.reform.deedpoll.domain.ForbiddenError without the capability, before anything else
     * @throws InvalidInputError                                  naming every parameter at fault
     */
    public MiParameters admit(CurrentUser caller, String from, String to, String granularity,
                              String includeMigrated) {
        caller.require(Capability.VIEW_MANAGEMENT_INFORMATION);
        return MiParameters.parse(from, to, granularity, includeMigrated);
    }

    /** K1. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public VolumeReport volume(MiParameters request) {
        List<MiPeriod> periods = request.periods();
        Map<LocalDate, Map<DeedPollType, Long>> received = new HashMap<>();
        for (ReceivedRow row : repository.received(request)) {
            received.computeIfAbsent(row.periodStart(), start -> zeroByType())
                .merge(DeedPollType.valueOf(row.type()), row.received(), Long::sum);
        }
        Map<LocalDate, Long> open = repository.openAtEnd(request.includeMigrated(),
                periods.stream().map(MiPeriod::end).toList()).stream()
            .collect(Collectors.toMap(OpenRow::periodEnd, OpenRow::openAtEnd));
        CasesExcludedRow excluded = repository.casesExcluded(request);
        // openAtEnd also leaves out migrated cases received before the range, which casesExcluded does not count.
        long migrated = excluded.migrated() + (request.includeMigrated() ? 0
            : repository.migratedCarriedIn(request.from(), periods.get(0).end()));

        List<VolumeReport.Period> figures = periods.stream().map(period -> {
            Map<DeedPollType, Long> byType = received.getOrDefault(startOf(request, period), zeroByType());
            long total = byType.values().stream().mapToLong(Long::longValue).sum();
            return new VolumeReport.Period(period, total, Collections.unmodifiableMap(byType),
                open.getOrDefault(period.end(), 0L));
        }).toList();
        return new VolumeReport(request, figures,
            new VolumeReport.Excluded(excluded.noDateReceived(), migrated));
    }

    /** K2. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public StageDurationsReport stageDurations(MiParameters request) {
        Map<LocalDate, Map<StageInterval, DurationFigures>> byPeriod = new HashMap<>();
        Map<StageInterval, Long> negative = zeroByInterval();
        Map<StageInterval, Long> migrated = zeroByInterval();
        for (StageRow row : repository.stageDurations(request)) {
            byPeriod.computeIfAbsent(row.periodStart(), start -> new EnumMap<>(StageInterval.class))
                .put(row.interval(), row.figures());
            negative.merge(row.interval(), row.negative(), Long::sum);
            migrated.merge(row.interval(), row.migrated(), Long::sum);
        }
        List<StageDurationsReport.Period> figures = request.periods().stream().map(period -> {
            Map<StageInterval, DurationFigures> found = byPeriod.getOrDefault(startOf(request, period), Map.of());
            Map<StageInterval, DurationFigures> intervals = new EnumMap<>(StageInterval.class);
            for (StageInterval interval : StageInterval.values()) {
                intervals.put(interval, found.getOrDefault(interval, DurationFigures.NONE));
            }
            return new StageDurationsReport.Period(period, Collections.unmodifiableMap(intervals));
        }).toList();
        return new StageDurationsReport(request, figures, new StageDurationsReport.Excluded(
            Collections.unmodifiableMap(negative), Collections.unmodifiableMap(migrated)));
    }

    /** K3. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ReferralOutcomesReport referralOutcomes(MiParameters request) {
        Map<LocalDate, DecisionRow> decisions = byStart(repository.decisions(request), DecisionRow::periodStart);
        Map<LocalDate, ConfirmationRow> confirmations =
            byStart(repository.confirmations(request), ConfirmationRow::periodStart);

        List<ReferralOutcomesReport.Period> figures = request.periods().stream().map(period -> {
            DecisionRow decided = decisions.get(startOf(request, period));
            ConfirmationRow confirmed = confirmations.get(startOf(request, period));
            return new ReferralOutcomesReport.Period(period,
                decided == null
                    ? new ReferralOutcomesReport.Decisions(new ReferralOutcomesReport.DecisionCount(0, 0),
                        new ReferralOutcomesReport.DecisionCount(0, 0))
                    : new ReferralOutcomesReport.Decisions(
                        new ReferralOutcomesReport.DecisionCount(decided.approved(), decided.approvedUrgent()),
                        new ReferralOutcomesReport.DecisionCount(decided.rejected(), decided.rejectedUrgent())),
                confirmed == null
                    ? new ReferralOutcomesReport.Confirmations(0, 0)
                    : new ReferralOutcomesReport.Confirmations(confirmed.confirmed(), confirmed.withdrawn()));
        }).toList();
        return new ReferralOutcomesReport(request, figures, new ReferralOutcomesReport.Excluded(
            decisions.values().stream().mapToLong(DecisionRow::migrated).sum(),
            confirmations.values().stream().mapToLong(ConfirmationRow::migrated).sum(),
            decisions.values().stream().mapToLong(DecisionRow::noOutcome).sum(),
            confirmations.values().stream().mapToLong(ConfirmationRow::noOutcome).sum()));
    }

    /** K4. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public CompletionReport completion(MiParameters request) {
        Map<LocalDate, CompletionRow> cohorts = byStart(repository.completion(request), CompletionRow::periodStart);
        CasesExcludedRow cases = repository.casesExcluded(request);

        List<CompletionReport.Cohort> figures = request.periods().stream().map(period -> {
            CompletionRow row = cohorts.get(startOf(request, period));
            return row == null
                ? new CompletionReport.Cohort(period, 0, 0, 0, 0, 0, DurationFigures.NONE)
                : new CompletionReport.Cohort(period, row.received(), row.enroled() + row.returned(), row.enroled(),
                    row.returned(), row.stillOpen(), row.figures());
        }).toList();
        return new CompletionReport(request, figures, new CompletionReport.Excluded(cases.noDateReceived(),
            cohorts.values().stream().mapToLong(CompletionRow::contradiction).sum(),
            cohorts.values().stream().mapToLong(CompletionRow::negative).sum(), cases.migrated()));
    }

    // The database buckets by the unclipped calendar period, so a clipped first period is found by its own start.
    private static LocalDate startOf(MiParameters request, MiPeriod period) {
        return request.granularity().start(period.start());
    }

    private static <T> Map<LocalDate, T> byStart(List<T> rows, Function<T, LocalDate> start) {
        return rows.stream().collect(Collectors.toMap(start, Function.identity()));
    }

    private static Map<DeedPollType, Long> zeroByType() {
        Map<DeedPollType, Long> counts = new EnumMap<>(DeedPollType.class);
        for (DeedPollType type : DeedPollType.values()) {
            counts.put(type, 0L);
        }
        return counts;
    }

    private static Map<StageInterval, Long> zeroByInterval() {
        Map<StageInterval, Long> counts = new EnumMap<>(StageInterval.class);
        for (StageInterval interval : StageInterval.values()) {
            counts.put(interval, 0L);
        }
        return counts;
    }
}
