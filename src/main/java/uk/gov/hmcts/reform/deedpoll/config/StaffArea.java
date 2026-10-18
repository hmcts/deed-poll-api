package uk.gov.hmcts.reform.deedpoll.config;

import org.springframework.security.core.GrantedAuthority;
import uk.gov.hmcts.reform.deedpoll.domain.auth.Role;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * The staff namespaces, and which roles may enter each: the one table {@link SecurityConfiguration} builds its rules
 * from and {@code StaffAccessRulesTest} checks every mapped route against.
 *
 * <p><strong>Membership, not capability.</strong> This decides whether a caller is staff of this service at all,
 * which authentication alone never did: until this table, any token IDAM issued could read and write every case.
 * What a member may then do is decided by the domain's capability and judicial-role checks.
 *
 * <ul>
 *   <li>{@link #CASEWORKER} admits Team leaders, Delivery Managers and temporary legacy administrators
 *       without an additional caseworker role. Gazette access still requires its separate capability.</li>
 *   <li>{@link #JUDICIAL} admits only the judicial role. Nothing on the judicial surface calls the caseworker
 *       one. A judge who also needs case search is given the caseworker role, which is an assignment and not a
 *       code change.</li>
 * </ul>
 */
public enum StaffArea {

    CASEWORKER("/caseworker", Role.CASEWORKER, Role.TEAM_LEADER, Role.DELIVERY_MANAGER, Role.DEEDPOLL_ADMIN),

    JUDICIAL("/judicial", Role.JUDICIAL);

    private final String prefix;
    private final List<Role> roles;

    StaffArea(String prefix, Role... roles) {
        this.prefix = prefix;
        this.roles = List.of(roles);
    }

    /**
     * The path segment every route in this area starts with, such as {@code /caseworker}.
     */
    public String prefix() {
        return prefix;
    }

    /** The request matcher pattern covering the whole area. */
    public String pattern() {
        return prefix + "/**";
    }

    /**
     * The authorities that admit a caller, as {@code IdamAuthentication} publishes them.
     */
    public String[] authorities() {
        return roles.stream().map(Role::claimValue).toArray(String[]::new);
    }

    /** Whether a caller holding these authorities is staff of this area. */
    public boolean admits(Collection<? extends GrantedAuthority> held) {
        List<String> wanted = Arrays.asList(authorities());
        return held.stream().map(GrantedAuthority::getAuthority).anyMatch(wanted::contains);
    }

    /**
     * The refusal, in the same words the domain's {@code requireAnyRole} uses for the same roles.
     */
    public String refusal() {
        return "Requires one of the following roles: " + String.join(", ", authorities());
    }

    /** The area a request path is in, or empty for a path outside every staff namespace. */
    public static Optional<StaffArea> of(String path) {
        return Arrays.stream(values())
            .filter(area -> path.equals(area.prefix) || path.startsWith(area.prefix + "/"))
            .findFirst();
    }
}
