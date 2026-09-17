package uk.gov.hmcts.reform.deedpoll.domain.auth;

import java.util.Arrays;
import java.util.Optional;

/**
 * Role identifiers.
 *
 * <p>{@link #DEEDPOLL_ADMIN} is ported from {@code reference/ts/auth/roles.ts}, where it is the whole
 * union: it ports the Dynamics security role "Deed Poll Admin User" -- the source's admin gate for
 * privacy editability and the create-time privacy auto-default (see {@code FormPresentationService}).
 * {@link #JUDICIAL} is not a port; the source system had no judicial user at all, because a referral was
 * an email to a Master.
 *
 * <p>The claim value follows the HMCTS IDAM convention, {@code caseworker-{product}-{subrole}}.
 *
 * <p>An enum where the reference has a string union, because the union's whole purpose is that no other
 * string can be a {@code Role} -- and in Java only an enum gives that. The wire value is kept on the
 * member rather than being the member's name: it is what IDAM asserts, so it must not change when a Java
 * name does.
 */
public enum Role {

    DEEDPOLL_ADMIN("caseworker-deedpoll-admin"),

    /**
     * A judge, who may claim a referral out of the shared pool and record a direction on one they hold.
     *
     * <p><strong>IDAM does not issue this yet</strong> -- provisioning it is the idam plan's -- and the
     * gate is nonetheless real and tested today. {@link #fromClaimValue} answers empty for a claim this
     * service has no rule for, so a token that never carries this role simply never passes
     * {@code CurrentUser.requireAnyRole(JUDICIAL)}: the judicial transitions fail closed for every token
     * IDAM currently issues, which is the safe direction for a surface with no login yet. Unit tests
     * supply a {@code CurrentUser} holding it directly, so the transitions are provable without IDAM.
     *
     * <p>{@code caseworker-deedpoll-judicial} is the working claim value, following the convention above.
     * The exact subrole string is the idam plan's to confirm; if it changes it changes here and nowhere
     * else, which is the reason the wire value lives on the member.
     */
    JUDICIAL("caseworker-deedpoll-judicial");

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
