package uk.gov.hmcts.reform.deedpoll.domain.auth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import uk.gov.hmcts.reform.deedpoll.domain.ForbiddenError;
import uk.gov.hmcts.reform.deedpoll.domain.UnauthorizedError;
import uk.gov.hmcts.reform.deedpoll.domain.deedpoll.WriteContext;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
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

    /** An IDAM subject: opaque, stable, and not a display name. */
    private static final String SUBJECT = "idam-user-123";

    @Nested
    @DisplayName("currentUser")
    class Construction {

        @Test
        @DisplayName("derives isAdmin from the roles rather than taking it on trust")
        void derivesIsAdmin() {
            assertThat(CurrentUser.currentUser(List.of(Role.DEEDPOLL_ADMIN), "A Caseworker", SUBJECT).isAdmin())
                .isTrue();
            assertThat(CurrentUser.currentUser(List.of(), "A Caseworker", SUBJECT).isAdmin()).isFalse();
        }

        @Test
        @DisplayName("trims the display name")
        void trimsTheName() {
            assertThat(CurrentUser.currentUser(List.of(), "  A Caseworker  ", SUBJECT).name())
                .isEqualTo("A Caseworker");
        }

        @Test
        @DisplayName("labels a caller with no name as the service account")
        void labelsAnUnnamedCallerAsTheServiceAccount() {
            assertThat(CurrentUser.currentUser(List.of(), null, SUBJECT).name())
                .isEqualTo(CurrentUser.SERVICE_ACCOUNT_NAME);
            assertThat(CurrentUser.currentUser(List.of(), "   ", SUBJECT).name())
                .isEqualTo(CurrentUser.SERVICE_ACCOUNT_NAME);
        }

        @Test
        @DisplayName("is nobody in particular by default")
        void anonymousIsNobodyInParticular() {
            assertThat(CurrentUser.ANONYMOUS.roles()).isEmpty();
            assertThat(CurrentUser.ANONYMOUS.isAdmin()).isFalse();
            assertThat(CurrentUser.ANONYMOUS.name()).isEqualTo(CurrentUser.SERVICE_ACCOUNT_NAME);
            assertThat(CurrentUser.ANONYMOUS.userId()).isNull();
        }
    }

    /** Not in the reference: the address that links a Masters-list entry (plan D1), and authorises nothing. */
    @Nested
    @DisplayName("email")
    class Email {

        @Test
        @DisplayName("is trimmed and lower-cased")
        void isNormalised() {
            assertThat(CurrentUser.currentUser(List.of(), "A", SUBJECT, "  Master.Sullivan@Example.GOV.uk ")
                           .email()).isEqualTo("master.sullivan@example.gov.uk");
        }

        @Test
        @DisplayName("is null when absent, blank or without an @")
        void isNullWhenMalformed() {
            assertThat(CurrentUser.currentUser(List.of(), "A", SUBJECT, null).email()).isNull();
            assertThat(CurrentUser.currentUser(List.of(), "A", SUBJECT, "   ").email()).isNull();
            assertThat(CurrentUser.currentUser(List.of(), "A", SUBJECT, "not-an-address").email()).isNull();
        }

        @Test
        @DisplayName("is null for every caller not built with one")
        void isNullWithoutOne() {
            assertThat(CurrentUser.currentUser(List.of(), "A", SUBJECT).email()).isNull();
            assertThat(CurrentUser.ANONYMOUS.email()).isNull();
        }

        @Test
        @DisplayName("is left out of toString, which a debug line prints whole")
        void isNotInToString() {
            CurrentUser user = CurrentUser.currentUser(List.of(Role.JUDICIAL), "A Master", SUBJECT, "a@example.com");

            assertThat(user.toString()).doesNotContain("a@example.com").contains("A Master", SUBJECT);
        }

        @Test
        @DisplayName("is left out of toString when it is the subject, as HMCTS IDAM gives it")
        void subjectAddressIsNotInToString() {
            CurrentUser user = IdamClaims.userFromClaims(Map.of("sub", "Judge@Example.com"));

            assertThat(user.email()).isEqualTo("judge@example.com");
            assertThat(user.toString()).doesNotContainIgnoringCase("judge@example.com").contains("redacted");
        }
    }

    /**
     * The caller's stable id, which the reference interface does not have.
     *
     * <p>Added because a saved query is owned by a caseworker, and a display name is not an identity: two
     * caseworkers can share one and one caseworker's changes over time, so a name as the owner key would
     * reassign queries when somebody marries.
     */
    @Nested
    @DisplayName("userId")
    class Identity {

        @Test
        @DisplayName("carries the token's subject, trimmed")
        void carriesTheSubject() {
            assertThat(CurrentUser.currentUser(List.of(), "A", SUBJECT).userId()).isEqualTo(SUBJECT);
            assertThat(CurrentUser.currentUser(List.of(), "A", "  " + SUBJECT + "  ").userId())
                .isEqualTo(SUBJECT);
            assertThat(CurrentUser.currentUser(List.of(), "A", SUBJECT).requireUserId()).isEqualTo(SUBJECT);
        }

        @Test
        @DisplayName("gets no fallback where the name gets the service account")
        void getsNoFallback() {
            // Deliberately unlike the name. An unattributed write is still a write worth recording, so a
            // missing name is substituted; an unidentified owner is not an owner, and a placeholder would make
            // every such caller the same person -- each able to edit and delete the others' queries.
            assertThat(CurrentUser.currentUser(List.of(), "A", null).userId()).isNull();
            assertThat(CurrentUser.currentUser(List.of(), "A", "   ").userId()).isNull();
        }

        @Test
        @DisplayName("refuses a caller with no subject rather than answering null")
        void refusesACallerWithNoSubject() {
            // 401 rather than 403: the caller has not been identified well enough to be refused for who they
            // are. Every authorisation decision about an owned resource reads it through this method, so the
            // fail-closed answer is stated once rather than at each call site.
            assertThatThrownBy(() -> CurrentUser.ANONYMOUS.requireUserId())
                .isInstanceOf(UnauthorizedError.class);
        }

        @Test
        @DisplayName("is not the display name, even where IDAM sends only a subject")
        void isNotTheDisplayName() {
            // IdamClaims falls back to `sub` for the display name, so the two can hold the same text -- and
            // they are still different fields with different rules, which this pins.
            CurrentUser user = CurrentUser.currentUser(List.of(), SUBJECT, SUBJECT);

            assertThat(user.name()).isEqualTo(SUBJECT);
            assertThat(user.userId()).isEqualTo(SUBJECT);
            assertThat(CurrentUser.currentUser(List.of(), "A Caseworker", SUBJECT).userId())
                .isNotEqualTo("A Caseworker");
        }

        /**
         * Not in the reference, which gets it from TypeScript's {@code readonly}: the roles a caller holds
         * must not be editable through the caller, or an authorisation decision could be changed after the
         * fact by whatever holds the list.
         */
        @Test
        @DisplayName("does not let the role list be edited after the caller is built")
        void rolesAreNotEditable() {
            CurrentUser user = CurrentUser.currentUser(List.of(Role.DEEDPOLL_ADMIN), "A", SUBJECT);

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
            CurrentUser user = CurrentUser.currentUser(List.of(Role.DEEDPOLL_ADMIN), "A", SUBJECT);

            assertThatCode(() -> user.requireAnyRole(Role.DEEDPOLL_ADMIN)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("refuses a caller who holds none of them, naming what is required")
        void refusesANonHolder() {
            CurrentUser user = CurrentUser.currentUser(List.of(), "A", SUBJECT);

            assertThatThrownBy(() -> user.requireAnyRole(Role.DEEDPOLL_ADMIN))
                .isInstanceOf(ForbiddenError.class)
                .hasMessage("Requires one of the following roles: " + Role.DEEDPOLL_ADMIN.claimValue());
        }

        @Test
        @DisplayName("requiring nothing is open, so a dynamically built list cannot fail closed")
        void requiringNothingIsOpen() {
            CurrentUser user = CurrentUser.currentUser(List.of(), "A", SUBJECT);

            assertThatCode(user::requireAnyRole).doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("the contexts a write needs")
    class WriteContexts {

        @Test
        @DisplayName("carries the admin flag, the author and the subject into a case write")
        void carriesTheAdminFlagAndAuthor() {
            WriteContext context = CurrentUser.currentUser(List.of(Role.DEEDPOLL_ADMIN), "A Caseworker", SUBJECT)
                .writeContext();

            assertThat(context.isAdmin()).isTrue();
            assertThat(context.author()).isEqualTo("A Caseworker");
            assertThat(context.userId()).isEqualTo(SUBJECT);
        }

        @Test
        @DisplayName("carries only the author into a note write")
        void carriesOnlyTheAuthorIntoANoteWrite() {
            assertThat(CurrentUser.currentUser(List.of(Role.DEEDPOLL_ADMIN), "A Caseworker", SUBJECT)
                           .noteAuthor().author())
                .isEqualTo("A Caseworker");
        }

        @Test
        @DisplayName("attributes an unidentified caller's writes to the service account")
        void attributesAnUnidentifiedCaller() {
            CurrentUser user = CurrentUser.ANONYMOUS;

            assertThat(user.writeContext().author()).isEqualTo(CurrentUser.SERVICE_ACCOUNT_NAME);
            assertThat(user.writeContext().userId()).isNull();
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
