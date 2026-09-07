package uk.gov.hmcts.reform.deedpoll.repository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import uk.gov.hmcts.reform.deedpoll.config.AbstractPostgresContainerTest;
import uk.gov.hmcts.reform.deedpoll.entity.DeedPoll;
import uk.gov.hmcts.reform.deedpoll.entity.enums.DeedPollState;

import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Asserts the case finders against a real Postgres, because what is being tested is SQL's semantics rather than
 * the application's.
 *
 * <p>The "open" filter is defined by exclusion -- everything not in a finished state -- and that is a decision
 * about NULL as much as about the listed states. Nothing else in the suite exercises these finders: the service
 * tests mock this repository, so a query that quietly dropped rows would pass every one of them.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional
class DeedPollRepositoryTest extends AbstractPostgresContainerTest {

    /**
     * The states "open" excludes, as {@code ListFilters} defines it.
     */
    private static final Set<DeedPollState> FINISHED = EnumSet.of(DeedPollState.Enroled, DeedPollState.Returned);

    @Autowired
    private DeedPollRepository deedPolls;

    @PersistenceContext
    private EntityManager entityManager;

    private DeedPoll persisted(DeedPollState state, LocalDateTime received) {
        DeedPoll deedPoll = new DeedPoll();
        deedPoll.setState(state);
        deedPoll.setDateReceived(received);
        entityManager.persist(deedPoll);
        return deedPoll;
    }

    @Test
    @DisplayName("a case with no state counts as open")
    void treatsACaseWithNoStateAsOpen() {
        // The case this exists for. A bare `state NOT IN (…)` evaluates to NULL, not true, for a NULL state, so
        // the derived finder dropped the row -- and the column is nullable with nothing defaulting it, so a case
        // created before its state is derived has exactly this shape. It would have been visible only under
        // "all", which is the failure the reference warns about: a case silently missing from a caseworker's
        // open list is not noticeable, whereas one wrongly in it is.
        DeedPoll noState = persisted(null, LocalDateTime.of(2026, 1, 2, 10, 0));
        entityManager.flush();

        List<DeedPoll> open = deedPolls.findOpenOrderByCreatedOnDesc(FINISHED);

        assertThat(open).extracting(DeedPoll::getId).contains(noState.getId());
    }

    @Test
    @DisplayName("an active state is open and a finished one is not")
    void excludesOnlyTheFinishedStates() {
        DeedPoll active = persisted(DeedPollState.AwaitingSignature, LocalDateTime.of(2026, 1, 3, 10, 0));
        DeedPoll enroled = persisted(DeedPollState.Enroled, LocalDateTime.of(2026, 1, 4, 10, 0));
        DeedPoll returned = persisted(DeedPollState.Returned, LocalDateTime.of(2026, 1, 5, 10, 0));
        entityManager.flush();

        List<DeedPoll> open = deedPolls.findOpenOrderByCreatedOnDesc(FINISHED);

        assertThat(open).extracting(DeedPoll::getId)
            .contains(active.getId())
            .doesNotContain(enroled.getId(), returned.getId());
    }

    @Test
    @DisplayName("the open filter returns newest first")
    void ordersOpenCasesNewestFirst() {
        // createdOn is stamped explicitly rather than left to the entity's audit callback, which only defaults it
        // when null. The column is timestamp(3), so two inserts completing inside the same millisecond would tie
        // and "order by createdOn desc" would then be free to return either -- an intermittent failure with no
        // diagnostic value. Three fixed timestamps make the ordering the only thing under test.
        DeedPoll first = persisted(DeedPollState.Received, LocalDateTime.of(2026, 1, 6, 10, 0));
        first.setCreatedOn(LocalDateTime.of(2026, 1, 6, 11, 0));
        entityManager.flush();
        DeedPoll second = persisted(DeedPollState.Received, LocalDateTime.of(2026, 1, 7, 10, 0));
        second.setCreatedOn(LocalDateTime.of(2026, 1, 7, 11, 0));
        entityManager.flush();
        DeedPoll third = persisted(null, LocalDateTime.of(2026, 1, 8, 10, 0));
        third.setCreatedOn(LocalDateTime.of(2026, 1, 8, 11, 0));
        entityManager.flush();

        List<String> open = deedPolls.findOpenOrderByCreatedOnDesc(FINISHED).stream()
            .map(DeedPoll::getId)
            .filter(List.of(first.getId(), second.getId(), third.getId())::contains)
            .toList();

        assertThat(open).containsExactly(third.getId(), second.getId(), first.getId());
    }

    @Test
    @DisplayName("the state filter matches only the states given, and a null state is not one of them")
    void matchesOnlyTheGivenStates() {
        DeedPoll received = persisted(DeedPollState.Received, LocalDateTime.of(2026, 1, 9, 10, 0));
        DeedPoll noState = persisted(null, LocalDateTime.of(2026, 1, 10, 10, 0));
        entityManager.flush();

        List<DeedPoll> matching = deedPolls.findByStateInOrderByCreatedOnDesc(Set.of(DeedPollState.Received));

        // The other direction from the open filter, and deliberately not symmetrical: "Received" means the state
        // is Received, so a case with no state is absent here and present there.
        assertThat(matching).extracting(DeedPoll::getId)
            .contains(received.getId())
            .doesNotContain(noState.getId());
    }
}
