package uk.gov.hmcts.reform.deedpoll.repository;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import uk.gov.hmcts.reform.deedpoll.entity.DeedPoll;
import uk.gov.hmcts.reform.deedpoll.entity.enums.DeedPollState;
import uk.gov.hmcts.reform.deedpoll.entity.enums.ReferralState;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Cases, read and written.
 *
 * <p>The three finders carry the sort in their names rather than leaving it to a caller, because the
 * reference's {@code findDeedPolls} sorts by {@code createdOn} descending and the list page depends on
 * it -- newest first is how a caseworker finds the case they just logged.
 *
 * <p>The three filter finders are the shapes {@code ListFilters.FilterWhere} has, and no others: adding a
 * finder here for a filter that does not exist is how a repository grows methods with no consumer.
 *
 * <p><strong>{@link JpaSpecificationExecutor} is for the field-level case query, and nothing else.</strong>
 * A query names case fields a caller chose, so its shape is not known in advance and no named finder can
 * express it -- but a {@code Specification} is built through the Criteria API, which binds its values, so
 * a query assembled from a request can never be a query concatenated from one.
 * {@code CaseSpecifications} is the only translation into it, and it is where the reasoning lives.
 */
public interface DeedPollRepository extends JpaRepository<DeedPoll, String>, JpaSpecificationExecutor<DeedPoll> {

    /** Every case, newest first. */
    List<DeedPoll> findAllByOrderByCreatedOnDesc();

