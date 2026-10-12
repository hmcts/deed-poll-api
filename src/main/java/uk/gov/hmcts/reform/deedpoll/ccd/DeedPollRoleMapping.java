package uk.gov.hmcts.reform.deedpoll.ccd;

import uk.gov.hmcts.reform.deedpoll.domain.auth.Role;

import java.util.Arrays;
import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Which IDAM role maps onto which of the definition's access profiles -- stated once, and read twice.
 *
 * <p>{@link DeedPollCaseType} writes each entry as a {@code RoleToAccessProfiles} row, which is how data-store
 * decides what a caller may do with the case. The {@code /ccd-persistence/**} chain applies the same entries to the
 * user that data-store forwards (plan 02, Task 5). So the user's roles are checked against the definition's own
 * grants rather than a second copy of them. A second copy would let the definition and the gate disagree about who
 * the system actor is, and nothing would fail to say so.
 *
 * @param byIdamRole each mapped IDAM role and the access profile it confers
 */
public record DeedPollRoleMapping(Map<String, DeedPollAccessProfile> byIdamRole) {

    public DeedPollRoleMapping {
        byIdamRole = Map.copyOf(byIdamRole);
    }

    /**
     * Maps the system actor separately from staff: staff receive read-only CCD access.
     */
    public static DeedPollRoleMapping from(CcdProperties properties) {
        return new DeedPollRoleMapping(Map.of(
            properties.systemRole(), DeedPollAccessProfile.DEED_POLL_SYSTEM,
            Role.CASEWORKER.claimValue(), DeedPollAccessProfile.DEED_POLL_READER,
            Role.DEEDPOLL_ADMIN.claimValue(), DeedPollAccessProfile.DEED_POLL_READER,
            Role.TEAM_LEADER.claimValue(), DeedPollAccessProfile.DEED_POLL_READER,
            Role.DELIVERY_MANAGER.claimValue(), DeedPollAccessProfile.DEED_POLL_READER,
            Role.JUDICIAL.claimValue(), DeedPollAccessProfile.DEED_POLL_READER));
    }

    /** The access profiles a caller holding these IDAM roles has, which is empty for a role nobody mapped. */
    public Set<DeedPollAccessProfile> profilesFor(Collection<String> idamRoles) {
        return idamRoles.stream()
            .map(byIdamRole::get)
            .filter(Objects::nonNull)
            .collect(Collectors.toUnmodifiableSet());
    }

    /**
     * The profiles the definition grants anything to on the case type. A profile with no grant can do nothing
     * through CCD, so holding one alone does not get a caller past the persistence chain.
     */
    public static Set<DeedPollAccessProfile> granted() {
        return Arrays.stream(DeedPollAccessProfile.values())
            .filter(profile -> !profile.getCaseTypePermissions().isEmpty())
            .collect(Collectors.toUnmodifiableSet());
    }
}
