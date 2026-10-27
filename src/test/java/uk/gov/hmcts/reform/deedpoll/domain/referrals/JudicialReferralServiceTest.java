package uk.gov.hmcts.reform.deedpoll.domain.referrals;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import uk.gov.hmcts.reform.deedpoll.domain.ConflictError;
import uk.gov.hmcts.reform.deedpoll.domain.ForbiddenError;
import uk.gov.hmcts.reform.deedpoll.domain.NotFoundError;
import uk.gov.hmcts.reform.deedpoll.domain.UnauthorizedError;
import uk.gov.hmcts.reform.deedpoll.domain.auth.CurrentUser;
import uk.gov.hmcts.reform.deedpoll.domain.auth.Role;
import uk.gov.hmcts.reform.deedpoll.domain.deedpoll.CasePaging;
import uk.gov.hmcts.reform.deedpoll.domain.documents.DocumentService;
import uk.gov.hmcts.reform.deedpoll.entity.DeedPoll;
import uk.gov.hmcts.reform.deedpoll.entity.DeedPollSupportingDocument;
import uk.gov.hmcts.reform.deedpoll.entity.Referral;
import uk.gov.hmcts.reform.deedpoll.entity.enums.MasterDecision;
import uk.gov.hmcts.reform.deedpoll.entity.enums.Privacy;
import uk.gov.hmcts.reform.deedpoll.entity.enums.ReferralState;
import uk.gov.hmcts.reform.deedpoll.entity.enums.Urgency;
import uk.gov.hmcts.reform.deedpoll.repository.ReferralRepository;

import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The judicial boundary, with no database and no HTTP: who may see which referral, what the two lists ask the
 * database for, what the read of one referral refuses, resolves and refreshes, and what the two writes delegate.
 *
 * <p><strong>Container-free deliberately, and it is the reason these rules live in this class at all.</strong>
 * The container-backed classes are excluded where CI runs -- {@code INTEGRATION_TESTS_ENABLED=false} on an agent
 * with no Docker -- and the visibility rule is the one rule in this feature whose failure is a disclosure rather
 * than a bug. So it is stated where every build runs it. {@code ReferralRepositoryTest} carries the half a fake
 * cannot stand in for: that Postgres really does put {@code Urgent} first and really does page the pool.
 *
 * <p>The ordering claim is split for the same reason and along the same line. Here: the service asks the
 * database for urgency descending then {@code createdOn} ascending, and returns the rows in the order it was
 * given them -- which is what proves it does not fetch-and-reorder, because the fixture is deliberately handed
 * back in an order no comparator would produce. There: the same sort, executed.
 */
class JudicialReferralServiceTest {

    /** A judge. The role IDAM does not issue yet, supplied directly -- which is what makes it testable. */
    private static final CurrentUser JUDGE =
        CurrentUser.currentUser(List.of(Role.JUDICIAL), "District Judge Bell", "judge-3");

    /** A second judge, for every question that is about which of two judges a referral belongs to. */
    private static final CurrentUser OTHER_JUDGE =
        CurrentUser.currentUser(List.of(Role.JUDICIAL), "District Judge Okafor", "judge-4");

    /**
     * A third judge who shares {@link #JUDGE}'s display name: a different person on the same bench.
     *
     * <p>The shape the ownership migration exists for. While visibility compared display names these two were one
     * person to it, so either could read the other's claimed referral -- and on this surface that means the
     * application and its documents, because {@code visibleTo} is the only boundary in front of a finder by referral
     * id.
     */
    private static final CurrentUser NAMESAKE_JUDGE =
        CurrentUser.currentUser(List.of(Role.JUDICIAL), "District Judge Bell", "judge-9");

    /**
     * {@link #JUDGE} after IDAM renamed them: the same subject, a new display name.
     */
    private static final CurrentUser RENAMED_JUDGE =
        CurrentUser.currentUser(List.of(Role.JUDICIAL), "Her Honour Judge Bell", "judge-3");

    /**
     * A judicial caller with no subject, so nothing they could own.
     *
     * <p>Not a token IDAM issues -- {@code IdamAccessTokenValidator} refuses one carrying no {@code sub} -- which
     * is why it is constructed here rather than reached through a claims document. It is the fail-closed case the
     * owner columns are nullable for.
     */
    private static final CurrentUser UNIDENTIFIED_JUDGE =
        CurrentUser.currentUser(List.of(Role.JUDICIAL), "Nobody In Particular", null);

    /** An ordinary caseworker: authenticated and named, holding no role this service asks about. */
    private static final CurrentUser CASEWORKER =
        CurrentUser.currentUser(List.of(), "Ada Caseworker", "caseworker-1");

    private static final CurrentUser ADMIN =
        CurrentUser.currentUser(List.of(Role.DEEDPOLL_ADMIN), "An Admin", "admin-2");

    /** The case every fixture referral below belongs to, which no judicial URL ever carries. */
    private static final String CASE_ID = "case-1";

    private static final UUID REFERRAL_ID = UUID.fromString("55555555-5555-5555-5555-555555555555");

    /** An id of the right shape that nothing was stored under: the other half of every 404 comparison. */
    private static final UUID UNKNOWN_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private ReferralRepositoryStub referrals;
    private ReferralService referralService;
    private DocumentService documents;
    private JudicialReferralService service;

    @BeforeEach
    void createService() {
        referrals = new ReferralRepositoryStub();
        referralService = mock(ReferralService.class);
        documents = mock(DocumentService.class);
        service = new JudicialReferralService(referrals.repository(), referralService, documents);
    }

    /**
     * Decision 3, state by state and judge by judge.
     *
     * <p>Every state is covered by an {@link EnumSource} rather than by a list of the interesting ones, so a
     * sixth {@link ReferralState} added later fails here until somebody decides whether a judge may see it --
     * which is a policy question and exactly the kind that gets answered by default if nothing asks it.
     */
    @Nested
    @DisplayName("visibleTo")
    class VisibleTo {

        /** The states a judge may see when the referral is theirs: the pool, plus their own work. */
        private static final Set<ReferralState> VISIBLE_TO_THE_HOLDER =
            EnumSet.of(ReferralState.Open, ReferralState.Claimed, ReferralState.Decided);

        @ParameterizedTest
        @EnumSource(ReferralState.class)
        @DisplayName("the judge who holds or decided it sees Open, Claimed and Decided, and nothing terminal")
        void theHoldingJudgeSeesTheirOwnLiveReferrals(ReferralState state) {
            Referral referral = referral(state, JUDGE);

            assertThat(JudicialReferralService.visibleTo(referral, JUDGE))
                .as("a %s referral claimed and decided by this judge", state)
                .isEqualTo(VISIBLE_TO_THE_HOLDER.contains(state));
        }

        @ParameterizedTest
        @EnumSource(ReferralState.class)
        @DisplayName("another judge sees only the pool, whatever state the referral is in")
        void anotherJudgeSeesOnlyThePool(ReferralState state) {
            Referral referral = referral(state, JUDGE);

            // The whole of decision 3's second half: a claimed or decided referral is invisible to every judge
            // but the one it belongs to, and invisible in the same way a non-existent one is -- so the 404 the
            // caller turns this into cannot be told apart from a wrong id.
            assertThat(JudicialReferralService.visibleTo(referral, OTHER_JUDGE))
                .as("a %s referral belonging to another judge", state)
                .isEqualTo(state == ReferralState.Open);
        }

