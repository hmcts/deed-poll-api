package uk.gov.hmcts.reform.deedpoll.domain.referrals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import uk.gov.hmcts.reform.deedpoll.entity.enums.ReferralState;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The projection rule behind the case's {@code currentReferralState} column, with no database and no service.
 *
 * <p><strong>Deliberately container-free, which is the point of the rule being a class of its own.</strong>
 * Container-backed tests are excluded on the Jenkins agent ({@code INTEGRATION_TESTS_ENABLED=false}), and this
 * is the rule a "simplification" into a per-transition push would get wrong -- so it has to run where CI runs.
 * {@code DeedPollRepositoryTest} proves the statements that carry the answer to Postgres, and
 * {@code CaseworkerReferralControllerTest} proves the column follows a referral through a real lifecycle.
 *
 * <p>The terminal split is asserted as a fact about every member of the enum rather than about the two states
 * that happen to be terminal today, so a sixth state cannot be added without this saying which side it is on.
 */
class CurrentReferralStateTest {

    @Test
    @DisplayName("a case with no referrals has no current referral state")
    void hasNoneWithoutReferrals() {
        // The NULL that is a real answer rather than a value nobody got round to writing: this is every case
        // that has never been referred.
        assertThat(CurrentReferralState.of(List.of())).isEmpty();
    }

    @Test
    @DisplayName("a case whose referrals are all terminal has no current referral state")
    void hasNoneWhenEveryReferralIsTerminal() {
        // The rule a push gets wrong. Closing a referral moves it to a terminal state, so the case's answer is
        // "none" and not "Closed" -- a case reading Closed would sit in the dashboard's "returned -- action
        // needed" queue forever, which is precisely the drift this class exists to make impossible.
        assertThat(CurrentReferralState.of(List.of(ReferralState.Closed, ReferralState.Withdrawn))).isEmpty();
        assertThat(CurrentReferralState.of(List.of(ReferralState.Closed))).isEmpty();
        assertThat(CurrentReferralState.of(List.of(ReferralState.Withdrawn))).isEmpty();
    }

    @Test
    @DisplayName("a migrated case, whose only referral is the backfilled Closed one, has no current state")
    void hasNoneForABackfilledCase() {
        // V7__backfill_referrals.sql seeds exactly one Closed referral per legacy case that had referral data,
        // so this is the shape of every migrated case: NULL, not Closed. Asserted as the projection rule as
        // well as against the migrated rows themselves, because this is the half that runs in CI.
        assertThat(CurrentReferralState.of(List.of(ReferralState.Closed))).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(value = ReferralState.class, names = {"Open", "Claimed", "Decided"})
    @DisplayName("a case with one referral in play reads that referral's state")
    void readsTheOneNonTerminalState(ReferralState live) {
        assertThat(CurrentReferralState.of(List.of(live))).contains(live);
    }

    @ParameterizedTest
    @EnumSource(value = ReferralState.class, names = {"Open", "Claimed", "Decided"})
    @DisplayName("the live referral wins over however much terminal history the case has")
    void ignoresTerminalHistory(ReferralState live) {
        // The ordinary shape of a case that has been referred more than once: several finished referrals and
        // one in play. Order is not the input's to decide, so the history is on both sides of the live one.
        List<ReferralState> states = List.of(
            ReferralState.Withdrawn, ReferralState.Closed, live, ReferralState.Closed);

        assertThat(CurrentReferralState.of(states)).contains(live);
    }

    @ParameterizedTest
    @EnumSource(ReferralState.class)
    @DisplayName("every state is answered exactly as isTerminal() classifies it, with no second list")
    void followsIsTerminalForEveryMember(ReferralState state) {
        // Written over the whole enum rather than over the five members named today: a sixth state added to
        // ReferralState is covered here the moment it exists, which is the reason isTerminal() is asked rather
        // than the terminal states being listed again in this class.
        assertThat(CurrentReferralState.of(List.of(state)).isPresent())
            .as("%s is %sterminal, so a case holding only it %s a current referral state",
                state, state.isTerminal() ? "" : "not ", state.isTerminal() ? "has no" : "has")
            .isEqualTo(!state.isTerminal());
    }

    @Test
    @DisplayName("takes the state in play from a set holding every member, rather than the first of the list")
    void doesNotJustTakeTheFirstState() {
        // Guards the mistake of reading states.getFirst(): Open is the enum's first member and Closed is not,
        // so a case whose history starts with a closed referral would otherwise answer Closed.
        List<ReferralState> everyState = Arrays.asList(ReferralState.values());

        assertThat(CurrentReferralState.of(everyState)).contains(ReferralState.Open);
    }

    @Test
    @DisplayName("ignores a null state rather than failing on it")
    void ignoresANullState() {
        // Not a shape the referral table can produce -- the column is NOT NULL -- but the projection is fed by
        // a query rather than by a constructor, and answering "no referral in play" is the safe reading of a
        // row that somehow has no state.
        assertThat(CurrentReferralState.of(Arrays.asList(null, ReferralState.Closed))).isEmpty();
        assertThat(CurrentReferralState.of(Arrays.asList(null, ReferralState.Decided)))
            .contains(ReferralState.Decided);
    }
}
