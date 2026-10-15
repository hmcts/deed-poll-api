package uk.gov.hmcts.reform.deedpoll.config;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.RequestMapping;
import uk.gov.hmcts.reform.deedpoll.api.caseworker.controller.CaseworkerSavedQueryController;
import uk.gov.hmcts.reform.deedpoll.domain.ForbiddenError;
import uk.gov.hmcts.reform.deedpoll.domain.NotFoundError;
import uk.gov.hmcts.reform.deedpoll.domain.auth.CurrentUser;
import uk.gov.hmcts.reform.deedpoll.domain.auth.Role;
import uk.gov.hmcts.reform.deedpoll.domain.savedqueries.CreateSavedQueryInput;
import uk.gov.hmcts.reform.deedpoll.domain.savedqueries.SavedQueryDefinition;
import uk.gov.hmcts.reform.deedpoll.domain.savedqueries.SavedQueryService;
import uk.gov.hmcts.reform.deedpoll.domain.savedqueries.SavedQueryView;
import uk.gov.hmcts.reform.deedpoll.domain.savedqueries.UpdateSavedQueryInput;
import uk.gov.hmcts.reform.deedpoll.entity.SavedQuery;
import uk.gov.hmcts.reform.deedpoll.entity.SavedQuerySelection;
import uk.gov.hmcts.reform.deedpoll.repository.SavedQueryRepository;
import uk.gov.hmcts.reform.deedpoll.repository.SavedQuerySelectionRepository;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * What the saved-query resource refuses, with no database and no HTTP.
 *
 * <p><strong>The four properties this class exists for are the plan's:</strong> namespace separation, a caller
 * with no roles, one caseworker reaching another's selections, and an unshared query reached by a non-owner.
 * Each is asserted <em>through {@link SavedQueryService}</em> rather than against the predicates it composes,
 * which is the gap between this class and the two that already cover this resource:
 * {@code SavedQueryPermissionsTest} states the truth table and says outright that it cannot tell whether the
 * service calls it, and {@code SavedQuerySelectionTest} covers the two selection writes. What is left, and is
 * here, is that the read paths are scoped to the caller and that the write paths refuse before they write.
 *
 * <p><strong>Plain unit test, and that is the point of the class.</strong> Container-backed tests are excluded
 * on the Jenkins agent -- {@code INTEGRATION_TESTS_ENABLED=false}, because it has no Docker -- so
 * {@code CaseworkerSavedQueryControllerTest} proves over real HTTP and a real database which status code each
 * refusal becomes, and runs nowhere in CI. These are the same refusals stated where CI runs them.
 *
 * <p>Three callers, as those classes have: the owner, an unrelated caseworker with no role this service
 * recognises, and an Administrator. A test with one caller cannot tell a per-user rule from no rule at all,
 * because every row would be theirs.
 */
class SavedQuerySecurityTest {

    private static final String CITIZEN = "uk.gov.hmcts.reform.deedpoll.api.citizen";

    private static final UUID QUERY_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    /** An id of the right shape that nothing was ever stored under. */
    private static final UUID UNKNOWN_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private static final CurrentUser OWNER = CurrentUser.currentUser(List.of(), "Olive Owner", "owner-1");

    /** A caller with no role this service recognises: the ordinary Basic User, and the fail-closed case. */
    private static final CurrentUser NO_ROLES = CurrentUser.currentUser(List.of(), "Otto Other", "other-2");

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
    @DisplayName("namespace separation")
    class NamespaceSeparation {

        /**
         * Imported per rule and then dropped: a whole-codebase graph in a static field stays reachable for the
         * whole test JVM, and several of them exhaust its heap.
         */
        private static JavaClasses mainClasses() {
            return new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("uk.gov.hmcts.reform.deedpoll");
        }

        @Test
        @DisplayName("every saved-query route is a caseworker route, by its mapping and not by convention")
        void everyRouteIsACaseworkerRoute() {
            RequestMapping mapping = CaseworkerSavedQueryController.class.getAnnotation(RequestMapping.class);

            // The resource is not nested under a case, unlike every other collection here, so nothing about the
            // path is forced by a parent -- which is exactly why it is worth asserting. A route mounted outside
            // /caseworker would still be authenticated, and would be authenticated for the wrong audience.
            assertThat(mapping).isNotNull();
            assertThat(mapping.value()).containsExactly("/caseworker/saved-queries");
        }

