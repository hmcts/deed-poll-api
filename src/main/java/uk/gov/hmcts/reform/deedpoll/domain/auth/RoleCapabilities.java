package uk.gov.hmcts.reform.deedpoll.domain.auth;

import java.util.Collection;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/** Explicit application grants, independent of role ordering and IDAM role inheritance. */
public final class RoleCapabilities {

    private static final Set<Capability> TEAM_LEADER = Set.of(
        Capability.OVERRIDE_PRIVACY, Capability.DELETE_NOTE, Capability.DELETE_WITNESS,
        Capability.MANAGE_SHARED_QUERIES, Capability.REPAIR_CCD);

    private static final Map<Role, Set<Capability>> GRANTS = Map.of(
        Role.TEAM_LEADER, TEAM_LEADER,
        Role.DEEDPOLL_ADMIN, TEAM_LEADER,
        Role.DELIVERY_MANAGER, deliveryManager());

    private RoleCapabilities() {
    }

    private static Set<Capability> deliveryManager() {
        EnumSet<Capability> inherited = EnumSet.copyOf(TEAM_LEADER);
        inherited.add(Capability.ACCESS_GAZETTE);
        inherited.add(Capability.MANAGE_PROCESS_CONFIG);
        return Set.copyOf(inherited);
    }

    /** Unmapped roles grant nothing; callers receive an immutable union. */
    public static Set<Capability> forRoles(Collection<Role> roles) {
        EnumSet<Capability> capabilities = EnumSet.noneOf(Capability.class);
        for (Role role : roles) {
            capabilities.addAll(GRANTS.getOrDefault(role, Set.of()));
        }
        return Set.copyOf(capabilities);
    }
}
