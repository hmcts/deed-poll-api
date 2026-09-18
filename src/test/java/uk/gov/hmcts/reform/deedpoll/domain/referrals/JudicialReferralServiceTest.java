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
import uk.gov.hmcts.reform.deedpoll.domain.ForbiddenError;
import uk.gov.hmcts.reform.deedpoll.domain.NotFoundError;
import uk.gov.hmcts.reform.deedpoll.domain.auth.CurrentUser;
import uk.gov.hmcts.reform.deedpoll.domain.auth.Role;
import uk.gov.hmcts.reform.deedpoll.domain.deedpoll.CasePaging;
import uk.gov.hmcts.reform.deedpoll.domain.notes.NoteService;
import uk.gov.hmcts.reform.deedpoll.entity.DeedPoll;
import uk.gov.hmcts.reform.deedpoll.entity.DeedPollNote;
import uk.gov.hmcts.reform.deedpoll.entity.Referral;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The judicial boundary, with no database and no HTTP: who may see which referral, what the shared pool asks the
 * database for, and what the read of one referral refuses, resolves and refreshes.
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
    private NoteService notes;
    private JudicialReferralService service;

    @BeforeEach
    void createService() {
        referrals = new ReferralRepositoryStub();
        referralService = mock(ReferralService.class);
        notes = mock(NoteService.class);
        service = new JudicialReferralService(referrals.repository(), referralService, notes);
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
            // with a null claimedBy is a shape that really exists. Comparing the caller's name to the column
            // rather than the other way round is what makes this false instead of an exception.
            Referral unheld = new Referral();
            unheld.setState(ReferralState.Claimed);

            assertThat(JudicialReferralService.visibleTo(unheld, JUDGE)).isFalse();
            assertThat(JudicialReferralService.visibleTo(unheld, OTHER_JUDGE)).isFalse();
        }

        @Test
        @DisplayName("a decided referral is visible to whoever decided it, not to whoever claimed it")
        void decidedReferralFollowsTheDecidingJudge() {
            // The two columns are separate and this asks about the right one. They agree in practice -- only
            // the holder can decide -- but a referral claimed by one judge and decided by another is the shape
            // a future hand-off would produce, and reading claimedBy here would then show it to the wrong one.
            Referral handedOver = referral(ReferralState.Decided, JUDGE);
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
            verifyNoInteractions(referralService, notes);
        }

        @ParameterizedTest
        @EnumSource(value = ReferralState.class, names = {"Open", "Claimed", "Decided"})
        @DisplayName("returns the referral, its application and its notes for one this judge may see")
        void returnsAVisibleReferral(ReferralState state) {
            Referral referral = stored(state, JUDGE);
            DeedPollNote note = new DeedPollNote();
            note.setNote("Fee taken, papers scanned");
            when(notes.findNotes(CASE_ID)).thenReturn(List.of(note));

            JudicialReferralView view = service.referral(REFERRAL_ID.toString(), JUDGE);

            assertThat(view.referral()).isSameAs(referral);
            // Resolved from the referral rather than named by the caller, which is the whole of decision 2: a
            // judicial URL carries no case id, so this is the only way the application is reached.
            assertThat(view.application().getId()).isEqualTo(CASE_ID);
            assertThat(view.caseNotes()).containsExactly(note);
            verify(notes).findNotes(CASE_ID);
        }

        @ParameterizedTest
        @EnumSource(value = ReferralState.class, names = {"Closed", "Withdrawn"})
        @DisplayName("404s a terminal referral even for the judge who directed it, and reads no notes")
        void notFoundForATerminalReferral(ReferralState terminal) {
            stored(terminal, JUDGE);

            assertThatThrownBy(() -> service.referral(REFERRAL_ID.toString(), JUDGE))
                .isInstanceOf(NotFoundError.class)
                .hasMessage(NOT_FOUND);

            // The refusal comes before the case is reached at all, so an invisible referral does not even cause
            // its application's timeline to be loaded.
            verifyNoInteractions(notes);
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
            verifyNoInteractions(referralService, notes);
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
     * <p>Both columns are set for every state rather than only the ones that would really carry them, because
     * the question these tests ask is what the <em>state</em> admits: a {@code Withdrawn} referral that still
     * names the judge who had it is exactly the row a visibility rule reading the name first would leak.
     */
    private static Referral referral(ReferralState state, CurrentUser holder) {
        Referral referral = new Referral();
        referral.setState(state);
        referral.setCreatedOn(LocalDateTime.of(2026, 5, 1, 9, 0));
        if (holder != null) {
            referral.setClaimedBy(holder.name());
            referral.setDecidedBy(holder.name());
        }
        return referral;
    }

    /**
     * The referral repository, answering the pool query with a page the test chose.
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
