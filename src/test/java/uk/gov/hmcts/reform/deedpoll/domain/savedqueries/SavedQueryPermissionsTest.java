package uk.gov.hmcts.reform.deedpoll.domain.savedqueries;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import uk.gov.hmcts.reform.deedpoll.domain.ForbiddenError;
import uk.gov.hmcts.reform.deedpoll.domain.UnauthorizedError;
import uk.gov.hmcts.reform.deedpoll.domain.auth.CurrentUser;
import uk.gov.hmcts.reform.deedpoll.domain.auth.Role;
import uk.gov.hmcts.reform.deedpoll.entity.SavedQuery;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The whole permission truth table for a saved query, with no database and no HTTP.
 *
 * <p><strong>Written as a plain unit test on purpose.</strong> Container-backed tests are excluded on the
 * Jenkins agent -- {@code INTEGRATION_TESTS_ENABLED=false}, because the agent has no Docker -- so
 * {@code CaseworkerSavedQueryControllerTest} proves the routes are wired to these rules and this proves the
 * rules, which is the half CI runs. Both halves are needed: this one cannot tell whether the service calls it,
 * and that one cannot afford sixteen cases.
 *
 * <p>Every combination is stated rather than sampled, because the interesting cells are the ones a reasonable
 * reading gets wrong: an Administrator does <em>not</em> see somebody else's unshared query, and a caseworker
 * with no roles at all is refused rather than defaulted.
 */
class SavedQueryPermissionsTest {

    private static final CurrentUser OWNER = CurrentUser.currentUser(List.of(), "Olive Owner", "owner-1");
    private static final CurrentUser OTHER = CurrentUser.currentUser(List.of(), "Otto Other", "other-2");
    private static final CurrentUser ADMIN =
        CurrentUser.currentUser(List.of(Role.DEEDPOLL_ADMIN), "An Admin", "admin-3");
    private static final CurrentUser OWNER_WHO_IS_ADMIN =
        CurrentUser.currentUser(List.of(Role.DEEDPOLL_ADMIN), "Olive Owner", "owner-1");

    /** A caller with no role this service recognises: the ordinary Basic User, and the fail-closed case. */
    private static final CurrentUser NO_ROLES = CurrentUser.currentUser(List.of(), "No Roles", "no-roles-4");

    @Nested
    @DisplayName("who can see a saved query")
    class Visibility {

        @Test
        @DisplayName("the owner sees their own, shared or not")
        void theOwnerSeesTheirOwn() {
            assertThat(SavedQueryPermissions.visibleTo(query(false), OWNER)).isTrue();
            assertThat(SavedQueryPermissions.visibleTo(query(true), OWNER)).isTrue();
        }

        @Test
        @DisplayName("anybody sees a shared one")
        void anybodySeesASharedOne() {
            assertThat(SavedQueryPermissions.visibleTo(query(true), OTHER)).isTrue();
            assertThat(SavedQueryPermissions.visibleTo(query(true), NO_ROLES)).isTrue();
        }

        @Test
        @DisplayName("nobody else sees an unshared one")
        void nobodyElseSeesAnUnsharedOne() {
            assertThat(SavedQueryPermissions.visibleTo(query(false), OTHER)).isFalse();
        }

        @Test
        @DisplayName("an Administrator does not see somebody else's unshared one either")
        void anAdministratorDoesNotSeeAnUnsharedOne() {
            // Decision 2's refinement, and the cell most likely to be "fixed" by someone tidying the override up:
            // an unshared query is nobody else's business, and an orphaned private one harms nobody because
            // nobody else can see it. This is also what makes the 404 in the service the only answer an
            // administrator can get for another user's private query -- there is no 403 branch to reach.
            assertThat(SavedQueryPermissions.visibleTo(query(false), ADMIN)).isFalse();
            assertThat(SavedQueryPermissions.visibleTo(query(true), ADMIN)).isTrue();
        }

        @Test
        @DisplayName("ownership is by subject, not by display name")
        void ownershipIsBySubject() {
            // Two people called Olive Owner are two owners, and one person who changes their name is still one.
            // A display name as the owner key would reassign queries when somebody marries.
            CurrentUser sameNameDifferentPerson =
                CurrentUser.currentUser(List.of(), "Olive Owner", "someone-else-9");

            assertThat(SavedQueryPermissions.visibleTo(query(false), sameNameDifferentPerson)).isFalse();
            assertThat(SavedQueryPermissions.editableBy(query(true), sameNameDifferentPerson)).isFalse();
        }

        @Test
        @DisplayName("a caller with no subject is refused rather than matched against a null owner")
        void callerWithNoSubjectIsRefused() {
            // Fails closed, and specifically: an unidentified caller must not become "the same anonymous
            // everybody" who owns every query with a null ownerId. The column is NOT NULL, so the comparison
            // would in fact never match -- but relying on that leaves the rule true by accident.
            //
            // Asserted on a SHARED query on purpose. That is the case where the identity is not needed to answer
            // the question, so it is the one a short-circuiting `shared || requireUserId()...` would let through
            // -- leaving the guard present exactly where it is least useful.
            assertThatThrownBy(() -> SavedQueryPermissions.visibleTo(query(true), CurrentUser.ANONYMOUS))
                .isInstanceOf(UnauthorizedError.class);
            assertThatThrownBy(() -> SavedQueryPermissions.visibleTo(query(false), CurrentUser.ANONYMOUS))
                .isInstanceOf(UnauthorizedError.class);
            assertThatThrownBy(() -> SavedQueryPermissions.editableBy(query(true), CurrentUser.ANONYMOUS))
                .isInstanceOf(UnauthorizedError.class);
        }
    }

