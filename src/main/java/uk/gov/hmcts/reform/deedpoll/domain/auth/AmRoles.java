package uk.gov.hmcts.reform.deedpoll.domain.auth;

import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Mapping organisational role assignments from AM onto the existing {@link Role} values.
 *
 * <p>AM is a second source of the same roles, not a new vocabulary: the result is unioned with what
 * {@link IdamClaims} reads, so {@link RoleCapabilities} and every {@code can}/{@code require} check stay the
 * single statement of what a role may do.
 *
 * <p><strong>The jurisdiction filter is what makes this safe.</strong> {@code ctsc} and {@code ctsc-team-leader} are
 * shared across services, so without it a Probate team leader would become a deed poll Team leader. An assignment
 * whose jurisdiction is missing or different therefore grants nothing.
 */
public final class AmRoles {

    private static final String ORGANISATION = "ORGANISATION";
    private static final String SPECIFIC = "SPECIFIC";

    private AmRoles() {
    }

    /**
     * The roles the assignments grant in {@code jurisdiction} at {@code now}.
     *
     * <p>Unknown role names map to nothing rather than failing, because an actor's assignments routinely include
     * other services' roles. A blank {@code gazetteSkill} means AM never yields {@link Role#DELIVERY_MANAGER}: the
     * skill code is not yet allocated, and no skill must not match every skill.
     */
    public static Set<Role> rolesFrom(
        List<OrganisationalRoleAssignment> assignments, String jurisdiction, String gazetteSkill, Instant now) {
        EnumSet<Role> roles = EnumSet.noneOf(Role.class);
        for (OrganisationalRoleAssignment assignment : assignments) {
            if (applies(assignment, jurisdiction, now)) {
                addRoles(roles, assignment, gazetteSkill);
            }
        }
        return Set.copyOf(roles);
    }

    private static boolean applies(OrganisationalRoleAssignment assignment, String jurisdiction, Instant now) {
        return ORGANISATION.equals(assignment.roleType())
            && !SPECIFIC.equals(assignment.grantType())
            && jurisdiction != null && !jurisdiction.isBlank()
            && assignment.attributes() != null
            && jurisdiction.equals(assignment.attributes().get("jurisdiction"))
            && (assignment.beginTime() == null || !assignment.beginTime().isAfter(now))
            && (assignment.endTime() == null || assignment.endTime().isAfter(now));
    }

    private static void addRoles(Set<Role> roles, OrganisationalRoleAssignment assignment, String gazetteSkill) {
        String name = assignment.roleName();
        if (name == null) {
            return;
        }
        switch (name) {
            case "ctsc" -> roles.add(Role.CASEWORKER);
            case "ctsc-team-leader" -> {
                roles.add(Role.TEAM_LEADER);
                if (hasSkill(assignment, gazetteSkill)) {
                    roles.add(Role.DELIVERY_MANAGER);
                }
            }
            case "judge", "fee-paid-judge" -> roles.add(Role.JUDICIAL);
            default -> {
                // Another service's role, or one with no deed poll rule: it grants nothing here.
            }
        }
    }

    private static boolean hasSkill(OrganisationalRoleAssignment assignment, String gazetteSkill) {
        return gazetteSkill != null && !gazetteSkill.isBlank()
            && assignment.authorisations() != null
            && assignment.authorisations().contains(gazetteSkill);
    }
}
