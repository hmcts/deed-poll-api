package uk.gov.hmcts.reform.deedpoll.ccd;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static uk.gov.hmcts.reform.deedpoll.ccd.DeedPollAccessProfile.DEED_POLL_SYSTEM;

class DeedPollRoleMappingTest {

    private static final DeedPollRoleMapping MAPPING =
        DeedPollRoleMapping.from(new CcdProperties("CASE_TYPE", "JURISDICTION", "system-role"));

    @Test
    @DisplayName("maps the configured system role onto DEED_POLL_SYSTEM, and nothing else onto anything")
    void mapsOnlyTheSystemRole() {
        assertThat(MAPPING.byIdamRole()).containsExactlyEntriesOf(Map.of("system-role", DEED_POLL_SYSTEM));
    }

    @Test
    @DisplayName("gives a caller holding the system role among others exactly DEED_POLL_SYSTEM")
    void profilesForTheSystemRole() {
        assertThat(MAPPING.profilesFor(List.of("caseworker", "system-role"))).containsExactly(DEED_POLL_SYSTEM);
    }

    @Test
    @DisplayName("gives nothing for roles nobody mapped -- the profile's own name included")
    void profilesForUnmappedRoles() {
        assertThat(MAPPING.profilesFor(List.of("caseworker-deedpoll", "DEED_POLL_SYSTEM", "SYSTEM-ROLE"))).isEmpty();
    }

    @Test
    @DisplayName("counts DEED_POLL_SYSTEM as granted, because the definition gives it create and read")
    void grantedProfiles() {
        assertThat(DeedPollRoleMapping.granted()).containsExactly(DEED_POLL_SYSTEM);
    }
}