    @Nested
    @DisplayName("who can change a saved query")
    class Editability {

        @Test
        @DisplayName("the owner may, shared or not")
        void theOwnerMay() {
            assertThat(SavedQueryPermissions.editableBy(query(false), OWNER)).isTrue();
            assertThat(SavedQueryPermissions.editableBy(query(true), OWNER)).isTrue();
        }

        @Test
        @DisplayName("an Administrator may, but only a shared one")
        void anAdministratorMayChangeASharedOne() {
            assertThat(SavedQueryPermissions.editableBy(query(true), ADMIN)).isTrue();
            // Unreachable in practice, because the visibility rule answers first -- asserted anyway, so that the
            // override cannot quietly widen if the two rules are ever composed in the other order.
            assertThat(SavedQueryPermissions.editableBy(query(false), ADMIN)).isFalse();
        }

        @Test
        @DisplayName("a caseworker with no roles may not change somebody else's, shared or not")
        void callerWithNoRolesMayNot() {
            assertThat(SavedQueryPermissions.editableBy(query(true), NO_ROLES)).isFalse();
            assertThat(SavedQueryPermissions.editableBy(query(false), NO_ROLES)).isFalse();
        }

        @Test
        @DisplayName("an owner who is also an Administrator may, so the two rules OR rather than conflict")
        void anOwnerWhoIsAlsoAnAdministratorMay() {
            assertThat(SavedQueryPermissions.editableBy(query(false), OWNER_WHO_IS_ADMIN)).isTrue();
        }

        @Test
        @DisplayName("the refusal is a ForbiddenError naming both permitted callers")
        void theRefusalNamesBothPermittedCallers() {
            assertThatThrownBy(() -> SavedQueryPermissions.requireEditableBy(query(true), OTHER))
                .isInstanceOf(ForbiddenError.class)
                .hasMessage(SavedQueryPermissions.NOT_YOURS);
        }

        @Test
        @DisplayName("and it is silent for a caller who may")
        void itIsSilentForACallerWhoMay() {
            assertThatCode(() -> SavedQueryPermissions.requireEditableBy(query(true), OWNER))
                .doesNotThrowAnyException();
            assertThatCode(() -> SavedQueryPermissions.requireEditableBy(query(true), ADMIN))
                .doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("who can publish a saved query")
    class Publishing {

        @Test
        @DisplayName("an Administrator may")
        void anAdministratorMay() {
            assertThat(SavedQueryPermissions.publishableBy(ADMIN)).isTrue();
            assertThat(SavedQueryPermissions.publishableBy(OWNER_WHO_IS_ADMIN)).isTrue();
        }

        @Test
        @DisplayName("a caseworker with no roles may not, which is the Basic User and the ordinary case")
        void caseworkerWithNoRolesMayNot() {
            assertThat(SavedQueryPermissions.publishableBy(NO_ROLES)).isFalse();
            // The owner of the query is no more entitled to publish it than anybody else: this is the one
            // permission here that ownership does not buy.
            assertThat(SavedQueryPermissions.publishableBy(OWNER)).isFalse();
        }

        @Test
        @DisplayName("holding some other role is not holding the admin one")
        void holdingAnotherRoleIsNotHoldingTheAdminOne() {
            // `isAdmin` is derived from the role list rather than passed, so this cannot pass by accident -- but
            // it is the cell a reasonable reading gets wrong, judicial being the more privileged-sounding role.
            CurrentUser judge = CurrentUser.currentUser(List.of(Role.JUDICIAL), "A Judge", "judge-5");

            assertThat(SavedQueryPermissions.publishableBy(judge)).isFalse();
        }

        @Test
        @DisplayName("a caller with no subject is refused rather than asked about its roles")
        void callerWithNoSubjectIsRefused() {
            // Ordered the same way as the other two predicates, and for the same reason: an unidentified caller
            // must not reach a question about privileges at all. An admin-shaped token with no `sub` would
            // otherwise publish.
            CurrentUser unidentified = CurrentUser.currentUser(List.of(Role.DEEDPOLL_ADMIN), "No Subject", null);

            assertThatThrownBy(() -> SavedQueryPermissions.publishableBy(unidentified))
                .isInstanceOf(UnauthorizedError.class);
        }

        @Test
        @DisplayName("the refusal is a ForbiddenError naming the rule rather than the caller")
        void theRefusalNamesTheRule() {
            assertThatThrownBy(() -> SavedQueryPermissions.requirePublishableBy(NO_ROLES))
                .isInstanceOf(ForbiddenError.class)
                .hasMessage(SavedQueryPermissions.NOT_PUBLISHABLE);
        }

        @Test
        @DisplayName("and it is silent for an Administrator")
        void itIsSilentForAnAdministrator() {
            assertThatCode(() -> SavedQueryPermissions.requirePublishableBy(ADMIN)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("it asks about the caller only, so the query's own state cannot widen it")
        void itAsksAboutTheCallerOnly() {
            // The signature takes no query, which is the structural half of decision 4 -- there is no row whose
            // `shared` or `ownerId` could make a non-Administrator publishable. Stated as a test because the
            // obvious "improvement" is to pass the query in for symmetry with the other two.
            assertThat(SavedQueryPermissions.publishableBy(OWNER)).isFalse();
            assertThat(SavedQueryPermissions.publishableBy(ADMIN)).isTrue();
        }
    }

    /**
     * A query owned by {@link #OWNER}, shared or not.
     */
    private static SavedQuery query(boolean shared) {
        SavedQuery query = new SavedQuery();
        query.setName("Enroled this month");
        query.setOwnerId(OWNER.userId());
        query.setOwnerName(OWNER.name());
        query.setShared(shared);
        return query;
    }
}
