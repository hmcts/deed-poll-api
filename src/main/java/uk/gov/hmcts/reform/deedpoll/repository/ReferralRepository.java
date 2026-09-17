package uk.gov.hmcts.reform.deedpoll.repository;

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
 * <p>The exception is the inactivity sweep -- {@link #findStaleClaims} and {@link #releaseStaleClaim} -- which
 * is about every case at once and so has no case to be scoped to. It stays inside the rule rather than beside
 * it: the finder returns a three-field projection instead of an entity, and neither is reachable from a request
 * path that names a case.
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