        /**
         * Vacuous today and deliberately written anyway, exactly as {@code NamespaceSeparationSecurityTest}
         * says of its three rules: the citizen namespace is empty until a later plan, so the rule checks an empty
         * set and becomes load-bearing the moment a class is added to it. The point of writing it now is that
         * nobody has to remember to. The two assertions either side of it are not vacuous.
         */
        @Test
        @DisplayName("no citizen class reaches the saved-query domain, its entities or its DTOs")
        void noCitizenClassReachesSavedQueries() {
            // Wider than the DTO rule in NamespaceSeparationSecurityTest, and deliberately: a saved query
            // publishes an IDAM subject as `ownerId` and a library of caseworker views, neither of which is
            // citizen surface. A citizen route that reused the domain service would inherit "shared, or mine"
            // as its visibility rule -- a rule written about caseworkers -- rather than being made to state one.
            noClasses()
                .that().resideInAPackage(CITIZEN + "..")
                .should().dependOnClassesThat()
                .resideInAnyPackage("uk.gov.hmcts.reform.deedpoll.domain.savedqueries..")
                .orShould().dependOnClassesThat().haveSimpleNameStartingWith("SavedQuery")
                .allowEmptyShould(true)
                .check(mainClasses());
        }

        @Test
        @DisplayName("the controller neither returns nor accepts an entity")
        void theControllerNeitherReturnsNorAcceptsAnEntity() {
            // The stored row carries `ownerId`, and a handler that returned it would publish whatever a later
            // column adds without anyone choosing to. Asserted over the mappings rather than read once, so a
            // seventh operation added to this controller has to make the same choice.
            for (Method method : CaseworkerSavedQueryController.class.getDeclaredMethods()) {
                if (method.isSynthetic()) {
                    continue;
                }
                assertThat(packageOf(method.getReturnType()))
                    .as("%s does not return an entity", method.getName())
                    .doesNotContain(".entity");
                for (Class<?> parameter : method.getParameterTypes()) {
                    assertThat(packageOf(parameter))
                        .as("%s does not bind an entity", method.getName())
                        .doesNotContain(".entity");
                }
            }
        }

        /** The declaring package of a type, unwrapping a collection's element only as far as the raw type. */
        private static String packageOf(Class<?> type) {
            return type.getPackage() == null ? "" : type.getPackage().getName();
        }
    }

    @Nested
    @DisplayName("a caller with no roles")
    class NoRoles {

        @Test
        @DisplayName("may save a query and select one, because neither is role-gated")
        void mayStillSaveAndSelect() {
            when(savedQueries.save(any())).thenAnswer(call -> {
                // An id, as a real save allocates one: the audit event is keyed on it.
                SavedQuery written = call.getArgument(0);
                if (written.getId() == null) {
                    written.setId(UUID.randomUUID());
                }
                return written;
            });
            stored(query(true));

            // The half that a fail-closed test on its own would quietly break: gating these because the note and
            // witness deletes are gated would remove a capability every caseworker is meant to have.
            assertThatCode(() -> service.createSavedQuery(
                new CreateSavedQueryInput("Ops", Optional.empty(), false, SavedQueryDefinition.NONE), NO_ROLES))
                .doesNotThrowAnyException();
            assertThatCode(() -> service.addSelection(QUERY_ID.toString(), NO_ROLES))
                .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("may not amend somebody else's shared query, and nothing is written")
        void mayNotAmendSomebodyElsesSharedQuery() {
            stored(query(true));

            assertThatThrownBy(() -> service.updateSavedQuery(QUERY_ID.toString(),
                                                              UpdateSavedQueryInput.EMPTY, NO_ROLES))
                .isInstanceOf(ForbiddenError.class);

            // The refusal has to come before the write, not after it: a 403 that had already saved would be the
            // worst of both answers, and the status code alone cannot tell the two apart.
            verify(savedQueries, never()).save(any());
        }

        @Test
        @DisplayName("may not delete somebody else's shared query, which survives")
        void mayNotDeleteSomebodyElsesSharedQuery() {
            stored(query(true));

            assertThatThrownBy(() -> service.removeSavedQuery(QUERY_ID.toString(), NO_ROLES))
                .isInstanceOf(ForbiddenError.class);

            verify(savedQueries, never()).delete(any());
        }

        @Test
        @DisplayName("is told 403 and not 404, because they can already see what they may not change")
        void isTold403AndNot404() {
            stored(query(true));

            // Both statuses on this resource are deliberate and the pair is the property: 404 conceals an id,
            // and there is nothing left to conceal about a query the caller is looking at.
            assertThatThrownBy(() -> service.removeSavedQuery(QUERY_ID.toString(), NO_ROLES))
                .isInstanceOf(ForbiddenError.class)
                .isNotInstanceOf(NotFoundError.class);
        }
    }

    @Nested
    @DisplayName("one caseworker's selections are not another's")
    class CrossUserSelections {