        @Test
        @DisplayName("a closed or withdrawn referral is invisible even to the judge who directed it")
        void terminalReferralIsInvisibleToItsOwnJudge() {
            // The judiciary's involvement is over, and "I directed it, so I may re-read the case for good" is
            // the reading this refuses. Asserted separately from the parameterised tests because it is the one
            // that would be tempting to relax.
            assertThat(JudicialReferralService.visibleTo(referral(ReferralState.Closed, JUDGE), JUDGE)).isFalse();
            assertThat(JudicialReferralService.visibleTo(referral(ReferralState.Withdrawn, JUDGE), JUDGE))
                .isFalse();
        }

        @Test
        @DisplayName("a claimed referral with no holder recorded is visible to nobody")
        void claimWithNoHolderIsVisibleToNobody() {
            // V7 backfilled referrals from the case's flat columns, which recorded no claim, so a Claimed row
            // with no owner is a shape that really exists -- as is a claim made before V14 added the owner
            // columns. Comparing the caller's subject to the column rather than the other way round is what
            // makes this false instead of an exception.
            Referral unheld = new Referral();
            unheld.setState(ReferralState.Claimed);

            assertThat(JudicialReferralService.visibleTo(unheld, JUDGE)).isFalse();
            assertThat(JudicialReferralService.visibleTo(unheld, OTHER_JUDGE)).isFalse();
        }

        @Test
        @DisplayName("a claim whose owner is null is invisible even to the judge it names")
        void claimWithANameButNoOwnerIsVisibleToNobody() {
            // The pre-migration row in full: the display name is there and the subject is not. This is the one
            // case where a fall back to the name would look helpful, and it is exactly the case where it would
            // be wrong -- it is where two judges sharing a name are least distinguishable. So: fail closed, and
            // let the sweep return the claim to the shared pool where anybody may take it.
            Referral pretendClaim = referral(ReferralState.Claimed, JUDGE);
            pretendClaim.setClaimedById(null);

            assertThat(JudicialReferralService.visibleTo(pretendClaim, JUDGE)).isFalse();

            Referral pretendDirection = referral(ReferralState.Decided, JUDGE);
            pretendDirection.setDecidedById(null);

            // And a direction recorded before the migration belongs to no judge's list, which is the
            // fail-closed rollout consequence rather than a bug: the caseworker surface still shows it in full.
            assertThat(JudicialReferralService.visibleTo(pretendDirection, JUDGE)).isFalse();
        }

        @Test
        @DisplayName("a different judge sharing the holder's display name sees nothing of theirs")
        void refusesANamesakeOfTheHolder() {
            // The disclosure V14 removes, stated at the boundary that admits it: while this compared names,
            // a namesake was handed the referral, the application behind it and its documents.
            for (ReferralState state : List.of(ReferralState.Claimed, ReferralState.Decided)) {
                assertThat(JudicialReferralService.visibleTo(referral(state, JUDGE), NAMESAKE_JUDGE))
                    .as("a %s referral of a judge who shares this caller's display name", state)
                    .isFalse();
            }
        }

        @Test
        @DisplayName("the holder still sees their own referral after IDAM renames them")
        void admitsTheHolderUnderANewDisplayName() {
            // The bug that came with the disclosure: a judge who married or took a title used to get a 404 on
            // their own claimed referral until the sweep released it.
            for (ReferralState state : List.of(ReferralState.Claimed, ReferralState.Decided)) {
                assertThat(JudicialReferralService.visibleTo(referral(state, JUDGE), RENAMED_JUDGE))
                    .as("a %s referral of this caller, claimed under their previous display name", state)
                    .isTrue();
            }
        }

        @Test
        @DisplayName("a caller with no subject is refused rather than compared, even for the shared pool")
        void refusesACallerWithNoSubject() {
            // Resolved before the switch rather than inside the Claimed and Decided arms, which is why the pool
            // is in this assertion too: a guard that short-circuited past an unidentified caller on the one
            // state where the answer is "yes to everybody" would be a rule that held by accident.
            for (ReferralState state : ReferralState.values()) {
                assertThatThrownBy(
                    () -> JudicialReferralService.visibleTo(referral(state, JUDGE), UNIDENTIFIED_JUDGE))
                    .as("visibility of a %s referral to a caller with no subject", state)
                    .isInstanceOf(UnauthorizedError.class);
            }
        }

        @Test
        @DisplayName("a decided referral is visible to whoever decided it, not to whoever claimed it")
        void decidedReferralFollowsTheDecidingJudge() {
            // The two columns are separate and this asks about the right one. They agree in practice -- only
            // the holder can decide -- but a referral claimed by one judge and decided by another is the shape
            // a future hand-off would produce, and reading claimedById here would then show it to the wrong one.
            Referral handedOver = referral(ReferralState.Decided, JUDGE);
            handedOver.setClaimedById(OTHER_JUDGE.userId());
            handedOver.setClaimedBy(OTHER_JUDGE.name());

            assertThat(JudicialReferralService.visibleTo(handedOver, JUDGE)).isTrue();
            assertThat(JudicialReferralService.visibleTo(handedOver, OTHER_JUDGE)).isFalse();
        }

        @Test
        @DisplayName("visibility is about the referral, not about the role -- the role gate is separate")
        void visibilitySaysNothingAboutTheRole() {
            // A caseworker never reaches this: requireAnyRole(JUDICIAL) refuses them first, and the pool test
            // below asserts that. Pinned so that nobody adds a role check here and leaves two places able to
            // disagree about which error a non-judicial caller gets -- 403 for the role, 404 for the referral.
            assertThat(JudicialReferralService.visibleTo(referral(ReferralState.Open, JUDGE), CASEWORKER)).isTrue();
            assertThat(JudicialReferralService.visibleTo(referral(ReferralState.Claimed, JUDGE), ADMIN)).isFalse();
        }
    }

    @Nested
    @DisplayName("pool")
    class Pool {

        @Test
        @DisplayName("refuses a caseworker, and refuses before sweeping or querying anything")
        void refusesACaseworker() {
            assertThatThrownBy(() -> service.pool(CASEWORKER, JudicialReferralService.FIRST_POOL_PAGE))
                .isInstanceOf(ForbiddenError.class)
                .hasMessageContaining(Role.JUDICIAL.claimValue());

            // The gate is the first statement, so a refused caller costs no query and -- more to the point --
            // cannot trigger the mutating sweep. An ungated read that swept would let any token move rows.
            verifyNoInteractions(referralService);
            referrals.verifyNothingRead();
        }

        @Test
        @DisplayName("refuses an admin too: judicial is not a level of seniority")
        void refusesAnAdmin() {
            // DEEDPOLL_ADMIN is the most privileged caseworker role there is, and it is still not a judge.
            assertThatThrownBy(() -> service.pool(ADMIN, JudicialReferralService.FIRST_POOL_PAGE))
                .isInstanceOf(ForbiddenError.class);
        }

