package uk.gov.hmcts.reform.deedpoll.api.caseworker.dto;

import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import uk.gov.hmcts.reform.deedpoll.domain.mi.ReferralOutcomesReport;

import java.time.LocalDate;
import java.util.List;

/**
 * K3, referral outcomes: Masters' decisions and applicants' confirmations per period, as counts only; no case or
 * referral is identified, and percentages are the reader's.
 */
public record MiReferralOutcomesResponse(
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    MiParametersResponse parameters,
    @ArraySchema(arraySchema = @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
        description = "Every period of the range in order, empty ones included."))
    List<Period> periods,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    Excluded excluded) {

    /** One period, the first and last clipped to the range. */
    @Schema(name = "MiReferralOutcomesPeriod")
    public record Period(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "The period's first day, inclusive.")
        LocalDate start,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "The period's last day, inclusive.")
        LocalDate end,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
            description = "Referrals whose decisionDate falls in the period, by decision.")
        Decisions decisions,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
            description = "Referrals whose confirmationReceivedAt falls in the period, by the applicant's "
                + "confirmationOutcome.")
        Confirmations confirmations) {
    }

    /** A period's decisions. */
    @Schema(name = "MiDecisions")
    public record Decisions(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        DecisionCount approved,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        DecisionCount rejected) {
    }

    /** One decision's count. */
    @Schema(name = "MiDecisionCount")
    public record DecisionCount(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        long count,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "How many of count were urgent referrals.")
        long urgent) {
    }

    /** A period's confirmations. */
    @Schema(name = "MiConfirmations")
    public record Confirmations(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        long confirmed,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        long withdrawn) {
    }

    /** What was left out, and why, over the whole range. */
    @Schema(name = "MiReferralOutcomesExcluded")
    public record Excluded(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
            description = "Decisions on migrated cases, left out because includeMigrated is false; 0 otherwise.")
        long migratedDecisions,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
            description = "Confirmations on migrated cases, likewise.")
        long migratedConfirmations,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
            description = "Referrals with a decisionDate in the range but no decision.")
        long decisionDateWithoutDecision,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
            description = "Referrals with a confirmationReceivedAt in the range but no confirmationOutcome.")
        long confirmationWithoutOutcome) {
    }

    public static MiReferralOutcomesResponse from(ReferralOutcomesReport report) {
        ReferralOutcomesReport.Excluded excluded = report.excluded();
        return new MiReferralOutcomesResponse(MiParametersResponse.from(report.parameters()),
            report.periods().stream().map(period -> new Period(period.period().start(), period.period().end(),
                new Decisions(count(period.decisions().approved()), count(period.decisions().rejected())),
                new Confirmations(period.confirmations().confirmed(), period.confirmations().withdrawn())))
                .toList(),
            new Excluded(excluded.migratedDecisions(), excluded.migratedConfirmations(),
                         excluded.decisionDateWithoutDecision(), excluded.confirmationWithoutOutcome()));
    }

    private static DecisionCount count(ReferralOutcomesReport.DecisionCount count) {
        return new DecisionCount(count.count(), count.urgent());
    }
}
