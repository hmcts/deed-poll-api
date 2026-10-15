package uk.gov.hmcts.reform.deedpoll.domain.savedqueries;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import uk.gov.hmcts.reform.deedpoll.domain.NotFoundError;
import uk.gov.hmcts.reform.deedpoll.domain.UnauthorizedError;
import uk.gov.hmcts.reform.deedpoll.domain.auth.CurrentUser;
import uk.gov.hmcts.reform.deedpoll.domain.auth.Role;
import uk.gov.hmcts.reform.deedpoll.entity.SavedQuery;
import uk.gov.hmcts.reform.deedpoll.entity.SavedQuerySelection;
import uk.gov.hmcts.reform.deedpoll.repository.SavedQueryRepository;
import uk.gov.hmcts.reform.deedpoll.repository.SavedQuerySelectionRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Selecting and unselecting a saved query, with no database and no HTTP.
 *
 * <p><strong>Mocked repositories, which is not this repo's habit and is deliberate here.</strong> Every other
 * service test runs against a real database, because every other service is a port whose reference asserts the
 * arguments a mocked Prisma client was called with and the stored row is the claim those assertions stand for.
 * Those tests are container-backed, and container-backed tests are excluded on the Jenkins agent --
 * {@code INTEGRATION_TESTS_ENABLED=false}, because it has no Docker. Selection has no reference to port, and its
 * rules are about <em>which row is touched and whether one is touched at all</em>, which is exactly what a
 * verified interaction states and what a stored row cannot: "the second call wrote nothing" is invisible in a
 * table that already holds the row from the first. So this states the rules where CI can run them, and
 * {@code CaseworkerSavedQueryControllerTest} proves against a real database and a real token that the routes are
 * wired to them and which status code each answer becomes.
 *
 * <p>Three callers, as that class has: the owner, an unrelated caseworker and an Administrator. A test with one
 * caller cannot tell a per-user rule from no rule at all, because every row would be theirs.
 */
class SavedQuerySelectionTest {

    private static final UUID QUERY_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    /** An id of the right shape that nothing was ever stored under. */
    private static final UUID UNKNOWN_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private static final CurrentUser OWNER = CurrentUser.currentUser(List.of(), "Olive Owner", "owner-1");
    private static final CurrentUser OTHER = CurrentUser.currentUser(List.of(), "Otto Other", "other-2");
    private static final CurrentUser ADMIN =
        CurrentUser.currentUser(List.of(Role.DEEDPOLL_ADMIN), "An Admin", "admin-3");

    private SavedQueryRepository savedQueries;
    private SavedQuerySelectionRepository selections;
    private SavedQueryService service;

    @BeforeEach
    void createService() {
        savedQueries = mock(SavedQueryRepository.class);
        selections = mock(SavedQuerySelectionRepository.class);
        service = new SavedQueryService(savedQueries, selections,
            mock(uk.gov.hmcts.reform.deedpoll.domain.audit.AuditTrail.class));
    }

    @Nested
    @DisplayName("adding a query to a caller's list")
    class AddSelection {

        @Test
        @DisplayName("records the query against the caller, and only the caller")
        void recordsTheQueryAgainstTheCaller() {
            stored(query(true));

            service.addSelection(QUERY_ID.toString(), OTHER);

            // The pair is the whole row, so this assertion is the whole write: a selection stored against the
            // wrong user id is one caseworker's choice appearing in another's navigation.
            ArgumentCaptor<SavedQuerySelection> written = ArgumentCaptor.forClass(SavedQuerySelection.class);
            verify(selections).save(written.capture());
            assertThat(written.getValue().getId().getSavedQueryId()).isEqualTo(QUERY_ID);
            assertThat(written.getValue().getId().getUserId()).isEqualTo(OTHER.userId());
        }

        @Test
        @DisplayName("is permitted on somebody else's shared query, which is what the library is for")
        void isPermittedWithoutEditRights() {
            SavedQuery shared = query(true);
            stored(shared);

            // The point of selection being a sub-resource: OTHER cannot amend this query -- asserted here rather
            // than assumed, because if they could the rest of this test would prove nothing about permission.
            assertThat(SavedQueryPermissions.editableBy(shared, OTHER)).isFalse();
            assertThatCode(() -> service.addSelection(QUERY_ID.toString(), OTHER)).doesNotThrowAnyException();

            verify(selections).save(any());
        }

        @Test
        @DisplayName("writes nothing the second time, because the row is only its key")
        void writesNothingTheSecondTime() {
            stored(query(true));
            when(selections.existsByIdSavedQueryIdAndIdUserId(QUERY_ID, OWNER.userId())).thenReturn(true);

            assertThatCode(() -> service.addSelection(QUERY_ID.toString(), OWNER)).doesNotThrowAnyException();

            // Not a conflict, and not a second row: there is no column for a repeat to update, so "already
            // selected" is the whole of the state and a client retrying a lost response has nothing to clean up.
            verify(selections, never()).save(any());
        }

        @Test
        @DisplayName("refuses a query the caller cannot see, and writes nothing")
        void refusesAQueryTheCallerCannotSee() {
            stored(query(false));

            // 404 and not 403: selecting must not be a way to discover that somebody else's private query
            // exists, so it answers exactly as an id that never existed does.
            assertThatThrownBy(() -> service.addSelection(QUERY_ID.toString(), OTHER))
                .isInstanceOf(NotFoundError.class)
                .hasMessage("Saved query " + QUERY_ID + " not found");

            verify(selections, never()).save(any());
        }