        @Test
        @DisplayName("sweeps lapsed claims before it queries, so a released referral is in the page it returns")
        void sweepsBeforeItQueries() {
            referrals.returning(List.of(), 0);

            service.pool(JUDGE, JudicialReferralService.FIRST_POOL_PAGE);

            // Order, not merely occurrence: decision 22 is that the referral is back in the pool in the same
            // request that observes the lapse, and a sweep after the query would be one request too late.
            InOrder order = inOrder(referralService, referrals.repository());
            order.verify(referralService).reclaimStaleClaims();
            order.verify(referrals.repository()).findByState(any(), any());
        }

        @Test
        @DisplayName("asks for Open referrals only, urgent first then oldest, one page at a time")
        void asksForTheOpenReferralsInWorkingOrder() {
            referrals.returning(List.of(), 0);

            service.pool(JUDGE, new CasePaging(3, 20));

            ArgumentCaptor<Pageable> asked = ArgumentCaptor.forClass(Pageable.class);
            verify(referrals.repository()).findByState(eq(ReferralState.Open), asked.capture());
            Pageable pageable = asked.getValue();

            // Spelled out rather than compared against POOL_ORDER, which would assert the constant against
            // itself. Descending urgency is the most urgent first because the "Urgency" Postgres type is
            // declared ('Normal', 'Urgent'); ReferralRepositoryTest runs that against a real database.
            assertThat(pageable.getSort()).containsExactly(
                Sort.Order.desc("urgency"),
                Sort.Order.asc("createdOn"));
            // Zero-based where the wire number is 1-based, which is the one translation CasePaging owns.
            assertThat(pageable.getPageNumber()).isEqualTo(2);
            assertThat(pageable.getPageSize()).isEqualTo(20);
        }

        @Test
        @DisplayName("shows ten by default, as a page size and not a cap")
        void showsTenByDefault() {
            assertThat(JudicialReferralService.FIRST_POOL_PAGE.page()).isEqualTo(CasePaging.FIRST_PAGE);
            assertThat(JudicialReferralService.FIRST_POOL_PAGE.size())
                .isEqualTo(JudicialReferralService.POOL_PAGE_SIZE)
                .isEqualTo(10);
        }

        @Test
        @DisplayName("returns the page in the order the database gave it, with the whole pool's total")
        void returnsThePageAsTheDatabaseOrderedIt() {
            Referral urgentAndOldest = referral(ReferralState.Open, null);
            urgentAndOldest.setUrgency(Urgency.Urgent);
            Referral normalAndNewest = referral(ReferralState.Open, null);
            Referral normalAndOldest = referral(ReferralState.Open, null);
            // Handed back in the order the query would have produced. A service that re-sorted by createdOn, or
            // by urgency alone, would not leave these three in this order.
            List<Referral> asOrdered = List.of(urgentAndOldest, normalAndOldest, normalAndNewest);
            referrals.returning(asOrdered, 37);

            ReferralPool pool = service.pool(JUDGE, JudicialReferralService.FIRST_POOL_PAGE);

            assertThat(pool.referrals()).containsExactlyElementsOf(asOrdered);
            // The total is the pool's, not the page's: thirty-seven referrals waiting, ten of them shown, so
            // four pages. That is the number that makes a small default page size safe.
            assertThat(pool.total()).isEqualTo(37);
            assertThat(pool.totalPages()).isEqualTo(4);
            assertThat(pool.page()).isEqualTo(1);
            assertThat(pool.size()).isEqualTo(10);
            assertThat(pool.isEmpty()).isFalse();
        }

        @Test
        @DisplayName("an empty pool is a page with no rows and no pages, not an error")
        void anEmptyPoolIsAnEmptyPage() {
            referrals.returning(List.of(), 0);

            ReferralPool pool = service.pool(JUDGE, JudicialReferralService.FIRST_POOL_PAGE);

            assertThat(pool.isEmpty()).isTrue();
            // Zero pages rather than one: "page 1 of 0" is the truth about a pool with nothing in it, where
            // "page 1 of 1" claims a page that has no rows on it.
            assertThat(pool.totalPages()).isZero();
            assertThat(pool.total()).isZero();
        }

        @Test
        @DisplayName("a page past the end of the pool is empty but still says how much work is waiting")
        void pagePastTheEndStillReportsTheTotal() {
            referrals.returning(List.of(), 12);

            ReferralPool pool = service.pool(JUDGE, new CasePaging(9, 10));

            // A judge who bookmarked a page of a pool that has since been worked down gets an answer, not a
            // 404: nothing on this page, twelve in the pool, two pages -- which is where to look instead.
            assertThat(pool.isEmpty()).isTrue();
            assertThat(pool.total()).isEqualTo(12);
            assertThat(pool.totalPages()).isEqualTo(2);
            assertThat(pool.page()).isEqualTo(9);
        }

        @Test
        @DisplayName("the returned page cannot be modified through the caller's list")
        void theReturnedPageIsACopy() {
            referrals.returning(List.of(referral(ReferralState.Open, null)), 1);

            ReferralPool pool = service.pool(JUDGE, JudicialReferralService.FIRST_POOL_PAGE);

            assertThatThrownBy(() -> pool.referrals().add(new Referral()))
                .isInstanceOf(UnsupportedOperationException.class);
        }
    }

    /**
     * The personal list: the same three claims as the pool -- gated, sweeps first, and lets the database filter,
     * order and page -- plus the one the pool has no notion of, that whose list it is comes from the token.
     *
     * <p>What the <em>membership</em> is cannot be asserted here, because it is a predicate in SQL:
     * {@code ReferralRepositoryTest} runs it against a real Postgres, which is also the only thing that can say
     * that {@code Claimed} really sorts above {@code Decided} and that a null owner really matches nothing. What is
     * asserted here is the half that is about this class: the question it asks, that it asks it with the caller's
     * subject and never their display name, and that it does not touch the answer it gets back.
     */
    @Nested
    @DisplayName("mine")
    class Mine {

        @Test
        @DisplayName("refuses a caseworker, and refuses before sweeping or querying anything")
        void refusesACaseworker() {
            assertThatThrownBy(() -> service.mine(CASEWORKER, JudicialReferralService.FIRST_POOL_PAGE))
                .isInstanceOf(ForbiddenError.class)
                .hasMessageContaining(Role.JUDICIAL.claimValue());

            // The gate is the first statement, so a refused caller costs no query and cannot trigger the mutating
            // sweep -- the pool's rule, and it has to hold on the second read-write list too.
            verifyNoInteractions(referralService);
            referrals.verifyNothingRead();
        }

        @Test
        @DisplayName("refuses an admin too: judicial is not a level of seniority")
        void refusesAnAdmin() {
            assertThatThrownBy(() -> service.mine(ADMIN, JudicialReferralService.FIRST_POOL_PAGE))
                .isInstanceOf(ForbiddenError.class);

            verifyNoInteractions(referralService);
            referrals.verifyNothingRead();
        }

        @Test
        @DisplayName("refuses a judge with no subject before sweeping, because they own nothing to list")
        void refusesACallerWithNoSubject() {
            assertThatThrownBy(() -> service.mine(UNIDENTIFIED_JUDGE, JudicialReferralService.FIRST_POOL_PAGE))
                .isInstanceOf(UnauthorizedError.class);

            // Before the sweep, exactly as claim() refuses one: the sweep is the first thing this method writes,
            // and a caller on their way to a 401 must not be able to release somebody else's lapsed claim. Asking
            // for the subject first is also what stops "everybody's list" being expressible at all.
            verifyNoInteractions(referralService);
            referrals.verifyNothingRead();
        }

