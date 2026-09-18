package uk.gov.hmcts.reform.deedpoll.domain.referrals;

import uk.gov.hmcts.reform.deedpoll.entity.enums.ReferralState;

import java.util.Collection;
import java.util.Optional;

/**
 * The rule behind {@code deedpoll_deedpoll."currentReferralState"}: given every state a case's referrals are
 * in, which one -- if any -- the case is currently in.
 *
 * <p><strong>That column is a denormalised read-model of {@code deedpoll_referral.state}, and it is
 * recomputed from truth after every transition rather than set to a per-site value. Do not "simplify" it into
 * a push.</strong> The tempting shape is for each transition to write what it thinks it did -- close sets the
 * case to {@code Closed}, withdraw sets it to {@code Withdrawn} -- and that shape is wrong in a way no test of
 * the transition itself would notice: closing moves the referral to a <em>terminal</em> state, so the case's
 * column must become NULL rather than {@code Closed}. A case left reading {@code Closed} forever is a case
 * that stays in the dashboard's "returned -- action needed" queue after the caseworker actioned it, and every
 * count drawn from the column is then wrong. Asking the referral table what the current state <em>is</em>
 * cannot make that class of mistake; assuming what a transition <em>did</em> invites it at each of six sites.
 *
 * <p>The column exists because the case list filters on columns of one entity -- see
 * {@code V9__current_referral_state.sql} for why the answer is copied onto the case rather than joined for --
 * and the consequence of denormalising is that something has to keep it in step. This class is the half of
 * that which has no database in it, so the rule that matters runs in CI: container-backed tests are excluded
 * on the Jenkins agent ({@code INTEGRATION_TESTS_ENABLED=false}), and "NULL when every referral is terminal"
 * is exactly the rule a drifting push gets wrong.
 *
 * <p><strong>Empty is a real answer, not a missing one.</strong> A case with no referrals, and a case whose
 * referrals are all {@code Closed} or {@code Withdrawn}, are both "no active referral" -- which is what every
 * migrated case is, because {@code V7__backfill_referrals.sql} seeds one {@code Closed} referral per legacy
 * case.
 *
 * <p>The terminal split is {@link ReferralState#isTerminal()}'s rather than a second hand-written set here,
 * for the reason that method exists: a sixth state added to the enum has to be covered by one definition of
 * "still in play", not by three that can disagree.
 */
final class CurrentReferralState {

    private CurrentReferralState() {
    }

    /**
     * The state a case is currently in, given the states of all of its referrals.
     *
     * <p>Takes every state rather than only the non-terminal ones, so that the "all terminal means none" half
     * of the rule is this method's and not its caller's -- a caller that pre-filtered would be free to get
     * the filter wrong.
     *
     * <p><strong>At most one state can qualify</strong>, because a case may hold at most one non-terminal
     * referral: {@code ReferralService.createReferral} refuses a second with a 409. This does not assert that
     * invariant, it relies on it -- taking the first qualifying state rather than complaining about a second
     * one, because a read-model repair is not the place to discover that the write path is broken.
     *
     * @param states every state the case's referrals are in, in any order, possibly empty
     * @return the one non-terminal state, or empty when the case has no referral in play
     */
    static Optional<ReferralState> of(Collection<ReferralState> states) {
        return states.stream()
            .filter(state -> state != null && !state.isTerminal())
            .findFirst();
    }
}
