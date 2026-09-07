package uk.gov.hmcts.reform.deedpoll.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import uk.gov.hmcts.reform.deedpoll.entity.DeedPoll;
import uk.gov.hmcts.reform.deedpoll.entity.enums.DeedPollState;

import java.util.Collection;
import java.util.List;

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