        @Test
        @DisplayName("sweeps lapsed claims before it queries, so a claim that lapsed is off this list already")
        void sweepsBeforeItQueries() {
            referrals.owning(List.of(), 0);

            service.mine(JUDGE, JudicialReferralService.FIRST_POOL_PAGE);

            // Order, not merely occurrence, and the reason is sharper here than for the pool: a stale claim left on
            // this page is one the judge would open, and opening it calls touch -- so the expiry window would be
            // revived by the very list that should have stopped showing it.
            InOrder order = inOrder(referralService, referrals.repository());
            order.verify(referralService).reclaimStaleClaims();
            order.verify(referrals.repository()).findOwnedBy(any(), any(), any(), any());
        }

        @Test
        @DisplayName("asks for this judge's own Claimed and Decided referrals, in the fixed order, one page")
        void asksForThisJudgesOwnWork() {
            referrals.owning(List.of(), 0);

            service.mine(JUDGE, new CasePaging(3, 20));

            ArgumentCaptor<Pageable> asked = ArgumentCaptor.forClass(Pageable.class);
            verify(referrals.repository()).findOwnedBy(
                eq(JUDGE.userId()), eq(ReferralState.Claimed), eq(ReferralState.Decided), asked.capture());
            Pageable pageable = asked.getValue();

            // Spelled out rather than compared against MINE_ORDER, which would assert the constant against itself.
            // Ascending state is Claimed before Decided because "ReferralState" is declared in lifecycle order;
            // ReferralRepositoryTest runs that, and the urgency and id keys, against a real database.
            assertThat(pageable.getSort()).containsExactly(
                Sort.Order.asc("state"),
                Sort.Order.desc("urgency"),
                Sort.Order.asc("createdOn"),
                Sort.Order.asc("id"));
            assertThat(pageable.getPageNumber()).isEqualTo(2);
            assertThat(pageable.getPageSize()).isEqualTo(20);
        }

        @Test
        @DisplayName("asks by the caller's subject and never by their display name")
        void asksBySubjectAndNeverByName() {
            referrals.owning(List.of(), 0);

            service.mine(JUDGE, JudicialReferralService.FIRST_POOL_PAGE);

            // The whole reason the ownership migration comes before this list rather than after it. Keyed on the
            // display name, "my referrals" would have listed a namesake's outstanding work -- an enumeration, where
            // the individual read at least needed the referral's id.
            verify(referrals.repository(), never()).findOwnedBy(eq(JUDGE.name()), any(), any(), any());
            verify(referrals.repository()).findOwnedBy(eq("judge-3"), any(), any(), any());
        }

        @Test
        @DisplayName("follows the subject through a rename, so a renamed judge keeps their own list")
        void followsTheSubjectThroughARename() {
            referrals.owning(List.of(), 0);

            // The other half of the defect V14 removed: a judge who married or took a title used to lose their own
            // claimed referrals. The list is asked for by the subject their claims were stamped with, which their
            // new display name has no bearing on.
            service.mine(RENAMED_JUDGE, JudicialReferralService.FIRST_POOL_PAGE);

            verify(referrals.repository()).findOwnedBy(eq(JUDGE.userId()), any(), any(), any());
        }

        @Test
        @DisplayName("returns the page in the order the database gave it, with the whole list's total")
        void returnsThePageAsTheDatabaseOrderedIt() {
            Referral claimed = referral(ReferralState.Claimed, JUDGE);
            Referral urgentDecided = referral(ReferralState.Decided, JUDGE);
            urgentDecided.setUrgency(Urgency.Urgent);
            Referral decided = referral(ReferralState.Decided, JUDGE);
            // Handed back in the order the query would have produced: claimed work first, and the urgent decided
            // one above the normal one. A service that re-sorted by urgency, or by state alone, would not leave
            // these three in this order.
            List<Referral> asOrdered = List.of(claimed, urgentDecided, decided);
            referrals.owning(asOrdered, 23);

            ReferralWorkload mine = service.mine(JUDGE, JudicialReferralService.FIRST_POOL_PAGE);

            assertThat(mine.referrals()).containsExactlyElementsOf(asOrdered);
            // The total is the list's and not the page's: twenty-three outstanding, ten shown, so three pages --
            // which is what tells a judge how much of their own work is behind the page they are looking at.
            assertThat(mine.total()).isEqualTo(23);
            assertThat(mine.totalPages()).isEqualTo(3);
            assertThat(mine.page()).isEqualTo(1);
            assertThat(mine.size()).isEqualTo(10);
            assertThat(mine.isEmpty()).isFalse();
        }

        @Test
        @DisplayName("filters nowhere but in the query, so the rows and the total describe the same result set")
        void filtersNowhereButInTheQuery() {
            // A row the membership rule excludes, handed back by the stub on purpose. The service returns it, which
            // is the point: there is no second filter in Java. This is not a claim about what the list contains --
            // that is the query's, asserted against a real Postgres -- it is a claim about where the filtering is.
            // A Java filter here would drop rows the total had already counted, so page 2 would omit work page 1
            // promised, and the rows it dropped would have been loaded for this judge anyway.
            Referral somebodyElses = referral(ReferralState.Claimed, OTHER_JUDGE);
            referrals.owning(List.of(somebodyElses), 1);

            ReferralWorkload mine = service.mine(JUDGE, JudicialReferralService.FIRST_POOL_PAGE);

            assertThat(mine.referrals()).containsExactly(somebodyElses);
            assertThat(mine.total()).isEqualTo(1);
        }

        @Test
        @DisplayName("a judge holding nothing gets a page with no rows and no pages, not an error")
        void holdingNothingIsAnEmptyPage() {
            referrals.owning(List.of(), 0);

            ReferralWorkload mine = service.mine(JUDGE, JudicialReferralService.FIRST_POOL_PAGE);

            assertThat(mine.isEmpty()).isTrue();
            assertThat(mine.totalPages()).isZero();
            assertThat(mine.total()).isZero();
        }

        @Test
        @DisplayName("a page past the end is empty but still says how much this judge has in hand")
        void pagePastTheEndStillReportsTheTotal() {
            referrals.owning(List.of(), 12);

            ReferralWorkload mine = service.mine(JUDGE, new CasePaging(9, 10));

            assertThat(mine.isEmpty()).isTrue();
            assertThat(mine.total()).isEqualTo(12);
            assertThat(mine.totalPages()).isEqualTo(2);
            assertThat(mine.page()).isEqualTo(9);
        }

        @Test
        @DisplayName("the returned page cannot be modified through the caller's list")
        void theReturnedPageIsACopy() {
            referrals.owning(List.of(referral(ReferralState.Claimed, JUDGE)), 1);

            ReferralWorkload mine = service.mine(JUDGE, JudicialReferralService.FIRST_POOL_PAGE);

            assertThatThrownBy(() -> mine.referrals().add(new Referral()))
                .isInstanceOf(UnsupportedOperationException.class);
        }
    }