        @Test
        @DisplayName("refuses an Administrator naming somebody else's unshared query")
        void refusesAnAdministratorNamingAnUnsharedQuery() {
            stored(query(false));

            // Decision 2's refinement, and there is no second check enforcing it: the visibility rule reaches
            // an administrator before the edit override does, so a private query is a 404 for them too.
            assertThatThrownBy(() -> service.addSelection(QUERY_ID.toString(), ADMIN))
                .isInstanceOf(NotFoundError.class)
                .hasMessage("Saved query " + QUERY_ID + " not found");

            verify(selections, never()).save(any());
        }

        @Test
        @DisplayName("refuses an id that never existed, with the same words")
        void refusesAnIdThatNeverExisted() {
            when(savedQueries.findById(UNKNOWN_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.addSelection(UNKNOWN_ID.toString(), OWNER))
                .isInstanceOf(NotFoundError.class)
                .hasMessage("Saved query " + UNKNOWN_ID + " not found");
        }

        @Test
        @DisplayName("refuses an id that is not an identifier at all, without asking the database")
        void refusesAMalformedId() {
            assertThatThrownBy(() -> service.addSelection("not-a-uuid", OWNER))
                .isInstanceOf(NotFoundError.class)
                .hasMessage("Saved query not-a-uuid not found");

            // Nothing could ever have been stored under it, so "not found" is the true answer rather than a 400,
            // and there is no lookup to make.
            verifyNoInteractions(savedQueries);
        }

        @Test
        @DisplayName("refuses a caller with no stable identifier, even for a shared query")
        void refusesAnUnidentifiedCaller() {
            stored(query(true));

            // Fails closed. A selection has to belong to somebody, and a null user id would make every
            // unidentified caller the same person sharing one list.
            assertThatThrownBy(() -> service.addSelection(QUERY_ID.toString(), CurrentUser.ANONYMOUS))
                .isInstanceOf(UnauthorizedError.class);

            verify(selections, never()).save(any());
        }
    }

    @Nested
    @DisplayName("removing a query from a caller's list")
    class RemoveSelection {

        @Test
        @DisplayName("deletes the caller's own row and names them in the delete")
        void deletesTheCallersOwnRow() {
            service.removeSelection(QUERY_ID.toString(), OWNER);

            // The user id is in the delete rather than in a guard in front of it, which is what makes reaching
            // another caseworker's selection unavailable rather than merely refused.
            verify(selections).deleteByIdSavedQueryIdAndIdUserId(QUERY_ID, OWNER.userId());
        }

        @Test
        @DisplayName("cannot reach another caller's row for the same query")
        void cannotReachAnotherCallersRow() {
            service.removeSelection(QUERY_ID.toString(), OTHER);

            verify(selections).deleteByIdSavedQueryIdAndIdUserId(QUERY_ID, OTHER.userId());
            verify(selections, never()).deleteByIdSavedQueryIdAndIdUserId(QUERY_ID, OWNER.userId());
        }

        @Test
        @DisplayName("is silent when nothing was selected, because unselecting twice is not a mistake")
        void isSilentWhenNothingWasSelected() {
            when(selections.deleteByIdSavedQueryIdAndIdUserId(QUERY_ID, OWNER.userId())).thenReturn(0L);

            // The repository reports a count rather than throwing, and the service does not turn 0 into a 404:
            // the request states what the caller wants to be true of their own list, and it already is.
            assertThatCode(() -> service.removeSelection(QUERY_ID.toString(), OWNER)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("is silent for a query the caller cannot see, and does not read it")
        void isSilentForAQueryTheCallerCannotSee() {
            // The one asymmetry with adding, and it is deliberate. Requiring visibility here would trap a
            // subscriber with a stale row for a query its owner has unshared -- the selection they most need to
            // clear, and the one they can no longer see. Nothing is disclosed, because the answer does not vary
            // with the id and the query is never even loaded.
            assertThatCode(() -> service.removeSelection(QUERY_ID.toString(), OTHER)).doesNotThrowAnyException();

            verifyNoInteractions(savedQueries);
            verify(selections).deleteByIdSavedQueryIdAndIdUserId(QUERY_ID, OTHER.userId());
        }

        @Test
        @DisplayName("is silent for an id that is not an identifier, and deletes nothing")
        void isSilentForAMalformedId() {
            assertThatCode(() -> service.removeSelection("not-a-uuid", OWNER)).doesNotThrowAnyException();

            verify(selections, never()).deleteByIdSavedQueryIdAndIdUserId(any(), anyString());
        }

        @Test
        @DisplayName("refuses a caller with no stable identifier rather than deleting against a null user")
        void refusesAnUnidentifiedCaller() {
            // Resolved before the id is, so the refusal does not depend on the id being well formed -- otherwise
            // an unidentified caller would get a silent 204 from the malformed-id path above.
            assertThatThrownBy(() -> service.removeSelection("not-a-uuid", CurrentUser.ANONYMOUS))
                .isInstanceOf(UnauthorizedError.class);
            assertThatThrownBy(() -> service.removeSelection(QUERY_ID.toString(), CurrentUser.ANONYMOUS))
                .isInstanceOf(UnauthorizedError.class);

            verifyNoInteractions(selections);
        }
    }

    /**
     * The repository answers with this query under {@link #QUERY_ID}.
     */
    private void stored(SavedQuery query) {
        when(savedQueries.findById(QUERY_ID)).thenReturn(Optional.of(query));
    }

    /**
     * A query owned by {@link #OWNER}, shared or not.
     */
    private static SavedQuery query(boolean shared) {
        SavedQuery query = new SavedQuery();
        query.setId(QUERY_ID);
        query.setName("Enroled this month");
        query.setOwnerId(OWNER.userId());
        query.setOwnerName(OWNER.name());
        query.setShared(shared);
        return query;
    }
}
