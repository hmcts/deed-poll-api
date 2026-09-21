package uk.gov.hmcts.reform.deedpoll.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uk.gov.hmcts.reform.deedpoll.entity.enums.ReferralState;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The two rules the referral lifecycle has before any service exists: which states are end states, and
 * which states the office may still pull a referral back from.
 *
 * <p>Deliberately container-free, and here rather than folded into a repository test, because the guards
 * that depend on them -- one live referral per case, the denormalised current-referral-state column, and
 * withdrawal -- are the rules most worth having CI actually run. Everything that touches a database is
 * excluded on the Jenkins agent.
 *
 * <p>Asserted exhaustively over {@code values()}, so a sixth state added without a decision about its
 * finality, or about whether it can be withdrawn, fails here rather than silently defaulting to "still
 * live" and "not withdrawable".
 */
class ReferralStateTest {

    @Test
    @DisplayName("Closed and Withdrawn are the terminal states, and nothing else is")
    void closedAndWithdrawnAreTheTerminalStates() {
        assertThat(Arrays.stream(ReferralState.values()).filter(ReferralState::isTerminal))
            .containsExactly(ReferralState.Closed, ReferralState.Withdrawn);
    }

    @Test
    @DisplayName("Open, Claimed and Decided are the states a case counts as still holding")
    void openClaimedAndDecidedAreNonTerminal() {
        // "Open" in the one-open-per-case sense is wider than the Open state: a referral a judge has
        // claimed, or decided but not yet closed, is still one the case is holding.
        assertThat(Arrays.stream(ReferralState.values()).filter(state -> !state.isTerminal()))
            .containsExactly(ReferralState.Open, ReferralState.Claimed, ReferralState.Decided);
    }

    @Test
    @DisplayName("Withdrawn is a state of its own, not a synonym for Closed")
    void withdrawnIsNotClosed() {
        // Both terminal, and deliberately distinct: closed means a direction came back and was actioned,
        // withdrawn means the caseworker pulled the referral back. Collapsing them would lose the only
        // record that a referral was abandoned.
        assertThat(ReferralState.Withdrawn).isNotEqualTo(ReferralState.Closed);
        assertThat(ReferralState.Withdrawn.isTerminal()).isTrue();
        assertThat(ReferralState.Closed.isTerminal()).isTrue();
    }

    @Test
    @DisplayName("Open and Claimed are the withdrawable states, and nothing else is")
    void openAndClaimedAreTheWithdrawableStates() {
        // The office may pull a referral back while the judiciary has recorded nothing on it -- unclaimed, or
        // claimed and still being read. Once a direction exists the office actions it and closes it.
        assertThat(Arrays.stream(ReferralState.values()).filter(ReferralState::canBeWithdrawn))
            .containsExactly(ReferralState.Open, ReferralState.Claimed);
    }

    @Test
    @DisplayName("Decided is non-terminal and yet not withdrawable, which is why there are two predicates")
    void decidedIsNonTerminalAndNotWithdrawable() {
        // The one state where the two questions disagree, and the reason canBeWithdrawn() is a second
        // predicate rather than a narrowing of isTerminal(). Narrowing isTerminal() to make this refusal true
        // would let a case hold a second referral beside a decided one, and would take the case out of the
        // "returned -- action needed" queue while the direction was still waiting to be actioned.
        assertThat(ReferralState.Decided.isTerminal()).isFalse();
        assertThat(ReferralState.Decided.canBeWithdrawn()).isFalse();
    }

    @Test
    @DisplayName("no terminal state is withdrawable, so the two predicates cannot contradict each other")
    void noTerminalStateIsWithdrawable() {
        // The other direction of the relationship: withdrawable is strictly narrower than non-terminal, never
        // merely different from it. A state that was both terminal and withdrawable would be a transition out
        // of an end state.
        assertThat(Arrays.stream(ReferralState.values()).filter(ReferralState::canBeWithdrawn))
            .noneMatch(ReferralState::isTerminal);
    }
}