    /**
     * Decision 3 again, one layer out: what the read does with a referral a judge may not see, and what it
     * refreshes when they may.
     *
     * <p>The 404s are asserted <em>by message</em> against the message an unknown id gets, not merely by type.
     * Indistinguishability is the property -- a withdrawn referral, another judge's, and one that never existed
     * have to answer identically or a referral id becomes discoverable by comparing refusals -- and a test that
     * only checked the exception class would pass against a message naming the state.
     */
    @Nested
    @DisplayName("referral")
    class ReadReferral {

        /** The message every refusal of this read has to give, whatever the reason for it. */
        private static final String NOT_FOUND = "Referral " + REFERRAL_ID + " not found";

        @Test
        @DisplayName("refuses a caseworker and an admin, before reading or touching anything")
        void refusesANonJudicialCaller() {
            for (CurrentUser caller : List.of(CASEWORKER, ADMIN)) {
                assertThatThrownBy(() -> service.referral(REFERRAL_ID.toString(), caller))
                    .isInstanceOf(ForbiddenError.class)
                    .hasMessageContaining(Role.JUDICIAL.claimValue());
            }

            // The gate is the first statement, so a caseworker learns nothing about whether the referral exists,
            // and their refused read cannot stamp a claim's inactivity clock.
            referrals.verifyNothingRead();
            verifyNoInteractions(referralService, documents);
        }

        @ParameterizedTest
        @EnumSource(value = ReferralState.class, names = {"Open", "Claimed", "Decided"})
        @DisplayName("returns the referral, its application and its readable documents for one this judge may see")
        void returnsAVisibleReferral(ReferralState state) {
            Referral referral = stored(state, JUDGE);
            DeedPollSupportingDocument document = new DeedPollSupportingDocument();
            when(documents.readableDocuments(CASE_ID)).thenReturn(List.of(document));

            JudicialReferralView view = service.referral(REFERRAL_ID.toString(), JUDGE);

            assertThat(view.referral()).isSameAs(referral);
            // Resolved from the referral rather than named by the caller, which is the whole of decision 2: a
            // judicial URL carries no case id, so this is the only way the application is reached.
            assertThat(view.application().getId()).isEqualTo(CASE_ID);
            assertThat(view.documents()).containsExactly(document);
        }

        @Test
        @DisplayName("returns a private case's readable documents, as for any other case")
        void returnsThePrivateCaseInFull() {
            Referral referral = stored(ReferralState.Open, JUDGE);
            referral.getDeedPoll().setPrivacy(Privacy.Private);
            DeedPollSupportingDocument document = new DeedPollSupportingDocument();
            when(documents.readableDocuments(CASE_ID)).thenReturn(List.of(document));

            JudicialReferralView view = service.referral(REFERRAL_ID.toString(), JUDGE);

            // Privacy restricts no read here, so a private case is the same view as any other.
            assertThat(view.application().getPrivacy()).isEqualTo(Privacy.Private);
            // The readable documents and not every row: which rows those are is DocumentService's rule, the
            // same one its download applies, so a judge is never listed a document their download would 404.
            assertThat(view.documents()).containsExactly(document);
            verify(documents, never()).findDocuments(any());
        }

        @ParameterizedTest
        @EnumSource(value = ReferralState.class, names = {"Closed", "Withdrawn"})
        @DisplayName("404s a terminal referral even for the judge who directed it, and reads no documents")
        void notFoundForATerminalReferral(ReferralState terminal) {
            stored(terminal, JUDGE);

            assertThatThrownBy(() -> service.referral(REFERRAL_ID.toString(), JUDGE))
                .isInstanceOf(NotFoundError.class)
                .hasMessage(NOT_FOUND);

            // The refusal comes before the case is reached at all, so an invisible referral does not even cause
            // its application's documents to be loaded.
            verifyNoInteractions(documents);
            verifyNoInteractions(referralService);
        }

        @ParameterizedTest
        @EnumSource(value = ReferralState.class, names = {"Claimed", "Decided"})
        @DisplayName("404s another judge's referral in the same words as an id nothing was stored under")
        void notFoundForAnotherJudgesReferral(ReferralState held) {
            stored(held, JUDGE);

            assertThatThrownBy(() -> service.referral(REFERRAL_ID.toString(), OTHER_JUDGE))
                .isInstanceOf(NotFoundError.class)
                .hasMessage(NOT_FOUND);

            // And the comparison that makes it a property rather than a coincidence: the same read of an id that
            // never existed says exactly the same thing, so one judge cannot learn that another holds a case.
            assertThatThrownBy(() -> service.referral(UNKNOWN_ID.toString(), OTHER_JUDGE))
                .isInstanceOf(NotFoundError.class)
                .hasMessage("Referral " + UNKNOWN_ID + " not found");
        }

        @Test
        @DisplayName("404s an id that is not a UUID, without asking the database about it")
        void notFoundForAnIdThatIsNotAUuid() {
            assertThatThrownBy(() -> service.referral("not-a-uuid", JUDGE))
                .isInstanceOf(NotFoundError.class)
                .hasMessage("Referral not-a-uuid not found");

            // Nothing could ever have been stored under it, so it is the same 404 rather than a 400 -- and the
            // repository is never asked, because there is no id to ask about.
            referrals.verifyNothingRead();
        }

        @Test
        @DisplayName("refreshes the claim when this judge is holding the referral")
        void refreshesTheClaimOfTheHoldingJudge() {
            stored(ReferralState.Claimed, JUDGE);

            service.referral(REFERRAL_ID.toString(), JUDGE);

            // Decision 7: a judge reading the papers of a referral they claimed is working it, so the inactivity
            // clock restarts. Through the api-2 transition rather than by stamping the column here, so there is
            // one place that owns what a touch is -- and it is given the case resolved from the referral.
            verify(referralService).touch(CASE_ID, REFERRAL_ID.toString(), JUDGE);
        }

        @Test
        @DisplayName("touches nothing when the referral is in the pool")
        void touchesNothingOnAPoolReferral() {
            stored(ReferralState.Open, JUDGE);

            service.referral(REFERRAL_ID.toString(), JUDGE);

            // A pool referral is visible to every judge, so a read that stamped it would let any judge move a row
            // they have not claimed -- and would make looking indistinguishable from working.
            verifyNoInteractions(referralService);
        }

        @Test
        @DisplayName("touches nothing on this judge's own decided referral, which has no claim left to refresh")
        void touchesNothingOnADecidedReferral() {
            stored(ReferralState.Decided, JUDGE);

            service.referral(REFERRAL_ID.toString(), JUDGE);

            // Visible to them and not held by them: ReferralService.touch demands state Claimed, so calling it
            // here would turn a perfectly legitimate read into a 409. Held is narrower than visible.
            verifyNoInteractions(referralService);
        }

        @Test
        @DisplayName("touches nothing on a Claimed referral with no holder recorded")
        void touchesNothingOnAnUnheldClaim() {
            Referral unheld = stored(ReferralState.Claimed, null);

            // Invisible to everybody, so this is a 404 first -- but the reason it is worth its own test is the
            // null: comparing the caller's name against the column rather than the other way round is what makes
            // both the visibility check and the held check false instead of throwing.
            assertThatThrownBy(() -> service.referral(REFERRAL_ID.toString(), JUDGE))
                .isInstanceOf(NotFoundError.class);

            assertThat(unheld.getClaimedBy()).isNull();
            verifyNoInteractions(referralService, documents);
        }
    }

