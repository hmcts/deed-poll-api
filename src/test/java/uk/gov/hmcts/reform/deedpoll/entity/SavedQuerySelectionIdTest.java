package uk.gov.hmcts.reform.deedpoll.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The composite key's identity contract, with no database.
 *
 * <p><strong>Written because nothing else states it.</strong> Hibernate compares these keys inside the
 * persistence context on every load, so {@code equals} and {@code hashCode} are load-bearing -- but they are
 * only ever called <em>by</em> Hibernate, which meant the container-backed tests exercised them without any
 * test asserting what they do. Coverage measured 50% on this class with the whole suite running, not just on
 * the Jenkins agent, which is what made it a real gap rather than the usual excluded-container artefact.
 *
 * <p>A plain unit test rather than an addition to {@code EntityMappingTest}: that one extends
 * {@code AbstractPostgresContainerTest} and is therefore excluded when {@code INTEGRATION_TESTS_ENABLED=false},
 * and a contract that needs no database should not be tested where CI cannot see it.
 *
 * <p>The two-selections-differing-only-by-user case is the one worth having: it is what stops one caseworker's
 * selection being mistaken for another's inside a single flush, and a key that ignored {@code userId} would
 * pass every other test here.
 */
class SavedQuerySelectionIdTest {

    private static final UUID QUERY = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID OTHER_QUERY = UUID.fromString("00000000-0000-0000-0000-0000000000b2");

    @Test
    @DisplayName("two ids naming the same query and user are equal, and agree on hashCode")
    void equalsItself() {
        SavedQuerySelectionId one = new SavedQuerySelectionId(QUERY, "user-1");
        SavedQuerySelectionId same = new SavedQuerySelectionId(QUERY, "user-1");

        assertThat(one).isEqualTo(same).hasSameHashCodeAs(same);
        assertThat(one).isEqualTo(one);
    }

    @Test
    @DisplayName("the user is part of the identity, so two users selecting one query are two keys")
    void distinguishesTheUser() {
        SavedQuerySelectionId mine = new SavedQuerySelectionId(QUERY, "user-1");
        SavedQuerySelectionId theirs = new SavedQuerySelectionId(QUERY, "user-2");

        assertThat(mine).isNotEqualTo(theirs);
    }

    @Test
    @DisplayName("the query is part of the identity, so one user's two selections are two keys")
    void distinguishesTheQuery() {
        SavedQuerySelectionId first = new SavedQuerySelectionId(QUERY, "user-1");
        SavedQuerySelectionId second = new SavedQuerySelectionId(OTHER_QUERY, "user-1");

        assertThat(first).isNotEqualTo(second);
    }

    @Test
    @DisplayName("nothing of another type is equal to one, including null")
    void equalsNothingElse() {
        SavedQuerySelectionId id = new SavedQuerySelectionId(QUERY, "user-1");

        assertThat(id).isNotEqualTo(null).isNotEqualTo("00000000-0000-0000-0000-0000000000a1/user-1");
    }

    @Test
    @DisplayName("the no-argument constructor Hibernate needs leaves both components null, and still compares")
    void comparesWhileEmpty() {
        // Hibernate instantiates through this before populating it, so equals runs against null components
        // during a load. Objects.equals is what makes that safe rather than a NullPointerException.
        SavedQuerySelectionId empty = new SavedQuerySelectionId();
        SavedQuerySelectionId alsoEmpty = new SavedQuerySelectionId();

        assertThat(empty).isEqualTo(alsoEmpty).hasSameHashCodeAs(alsoEmpty);
        assertThat(empty).isNotEqualTo(new SavedQuerySelectionId(QUERY, "user-1"));
    }

    @Test
    @DisplayName("behaves in a hash set, which is how the persistence context holds it")
    void worksAsAHashKey() {
        Set<SavedQuerySelectionId> keys = new HashSet<>();
        keys.add(new SavedQuerySelectionId(QUERY, "user-1"));
        keys.add(new SavedQuerySelectionId(QUERY, "user-1"));
        keys.add(new SavedQuerySelectionId(QUERY, "user-2"));

        assertThat(keys).hasSize(2).contains(new SavedQuerySelectionId(QUERY, "user-1"));
    }
}
