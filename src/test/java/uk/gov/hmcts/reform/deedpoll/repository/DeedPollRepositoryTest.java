package uk.gov.hmcts.reform.deedpoll.repository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import uk.gov.hmcts.reform.deedpoll.config.AbstractPostgresContainerTest;
import uk.gov.hmcts.reform.deedpoll.entity.DeedPoll;
import uk.gov.hmcts.reform.deedpoll.entity.Referral;
import uk.gov.hmcts.reform.deedpoll.entity.enums.DeedPollState;
import uk.gov.hmcts.reform.deedpoll.entity.enums.ReferralState;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Asserts the case finders against a real Postgres, because what is being tested is SQL's semantics rather than
 * the application's.
 *
 * <p>The "open" filter is defined by exclusion -- everything not in a finished state -- and that is a decision
 * about NULL as much as about the listed states. Nothing else in the suite exercises these finders: the service
 * tests mock this repository, so a query that quietly dropped rows would pass every one of them.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional
class DeedPollRepositoryTest extends AbstractPostgresContainerTest {

    /**
     * The states "open" excludes, as {@code ListFilters} defines it.
     */
    private static final Set<DeedPollState> FINISHED = EnumSet.of(DeedPollState.Enroled, DeedPollState.Returned);

    /**
     * The states a referral is still in play in, as {@link ReferralState#isTerminal()} defines them -- what the
     * service passes the set-based recompute rather than a list written into the query.
     */
    private static final Set<ReferralState> NON_TERMINAL =
        EnumSet.copyOf(Arrays.stream(ReferralState.values()).filter(state -> !state.isTerminal()).toList());

    @Autowired
    private DeedPollRepository deedPolls;

    @PersistenceContext
    private EntityManager entityManager;

    private DeedPoll persisted(DeedPollState state, LocalDateTime received) {
        DeedPoll deedPoll = new DeedPoll();
        deedPoll.setState(state);
        deedPoll.setDateReceived(received);
        entityManager.persist(deedPoll);
        return deedPoll;
    }

    @Test
    @DisplayName("a case with no state counts as open")
    void treatsACaseWithNoStateAsOpen() {
        // The case this exists for. A bare `state NOT IN (…)` evaluates to NULL, not true, for a NULL state, so
        // the derived finder dropped the row -- and the column is nullable with nothing defaulting it, so a case
        // created before its state is derived has exactly this shape. It would have been visible only under
        // "all", which is the failure the reference warns about: a case silently missing from a caseworker's
        // open list is not noticeable, whereas one wrongly in it is.
        DeedPoll noState = persisted(null, LocalDateTime.of(2026, 1, 2, 10, 0));
        entityManager.flush();

        List<DeedPoll> open = deedPolls.findOpenOrderByCreatedOnDesc(FINISHED);

        assertThat(open).extracting(DeedPoll::getId).contains(noState.getId());
    }

    @Test
    @DisplayName("an active state is open and a finished one is not")
    void excludesOnlyTheFinishedStates() {
        DeedPoll active = persisted(DeedPollState.AwaitingSignature, LocalDateTime.of(2026, 1, 3, 10, 0));
        DeedPoll enroled = persisted(DeedPollState.Enroled, LocalDateTime.of(2026, 1, 4, 10, 0));
        DeedPoll returned = persisted(DeedPollState.Returned, LocalDateTime.of(2026, 1, 5, 10, 0));
        entityManager.flush();

        List<DeedPoll> open = deedPolls.findOpenOrderByCreatedOnDesc(FINISHED);

        assertThat(open).extracting(DeedPoll::getId)
            .contains(active.getId())
            .doesNotContain(enroled.getId(), returned.getId());
    }

    @Test
    @DisplayName("the open filter returns newest first")
    void ordersOpenCasesNewestFirst() {
        // createdOn is stamped explicitly rather than left to the entity's audit callback, which only defaults it
        // when null. The column is timestamp(3), so two inserts completing inside the same millisecond would tie
        // and "order by createdOn desc" would then be free to return either -- an intermittent failure with no
        // diagnostic value. Three fixed timestamps make the ordering the only thing under test.
        DeedPoll first = persisted(DeedPollState.Received, LocalDateTime.of(2026, 1, 6, 10, 0));
        first.setCreatedOn(LocalDateTime.of(2026, 1, 6, 11, 0));
        entityManager.flush();
        DeedPoll second = persisted(DeedPollState.Received, LocalDateTime.of(2026, 1, 7, 10, 0));
        second.setCreatedOn(LocalDateTime.of(2026, 1, 7, 11, 0));
        entityManager.flush();
        DeedPoll third = persisted(null, LocalDateTime.of(2026, 1, 8, 10, 0));
        third.setCreatedOn(LocalDateTime.of(2026, 1, 8, 11, 0));
        entityManager.flush();

        List<String> open = deedPolls.findOpenOrderByCreatedOnDesc(FINISHED).stream()
            .map(DeedPoll::getId)
            .filter(List.of(first.getId(), second.getId(), third.getId())::contains)
            .toList();

        assertThat(open).containsExactly(third.getId(), second.getId(), first.getId());
    }