    /**
     * The two writes, which are the visibility check in front of an api-2 transition and nothing else.
     *
     * <p>What is asserted here is exactly that: that the gate and the 404 come first, that the transition is
     * called with the case <em>resolved from the referral</em> rather than with anything a caller supplied, and
     * that nothing about a state change is decided in this service. {@code ReferralService} is a mock, so a test
     * that passes while this class quietly guarded {@code Open} itself would be asserting the duplicate rather
     * than the delegation -- which is why the conflicts are asserted as the mock's exception travelling out
     * untouched.
     */
    @Nested
    @DisplayName("claim")
    class Claim {

        @Test
        @DisplayName("refuses a caseworker and an admin, before sweeping, resolving or claiming anything")
        void refusesANonJudicialCaller() {
            for (CurrentUser caller : List.of(CASEWORKER, ADMIN)) {
                assertThatThrownBy(() -> service.claim(REFERRAL_ID.toString(), caller))
                    .isInstanceOf(ForbiddenError.class)
                    .hasMessageContaining(Role.JUDICIAL.claimValue());
            }

            // The gate is the first statement, so a refused caller cannot trigger the mutating sweep -- which
            // writes notes and moves other judges' claims -- by being refused often enough.
            referrals.verifyNothingRead();
            verifyNoInteractions(referralService, documents);
        }

        @Test
        @DisplayName("refuses a judge with no subject before sweeping, because a claim needs an owner")
        void refusesACallerWithNoSubject() {
            stored(ReferralState.Open, null);

            // 401, and before the sweep: a caller IDAM has not identified has nothing to own the claim with, so
            // they must not be able to release another judge's lapsed claim on their way to being refused. The
            // transition refuses them too -- this is the same rule asked one step earlier, where the write is.
            assertThatThrownBy(() -> service.claim(REFERRAL_ID.toString(), UNIDENTIFIED_JUDGE))
                .isInstanceOf(UnauthorizedError.class);

            verify(referralService, never()).reclaimStaleClaims();
            verify(referralService, never()).claim(any(), any(), any());
            verifyNoInteractions(documents);
        }

        @Test
        @DisplayName("sweeps before it resolves, so a claim that has just lapsed is claimable rather than a 404")
        void sweepsBeforeItResolves() {
            stored(ReferralState.Open, null);

            service.claim(REFERRAL_ID.toString(), JUDGE);

            // Order, and it is the whole reason the sweep is here at all: a referral whose claim lapsed a moment
            // ago is still Claimed by the judge who abandoned it, which visibleTo says this caller may not see. So
            // resolving first would answer 404 for a referral the pool is about to offer them.
            InOrder order = inOrder(referralService, referrals.repository());
            order.verify(referralService).reclaimStaleClaims();
            order.verify(referrals.repository()).findWithCaseById(REFERRAL_ID);
            order.verify(referralService).claim(CASE_ID, REFERRAL_ID.toString(), JUDGE);
        }

        @Test
        @DisplayName("hands the transition the case resolved from the referral, and answers with the papers")
        void delegatesToTheTransition() {
            Referral pooled = stored(ReferralState.Open, null);
            Referral claimed = referral(ReferralState.Claimed, JUDGE);
            when(referralService.claim(CASE_ID, REFERRAL_ID.toString(), JUDGE)).thenReturn(claimed);
            DeedPollSupportingDocument document = new DeedPollSupportingDocument();
            when(documents.readableDocuments(CASE_ID)).thenReturn(List.of(document));

            JudicialReferralView view = service.claim(REFERRAL_ID.toString(), JUDGE);

            // The case id is never a parameter of the judicial surface, so this is the one place it can come from.
            verify(referralService).claim(CASE_ID, REFERRAL_ID.toString(), JUDGE);
            // The transition's answer, not the row that was resolved: the state a judge is shown has to be the
            // post-transition one, and returning the pre-claim referral would show them Open.
            assertThat(view.referral()).isSameAs(claimed).isNotSameAs(pooled);
            assertThat(view.application().getId()).isEqualTo(CASE_ID);
            // The same things a read answers with, because the screen after a claim is the papers.
            assertThat(view.documents()).containsExactly(document);
        }

        @ParameterizedTest
        @EnumSource(value = ReferralState.class, names = {"Closed", "Withdrawn"})
        @DisplayName("404s a terminal referral without claiming it")
        void notFoundForATerminalReferral(ReferralState terminal) {
            stored(terminal, JUDGE);

            assertThatThrownBy(() -> service.claim(REFERRAL_ID.toString(), JUDGE))
                .isInstanceOf(NotFoundError.class)
                .hasMessage("Referral " + REFERRAL_ID + " not found");

            verify(referralService, never()).claim(any(), any(), any());
        }

        @ParameterizedTest
        @EnumSource(value = ReferralState.class, names = {"Claimed", "Decided"})
        @DisplayName("404s a referral another judge holds, in the words an unknown id gets, and claims nothing")
        void notFoundForAnotherJudgesReferral(ReferralState held) {
            stored(held, JUDGE);

            // Indistinguishable from an id that never existed: a judge who guesses at ids must not be able to tell
            // "somebody else has this" from "there is no such referral", or the pool becomes a list of what other
            // judges are working on.
            assertThatThrownBy(() -> service.claim(REFERRAL_ID.toString(), OTHER_JUDGE))
                .isInstanceOf(NotFoundError.class)
                .hasMessage("Referral " + REFERRAL_ID + " not found");
            assertThatThrownBy(() -> service.claim(UNKNOWN_ID.toString(), OTHER_JUDGE))
                .isInstanceOf(NotFoundError.class)
                .hasMessage("Referral " + UNKNOWN_ID + " not found");

            verify(referralService, never()).claim(any(), any(), any());
        }

        @Test
        @DisplayName("404s an id that is not a UUID, without asking the database about it")
        void notFoundForAnIdThatIsNotAUuid() {
            assertThatThrownBy(() -> service.claim("not-a-uuid", JUDGE))
                .isInstanceOf(NotFoundError.class)
                .hasMessage("Referral not-a-uuid not found");

            verify(referrals.repository(), never()).findWithCaseById(any());
        }

        @Test
        @DisplayName("lets the transition's conflict out untouched, rather than deciding claimability itself")
        void passesTheTransitionsConflictThrough() {
            // A referral this judge may see and may not claim -- their own Decided one, or an Open one another
            // judge took between their loading the pool and their clicking. ReferralService owns that rule, and the
            // point of this test is that nothing here duplicates it: a 409 from the transition is the answer, where
            // a second guard in this service would eventually disagree with the first.
            stored(ReferralState.Decided, JUDGE);
            when(referralService.claim(CASE_ID, REFERRAL_ID.toString(), JUDGE))
                .thenThrow(new ConflictError("Referral " + REFERRAL_ID + " is Decided"));

            assertThatThrownBy(() -> service.claim(REFERRAL_ID.toString(), JUDGE))
                .isInstanceOf(ConflictError.class)
                .hasMessageContaining("Decided");
        }
    }

    @Nested
    @DisplayName("direct")
    class Direct {

