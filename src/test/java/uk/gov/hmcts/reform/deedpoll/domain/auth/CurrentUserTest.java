package uk.gov.hmcts.reform.deedpoll.domain.auth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import uk.gov.hmcts.reform.deedpoll.domain.ForbiddenError;
import uk.gov.hmcts.reform.deedpoll.domain.deedpoll.WriteContext;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Ports {@code currentUser()} from {@code reference/ts/auth/current-user.ts} and {@code requireRoles} from
 * {@code apps/api/src/auth/require-roles.ts}.
 *
 * <p>Neither has a test file of its own in the reference: {@code current-user.ts} is exercised entirely through
 * {@code idam-claims.test.ts}, and {@code require-roles.test.ts} drives Express middleware, which has no
 * equivalent to port. The behaviour does, so it is asserted here directly rather than left to the transport
 * tests -- an authorisation rule that is only tested through a filter chain is a rule nobody can read.
 */
class CurrentUserTest {

    @Nested
    @DisplayName("currentUser")
    class Construction {

        @Test
        @DisplayName("derives isAdmin from the roles rather than taking it on trust")
        void derivesIsAdmin() {
            assertThat(CurrentUser.currentUser(List.of(Role.DEEDPOLL_ADMIN), "A Caseworker").isAdmin()).isTrue();
            assertThat(CurrentUser.currentUser(List.of(), "A Caseworker").isAdmin()).isFalse();
        }

        @Test
        @DisplayName("trims the display name")
        void trimsTheName() {
            assertThat(CurrentUser.currentUser(List.of(), "  A Caseworker  ").name()).isEqualTo("A Caseworker");
        }

        @Test
        @DisplayName("labels a caller with no name as the service account")
        void labelsAnUnnamedCallerAsTheServiceAccount() {
            assertThat(CurrentUser.currentUser(List.of(), null).name()).isEqualTo(CurrentUser.SERVICE_ACCOUNT_NAME);
            assertThat(CurrentUser.currentUser(List.of(), "   ").name()).isEqualTo(CurrentUser.SERVICE_ACCOUNT_NAME);
        }

        @Test
        @DisplayName("is nobody in particular by default")
        void anonymousIsNobodyInParticular() {
            assertThat(CurrentUser.ANONYMOUS.roles()).isEmpty();
            assertThat(CurrentUser.ANONYMOUS.isAdmin()).isFalse();
            assertThat(CurrentUser.ANONYMOUS.name()).isEqualTo(CurrentUser.SERVICE_ACCOUNT_NAME);
        }

        /**
         * Not in the reference, which gets it from TypeScript's {@code readonly}: the roles a caller holds
         * must not be editable through the caller, or an authorisation decision could be changed after the
         * fact by whatever holds the list.
         */
        @Test
        @DisplayName("does not let the role list be edited after the caller is built")
        void rolesAreNotEditable() {
            CurrentUser user = CurrentUser.currentUser(List.of(Role.DEEDPOLL_ADMIN), "A");

            assertThatThrownBy(() -> user.roles().add(Role.DEEDPOLL_ADMIN))
                .isInstanceOf(UnsupportedOperationException.class);
        }
    }

    @Nested
    @DisplayName("requireAnyRole")
    class RequireAnyRole {

        @Test
        @DisplayName("permits a caller who holds one of the required roles")
        void permitsAHolder() {
            CurrentUser.currentUser(List.of(Role.DEEDPOLL_ADMIN), "A").requireAnyRole(Role.DEEDPOLL_ADMIN);
        }

        @Test
        @DisplayName("refuses a caller who holds none of them, naming what is required")
        void refusesANonHolder() {
            CurrentUser user = CurrentUser.currentUser(List.of(), "A");

            assertThatThrownBy(() -> user.requireAnyRole(Role.DEEDPOLL_ADMIN))
                .isInstanceOf(ForbiddenError.class)
                .hasMessage("Requires one of the following roles: " + Role.DEEDPOLL_ADMIN.claimValue());
        }

        @Test
        @DisplayName("requiring nothing is open, so a dynamically built list cannot fail closed")
        void requiringNothingIsOpen() {
            CurrentUser.currentUser(List.of(), "A").requireAnyRole();
        }
    }

    @Nested
    @DisplayName("the contexts a write needs")
    class WriteContexts {

        @Test
        @DisplayName("carries both the admin flag and the author into a case write")
        void carriesTheAdminFlagAndAuthor() {
            WriteContext context = CurrentUser.currentUser(List.of(Role.DEEDPOLL_ADMIN), "A Caseworker")
                .writeContext();

            assertThat(context.isAdmin()).isTrue();
            assertThat(context.author()).isEqualTo("A Caseworker");
        }

        @Test
        @DisplayName("carries only the author into a note write")
        void carriesOnlyTheAuthorIntoANoteWrite() {
            assertThat(CurrentUser.currentUser(List.of(Role.DEEDPOLL_ADMIN), "A Caseworker").noteAuthor().author())
                .isEqualTo("A Caseworker");
        }

        @Test
        @DisplayName("attributes an unidentified caller's writes to the service account")
        void attributesAnUnidentifiedCaller() {
            CurrentUser user = CurrentUser.ANONYMOUS;

            assertThat(user.writeContext().author()).isEqualTo(CurrentUser.SERVICE_ACCOUNT_NAME);
            assertThat(user.noteAuthor().author()).isEqualTo(CurrentUser.SERVICE_ACCOUNT_NAME);
        }

        /**
         * The constant moved here with the auth port, so this asserts the alias {@link WriteContext} keeps
         * still names the same string -- a second copy of it would be a second thing to get wrong, and the
         * value is what the source system's automated writes are recorded as.
         */
        @Test
        @DisplayName("is one service-account name, not two")
        void oneServiceAccountName() {
            assertThat(WriteContext.SERVICE_ACCOUNT_NAME).isEqualTo(CurrentUser.SERVICE_ACCOUNT_NAME);
        }
    }
}
