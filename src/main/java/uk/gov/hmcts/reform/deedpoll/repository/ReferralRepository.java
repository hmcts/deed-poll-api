package uk.gov.hmcts.reform.deedpoll.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import uk.gov.hmcts.reform.deedpoll.entity.Referral;
import uk.gov.hmcts.reform.deedpoll.entity.enums.ReferralState;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Referrals, always scoped to their parent case.
 *
 * <p>Every finder carries {@code deedPollId}, which is the same rule {@link DeedPollNoteRepository}
 * states and for the same reason: with the scope left to the caller, cross-case safety would be a
 * convention one line long that a later method could omit. {@code findById} exists on
 * {@link JpaRepository} and nothing may use it -- <strong>do not add a finder by referral id alone</strong>.
 * A caller wanting one referral has to say which case it belongs to, so a cross-case read or write is
 * not expressible rather than merely discouraged.
 *
 * <p>There are four exceptions, and every one of them belongs to the judicial surface or to the sweep, which
 * are the two things in this service that are about every case at once rather than about <em>a</em> case's
 * referrals:
 *
 * <ul>
 *   <li>the inactivity sweep -- {@link #findStaleClaims} and {@link #releaseStaleClaim}. It stays inside the
 *       rule rather than beside it: the finder returns a three-field projection instead of an entity, and
 *       neither is reachable from a request path that names a case;</li>
 *   <li>the judicial pool -- {@link #findByState}. The shared pool <em>is</em> the cross-case list, and a
 *       judge reaches it without naming any case, which is the point of it: the judicial surface is keyed on
 *       the referral so that a judge cannot name an application at all. The scoping this drops is replaced by
 *       a state filter the caller does not choose ({@code Open} only) and, on every read of an individual
 *       referral, by {@code JudicialReferralService.visibleTo};</li>
 *   <li>one judge's own work -- {@link #findOwnedBy}. The personal list is the other cross-case judicial list,
 *       and it names no case for the same reason the pool does not. What replaces the scoping here is narrower
 *       than the pool's: the rows are those whose owner column holds the caller's own IDAM subject, which is a
 *       scope a caller cannot widen because it comes from their token rather than from the request;</li>
 *   <li>the judicial read of one referral -- {@link #findWithCaseById}. This is the exception that is a finder
 *       by referral id alone, which the paragraph above forbids, and it exists because the judicial surface
 *       carries no case id to scope with: a judicial URL is {@code /judicial/referrals/{referralId}} precisely
 *       so that a judge cannot name an application. <strong>The safety boundary has moved rather than
 *       gone</strong> -- {@code JudicialReferralService.visibleTo} is what stands where the parent id stands on
 *       the caseworker surface -- and {@code ReferralSecurityTest} enforces that by asserting nothing but
 *       {@code JudicialReferralService} calls it.</li>
 * </ul>
 *
 * <p>This differs from {@link SavedQueryRepository}, which does read by id: a saved query's visibility
 * is a property of the entity, so it cannot be checked before the entity is loaded. A referral's scope
 * is its parent, which the caller already named in the URL.
 *
 * <p>The list carries its own sort. The referrals tab shows history newest first -- the current open
 * referral above the ones that finished -- and V6 indexes {@code "deedPollId"} to serve it.
 */
public interface ReferralRepository extends JpaRepository<Referral, UUID> {

    /** Every referral on one case, newest first. */
    List<Referral> findByDeedPollIdOrderByCreatedOnDesc(String deedPollId);

    /** One referral, only if it belongs to the given case. */
    Optional<Referral> findByIdAndDeedPollId(UUID id, String deedPollId);

    /**
     * One page of the referrals in a given state, across every case -- the judicial pool.
     *
     * <p><strong>The order and the page are the database's, and that is the requirement rather than an
     * implementation detail.</strong> The pool is worked urgent-first and oldest-first within each urgency
     * band, so a caller that fetched and reordered would be sorting one page of an arbitrary slice -- the
     * longest-waiting urgent referral would sit on page 4 and never be worked.
     * {@code JudicialReferralService.POOL_ORDER} is the sort, and it relies on {@code "Urgency"} being declared
     * {@code ('Normal', 'Urgent')} in {@code V1__enums.sql}: Postgres orders an enum by its declared order, so
     * descending urgency is the most urgent first. {@code ReferralRepositoryTest} asserts that against a real
     * Postgres, because the declaration order is the load-bearing part and no fake can stand in for it.
     *
     * <p>{@link org.springframework.data.domain.Page} rather than a list, because a pool without its total is
     * a pool that cannot say how much work is waiting -- which is the number decision 15 is about, and the
     * reason the page size is a page size and not a cap. The count is the same criterion the page was drawn
     * from, so it comes from the same call.
     *
     * <p>No index backs this: the table is small, the plan that added the pool adds no migration, and an index
     * on {@code "state"} alone would be nearly the whole table anyway. Adding one when the pool is slow is a
     * migration, not a redesign.
     *
     * @param state the state a pooled referral is in, {@link ReferralState#Open}
     * @param pageable the page and the order, which the caller supplies rather than this method naming
     * @return the referrals on that page, with the total across every page
     */
    Page<Referral> findByState(ReferralState state, Pageable pageable);

    /**
     * One page of the referrals a single judge has in hand -- the personal list behind
     * {@code GET /judicial/referrals/mine}.
     *
     * <p><strong>Two arms rather than one, because the two states record their owner in different
     * columns.</strong> A claimed referral belongs to whoever holds it ({@code claimedById}) and a decided one to
     * whoever directed it ({@code decidedById}); they agree in practice, because only the holder may direct, but
     * they are separate columns and a hand-off would make them differ. So the predicate asks each state about its
     * own column instead of either one about both -- which is also what keeps this and
     * {@code JudicialReferralService.visibleTo} the same rule, so the list cannot offer a referral whose link then
     * answers 404.
     *
     * <p><strong>No other state appears, and that is the membership rule rather than a filter.</strong>
     * {@code Open} belongs to the shared pool, and {@code Closed}/{@code Withdrawn} are the office taking the
     * referral back -- so a terminal row that still names the judge who had it is excluded by the state arms,
     * whatever its owner columns still hold.
     *
     * <p><strong>A null owner matches nothing, which is the fail-closed half.</strong> SQL's {@code =} is never
     * true of a null column, so a claim made before {@code V14__referral_owner_subjects.sql} -- and every referral
     * V7 backfilled -- is on nobody's list, with no fall back to the display name. That is deliberate: the name
     * fall back would put two judges who share one on each other's lists, which is precisely the disclosure V14
     * removed. A null {@code owner} argument likewise matches nothing rather than everything, though the caller
     * refuses one first.
     *
     * <p><strong>The database filters, orders, pages and counts.</strong> The alternative -- read the referrals
     * and pick the caller's out in Java -- would page an arbitrary slice and would have to count separately, so a
     * judge's second page could omit work their first page's total had promised.
     * {@code JudicialReferralService.MINE_ORDER} is the sort, and the count is Spring Data's over this same
     * predicate, so the two cannot describe different result sets.
     *
     * <p>The states are parameters rather than literals in the JPQL for the reason {@link #findStaleClaims}'s is:
     * the caller says out loud which states it means a judge's own work to be in, instead of two places having to
     * be kept in step.
     *
     * <p>No index backs this either, for the reason given above for the pool and in V14: the table is small and
     * the predicate reads the same {@code "state"} column the pool already scans without one.
     *
     * @param owner the caller's IDAM subject, from their token and never from the request
     * @param claimed the state a held referral is in, {@link ReferralState#Claimed}
     * @param decided the state a directed referral is in, {@link ReferralState#Decided}
     * @param pageable the page and the order, which the caller supplies rather than this method naming
     * @return the referrals on that page, with the total across every page
     */
    @Query("""
        select r from Referral r
         where (r.state = :claimed and r.claimedById = :owner)
            or (r.state = :decided and r.decidedById = :owner)
        """)
    Page<Referral> findOwnedBy(@Param("owner") String owner,
                               @Param("claimed") ReferralState claimed,
                               @Param("decided") ReferralState decided,
                               Pageable pageable);

    /**
     * One referral and the application it was raised on, by referral id alone -- the judicial read.
     *
     * <p><strong>The one finder here that names no case, and the reason is the judicial surface's shape rather
     * than convenience.</strong> A judicial URL carries a referral id and nothing else, so there is no parent to
     * scope with; the check that replaces the scope is
     * {@code JudicialReferralService.visibleTo}, which admits the shared pool and the caller's own claimed and
     * decided referrals and answers 404 for everything else. It is therefore only safe while it is reachable
     * from that one gated place, which is a rule {@code ReferralSecurityTest} asserts in bytecode rather than a
     * comment: a caseworker path calling this would have an unscoped read of any referral with neither the
     * caseworker surface's parent id nor the judicial surface's visibility check in front of it.
     *
     * <p><strong>The case is join-fetched, and that is a correctness requirement.</strong>
     * {@code Referral.deedPoll} is {@code LAZY} and {@code spring.jpa.open-in-view} is false, so a controller
     * mapping the narrow judicial application view after the transaction closed would hit a
     * {@code LazyInitializationException} on an uninitialised proxy. Fetching it in the same statement is also
     * one round trip instead of two.
     *
     * @param id the referral, whoever it belongs to
     * @return the referral with its application loaded, or empty for an id nothing was stored under
     */
    @Query("select r from Referral r join fetch r.deedPoll where r.id = :id")
    Optional<Referral> findWithCaseById(@Param("id") UUID id);

    /**
     * Whether the case already holds a referral in one of the given states.
     *
     * <p>The one-open-per-case guard, asked as an existence check rather than by loading the list: the
     * answer is a boolean and the caller has no use for the row. The states are passed in rather than
     * baked into the method name so that "non-terminal" stays defined in one place --
     * {@link ReferralState#isTerminal()} -- instead of being spelled out again in a derived query name
     * that a sixth state would silently not cover.
     *
     * @param deedPollId the parent case
     * @param states     the states to look for, normally the non-terminal ones
     * @return true if at least one referral of that case is in one of those states
     */
    boolean existsByDeedPollIdAndStateIn(String deedPollId, Collection<ReferralState> states);

    /**
     * Every state one case's referrals are in -- the input to the case's denormalised current referral state.
     *
     * <p>States rather than entities, and all of them rather than the non-terminal ones. The projection
     * because {@code CurrentReferralState} needs nothing else off the row and the recompute runs at every
     * transition; all of them because "every referral is terminal, so the case has none in play" is the rule's
     * own half of the answer, and a finder that pre-filtered would move the terminal split out of
     * {@link ReferralState#isTerminal()} and into a query name.
     *
     * <p>Scoped to its case like every other finder here, which is what makes the recompute of one case's
     * column unable to read another's referrals.
     *
     * @param deedPollId the parent case
     * @return one state per referral of that case, in no particular order, empty for a case never referred
     */
    @Query("select r.state from Referral r where r.deedPoll.id = :deedPollId")
    List<ReferralState> findStatesByDeedPollId(@Param("deedPollId") String deedPollId);

    /**
     * When each still-unanswered confirmation on one case was asked for -- the input to the case's
     * denormalised {@code awaitingConfirmationSince}.
     *
     * <p>A projection of the dates for the reason the state finder above is one: {@code ConfirmationWait}
     * needs nothing else off the row, and the recompute runs at both sites that can change the answer.
     *
     * <p><strong>Pre-filtered here, where the state finder deliberately is not.</strong> The two halves of
     * "outstanding" are properties of the row rather than a rule anybody could get wrong -- a referral nobody
     * asked has a null request date, and one whose answer is recorded has an outcome -- so the predicate is
     * the {@code WHERE} clause decision 7 says it is, and it is the same predicate {@code V16}'s partial index
     * is built on. That is why this is not "every request date, filtered in Java": the index is only reachable
     * from a query that states it.
     *
     * <p>Scoped to its case like every other finder here, so one case's recompute cannot read another's
     * referrals.
     *
     * @param deedPollId the parent case
     * @return the request date of each outstanding confirmation, in no particular order, empty when the case
     *     is waiting for nothing
     */
    @Query("""
        select r.confirmationRequestedAt
          from Referral r
         where r.deedPoll.id = :deedPollId
           and r.confirmationRequestedAt is not null
           and r.confirmationOutcome is null
        """)
    List<LocalDateTime> findOutstandingConfirmationRequestsByDeedPollId(@Param("deedPollId") String deedPollId);

    /**
     * The claimed referrals nobody has touched since {@code cutoff} -- the sweep's candidates.
     *
     * <p><strong>Not scoped to a case, and it is the one finder that is not.</strong> The rule stated at the
     * top of this interface is about reading and writing <em>a</em> referral, which a caller names by case;
     * an inactivity sweep is by definition about every case at once, and there is no case id to scope it to.
     * It is safe for the reason the scoping rule exists: it returns no case data -- three fields of a
     * {@link StaleClaim}, no entity -- and it is reachable only through {@code ReferralService}'s sweep,
     * never from a request path carrying a case id.
     *
     * <p>{@code claimed} is a parameter rather than baked into the JPQL so that the caller says out loud
     * which state it is sweeping, matching the conditional {@link #releaseStaleClaim} that follows it: the
     * two predicates have to agree, and a state named in one place and hard-coded in the other is how they
     * would come to disagree.
     *
     * @param claimed the state a held referral is in, {@link ReferralState#Claimed}
     * @param cutoff  the instant a claim must have been touched after to survive, normally now minus the
     *                configured window
     * @return one row per candidate, in no particular order
     */
    @Query("""
        select new uk.gov.hmcts.reform.deedpoll.repository.StaleClaim(r.id, r.deedPoll.id, r.claimedBy)
          from Referral r
         where r.state = :claimed
           and r.lastTouchedAt < :cutoff
        """)
    List<StaleClaim> findStaleClaims(@Param("claimed") ReferralState claimed,
                                     @Param("cutoff") LocalDateTime cutoff);

    /**
     * Returns one lapsed claim to the shared pool, if it is still lapsed when the statement runs.
     *
     * <p><strong>The conditions in the {@code WHERE} are what make the sweep safe with several replicas,
     * and they are not redundant with the query that found the row.</strong> Between the candidate list and
     * this statement, the judge may have touched the referral, a caseworker may have withdrawn it, or
     * another replica's sweep may have released it already -- and each of those makes the predicate false,
     * so this answers 0 and the caller writes no note. Exactly one replica gets 1 for a given lapse, which
     * is why the timeline gets exactly one lapse note however many pool loads happen at once. There is no
     * lock and none is needed: a single {@code UPDATE} evaluating its own predicate is atomic.
     *
     * <p>Nulling {@code "lastTouchedAt"} is part of that, not tidiness -- it is what stops the predicate
     * matching the same row on the next sweep.
     *
     * <p>{@code "claimedById"} is cleared alongside {@code "claimedBy"}, and it is the one of the two that
     * matters: it is what every ownership check compares, so a released claim whose owner survived would leave
     * the judge who abandoned the referral still able to direct it, and would keep it on their personal list
     * after the pool had re-offered it. {@code "decidedById"} is never cleared here -- a direction is permanent,
     * and this statement only ever touches rows that are still {@code Claimed}.
     *
     * <p>Native SQL, and {@code {@literal @}Modifying}: this <em>is</em> the statement, so it is written
     * where a reader can check it against the rule rather than assembled by a translator. Identifiers are
     * quoted because they are mixed case, as {@code ReferenceCounterRepository} explains. {@code 'Open'}
     * and {@code 'Claimed'} are untyped literals, which Postgres resolves against the
     * {@code "ReferralState"} column they are compared and assigned to.
     *
     * <p>{@code clearAutomatically} because a statement Hibernate did not route through the session leaves
     * any already-loaded copy of that row stale, and {@code flushAutomatically} so a pending write by the
     * caller cannot be lost behind it.
     *
     * @param id        the referral to release
     * @param cutoff    the same cutoff the candidate list was taken with
     * @param now       the audit stamp for the release
     * @param releasedBy who to record as having written it, the service account
     * @return 1 if this statement released the claim, 0 if it was no longer there to release
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(nativeQuery = true, value = """
        UPDATE "deedpoll_referral"
           SET "state" = 'Open',
               "claimedBy" = null,
               "claimedById" = null,
               "claimedAt" = null,
               "lastTouchedAt" = null,
               "modifiedOn" = :now,
               "modifiedBy" = :releasedBy
         WHERE "id" = :id
           AND "state" = 'Claimed'
           AND "lastTouchedAt" < :cutoff
        """)
    int releaseStaleClaim(@Param("id") UUID id,
                          @Param("cutoff") LocalDateTime cutoff,
                          @Param("now") LocalDateTime now,
                          @Param("releasedBy") String releasedBy);
}
