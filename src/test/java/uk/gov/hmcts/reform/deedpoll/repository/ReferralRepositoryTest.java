package uk.gov.hmcts.reform.deedpoll.repository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;
import uk.gov.hmcts.reform.deedpoll.config.AbstractPostgresContainerTest;
import uk.gov.hmcts.reform.deedpoll.domain.referrals.JudicialReferralService;
import uk.gov.hmcts.reform.deedpoll.entity.DeedPoll;
import uk.gov.hmcts.reform.deedpoll.entity.Referral;
import uk.gov.hmcts.reform.deedpoll.entity.enums.ReferralState;
import uk.gov.hmcts.reform.deedpoll.entity.enums.Urgency;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Asserts the referral finders against a real Postgres, because what they promise is about SQL rather
 * than about the application: every one of them is scoped to a parent case, and the ordering and the
 * existence check are the database's semantics.
 *
 * <p>Nothing else in the suite exercises them -- the service tests mock this repository -- so a finder
 * that quietly ignored its {@code deedPollId} would pass every one of those while making a referral of
 * one case readable through another's URL.
 *
 * <p>The scoping is asserted by getting the wrong answer's absence, not by reading the method names:
 * two cases each hold a referral, and each finder is asked about both.
 *
 * <p>The judicial pool is here for the same reason as the sweep, and it is the one read that is deliberately
 * <em>not</em> case-scoped: what makes it right is the database's ordering and paging. Urgent-first depends on
 * {@code "Urgency"} being declared {@code ('Normal', 'Urgent')} in {@code V1__enums.sql}, because Postgres
 * orders an enum by its declared order -- a fact only a real Postgres can assert -- and paging in working order
 * is the difference between the longest-waiting urgent referral being on page 1 and it never being worked.
 * {@code JudicialReferralServiceTest} asserts the other half container-free: that the service asks for that
 * order rather than fetching and reordering.
 *
 * <p>The stale-claim sweep's two statements are here for a stronger reason: what makes them correct is SQL
 * that no fake can stand in for. {@code ReferralServiceTest} proves the arithmetic -- which rows the service
 * asks about and with which cutoff -- against a repository that applies the same predicate in Java, and only
 * a real Postgres can say that the conditional {@code UPDATE} refuses a row somebody touched in the meantime
 * and answers zero the second time it is run. That second answer is the whole multi-replica argument.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional
class ReferralRepositoryTest extends AbstractPostgresContainerTest {

    /**
     * The states a case may hold only one of at a time, as {@link ReferralState#isTerminal()} defines them.
     */
    private static final Set<ReferralState> NON_TERMINAL =
        EnumSet.copyOf(Arrays.stream(ReferralState.values()).filter(state -> !state.isTerminal()).toList());