        private static final DirectionInput APPROVAL =
            new DirectionInput(MasterDecision.Approved, Optional.of("Granted"), true);

        @Test
        @DisplayName("refuses a caseworker and an admin, before resolving or recording anything")
        void refusesANonJudicialCaller() {
            for (CurrentUser caller : List.of(CASEWORKER, ADMIN)) {
                assertThatThrownBy(() -> service.direct(REFERRAL_ID.toString(), APPROVAL, caller))
                    .isInstanceOf(ForbiddenError.class)
                    .hasMessageContaining(Role.JUDICIAL.claimValue());
            }

            referrals.verifyNothingRead();
            verifyNoInteractions(referralService, documents);
        }

        @Test
        @DisplayName("hands the transition the direction and the case resolved from the referral")
        void delegatesToTheTransition() {
            stored(ReferralState.Claimed, JUDGE);
            Referral decided = referral(ReferralState.Decided, JUDGE);
            when(referralService.decide(CASE_ID, REFERRAL_ID.toString(), APPROVAL, JUDGE)).thenReturn(decided);

            JudicialReferralView view = service.direct(REFERRAL_ID.toString(), APPROVAL, JUDGE);

            // The direction travels unaltered: decidedBy and decisionDate are the transition's to set from the
            // token and the clock, and there is nothing for this service to add to what the schema validated.
            verify(referralService).decide(CASE_ID, REFERRAL_ID.toString(), APPROVAL, JUDGE);
            assertThat(view.referral()).isSameAs(decided);
        }

        @Test
        @DisplayName("does not sweep, so this request cannot take the referral off the judge answering it")
        void doesNotSweep() {
            stored(ReferralState.Claimed, JUDGE);

            service.direct(REFERRAL_ID.toString(), APPROVAL, JUDGE);

            // The deliberate difference from claim. A judge submitting a direction was working the referral, and a
            // sweep here would release it between their reading the papers and their answering. Their claim may
            // still lapse by another judge's pool load, and that is then the transition's 409 -- but not this
            // request's doing.
            verify(referralService, never()).reclaimStaleClaims();
        }

        @ParameterizedTest
        @EnumSource(value = ReferralState.class, names = {"Closed", "Withdrawn"})
        @DisplayName("404s a terminal referral without recording a direction")
        void notFoundForATerminalReferral(ReferralState terminal) {
            stored(terminal, JUDGE);

            assertThatThrownBy(() -> service.direct(REFERRAL_ID.toString(), APPROVAL, JUDGE))
                .isInstanceOf(NotFoundError.class)
                .hasMessage("Referral " + REFERRAL_ID + " not found");

            verify(referralService, never()).decide(any(), any(), any(), any());
        }

        @Test
        @DisplayName("404s a referral another judge holds, so one judge cannot direct another's case")
        void notFoundForAnotherJudgesClaim() {
            stored(ReferralState.Claimed, JUDGE);

            // The visibility rule doing the work the case-scoping does on the caseworker surface: this is a 404
            // rather than the 409 the holder check would give, because the referral is not this judge's to be told
            // anything about.
            assertThatThrownBy(() -> service.direct(REFERRAL_ID.toString(), APPROVAL, OTHER_JUDGE))
                .isInstanceOf(NotFoundError.class)
                .hasMessage("Referral " + REFERRAL_ID + " not found");

            verify(referralService, never()).decide(any(), any(), any(), any());
        }

        @Test
        @DisplayName("lets the transition's conflict out untouched for a pool referral nobody holds")
        void passesTheTransitionsConflictThrough() {
            // Visible and not held: an Open referral is visible to every judge, and directing one nobody has
            // claimed is the transition's conflict rather than this service's -- so a judge who skipped the claim
            // is told plainly instead of writing a direction onto an unclaimed referral.
            stored(ReferralState.Open, null);
            when(referralService.decide(CASE_ID, REFERRAL_ID.toString(), APPROVAL, JUDGE))
                .thenThrow(new ConflictError("Referral " + REFERRAL_ID + " is not claimed by " + JUDGE.name()));

            assertThatThrownBy(() -> service.direct(REFERRAL_ID.toString(), APPROVAL, JUDGE))
                .isInstanceOf(ConflictError.class)
                .hasMessageContaining("not claimed by");
        }
    }

    @Nested
    @DisplayName("revise")
    class Revise {

        private static final DirectionInput REFUSAL =
            new DirectionInput(MasterDecision.Rejected, Optional.of("Refused on reflection"), false);

        @Test
        @DisplayName("refuses a caseworker and an admin, before resolving or recording anything")
        void refusesANonJudicialCaller() {
            for (CurrentUser caller : List.of(CASEWORKER, ADMIN)) {
                assertThatThrownBy(() -> service.revise(REFERRAL_ID.toString(), REFUSAL, caller))
                    .isInstanceOf(ForbiddenError.class)
                    .hasMessageContaining(Role.JUDICIAL.claimValue());
            }

            referrals.verifyNothingRead();
            verifyNoInteractions(referralService, documents);
        }

        @Test
        @DisplayName("hands the transition the direction and the case resolved from the referral")
        void delegatesToTheTransition() {
            stored(ReferralState.Decided, JUDGE);
            Referral revised = referral(ReferralState.Decided, JUDGE);
            when(referralService.reviseDirection(CASE_ID, REFERRAL_ID.toString(), REFUSAL, JUDGE))
                .thenReturn(revised);

            JudicialReferralView view = service.revise(REFERRAL_ID.toString(), REFUSAL, JUDGE);

            verify(referralService).reviseDirection(CASE_ID, REFERRAL_ID.toString(), REFUSAL, JUDGE);
            assertThat(view.referral()).isSameAs(revised);
            // A decided referral has no claim, so nothing here may sweep or touch one.
            verify(referralService, never()).reclaimStaleClaims();
            verify(referralService, never()).touch(any(), any(), any());
        }

        @ParameterizedTest
        @EnumSource(value = ReferralState.class, names = {"Closed", "Withdrawn"})
        @DisplayName("404s a referral the office has finished with, without revising anything")
        void notFoundForATerminalReferral(ReferralState terminal) {
            stored(terminal, JUDGE);

            assertThatThrownBy(() -> service.revise(REFERRAL_ID.toString(), REFUSAL, JUDGE))
                .isInstanceOf(NotFoundError.class)
                .hasMessage("Referral " + REFERRAL_ID + " not found");

            verify(referralService, never()).reviseDirection(any(), any(), any(), any());
        }

        @Test
        @DisplayName("404s a direction another judge gave, in the words an unknown id gets")
        void notFoundForAnotherJudgesDirection() {
            stored(ReferralState.Decided, JUDGE);

            assertThatThrownBy(() -> service.revise(REFERRAL_ID.toString(), REFUSAL, OTHER_JUDGE))
                .isInstanceOf(NotFoundError.class)
                .hasMessage("Referral " + REFERRAL_ID + " not found");

            verify(referralService, never()).reviseDirection(any(), any(), any(), any());
        }

