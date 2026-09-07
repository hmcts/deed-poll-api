package uk.gov.hmcts.reform.deedpoll.domain.auth;

import java.util.Arrays;
import java.util.Optional;

/**
 * Role identifiers.
 *
 * <p>Ported from {@code reference/ts/auth/roles.ts}. The single privileged role ports the Dynamics
 * security role "Deed Poll Admin User" -- the source's admin gate for privacy editability and the
 * create-time privacy auto-default (see {@code FormPresentationService}).
 *
 * <p>The claim value follows the HMCTS IDAM convention, {@code caseworker-{product}-{subrole}}.
 *
 * <p>An enum where the reference has a string union of one member, because the union's whole purpose is
 * that no other string can be a {@code Role} -- and in Java only an enum gives that. The wire value is
 * kept on the member rather than being the member's name: it is what IDAM asserts, so it must not change
 * when a Java name does.
 */
public enum Role {

    DEEDPOLL_ADMIN("caseworker-deedpoll-admin");

    private final String claimValue;

    Role(String claimValue) {
        this.claimValue = claimValue;
    }

    /** The string IDAM asserts for this role. */
    public String claimValue() {
        return claimValue;
    }

    /**
     * The role this claim value names, or empty if this service has no rules for it.
     *
     * <p>Empty rather than an exception: IDAM issues roles across the whole CFT estate, so a
     * caseworker's token routinely names roles that belong to other services entirely, and that is
     * ordinary rather than exceptional.
     */
    public static Optional<Role> fromClaimValue(String claimValue) {
        return Arrays.stream(values()).filter(role -> role.claimValue.equals(claimValue)).findFirst();
    }
}
