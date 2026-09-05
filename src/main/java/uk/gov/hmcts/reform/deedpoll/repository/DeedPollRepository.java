package uk.gov.hmcts.reform.deedpoll.repository;

import org.springframework.data.jpa.repository.JpaRepository;
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

    /** Cases in none of the given states, newest first -- the "open" filter. */
    List<DeedPoll> findByStateNotInOrderByCreatedOnDesc(Collection<DeedPollState> states);
}