        @Test
        @DisplayName("the library is flagged from the caller's own selections and nobody else's")
        void theLibraryIsFlaggedFromTheCallersOwn() {
            SavedQuery shared = query(true);
            when(savedQueries.findBySharedTrueOrOwnerIdOrderByNameAsc(anyString())).thenReturn(List.of(shared));
            when(selections.findByIdUserId(OWNER.userId()))
                .thenReturn(List.of(new SavedQuerySelection(QUERY_ID, OWNER.userId())));
            when(selections.findByIdUserId(NO_ROLES.userId())).thenReturn(List.of());

            assertThat(service.findSavedQueries(OWNER)).singleElement()
                .extracting(SavedQueryView::selected).isEqualTo(true);
            // The same shared query, a different caller, the other answer -- which is why `selected` cannot be a
            // column, and why a list that read every selection would put one caseworker's choices in another's
            // navigation bar.
            assertThat(service.findSavedQueries(NO_ROLES)).singleElement()
                .extracting(SavedQueryView::selected).isEqualTo(false);

            // Stated as an interaction as well as an outcome: the user id is in the read rather than in a filter
            // applied to its result, so another caseworker's selections are never loaded at all.
            verify(selections).findByIdUserId(OWNER.userId());
            verify(selections).findByIdUserId(NO_ROLES.userId());
            verify(selections, never()).findByIdUserId(ADMIN.userId());
        }

        @Test
        @DisplayName("reading one query asks whether this caller selected it, not whether anyone did")
        void readingOneQueryAsksAboutThisCaller() {
            stored(query(true));

            assertThat(service.findSavedQuery(QUERY_ID.toString(), NO_ROLES).selected()).isFalse();

            verify(selections).existsByIdSavedQueryIdAndIdUserId(QUERY_ID, NO_ROLES.userId());
            verify(selections, never()).existsByIdSavedQueryIdAndIdUserId(QUERY_ID, OWNER.userId());
        }

        @Test
        @DisplayName("an Administrator's reads are scoped to themselves too, so the override is not a wider view")
        void anAdministratorsReadsAreScopedToo() {
            stored(query(true));

            service.findSavedQuery(QUERY_ID.toString(), ADMIN);

            // The edit override is about changing a shared query, never about seeing more of it: an administrator
            // who could read other people's selections would be reading which caseworkers watch which queries.
            verify(selections).existsByIdSavedQueryIdAndIdUserId(QUERY_ID, ADMIN.userId());
            verify(selections, never()).existsByIdSavedQueryIdAndIdUserId(QUERY_ID, OWNER.userId());
        }
    }

    @Nested
    @DisplayName("an unshared query reached by a non-owner")
    class UnsharedQuery {

        @Test
        @DisplayName("is 404 for a caseworker and for an Administrator alike, in the same words")
        void isNotFoundForEverybodyElse() {
            stored(query(false));

            // Identical to the message an id that never existed gets, so the two cannot be told apart and no id
            // is discoverable by watching status codes. The Administrator case is decision 2's refinement and
            // needs no second check: the visibility rule answers before the edit override is reached.
            String expected = "Saved query " + QUERY_ID + " not found";
            assertThatThrownBy(() -> service.findSavedQuery(QUERY_ID.toString(), NO_ROLES))
                .isInstanceOf(NotFoundError.class).hasMessage(expected);
            assertThatThrownBy(() -> service.findSavedQuery(QUERY_ID.toString(), ADMIN))
                .isInstanceOf(NotFoundError.class).hasMessage(expected);

            when(savedQueries.findById(UNKNOWN_ID)).thenReturn(Optional.empty());
            assertThatThrownBy(() -> service.findSavedQuery(UNKNOWN_ID.toString(), NO_ROLES))
                .isInstanceOf(NotFoundError.class)
                .hasMessage("Saved query " + UNKNOWN_ID + " not found");
        }

        @Test
        @DisplayName("cannot be amended or deleted by either of them, and neither is a 403")
        void cannotBeAmendedOrDeleted() {
            stored(query(false));

            for (CurrentUser caller : List.of(NO_ROLES, ADMIN)) {
                assertThatThrownBy(() -> service.updateSavedQuery(
                    QUERY_ID.toString(), UpdateSavedQueryInput.EMPTY, caller))
                    .isInstanceOf(NotFoundError.class);
                assertThatThrownBy(() -> service.removeSavedQuery(QUERY_ID.toString(), caller))
                    .isInstanceOf(NotFoundError.class);
            }

            // A 403 would confirm the id names something, which is the whole difference between this case and the
            // shared one above.
            verify(savedQueries, never()).save(any());
            verify(savedQueries, never()).delete(any());
        }

        @Test
        @DisplayName("is not in the library, because the read carries the caller's own id")
        void isNotInTheLibrary() {
            service.findSavedQueries(NO_ROLES);

            // "Shared or mine" is in the finder rather than in a filter over everything: an unshared query of
            // somebody else's is not fetched and discarded, it is never selected. A finder taking no user id
            // would have had to be trimmed afterwards, and a trim is a step that can be forgotten.
            verify(savedQueries).findBySharedTrueOrOwnerIdOrderByNameAsc(NO_ROLES.userId());
            verify(savedQueries, never()).findBySharedTrueOrOwnerIdOrderByNameAsc(OWNER.userId());
            verify(savedQueries, never()).findAll();
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