        @Test
        @DisplayName("refuses a judge with no subject, rather than 404ing or 409ing them, and revises nothing")
        void refusesACallerWithNoSubject() {
            stored(ReferralState.Decided, JUDGE);

            assertThatThrownBy(() -> service.revise(REFERRAL_ID.toString(), REFUSAL, UNIDENTIFIED_JUDGE))
                .isInstanceOf(UnauthorizedError.class);

            verify(referralService, never()).reviseDirection(any(), any(), any(), any());
        }
    }

    @Nested
    @DisplayName("withdraw")
    class Withdraw {

        @Test
        @DisplayName("refuses a caseworker and an admin, before resolving or recording anything")
        void refusesANonJudicialCaller() {
            for (CurrentUser caller : List.of(CASEWORKER, ADMIN)) {
                assertThatThrownBy(() -> service.withdraw(REFERRAL_ID.toString(), caller))
                    .isInstanceOf(ForbiddenError.class)
                    .hasMessageContaining(Role.JUDICIAL.claimValue());
            }

            referrals.verifyNothingRead();
            verifyNoInteractions(referralService, documents);
        }

        @Test
        @DisplayName("hands the transition the case resolved from the referral")
        void delegatesToTheTransition() {
            stored(ReferralState.Decided, JUDGE);
            Referral claimed = referral(ReferralState.Claimed, JUDGE);
            when(referralService.withdrawDirection(CASE_ID, REFERRAL_ID.toString(), JUDGE)).thenReturn(claimed);

            JudicialReferralView view = service.withdraw(REFERRAL_ID.toString(), JUDGE);

            verify(referralService).withdrawDirection(CASE_ID, REFERRAL_ID.toString(), JUDGE);
            assertThat(view.referral()).isSameAs(claimed);
            verify(referralService, never()).reclaimStaleClaims();
            verify(referralService, never()).touch(any(), any(), any());
        }

        @ParameterizedTest
        @EnumSource(value = ReferralState.class, names = {"Closed", "Withdrawn"})
        @DisplayName("404s a referral the office has finished with, without withdrawing anything")
        void notFoundForATerminalReferral(ReferralState terminal) {
            stored(terminal, JUDGE);

            assertThatThrownBy(() -> service.withdraw(REFERRAL_ID.toString(), JUDGE))
                .isInstanceOf(NotFoundError.class)
                .hasMessage("Referral " + REFERRAL_ID + " not found");

            verify(referralService, never()).withdrawDirection(any(), any(), any());
        }

        @Test
        @DisplayName("404s a direction another judge gave, in the words an unknown id gets")
        void notFoundForAnotherJudgesDirection() {
            stored(ReferralState.Decided, JUDGE);

            assertThatThrownBy(() -> service.withdraw(REFERRAL_ID.toString(), OTHER_JUDGE))
                .isInstanceOf(NotFoundError.class)
                .hasMessage("Referral " + REFERRAL_ID + " not found");

            verify(referralService, never()).withdrawDirection(any(), any(), any());
        }

        @Test
        @DisplayName("refuses a judge with no subject, rather than 404ing or 409ing them, and withdraws nothing")
        void refusesACallerWithNoSubject() {
            stored(ReferralState.Decided, JUDGE);

            assertThatThrownBy(() -> service.withdraw(REFERRAL_ID.toString(), UNIDENTIFIED_JUDGE))
                .isInstanceOf(UnauthorizedError.class);

            verify(referralService, never()).withdrawDirection(any(), any(), any());
        }

        @Test
        @DisplayName("refuses a judge with no subject a document of a claimed referral, and opens nothing")
        void refusesADocumentToACallerWithNoSubject() {
            stored(ReferralState.Claimed, JUDGE);

            assertThatThrownBy(() -> service.document(REFERRAL_ID.toString(), "document-1", UNIDENTIFIED_JUDGE))
                .isInstanceOf(UnauthorizedError.class);

            verifyNoInteractions(documents);
        }
    }

    /**
     * A referral of {@link #CASE_ID} in the given state, stored where the judicial read can find it.
     *
     * <p>Through {@code findWithCaseById}, which is the finder by referral id alone that only this service may
     * use: the fixture mirrors the one read the judicial surface has, rather than a case-scoped one it does not.
     */
    private Referral stored(ReferralState state, CurrentUser holder) {
        DeedPoll parent = new DeedPoll();
        parent.setId(CASE_ID);
        Referral referral = referral(state, holder);
        referral.setId(REFERRAL_ID);
        referral.setDeedPoll(parent);
        referrals.storing(referral);
        return referral;
    }

    /**
     * A referral in the given state, claimed and decided by the given judge where those columns apply.
     *
     * <p>All four owner columns are set for every state rather than only the ones that would really carry them,
     * because the question these tests ask is what the <em>state</em> admits: a {@code Withdrawn} referral that
     * still names the judge who had it is exactly the row a visibility rule reading the owner first would leak.
     *
     * <p>The subjects and the display names both, as a real claim and a real direction write both -- so the two
     * tests that matter are the ones where they disagree: a namesake of the holder, and the holder after a
     * rename.
     */
    private static Referral referral(ReferralState state, CurrentUser holder) {
        Referral referral = new Referral();
        referral.setState(state);
        referral.setCreatedOn(LocalDateTime.of(2026, 5, 1, 9, 0));
        if (holder != null) {
            referral.setClaimedById(holder.userId());
            referral.setClaimedBy(holder.name());
            referral.setDecidedById(holder.userId());
            referral.setDecidedBy(holder.name());
        }
        return referral;
    }

    /**
     * The referral repository, answering either list's query with a page the test chose.
     *
     * <p>A small wrapper rather than a bare mock so that "nothing was read" is one assertion: the refusal test
     * has to say that a gated-out caller reached neither the sweep nor the query, and
     * {@code verifyNoInteractions} on a repository that the service also never touches otherwise is the
     * strongest form of that.
     */
    private static final class ReferralRepositoryStub {

        private final ReferralRepository repository = mock(ReferralRepository.class);

        ReferralRepository repository() {
            return repository;
        }

        /**
         * The pool holds {@code total} referrals, of which these are the ones on the page asked for.
         */
        void returning(List<Referral> page, long total) {
            when(repository.findByState(any(), any())).thenAnswer(call -> {
                Pageable asked = call.getArgument(1);
                return page(page, asked, total);
            });
        }

        /**
         * The caller's personal list holds {@code total} referrals, of which these are the ones on the page asked
         * for.
         *
         * <p>Answers whatever owner and states it is asked about, deliberately: the predicate belongs to the query
         * and is asserted against a real Postgres, so a stub that applied it here would be asserting a second
         * implementation of it -- and would hide a service that filtered in Java.
         */
        void owning(List<Referral> page, long total) {
            when(repository.findOwnedBy(any(), any(), any(), any())).thenAnswer(call -> {
                Pageable asked = call.getArgument(3);
                return page(page, asked, total);
            });
        }

        /**
         * This referral is the only one stored, reachable by its own id and by no other.
         */
        void storing(Referral referral) {
            when(repository.findWithCaseById(referral.getId())).thenReturn(Optional.of(referral));
        }

        void verifyNothingRead() {
            verifyNoInteractions(repository);
        }

        private static Page<Referral> page(List<Referral> rows, Pageable asked, long total) {
            return new PageImpl<>(rows, PageRequest.of(asked.getPageNumber(), asked.getPageSize()), total);
        }
    }
}