    /** A fixed "now" for the sweep tests, so nothing turns on how long a container took to start. */
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 6, 1, 12, 0);

    /**
     * Two hours before {@link #NOW}: the cutoff a default-configured sweep would ask about.
     */
    private static final LocalDateTime CUTOFF = NOW.minusHours(2);

    private static final String SERVICE_ACCOUNT = "# svc_HMCTS_LCPT_Dev";

    @Autowired
    private ReferralRepository referrals;

    @PersistenceContext
    private EntityManager entityManager;

    private DeedPoll persistedCase() {
        DeedPoll deedPoll = new DeedPoll();
        deedPoll.setDateReceived(LocalDateTime.of(2026, 1, 2, 10, 0));
        entityManager.persist(deedPoll);
        return deedPoll;
    }

    private Referral persistedReferral(DeedPoll parent, ReferralState state, LocalDateTime createdOn) {
        Referral referral = new Referral();
        referral.setDeedPoll(parent);
        referral.setState(state);
        // Set explicitly rather than left to @PrePersist: these tests are about ordering, and three
        // referrals created in the same millisecond have no order to assert.
        referral.setCreatedOn(createdOn);
        entityManager.persist(referral);
        return referral;
    }

    /**
     * An {@code Open} referral on a case of its own, with the urgency and age the pool orders by.
     *
     * <p>A case each because a case may hold only one live referral, so a pool of five is five cases -- which
     * is also the shape the pool query has to cope with, being the one read here that is not case-scoped.
     */
    private Referral pooledReferral(Urgency urgency, LocalDateTime createdOn) {
        Referral referral = persistedReferral(persistedCase(), ReferralState.Open, createdOn);
        referral.setUrgency(urgency);
        return referral;
    }

    /**
     * A referral held by a judge who last interacted with it at {@code lastTouchedAt}.
     */
    private Referral claimedReferral(DeedPoll parent, String judge, LocalDateTime lastTouchedAt) {
        Referral referral = persistedReferral(parent, ReferralState.Claimed, lastTouchedAt);
        referral.setClaimedBy(judge);
        referral.setClaimedAt(lastTouchedAt);
        referral.setLastTouchedAt(lastTouchedAt);
        return referral;
    }

    @Test
    @DisplayName("lists one case's referrals, newest first, and never another case's")
    void listsOneCasesReferralsNewestFirst() {
        DeedPoll deedPoll = persistedCase();
        DeedPoll otherCase = persistedCase();

        Referral oldest = persistedReferral(deedPoll, ReferralState.Withdrawn,
                                            LocalDateTime.of(2026, 2, 1, 9, 0));
        Referral newest = persistedReferral(deedPoll, ReferralState.Open,
                                            LocalDateTime.of(2026, 4, 1, 9, 0));
        Referral middle = persistedReferral(deedPoll, ReferralState.Closed,
                                            LocalDateTime.of(2026, 3, 1, 9, 0));
        Referral elsewhere = persistedReferral(otherCase, ReferralState.Open,
                                               LocalDateTime.of(2026, 5, 1, 9, 0));
        entityManager.flush();
        entityManager.clear();

        List<Referral> found = referrals.findByDeedPollIdOrderByCreatedOnDesc(deedPoll.getId());

        // History plus the current open one, in the order the referrals tab shows them. The other case's
        // is the most recent of all, so a finder that dropped its scope would put it first.
        assertThat(found).extracting(Referral::getId)
            .containsExactly(newest.getId(), middle.getId(), oldest.getId())
            .doesNotContain(elsewhere.getId());
    }

    @Test
    @DisplayName("finds a referral only through its own case")
    void findsAReferralOnlyThroughItsOwnCase() {
        DeedPoll deedPoll = persistedCase();
        Referral referral = persistedReferral(deedPoll, ReferralState.Open, LocalDateTime.of(2026, 2, 1, 9, 0));
        final DeedPoll otherCase = persistedCase();
        entityManager.flush();
        entityManager.clear();

        assertThat(referrals.findByIdAndDeedPollId(referral.getId(), deedPoll.getId())).isPresent();
        // The scoping rule, as a fact rather than a convention: there is no finder by id alone, so this is
        // the only way to read a referral and it answers empty for the wrong parent.
        assertThat(referrals.findByIdAndDeedPollId(referral.getId(), otherCase.getId())).isEmpty();
        assertThat(referrals.findByIdAndDeedPollId(UUID.randomUUID(), deedPoll.getId())).isEmpty();
    }

    @Test
    @DisplayName("reports a non-terminal referral on the case, and does not confuse one case with another")
    void reportsANonTerminalReferralOnTheCase() {
        DeedPoll withOpenReferral = persistedCase();
        persistedReferral(withOpenReferral, ReferralState.Open, LocalDateTime.of(2026, 2, 1, 9, 0));

        DeedPoll withOnlyTerminalReferrals = persistedCase();
        persistedReferral(withOnlyTerminalReferrals, ReferralState.Closed, LocalDateTime.of(2026, 2, 1, 9, 0));
        persistedReferral(withOnlyTerminalReferrals, ReferralState.Withdrawn, LocalDateTime.of(2026, 3, 1, 9, 0));

        final DeedPoll withNoReferrals = persistedCase();
        entityManager.flush();
        entityManager.clear();

        assertThat(referrals.existsByDeedPollIdAndStateIn(withOpenReferral.getId(), NON_TERMINAL)).isTrue();
        // Both terminal states, which is what lets a case be referred again after one was withdrawn.
        assertThat(referrals.existsByDeedPollIdAndStateIn(withOnlyTerminalReferrals.getId(), NON_TERMINAL))
            .isFalse();
        assertThat(referrals.existsByDeedPollIdAndStateIn(withNoReferrals.getId(), NON_TERMINAL)).isFalse();
    }

    @Test
    @DisplayName("finds the claims nobody has touched since the cutoff, with the case and the judge on each")
    void findsTheClaimsNobodyHasTouchedSinceTheCutoff() {
        DeedPoll deedPoll = persistedCase();
        final Referral lapsed = claimedReferral(deedPoll, "District Judge Bell", CUTOFF.minusMinutes(1));
        final Referral fresh = claimedReferral(persistedCase(), "District Judge Okafor", CUTOFF.plusMinutes(1));
        // Withdrawn out from under the judge who held it, which is the withdraw-voids-a-claim case: the row
        // still carries a stale lastTouchedAt, and the sweep must not treat it as a claim to hand back.
        Referral withdrawn = claimedReferral(persistedCase(), "District Judge Bell", CUTOFF.minusHours(9));
        withdrawn.setState(ReferralState.Withdrawn);
        entityManager.flush();
        entityManager.clear();

        List<StaleClaim> stale = referrals.findStaleClaims(ReferralState.Claimed, CUTOFF);

        assertThat(stale).extracting(StaleClaim::id)
            .containsExactly(lapsed.getId())
            .doesNotContain(fresh.getId(), withdrawn.getId());
        // The two facts the note needs, taken before the release erases one of them. The case id comes off
        // the foreign key without loading the case, which is what lets the sweep read it after the update.
        assertThat(stale.getFirst().deedPollId()).isEqualTo(deedPoll.getId());
        assertThat(stale.getFirst().claimedBy()).isEqualTo("District Judge Bell");
    }

    @Test
    @DisplayName("releases a lapsed claim back to the pool in one statement, clearing all three claim columns")
    void releasesALapsedClaimInOneStatement() {
        DeedPoll deedPoll = persistedCase();
        Referral lapsed = claimedReferral(deedPoll, "District Judge Bell", CUTOFF.minusHours(1));
        entityManager.flush();

        int released = referrals.releaseStaleClaim(lapsed.getId(), CUTOFF, NOW, SERVICE_ACCOUNT);

        assertThat(released).isEqualTo(1);
        Referral reloaded = entityManager.find(Referral.class, lapsed.getId());
        assertThat(reloaded.getState()).isEqualTo(ReferralState.Open);
        assertThat(reloaded.getClaimedBy()).isNull();
        assertThat(reloaded.getClaimedAt()).isNull();
        // The column the predicate reads, nulled by the statement that read it -- which is what makes the
        // release happen once rather than on every sweep from here on.
        assertThat(reloaded.getLastTouchedAt()).isNull();
        assertThat(reloaded.getModifiedBy()).isEqualTo(SERVICE_ACCOUNT);
        assertThat(reloaded.getModifiedOn()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("releases nothing a second time, so two replicas sweeping at once write one note between them")
    void releasesNothingASecondTime() {
        Referral lapsed = claimedReferral(persistedCase(), "District Judge Bell", CUTOFF.minusHours(1));
        entityManager.flush();

        assertThat(referrals.releaseStaleClaim(lapsed.getId(), CUTOFF, NOW, SERVICE_ACCOUNT)).isEqualTo(1);
        // The whole multi-replica argument, as a fact about the statement rather than about the service: the
        // second caller is told it changed nothing, so it writes no second lapse note.
        assertThat(referrals.releaseStaleClaim(lapsed.getId(), CUTOFF, NOW, SERVICE_ACCOUNT)).isZero();
    }

    @Test
    @DisplayName("releases nothing when the judge touched it after the cutoff, or when nobody holds it")
    void releasesNothingItShouldNot() {
        DeedPoll deedPoll = persistedCase();
        Referral fresh = claimedReferral(deedPoll, "District Judge Bell", CUTOFF.plusSeconds(1));
        Referral decided = claimedReferral(persistedCase(), "District Judge Okafor", CUTOFF.minusHours(9));
        decided.setState(ReferralState.Decided);
        entityManager.flush();

        // One second inside the window is a judge still working, and a decided referral is not a claim at
        // all -- both are rows the statement's own predicate has to refuse, because the candidate query and
        // the release are separated by however long the sweep's loop takes.
        assertThat(referrals.releaseStaleClaim(fresh.getId(), CUTOFF, NOW, SERVICE_ACCOUNT)).isZero();
        assertThat(referrals.releaseStaleClaim(decided.getId(), CUTOFF, NOW, SERVICE_ACCOUNT)).isZero();

        entityManager.clear();
        assertThat(entityManager.find(Referral.class, fresh.getId()).getClaimedBy())
            .isEqualTo("District Judge Bell");
        assertThat(entityManager.find(Referral.class, decided.getId()).getState())
            .isEqualTo(ReferralState.Decided);
    }

    @Test
    @DisplayName("reads the states of one case's referrals, all of them, and never another case's")
    void readsTheStatesOfOneCasesReferrals() {
        DeedPoll deedPoll = persistedCase();
        persistedReferral(deedPoll, ReferralState.Closed, LocalDateTime.of(2026, 2, 1, 9, 0));
        persistedReferral(deedPoll, ReferralState.Withdrawn, LocalDateTime.of(2026, 3, 1, 9, 0));
        persistedReferral(deedPoll, ReferralState.Decided, LocalDateTime.of(2026, 4, 1, 9, 0));

        DeedPoll otherCase = persistedCase();
        persistedReferral(otherCase, ReferralState.Open, LocalDateTime.of(2026, 5, 1, 9, 0));

        final DeedPoll neverReferred = persistedCase();
        entityManager.flush();
        entityManager.clear();

        // Every state rather than the non-terminal ones: "all of them are terminal, so the case has no referral
        // in play" is CurrentReferralState's half of the answer, and a query that pre-filtered would move the
        // terminal split out of ReferralState.isTerminal() and into a finder name.
        assertThat(referrals.findStatesByDeedPollId(deedPoll.getId()))
            .containsExactlyInAnyOrder(ReferralState.Closed, ReferralState.Withdrawn, ReferralState.Decided);
        // Scoped, like every other finder here: a recompute that read across cases would put a case with no
        // referral into the dashboard's counts.
        assertThat(referrals.findStatesByDeedPollId(otherCase.getId()))
            .containsExactly(ReferralState.Open);
        assertThat(referrals.findStatesByDeedPollId(neverReferred.getId())).isEmpty();
    }

    @Test
    @DisplayName("pools the Open referrals of every case, urgent first and longest-waiting within each band")
    void poolsTheOpenReferralsInWorkingOrder() {
        // Four cases, because the pool is the one read here that is deliberately not scoped to one: a judge
        // reaches it without naming a case, which is what stops them naming one at all.
        Referral normalAndNewer = pooledReferral(Urgency.Normal, LocalDateTime.of(2026, 4, 1, 9, 0));
        Referral urgentAndOlder = pooledReferral(Urgency.Urgent, LocalDateTime.of(2026, 3, 1, 9, 0));
        Referral normalAndOlder = pooledReferral(Urgency.Normal, LocalDateTime.of(2026, 2, 1, 9, 0));
        Referral urgentAndNewer = pooledReferral(Urgency.Urgent, LocalDateTime.of(2026, 5, 1, 9, 0));
        entityManager.flush();
        entityManager.clear();

        List<Referral> pool = referrals.findByState(
            ReferralState.Open, PageRequest.of(0, 10, JudicialReferralService.POOL_ORDER)).getContent();

        // Both halves of the order at once, and the fixture is built so that neither key alone produces this
        // sequence: by age alone the oldest is a Normal one, and by urgency alone the two Urgent rows could come
        // in either order. This is also what pins "Urgency" being declared ('Normal', 'Urgent') in V1 -- Postgres
        // orders an enum by its declared order, which is the whole reason descending urgency means most urgent.
        assertThat(pool).extracting(Referral::getId).containsExactly(
            urgentAndOlder.getId(),
            urgentAndNewer.getId(),
            normalAndOlder.getId(),
            normalAndNewer.getId());
    }

    @Test
    @DisplayName("pools only Open referrals, so nothing a judge holds or a caseworker finished is offered again")
    void poolsOnlyOpenReferrals() {
        final Referral open = pooledReferral(Urgency.Normal, LocalDateTime.of(2026, 2, 1, 9, 0));
        // One of every other state, each on its own case, because a case may hold only one live referral.
        for (ReferralState state : List.of(ReferralState.Claimed, ReferralState.Decided,
                                           ReferralState.Closed, ReferralState.Withdrawn)) {
            persistedReferral(persistedCase(), state, LocalDateTime.of(2026, 1, 1, 9, 0));
        }
        entityManager.flush();
        entityManager.clear();

        Page<Referral> pool = referrals.findByState(
            ReferralState.Open, PageRequest.of(0, 10, JudicialReferralService.POOL_ORDER));

        // The filter is the pool's only scope, so it carries the weight the case id carries everywhere else
        // here: a claimed referral shown in the pool would be two judges reading the same papers.
        assertThat(pool.getContent()).extracting(Referral::getId).containsExactly(open.getId());
        assertThat(pool.getTotalElements()).isEqualTo(1);
    }

    @Test
    @DisplayName("pages the pool in the database, and reports the whole pool's total on every page")
    void pagesThePoolInTheDatabase() {
        List<Referral> byWorkingOrder = new ArrayList<>();
        for (int day = 1; day <= 5; day++) {
            byWorkingOrder.add(pooledReferral(Urgency.Normal, LocalDateTime.of(2026, 2, day, 9, 0)));
        }
        entityManager.flush();
        entityManager.clear();

        Page<Referral> first = referrals.findByState(
            ReferralState.Open, PageRequest.of(0, 2, JudicialReferralService.POOL_ORDER));
        Page<Referral> second = referrals.findByState(
            ReferralState.Open, PageRequest.of(1, 2, JudicialReferralService.POOL_ORDER));
        Page<Referral> pastTheEnd = referrals.findByState(
            ReferralState.Open, PageRequest.of(9, 2, JudicialReferralService.POOL_ORDER));

        // Two rows a page, sliced in working order rather than in insertion order, which is the claim a
        // fetch-then-reorder would fail: the longest-waiting referral is on page 1 and not page 3.
        assertThat(first.getContent()).extracting(Referral::getId)
            .containsExactly(byWorkingOrder.get(0).getId(), byWorkingOrder.get(1).getId());
        assertThat(second.getContent()).extracting(Referral::getId)
            .containsExactly(byWorkingOrder.get(2).getId(), byWorkingOrder.get(3).getId());
        // Empty rather than an error, and still counted: a judge on a page past the end is told how much is
        // waiting, which is what makes a ten-row default a page size and not a cap.
        assertThat(pastTheEnd.getContent()).isEmpty();
        assertThat(pastTheEnd.getTotalElements()).isEqualTo(5);
        assertThat(first.getTotalElements()).isEqualTo(5);
        assertThat(first.getTotalPages()).isEqualTo(3);
    }

    @Test
    @DisplayName("counts Claimed and Decided as states a case may already be holding")
    void countsClaimedAndDecidedAsNonTerminal() {
        // The states no operation reaches yet. The guard is written against "non-terminal" rather than
        // against 'Open', so this asserts today what the directions plans will rely on: a case whose
        // referral has been picked up by a judge is still a case that cannot be referred again.
        for (ReferralState state : List.of(ReferralState.Claimed, ReferralState.Decided)) {
            DeedPoll deedPoll = persistedCase();
            persistedReferral(deedPoll, state, LocalDateTime.of(2026, 2, 1, 9, 0));
            entityManager.flush();
            entityManager.clear();

            assertThat(referrals.existsByDeedPollIdAndStateIn(deedPoll.getId(), NON_TERMINAL))
                .as("a %s referral is one the case is still holding", state)
                .isTrue();
        }
    }
}
