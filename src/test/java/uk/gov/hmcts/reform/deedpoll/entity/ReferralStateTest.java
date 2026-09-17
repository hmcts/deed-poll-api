package uk.gov.hmcts.reform.deedpoll.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uk.gov.hmcts.reform.deedpoll.entity.enums.ReferralState;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The one rule the referral lifecycle has before any service exists: which states are end states.
 *
 * <p>Deliberately container-free, and here rather than folded into a repository test, because the two
 * guards that depend on it -- one open referral per case, and withdraw-only-while-live -- are the rules
 * most worth having CI actually run. Everything that touches a database is excluded on the Jenkins
 * agent.
 *
 * <p>Asserted exhaustively over {@code values()}, so a sixth state added without a decision about its
 * finality fails here rather than silently defaulting to "still live".
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
}
