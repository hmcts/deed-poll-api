package uk.gov.hmcts.reform.deedpoll.repository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.hibernate.Hibernate;
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
import uk.gov.hmcts.reform.deedpoll.entity.enums.ConfirmationOutcome;
import uk.gov.hmcts.reform.deedpoll.entity.enums.ReferralState;
import uk.gov.hmcts.reform.deedpoll.entity.enums.Urgency;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

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
 * <p>The personal list is here for the same reasons and one of its own. Its membership rule is <em>SQL</em> -- two
 * state arms reading two different owner columns, and a null owner matching nothing because {@code =} is never true
 * of one -- so a fake applying the same predicate in Java would be asserting a second implementation of it rather
 * than the one that runs. Its order adds two facts only Postgres can state: that {@code "ReferralState"} being
 * declared in lifecycle order puts {@code Claimed} above {@code Decided}, and that the {@code uuid} tie-break which
 * makes paging stable compares sixteen unsigned bytes rather than two signed longs.
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

    /**
     * The order Postgres compares {@code uuid} in: sixteen bytes, unsigned.
     *
     * <p>Not {@link UUID#compareTo}, which compares the two halves as <em>signed</em> longs -- so a v4 id whose
     * first bit is set sorts first in Java and last in Postgres, and about half of them have it set. The personal
     * list's tie-break expectation is built with this rather than with the natural order, which is the difference
     * between asserting Postgres's behaviour and asserting Java's.
     */
    private static final Comparator<UUID> AS_POSTGRES_ORDERS_THEM =
        Comparator.comparing(UUID::getMostSignificantBits, Long::compareUnsigned)
            .thenComparing(UUID::getLeastSignificantBits, Long::compareUnsigned);

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
     *
     * <p>Both owner columns, as a real claim writes both: {@code claimedBy} is the display name the lapse note
     * reads and {@code claimedById} is the subject the claim belongs to. The subject is derived from the name here
     * only so that a fixture reads as one judge; nothing under test compares the two.
     */
    private Referral claimedReferral(DeedPoll parent, String judge, LocalDateTime lastTouchedAt) {
        Referral referral = persistedReferral(parent, ReferralState.Claimed, lastTouchedAt);
        referral.setClaimedBy(judge);
        referral.setClaimedById(subjectOf(judge));
        referral.setClaimedAt(lastTouchedAt);
        referral.setLastTouchedAt(lastTouchedAt);
        return referral;
    }

    /** A stable-looking IDAM subject for a fixture judge, which is emphatically not their display name. */
    private static String subjectOf(String judge) {
        return "idam-" + judge.toLowerCase(Locale.UK).replace(' ', '-');
    }

    /**
     * A referral on a case of its own, in the given state, owned by the given judge's subject and nobody else.
     *
     * <p>Both owner columns, as a real claim and a real direction leave them: only the holder may direct, so a
     * {@code Decided} row carries the same subject in both. Every state gets them, not only the two that would
     * really carry them, because what the personal list's two arms have to exclude is exactly an {@code Open},
     * {@code Closed} or {@code Withdrawn} row that still names the judge who had it.
     *
     * <p>A case each, for the reason {@link #pooledReferral} takes one: a case may hold only one live referral.
     */
    private Referral ownedReferral(ReferralState state, String judge, Urgency urgency, LocalDateTime createdOn) {
        Referral referral = persistedReferral(persistedCase(), state, createdOn);
        referral.setUrgency(urgency);
        referral.setClaimedBy(judge);
        referral.setClaimedById(subjectOf(judge));
        referral.setDecidedBy(judge);
        referral.setDecidedById(subjectOf(judge));
        return referral;
    }

    /**
     * One page of a judge's own referrals, as {@code JudicialReferralService.mine} asks for it.
     */
    private Page<Referral> personalList(String judge, int page, int size) {
        return referrals.findOwnedBy(
            subjectOf(judge), ReferralState.Claimed, ReferralState.Decided,
            PageRequest.of(page, size, JudicialReferralService.MINE_ORDER));
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
    @DisplayName("finds a referral by id with its application already loaded, for the judicial read")
    void findsAReferralByIdWithItsApplicationLoaded() {
        DeedPoll deedPoll = persistedCase();
        deedPoll.setNewForename("Robin");
        Referral referral = persistedReferral(deedPoll, ReferralState.Open, LocalDateTime.of(2026, 2, 1, 9, 0));
        entityManager.flush();
        entityManager.clear();

        Referral found = referrals.findWithCaseById(referral.getId()).orElseThrow();

        // The join fetch is the part only a real session can assert. Referral.deedPoll is LAZY and
        // spring.jpa.open-in-view is false, so a judicial DTO mapped after the transaction closed would hit a
        // LazyInitializationException unless the case came back with the referral -- and a test that merely read
        // a field inside the session would pass either way.
        assertThat(Hibernate.isInitialized(found.getDeedPoll()))
            .as("the application arrives initialised rather than as a proxy to be resolved later")
            .isTrue();
        assertThat(found.getDeedPoll().getNewForename()).isEqualTo("Robin");
        // No case in the question, which is what makes this the judicial exception to the scoping rule: the
        // referral is reachable by its own id alone, and JudicialReferralService.visibleTo is what stands where
        // the parent id stands on the caseworker surface.
        assertThat(referrals.findWithCaseById(UUID.randomUUID())).isEmpty();
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
    @DisplayName("releases a lapsed claim back to the pool in one statement, clearing every claim column")
    void releasesALapsedClaimInOneStatement() {
        DeedPoll deedPoll = persistedCase();
        Referral lapsed = claimedReferral(deedPoll, "District Judge Bell", CUTOFF.minusHours(1));
        entityManager.flush();

        int released = referrals.releaseStaleClaim(lapsed.getId(), CUTOFF, NOW, SERVICE_ACCOUNT);

        assertThat(released).isEqualTo(1);
        Referral reloaded = entityManager.find(Referral.class, lapsed.getId());
        assertThat(reloaded.getState()).isEqualTo(ReferralState.Open);
        assertThat(reloaded.getClaimedBy()).isNull();
        // The owner, cleared by the same statement -- and this is the one of the four whose survival would be a
        // security bug rather than stale data: the judge who abandoned the referral would still be able to direct
        // it after the pool had re-offered it to everybody.
        assertThat(reloaded.getClaimedById()).isNull();
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
        // The owner survives too, which is what keeps the still-working judge able to direct it: a statement that
        // cleared the owner without changing the state would take a referral off somebody who still holds it.
        assertThat(entityManager.find(Referral.class, fresh.getId()).getClaimedById())
            .isEqualTo(subjectOf("District Judge Bell"));
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
    @DisplayName("reads one case's outstanding confirmation requests, and neither the answered nor another case's")
    void readsTheOutstandingConfirmationRequestsOfOneCase() {
        LocalDateTime asked = LocalDateTime.of(2026, 5, 3, 9, 30);
        DeedPoll deedPoll = persistedCase();
        // Outstanding: asked, no answer. The case has waited since this.
        Referral waiting = persistedReferral(deedPoll, ReferralState.Closed, LocalDateTime.of(2026, 5, 3, 9, 0));
        waiting.setConfirmationRequestedAt(asked);
        // Answered, so no longer outstanding -- this is what takes a case out of the waiting list.
        Referral answered = persistedReferral(deedPoll, ReferralState.Open, LocalDateTime.of(2026, 6, 1, 9, 0));
        answered.setConfirmationRequestedAt(asked.plusDays(10));
        answered.setConfirmationOutcome(ConfirmationOutcome.Confirmed);
        // Never asked, which is every referral that predates the confirmation columns.
        persistedReferral(deedPoll, ReferralState.Open, LocalDateTime.of(2026, 7, 1, 9, 0));

        DeedPoll otherCase = persistedCase();
        Referral otherWaiting =
            persistedReferral(otherCase, ReferralState.Open, LocalDateTime.of(2026, 5, 1, 9, 0));
        otherWaiting.setConfirmationRequestedAt(asked.minusDays(30));

        final DeedPoll neverReferred = persistedCase();
        entityManager.flush();
        entityManager.clear();

        // Pre-filtered in SQL rather than in Java, which is what makes V16's partial index reachable and is the
        // WHERE clause the plan says the wait is. The referral's own state is deliberately not part of it: a
        // caseworker may close a referral before the reply arrives, and the applicant still answered a question
        // the office asked -- hence the waiting row here being Closed.
        assertThat(referrals.findOutstandingConfirmationRequestsByDeedPollId(deedPoll.getId()))
            .containsExactly(asked);
        // Scoped, like every other finder here: a recompute that read across cases would put one case into the
        // waiting list on the strength of another's unanswered email.
        assertThat(referrals.findOutstandingConfirmationRequestsByDeedPollId(otherCase.getId()))
            .containsExactly(asked.minusDays(30));
        assertThat(referrals.findOutstandingConfirmationRequestsByDeedPollId(neverReferred.getId())).isEmpty();
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
    @DisplayName("lists a judge's own claimed and decided referrals, and no other state of theirs")
    void listsOneJudgesOwnOutstandingWork() {
        Referral claimed = ownedReferral(ReferralState.Claimed, "District Judge Bell",
                                        Urgency.Normal, LocalDateTime.of(2026, 2, 1, 9, 0));
        Referral decided = ownedReferral(ReferralState.Decided, "District Judge Bell",
                                        Urgency.Normal, LocalDateTime.of(2026, 3, 1, 9, 0));
        // The three states that are not this judge's outstanding work, each still naming them: Open belongs to the
        // shared pool however it got there, and Closed and Withdrawn are the office taking the referral back. A
        // predicate written on the owner columns alone would list all three.
        Referral open = ownedReferral(ReferralState.Open, "District Judge Bell",
                                      Urgency.Normal, LocalDateTime.of(2026, 1, 1, 9, 0));
        Referral closed = ownedReferral(ReferralState.Closed, "District Judge Bell",
                                        Urgency.Normal, LocalDateTime.of(2026, 1, 2, 9, 0));
        Referral withdrawn = ownedReferral(ReferralState.Withdrawn, "District Judge Bell",
                                           Urgency.Normal, LocalDateTime.of(2026, 1, 3, 9, 0));
        entityManager.flush();
        entityManager.clear();

        Page<Referral> mine = personalList("District Judge Bell", 0, 10);

        assertThat(mine.getContent()).extracting(Referral::getId)
            .containsExactly(claimed.getId(), decided.getId())
            .doesNotContain(open.getId(), closed.getId(), withdrawn.getId());
        assertThat(mine.getTotalElements()).isEqualTo(2);
    }

    @Test
    @DisplayName("lists nobody else's, including a different judge who shares the display name")
    void listsNobodyElsesWork() {
        final Referral theirs = ownedReferral(ReferralState.Claimed, "District Judge Bell",
                                              Urgency.Normal, LocalDateTime.of(2026, 2, 1, 9, 0));
        final Referral anotherJudges = ownedReferral(ReferralState.Claimed, "District Judge Okafor",
                                                    Urgency.Normal, LocalDateTime.of(2026, 2, 2, 9, 0));
        // The row the ownership migration exists for: same display name, different person, so a different subject.
        Referral namesakes = ownedReferral(ReferralState.Decided, "District Judge Bell",
                                           Urgency.Normal, LocalDateTime.of(2026, 2, 3, 9, 0));
        namesakes.setClaimedById("idam-a-different-bell");
        namesakes.setDecidedById("idam-a-different-bell");
        // And the pre-migration row: a claim whose display name is recorded and whose owner is not. It is on
        // nobody's list rather than on the list of whoever shares the name, which is the fail-closed half of V14 --
        // SQL's = is never true of a null column, so no fall back to the name is even expressible here.
        Referral noOwner = ownedReferral(ReferralState.Claimed, "District Judge Bell",
                                         Urgency.Normal, LocalDateTime.of(2026, 2, 4, 9, 0));
        noOwner.setClaimedById(null);
        noOwner.setDecidedById(null);
        entityManager.flush();
        entityManager.clear();

        assertThat(personalList("District Judge Bell", 0, 10).getContent()).extracting(Referral::getId)
            .containsExactly(theirs.getId())
            .doesNotContain(anotherJudges.getId(), namesakes.getId(), noOwner.getId());
        // Both directions, because one judge's list cannot tell a per-subject predicate from one that lists
        // everything: the other judge sees their own referral and nothing of this judge's.
        assertThat(personalList("District Judge Okafor", 0, 10).getContent()).extracting(Referral::getId)
            .containsExactly(anotherJudges.getId());
        assertThat(referrals.findOwnedBy("idam-a-different-bell", ReferralState.Claimed, ReferralState.Decided,
                                         PageRequest.of(0, 10, JudicialReferralService.MINE_ORDER)).getContent())
            .extracting(Referral::getId)
            .containsExactly(namesakes.getId());
    }

    @Test
    @DisplayName("orders a judge's work claimed first, then urgent, then longest-held, then by id")
    void ordersAJudgesWorkForResuming() {
        String judge = "District Judge Bell";
        // Deliberately built so that no single key produces the expected sequence: the oldest referral is Decided,
        // the only Urgent Decided one is the newest, and the two tied rows differ in nothing but their ids.
        Referral decidedNormal = ownedReferral(ReferralState.Decided, judge,
                                               Urgency.Normal, LocalDateTime.of(2026, 1, 1, 9, 0));
        Referral decidedUrgent = ownedReferral(ReferralState.Decided, judge,
                                               Urgency.Urgent, LocalDateTime.of(2026, 6, 1, 9, 0));
        Referral claimedNormal = ownedReferral(ReferralState.Claimed, judge,
                                               Urgency.Normal, LocalDateTime.of(2026, 3, 1, 9, 0));
        Referral claimedUrgentOlder = ownedReferral(ReferralState.Claimed, judge,
                                                    Urgency.Urgent, LocalDateTime.of(2026, 2, 1, 9, 0));
        Referral tiedA = ownedReferral(ReferralState.Claimed, judge,
                                       Urgency.Urgent, LocalDateTime.of(2026, 2, 1, 9, 0));
        entityManager.flush();
        entityManager.clear();

        List<Referral> mine = personalList(judge, 0, 10).getContent();

        // Claimed above Decided is the "ReferralState" type being declared in lifecycle order in V6 and Postgres
        // ordering an enum by its declaration -- the same fact POOL_ORDER leans on for urgency, and only a real
        // database can state it. The id is last, and the two tied rows are compared in the order Postgres compares
        // uuid in, which is what makes paging through this list stable rather than nearly stable.
        List<UUID> tied = Stream.of(claimedUrgentOlder.getId(), tiedA.getId())
            .sorted(AS_POSTGRES_ORDERS_THEM)
            .toList();
        assertThat(mine).extracting(Referral::getId).containsExactly(
            tied.get(0),
            tied.get(1),
            claimedNormal.getId(),
            decidedUrgent.getId(),
            decidedNormal.getId());
    }

    @Test
    @DisplayName("pages a judge's work in the database, and reports their whole total on every page")
    void pagesAJudgesWorkInTheDatabase() {
        String judge = "District Judge Bell";
        List<Referral> byWorkingOrder = new ArrayList<>();
        for (int day = 1; day <= 5; day++) {
            byWorkingOrder.add(ownedReferral(ReferralState.Claimed, judge,
                                             Urgency.Normal, LocalDateTime.of(2026, 2, day, 9, 0)));
        }
        // Another judge's, so the totals below are this judge's and not the table's.
        ownedReferral(ReferralState.Claimed, "District Judge Okafor",
                      Urgency.Normal, LocalDateTime.of(2026, 2, 1, 9, 0));
        entityManager.flush();
        entityManager.clear();

        Page<Referral> first = personalList(judge, 0, 2);
        Page<Referral> second = personalList(judge, 1, 2);
        Page<Referral> pastTheEnd = personalList(judge, 9, 2);

        // Two rows a page, sliced in working order rather than insertion order, and counted over the same predicate
        // the page was drawn from: a total that came from the page's row count would say "2 of 2" on every page.
        assertThat(first.getContent()).extracting(Referral::getId)
            .containsExactly(byWorkingOrder.get(0).getId(), byWorkingOrder.get(1).getId());
        assertThat(second.getContent()).extracting(Referral::getId)
            .containsExactly(byWorkingOrder.get(2).getId(), byWorkingOrder.get(3).getId());
        assertThat(pastTheEnd.getContent()).isEmpty();
        assertThat(pastTheEnd.getTotalElements()).isEqualTo(5);
        assertThat(first.getTotalElements()).isEqualTo(5);
        assertThat(first.getTotalPages()).isEqualTo(3);
    }

    @Test
    @DisplayName("lists a Decided referral for whoever decided it, not for whoever claimed it")
    void listsADecisionForItsAuthor() {
        // The two arms really do read different columns, which is the shape a hand-off would produce: the judge who
        // claimed it is not the one who directed it, and the direction is the one that is theirs to re-read.
        Referral handedOver = ownedReferral(ReferralState.Decided, "District Judge Bell",
                                           Urgency.Normal, LocalDateTime.of(2026, 2, 1, 9, 0));
        handedOver.setClaimedById(subjectOf("District Judge Okafor"));
        handedOver.setClaimedBy("District Judge Okafor");
        entityManager.flush();
        entityManager.clear();

        assertThat(personalList("District Judge Bell", 0, 10).getContent())
            .extracting(Referral::getId).containsExactly(handedOver.getId());
        assertThat(personalList("District Judge Okafor", 0, 10).getContent()).isEmpty();
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
