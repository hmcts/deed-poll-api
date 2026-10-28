package uk.gov.hmcts.reform.deedpoll.domain.auth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ports every test block in {@code reference/ts/auth/idam-claims.test.ts}.
 *
 * <p>The reference's object literals become maps, and its {@code undefined} becomes an absent key -- Java has
 * one absence where JavaScript has two, which collapses the reference's "absent or an unexpected shape" case
 * from five values to four. The same deviation the reference-number and case-state ports already record.
 */
class IdamClaimsTest {

    private static final String ADMIN = Role.DEEDPOLL_ADMIN.claimValue();

    private static Map<String, Object> claims(Object... keysAndValues) {
        Map<String, Object> raw = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            raw.put(String.valueOf(keysAndValues[i]), keysAndValues[i + 1]);
        }
        return raw;
    }

    @Test
    @DisplayName("keeps a role this service recognises")
    void keepsAKnownRole() {
        CurrentUser user = IdamClaims.userFromClaims(claims("roles", List.of(ADMIN)));

        assertThat(user.roles()).containsExactly(Role.DEEDPOLL_ADMIN);
        assertThat(user.isAdmin()).isTrue();
    }

    @Test
    @DisplayName("drops roles from other CFT services rather than carrying them")
    void dropsUnknownRoles() {
        // IDAM issues roles across the whole estate, so a caseworker's token names roles this service has no
        // rules for. Carrying them would put unmanaged strings into the object authorization decisions are
        // made from.
        CurrentUser user = IdamClaims.userFromClaims(
            claims("roles", List.of("caseworker-civil", "citizen", ADMIN, "judiciary")));

        assertThat(user.roles()).containsExactly(Role.DEEDPOLL_ADMIN);
    }

    @Test
    @DisplayName("is not an admin when the token names no role this service knows")
    void isNotAdminWithoutAKnownRole() {
        CurrentUser user = IdamClaims.userFromClaims(claims("roles", List.of("caseworker-civil")));

        assertThat(user.roles()).isEmpty();
        assertThat(user.isAdmin()).isFalse();
    }

    @Test
    @DisplayName("accepts roles as a comma-separated string, which some IDAM configurations send")
    void acceptsCommaSeparatedRoles() {
        // Getting this wrong yields an empty role list: fails closed, but looks like a permissions bug rather
        // than a parsing one, which is the expensive kind to diagnose.
        assertThat(IdamClaims.userFromClaims(claims("roles", "citizen, " + ADMIN)).isAdmin()).isTrue();
    }

    @Test
    @DisplayName("trims whitespace around a role")
    void trimsWhitespaceAroundARole() {
        assertThat(IdamClaims.userFromClaims(claims("roles", List.of(" " + ADMIN + " "))).isAdmin()).isTrue();
    }

    @ParameterizedTest(name = "roles = {0}")
    @MethodSource("unexpectedRoleShapes")
    @DisplayName("fails closed when roles are absent or an unexpected shape")
    void failsClosedOnAnUnexpectedRolesShape(Object roles) {
        assertThat(IdamClaims.userFromClaims(claims("roles", roles)).roles()).isEmpty();
    }

    private static Stream<Object> unexpectedRoleShapes() {
        // The reference also passes `undefined`; an absent key is the same thing here, and is covered by
        // "falls back to the service account only when nothing identifies the user" below.
        return Stream.of(null, 42, Map.of(), true);
    }

    @Test
    @DisplayName("prefers the name claim for the audit columns")
    void prefersTheNameClaim() {
        assertThat(IdamClaims.userFromClaims(claims("name", "A Caseworker")).name()).isEqualTo("A Caseworker");
    }

    @Test
    @DisplayName("composes given and family names when there is no name claim")
    void composesGivenAndFamilyNames() {
        assertThat(IdamClaims.userFromClaims(claims("given_name", "Ada", "family_name", "Lovelace")).name())
            .isEqualTo("Ada Lovelace");
    }

    @Test
    @DisplayName("uses whichever of given or family name is present")
    void usesWhicheverNamePartIsPresent() {
        assertThat(IdamClaims.userFromClaims(claims("given_name", "Ada")).name()).isEqualTo("Ada");
        assertThat(IdamClaims.userFromClaims(claims("family_name", "Lovelace")).name()).isEqualTo("Lovelace");
    }

    @Test
    @DisplayName("falls back to sub before the service account, so a real person is not logged as automation")
    void fallsBackToSub() {
        // createdBy reading as an opaque identifier is still traceable; attributing a person's write to the
        // service account is not.
        assertThat(IdamClaims.userFromClaims(claims("sub", "idam-user-123")).name()).isEqualTo("idam-user-123");
    }

    @Test
    @DisplayName("falls back to the service account only when nothing identifies the user")
    void fallsBackToTheServiceAccount() {
        assertThat(IdamClaims.userFromClaims(Map.of()).name()).isEqualTo(CurrentUser.SERVICE_ACCOUNT_NAME);
    }

    @Test
    @DisplayName("ignores a blank name rather than storing an empty audit value")
    void ignoresABlankName() {
        assertThat(IdamClaims.userFromClaims(claims("name", "   ", "sub", "idam-user-123")).name())
            .isEqualTo("idam-user-123");
    }

    @Test
    @DisplayName("ignores extra claims it does not read")
    void ignoresExtraClaims() {
        CurrentUser user = IdamClaims.userFromClaims(claims(
            "roles", List.of(ADMIN), "name", "A", "iss", "x", "aud", "y", "exp", 1, "custom", Map.of()));

        assertThat(user.isAdmin()).isTrue();
        assertThat(user.name()).isEqualTo("A");
    }

    /**
     * Not in the reference, which gets it from the type system: {@code userFromClaims} returns a
     * {@code CurrentUser}, so a name is never absent. Here it is worth asserting, because the audit columns
     * are {@code NOT NULL} and a null name would fail the write rather than the read.
     */
    @Test
    @DisplayName("never yields a blank author, whatever the claims say")
    void neverYieldsABlankAuthor() {
        assertThat(IdamClaims.userFromClaims(claims("name", " ", "given_name", "", "sub", "  ")).name())
            .isEqualTo(CurrentUser.SERVICE_ACCOUNT_NAME);
    }

    /**
     * Not in the reference. Jackson gives an array claim as a {@code List}, but a userinfo response is a
     * document controlled elsewhere and a non-string member is exactly the "unexpected shape" the reference
     * guards the whole claim against -- one member should not lose the others.
     */
    @Test
    @DisplayName("ignores a non-string member of the roles array without dropping the rest")
    void ignoresANonStringRoleMember() {
        List<Object> roles = List.of(42, ADMIN);

        assertThat(IdamClaims.userFromClaims(claims("roles", roles)).roles()).containsExactly(Role.DEEDPOLL_ADMIN);
    }

    @Test
    @DisplayName("roleNames keeps every role as IDAM spelt it, including the ones userFromClaims drops")
    void roleNamesKeepsUnknownRoles() {
        // Not in the reference: the CCD persistence chain's one reader, which maps a configured role.
        assertThat(IdamClaims.roleNames(claims("roles", List.of(" caseworker-civil ", ADMIN, "", "system-role"))))
            .containsExactly("caseworker-civil", ADMIN, "system-role");
        assertThat(IdamClaims.roleNames(claims("roles", "a, b"))).containsExactly("a", "b");
        assertThat(IdamClaims.roleNames(claims())).isEmpty();
    }

    @Test
    @DisplayName("carries userinfo's email, trimmed and lower-cased")
    void carriesTheEmail() {
        // Not in the reference: the one reader is linking a Masters-list entry.
        assertThat(IdamClaims.userFromClaims(claims("sub", "s", "email", " Judge@Example.COM ")).email())
            .isEqualTo("judge@example.com");
    }

    @Test
    @DisplayName("has no email when the claim is absent, blank, malformed or not a string")
    void hasNoEmailWhenTheClaimIsUnusable() {
        assertThat(IdamClaims.userFromClaims(claims("sub", "s")).email()).isNull();
        assertThat(IdamClaims.userFromClaims(claims("sub", "s", "email", "  ")).email()).isNull();
        assertThat(IdamClaims.userFromClaims(claims("sub", "s", "email", "judge.example.com")).email()).isNull();
        assertThat(IdamClaims.userFromClaims(claims("sub", "s", "email", List.of("a@b.c"))).email()).isNull();
    }
}