    /**
     * One case, with its row locked for the rest of the caller's transaction -- {@code SELECT … FOR UPDATE}.
     *
     * <p><strong>Why a patch has to read under a lock.</strong> A patch is a read-modify-write: the update
     * path loads the case, overlays the patch onto what it read, derives {@code state} from that overlay and
     * writes the result. Hibernate has no dirty-column tracking on this entity, so the write is the whole
     * row. Two caseworkers patching the same case at once therefore both read the pre-patch row, and the
     * second {@code UPDATE} -- which waits for the first to commit, then applies the snapshot it took before
     * that commit -- writes every column back as it was, silently discarding the first caseworker's edit even
     * when the two touched entirely different fields. The derived {@code state} is wrong in the same way: it
     * was computed from an overlay that did not include the other write's dates.
     *
     * <p>This is a divergence the port introduced, not the reference's behaviour: the reference issues
     * {@code prisma.deedPoll.update} with only the patched and derived keys, so its {@code SET} clause names
     * just those columns and two disjoint patches do not collide. Locking the row here restores that
     * property and goes further than a narrower {@code SET} could -- the second transaction re-reads the
     * committed row after the lock is released, so its overlay, and the state derived from it, are built on
     * the first write rather than around it.
     *
     * <p>Not a {@code @Lock} on {@code findById}: the read paths -- the list, the case view, the submit
     * validation -- must not take a write lock on rows they only display, and
     * {@code CaseworkerReadController} reads far more cases than the write path writes. The lock belongs to
     * the operation that needs it, so a reader cannot acquire one by accident.
     *
     * <p>Optimistic locking would have been the other answer, and is deliberately not used: it needs a
     * version column, which means a column the reference schema does not have, and it converts a collision
     * into a 409 the caller has to retry. There is no {@code If-Match} in this API for a caller to retry
     * against, and a caseworker's save being rejected because a colleague saved first is a worse outcome
     * than the two saves being applied in order.
     *
     * <p><strong>Only the patch path may take this lock, and the recompute beside it deliberately does not.</strong>
     * {@code ReferralService.refreshAwaitingConfirmation} needs a case-wide mutual exclusion too, and takes it
     * through {@link #lockForConfirmationRecompute} instead -- {@code FOR NO KEY UPDATE} rather than
     * {@code FOR UPDATE}, because by the time it runs its own transaction has already inserted a row that
     * references the case. That javadoc carries the reasoning; the short version is that {@code FOR UPDATE} is the
     * one row-lock strength a foreign-key {@code KEY SHARE} conflicts with, so taking it here would turn two
     * ordinary flows into a deadlock. A patch writes no child row, so it is free to take the stronger one.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from DeedPoll d where d.id = :id")
    Optional<DeedPoll> findByIdForUpdate(@Param("id") String id);

    /**
     * Locks one case for the {@code awaitingConfirmationSince} recompute -- {@code SELECT … FOR NO KEY UPDATE}.
     *
     * <p><strong>Why the recompute needs a lock at all.</strong> It is a read-modify-write:
     * {@code ReferralService.refreshAwaitingConfirmation} asks the referral table which of the case's requests are
     * still outstanding and writes the earliest onto the case. Two answers arriving on one case's two outstanding
     * requests at the same moment each read the referrals before the other committed, so each still sees the
     * other's referral as unanswered and the last writer leaves the case in the waiting list with nothing
     * outstanding on it. Nothing repairs that: the two sites are the column's only writers and no third answer is
     * coming. Taking the lock before the read serialises the pair, and the second transaction's read -- a fresh
     * statement snapshot, once the first has committed and released it -- then sees both answers and writes NULL.
     *
     * <p><strong>Why {@code FOR NO KEY UPDATE} and not {@link #findByIdForUpdate}'s {@code FOR UPDATE}, which is
     * the whole point of this method existing.</strong> Both writers of the column reach the recompute having
     * already inserted a row that references the case in the same transaction -- a case note for
     * {@code recordConfirmation}, an outbox row for {@code CaseEmailService.send} -- and Postgres takes a
     * {@code FOR KEY SHARE} lock on the parent row to enforce each of those foreign keys. {@code FOR KEY SHARE}
     * conflicts with exactly one requested strength, {@code FOR UPDATE}. So two such transactions asking for
     * {@code FOR UPDATE} on one case each wait for the other's key-share to be released and Postgres aborts one of
     * them, and a send blocked behind a recording's referral lock while holding the case's key-share deadlocks
     * with that recording in the same way. {@code FOR NO KEY UPDATE} conflicts with itself -- which is all the
     * mutual exclusion the recompute needs -- and with nothing a foreign key takes, so the pair queues instead.
     *
     * <p>The strength is what makes it deadlock-free, not the position -- but the position still has to be the
     * one it is. <strong>Every write path here writes its referral row first and touches the case last</strong>
     * (a transition's {@link #writeCurrentReferralState} included), so a case lock hoisted in front of a referral
     * write would be a second lock order on the same pair of rows, and two orders on one pair is a deadlock
     * whatever the strengths are. Taken at the tail, as both callers take it, there is one order.
     *
     * <p>The row is returned only so there is something to select; no caller reads it, and
     * {@link #writeAwaitingConfirmationSince} is still a statement over one column. A case that has since gone
     * answers empty, and the write behind it answers 0, exactly as they did before any lock was taken.
     *
     * <p>Native SQL because no JPA lock mode maps to {@code FOR NO KEY UPDATE}: Hibernate renders
     * {@code PESSIMISTIC_WRITE} as {@code FOR UPDATE} and {@code PESSIMISTIC_READ} as {@code FOR SHARE}, and
     * {@code FOR SHARE} conflicts with the plain {@code UPDATE} the recompute finishes with. Quoted mixed-case
     * identifiers for the reason every native statement here has them.
     *
     * @param id the case to lock
     * @return the case's id, or empty if there is no such case
     */
    @Query(nativeQuery = true, value = """
        SELECT "id" FROM "deedpoll_deedpoll" WHERE "id" = :id FOR NO KEY UPDATE
        """)
    Optional<String> lockForConfirmationRecompute(@Param("id") String id);

