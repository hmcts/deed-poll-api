package uk.gov.hmcts.reform.deedpoll.repository;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import uk.gov.hmcts.reform.deedpoll.entity.DeedPoll;
import uk.gov.hmcts.reform.deedpoll.entity.enums.DeedPollState;

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
 * <p>The two state finders are the shapes {@code ListFilters.StateWhere} has, and no others: adding a
 * finder here for a filter that does not exist is how a repository grows methods with no consumer.
 */
public interface DeedPollRepository extends JpaRepository<DeedPoll, String> {

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
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from DeedPoll d where d.id = :id")
    Optional<DeedPoll> findByIdForUpdate(@Param("id") String id);

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
}
