package uk.gov.hmcts.reform.deedpoll.domain.mi;

import java.util.List;

/**
 * K3: Masters' decisions and applicants' confirmations per period, as counts only.
 *
 * <p>Staff withdrawing a referral is not reported: no column records when it happened.
 */
public record ReferralOutcomesReport(MiParameters parameters, List<Period> periods, Excluded excluded) {

    /** One period's outcomes. */
    public record Period(MiPeriod period, Decisions decisions, Confirmations confirmations) {
    }

    /**
     * Referrals whose {@code decisionDate} falls in the period, by {@code decision}.
     */
    public record Decisions(DecisionCount approved, DecisionCount rejected) {
    }

    /**
     * One decision's count.
     *
     * @param urgent how many of {@code count} were urgent referrals
     */
    public record DecisionCount(long count, long urgent) {
    }

    /**
     * Referrals whose {@code confirmationReceivedAt} falls in the period, by {@code confirmationOutcome}.
     */
    public record Confirmations(long confirmed, long withdrawn) {
    }

    /**
     * What was left out, and why.
     *
     * @param migratedDecisions          decisions on migrated cases, left out because {@code includeMigrated} is false
     * @param migratedConfirmations      confirmations on migrated cases, likewise
     * @param decisionDateWithoutDecision referrals with a {@code decisionDate} in the range but no {@code decision}
     * @param confirmationWithoutOutcome  referrals with a {@code confirmationReceivedAt} in the range but no
     *                                    {@code confirmationOutcome}
     */
    public record Excluded(long migratedDecisions, long migratedConfirmations, long decisionDateWithoutDecision,
                           long confirmationWithoutOutcome) {
    }
}