    /**
     * One case with its documents and each document's type, in a single query.
     *
     * <p>Exists because the case view renders every document's type name. Reaching it through the lazy
     * {@code @ManyToOne} was one select per document on top of the collection load -- invisible with the
     * two or three documents a test fixture has, and linear in the real thing.
     *
     * <p><strong>Only the documents collection is fetched.</strong> Adding {@code notes} or
     * {@code witnesses} here would make it a multiple-bag fetch, which Hibernate answers with a
     * cartesian product across the two collections. Those two are still initialised separately by
     * {@code DeedPollService.findDeedPoll}, which is why this finder is documents-specific rather than a
     * general "with children" one.
     */
    @EntityGraph(attributePaths = {"documents", "documents.documentType"})
    Optional<DeedPoll> findWithDocumentsById(String id);

    /** Cases in one of the given states, newest first. */
    List<DeedPoll> findByStateInOrderByCreatedOnDesc(Collection<DeedPollState> states);

    /**
     * Cases in none of the given states, newest first -- the "open" filter.
     *
     * <p><strong>A case with no state counts as open</strong>, which is why this is a written query and not
     * the derived {@code findByStateNotIn...}. The derived form generates a bare {@code state NOT IN (…)},
     * and SQL evaluates that to NULL rather than true for a NULL state, so the row is dropped. The column is
     * nullable and nothing defaults it, so a case created without a state -- which the create schema
     * permits, since state is normally derived by {@link uk.gov.hmcts.reform.deedpoll.domain.deedpoll.CaseState}
     * from the dates -- would be invisible in every filter but "all".
     *
     * <p>Including it is the direction the reference argues for: "open" is defined by exclusion so that
     * anything not yet finished shows up by default, because a case appearing in a caseworker's open list is
     * noticeable whereas one silently missing from it is not.
     */
    @Query("select d from DeedPoll d where d.state is null or d.state not in :states order by d.createdOn desc")
    List<DeedPoll> findOpenOrderByCreatedOnDesc(@Param("states") Collection<DeedPollState> states);

    /**
     * The cases waiting for an applicant's confirmation, longest wait first -- the "awaiting confirmation"
     * filter.
     *
     * <p>Ordered by the column rather than by {@code createdOn} like the three finders above, because the whole
     * point of this tab is which case has waited longest: a list of waiting cases in creation order would put a
     * case asked yesterday above one asked in May. The specification path states the same criterion and the
     * same order, and {@code CaseSpecificationsTest} pins the two together.
     *
     * <p>A derived query rather than a written one, and safely so where {@code findOpenOrderByCreatedOnDesc}
     * could not be: {@code IS NOT NULL} is not the {@code NOT IN} that evaluates to NULL for a NULL column, so
     * there is no third case to spell out. It is also exactly the predicate
     * {@code V17__case_awaiting_confirmation.sql}'s partial index is built on.
     */
    List<DeedPoll> findByAwaitingConfirmationSinceIsNotNullOrderByAwaitingConfirmationSinceAsc();

    /**
     * Writes one case's denormalised current referral state -- the recompute's second half.
     *
     * <p><strong>A statement over the one column, not {@code save} on the loaded case.</strong> This entity has
     * no dirty-column tracking, so a save writes every column back, and a referral transition holds no lock on
     * the case row: it would read the case, then write all of it a moment later, discarding whatever a
     * concurrent patch committed in between -- the lost update {@link #findByIdForUpdate} exists to prevent for
     * the patch path. A single-column {@code UPDATE} cannot lose a write to any other column, so a transition
     * needs no lock on the case to keep a patch's columns safe from it.
     *
     * <p><strong>Nor does this recompute need one against itself</strong>, where
     * {@link #writeAwaitingConfirmationSince}'s does: a case may hold only one non-terminal referral, so two
     * transitions of one case are two writes to the same referral row and the row settles their order. Two
     * <em>confirmations</em> of one case are writes to two different rows, which is why that column's recompute is
     * the one that reads under a lock.
     *
     * <p>{@code null} is a value this is asked to write and not a no-op: it is what a case whose last live
     * referral has just been closed or withdrawn must read.
     *
     * <p>The persistence context is deliberately <strong>not</strong> cleared. A bulk statement bypasses the
     * session, so a case already loaded in it keeps the old value -- which is harmless here because no referral
     * flow reads the case's own referral state back, and none writes the case, while clearing would detach the
     * referral the service returns for the controller to map.
     *
     * @param id    the case
     * @param state its current referral state, or null when it has no referral in play
     * @return 1 if the case exists, 0 if it does not
     */
    @Modifying(flushAutomatically = true)
    @Query("update DeedPoll d set d.currentReferralState = :state where d.id = :id")
    int writeCurrentReferralState(@Param("id") String id, @Param("state") ReferralState state);