    @Test
    @DisplayName("the state filter matches only the states given, and a null state is not one of them")
    void matchesOnlyTheGivenStates() {
        DeedPoll received = persisted(DeedPollState.Received, LocalDateTime.of(2026, 1, 9, 10, 0));
        DeedPoll noState = persisted(null, LocalDateTime.of(2026, 1, 10, 10, 0));
        entityManager.flush();

        List<DeedPoll> matching = deedPolls.findByStateInOrderByCreatedOnDesc(Set.of(DeedPollState.Received));

        // The other direction from the open filter, and deliberately not symmetrical: "Received" means the state
        // is Received, so a case with no state is absent here and present there.
        assertThat(matching).extracting(DeedPoll::getId)
            .contains(received.getId())
            .doesNotContain(noState.getId());
    }

    @Test
    @DisplayName("writes one case's current referral state, and writes null as a value rather than skipping it")
    void writesOneCasesCurrentReferralState() {
        DeedPoll deedPoll = persisted(DeedPollState.Received, LocalDateTime.of(2026, 1, 11, 10, 0));
        final DeedPoll untouched = persisted(DeedPollState.Received, LocalDateTime.of(2026, 1, 11, 10, 0));
        entityManager.flush();

        assertThat(deedPolls.writeCurrentReferralState(deedPoll.getId(), ReferralState.Decided)).isEqualTo(1);
        entityManager.clear();

        // The enum is written as its own name, not an ordinal: the column is the "ReferralState" Postgres type,
        // and reading Decided back is what says the statement bound it as one.
        assertThat(entityManager.find(DeedPoll.class, deedPoll.getId()).getCurrentReferralState())
            .isEqualTo(ReferralState.Decided);
        // One case, addressed by id: a statement that dropped its WHERE would take every case into the
        // dashboard's "returned -- action needed" queue at once.
        assertThat(entityManager.find(DeedPoll.class, untouched.getId()).getCurrentReferralState()).isNull();

        // Back to null, which is the value a close or a withdrawal has to write. A statement that treated null
        // as "nothing to do" would leave every actioned case in that queue for good.
        assertThat(deedPolls.writeCurrentReferralState(deedPoll.getId(), null)).isEqualTo(1);
        entityManager.clear();
        assertThat(entityManager.find(DeedPoll.class, deedPoll.getId()).getCurrentReferralState()).isNull();
    }

    @Test
    @DisplayName("recomputes many cases' current referral state from the referral table in one statement")
    void recomputesManyCasesFromTheReferralTable() {
        // The sweep's shape: several cases whose claims lapsed in the same call. Each column is set from the
        // case's own non-terminal referral rather than to the state the sweep happens to move a claim to, so
        // the bulk site cannot drift from the per-case one.
        DeedPoll reopened = caseWithReferrals(ReferralState.Open);
        DeedPoll stillDecided = caseWithReferrals(ReferralState.Closed, ReferralState.Decided);
        DeedPoll finished = caseWithReferrals(ReferralState.Closed, ReferralState.Withdrawn);
        DeedPoll notInTheSet = caseWithReferrals(ReferralState.Claimed);
        deedPolls.writeCurrentReferralState(notInTheSet.getId(), ReferralState.Claimed);
        entityManager.flush();

        int written = deedPolls.refreshCurrentReferralStates(
            List.of(reopened.getId(), stillDecided.getId(), finished.getId()), NON_TERMINAL);

        assertThat(written).isEqualTo(3);
        entityManager.clear();
        assertThat(entityManager.find(DeedPoll.class, reopened.getId()).getCurrentReferralState())
            .isEqualTo(ReferralState.Open);
        assertThat(entityManager.find(DeedPoll.class, stillDecided.getId()).getCurrentReferralState())
            .isEqualTo(ReferralState.Decided);
        // Every referral terminal, so null -- the answer a hard-coded 'Open' could not have given, and the one
        // every migrated case has after V7 seeds it a single Closed referral.
        assertThat(entityManager.find(DeedPoll.class, finished.getId()).getCurrentReferralState()).isNull();
        // Outside the set the sweep released, so untouched: its judge is still working.
        assertThat(entityManager.find(DeedPoll.class, notInTheSet.getId()).getCurrentReferralState())
            .isEqualTo(ReferralState.Claimed);
    }

    /** A case holding one referral per given state, so a recompute has a history to read past. */
    private DeedPoll caseWithReferrals(ReferralState... states) {
        DeedPoll deedPoll = persisted(DeedPollState.Received, LocalDateTime.of(2026, 1, 12, 10, 0));
        for (ReferralState state : states) {
            Referral referral = new Referral();
            referral.setDeedPoll(deedPoll);
            referral.setState(state);
            entityManager.persist(referral);
        }
        return deedPoll;
    }
}
