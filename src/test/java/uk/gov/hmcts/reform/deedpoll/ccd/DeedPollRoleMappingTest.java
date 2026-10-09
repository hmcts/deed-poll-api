package uk.gov.hmcts.reform.deedpoll.ccd;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import uk.gov.hmcts.reform.deedpoll.domain.auth.Role;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static uk.gov.hmcts.reform.deedpoll.ccd.DeedPollAccessProfile.DEED_POLL_READER;
import static uk.gov.hmcts.reform.deedpoll.ccd.DeedPollAccessProfile.DEED_POLL_SYSTEM;

class DeedPollRoleMappingTest {

    private static final DeedPollRoleMapping MAPPING =
        DeedPollRoleMapping.from(new CcdProperties("CASE_TYPE", "JURISDICTION", "system-role"));

    @ParameterizedTest
    @EnumSource(Role.class)
    void eachStaffRoleAloneGrantsOnlyTheReaderProfile(Role role) {
        assertThat(MAPPING.profilesFor(List.of(role.claimValue()))).containsExactly(DEED_POLL_READER);
    }

    @Test
    @DisplayName("maps the configured system actor separately from read-only staff")
    void mapsSystemAndStaffRoles() {
        assertThat(MAPPING.byIdamRole()).containsExactlyEntriesOf(Map.of(
            "system-role", DEED_POLL_SYSTEM,
            Role.CASEWORKER.claimValue(), DEED_POLL_READER,
            Role.DEEDPOLL_ADMIN.claimValue(), DEED_POLL_READER,
            Role.TEAM_LEADER.claimValue(), DEED_POLL_READER,
            Role.DELIVERY_MANAGER.claimValue(), DEED_POLL_READER,
            Role.JUDICIAL.claimValue(), DEED_POLL_READER));
    }

    @Test
    @DisplayName("gives a caller holding the system role among others exactly DEED_POLL_SYSTEM")
    void profilesForTheSystemRole() {
        assertThat(MAPPING.profilesFor(List.of("caseworker", "system-role"))).containsExactly(DEED_POLL_SYSTEM);
    }

    @Test
    @DisplayName("gives nothing for roles nobody mapped -- the profile's own name included")
    void profilesForUnmappedRoles() {
        assertThat(MAPPING.profilesFor(List.of("caseworker", "citizen", "DEED_POLL_SYSTEM",
            "DEED_POLL_READER", "SYSTEM-ROLE"))).isEmpty();
    }

    @Test
    @DisplayName("counts system and reader profiles as granted on the case type")
    void grantedProfiles() {
        assertThat(DeedPollRoleMapping.granted()).containsExactlyInAnyOrder(DEED_POLL_SYSTEM, DEED_POLL_READER);
    }
}