    /**
     * Writes one case's denormalised "awaiting confirmation since" -- the other recompute's second half.
     *
     * <p>A single-column statement rather than {@code save} on the loaded case, for the reason
     * {@link #writeCurrentReferralState} gives in full: this entity has no dirty-column tracking, so a save
     * writes every column back, and a statement over one column cannot lose a concurrent write to another.
     * The caller does hold {@link #lockForConfirmationRecompute}'s lock on the case row by the time it gets here,
     * and that is about a different problem -- two recomputes of <em>this</em> column racing each other, which no
     * single-column statement can settle because what races is the read each of them computes from.
     *
     * <p>{@code null} is a value this is asked to write and not a no-op: it is what a case reads once the
     * answer is recorded, and writing it is how the case leaves the waiting list.
     *
     * <p>The persistence context is deliberately not cleared, as above: no flow reads this column back in the
     * transaction that writes it, and clearing would detach the entities the caller is still mapping from.
     *
     * @param id    the case
     * @param since when its longest outstanding wait began, or null when it is waiting for nothing
     * @return 1 if the case exists, 0 if it does not
     */
    @Modifying(flushAutomatically = true)
    @Query("update DeedPoll d set d.awaitingConfirmationSince = :since where d.id = :id")
    int writeAwaitingConfirmationSince(@Param("id") String id, @Param("since") LocalDateTime since);

    /**
     * Recomputes the current referral state of many cases at once, from the referral table.
     *
     * <p><strong>For the stale-claim sweep, which is the one transition that moves many referrals in one
     * statement.</strong> Calling the per-case recompute in a loop behind it would be a query pair per
     * reclaimed claim and, worse, would put the sweep's own atomicity back in the application: the sweep's
     * argument is that a conditional {@code UPDATE} settles a multi-replica race without a lock, and its
     * read-model counterpart is only as atomic as the statement that carries it. So this is one statement over
     * the whole set.
     *
     * <p><strong>Still recompute-from-truth</strong>, and not {@code SET 'Open'} for the state the sweep
     * happens to move a claim to: the value comes from a correlated read of the case's own non-terminal
     * referral, so the bulk site cannot drift from the single-case one. A case whose referrals are by then all
     * terminal gets NULL from the subquery, which is the right answer and the one a hard-coded value could not
     * give.
     *
     * <p>The subquery yields at most one row because a case holds at most one non-terminal referral -- the
     * invariant {@code ReferralService.createReferral} enforces with a 409. If that guard were ever removed,
     * this statement would fail loudly rather than pick a state arbitrarily, which is the preferable failure.
     *
     * @param deedPollIds the cases to recompute, which must not be empty
     * @param liveStates  the non-terminal states, passed in so {@link ReferralState#isTerminal()} stays the one
     *                    definition of "in play" rather than being spelled out again in JPQL
     * @return how many case rows were written
     */
    @Modifying(flushAutomatically = true)
    @Query("""
        update DeedPoll d
           set d.currentReferralState = (select r.state
                                           from Referral r
                                          where r.deedPoll.id = d.id
                                            and r.state in :liveStates)
         where d.id in :deedPollIds
        """)
    int refreshCurrentReferralStates(@Param("deedPollIds") Collection<String> deedPollIds,
                                     @Param("liveStates") Collection<ReferralState> liveStates);
}
