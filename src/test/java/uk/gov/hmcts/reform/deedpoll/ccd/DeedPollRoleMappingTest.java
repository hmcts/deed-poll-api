package uk.gov.hmcts.reform.deedpoll.ccd;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static uk.gov.hmcts.reform.deedpoll.ccd.DeedPollAccessProfile.DEED_POLL_DOCUMENT_READER;
import static uk.gov.hmcts.reform.deedpoll.ccd.DeedPollAccessProfile.DEED_POLL_SYSTEM;

class DeedPollRoleMappingTest {

    private static final DeedPollRoleMapping MAPPING =
        DeedPollRoleMapping.from(new CcdProperties("CASE_TYPE", "JURISDICTION", "system-role"));

    @Test
    @DisplayName("maps the system role and the confirmed admin role to separate profiles")
    void mapsSystemAndDocumentReaderRoles() {
        assertThat(MAPPING.byIdamRole()).containsExactlyEntriesOf(Map.of(
            "system-role", DEED_POLL_SYSTEM, "caseworker-deedpoll-admin", DEED_POLL_DOCUMENT_READER));
    }

    @Test
    @DisplayName("gives a caller holding the system role among others exactly DEED_POLL_SYSTEM")
    void profilesForTheSystemRole() {
        assertThat(MAPPING.profilesFor(List.of("caseworker", "system-role"))).containsExactly(DEED_POLL_SYSTEM);
    }

    @Test
    void adminReadsWithoutSystemPrivileges() {
        assertThat(MAPPING.profilesFor(List.of("caseworker-deedpoll-admin")))
            .containsExactly(DEED_POLL_DOCUMENT_READER);
    }

    @Test
    @DisplayName("gives nothing for roles nobody mapped -- the profile's own name included")
    void profilesForUnmappedRoles() {
        assertThat(MAPPING.profilesFor(List.of("caseworker", "caseworker-deedpoll", "caseworker-deedpoll-judicial",
            "DEED_POLL_SYSTEM", "DEED_POLL_DOCUMENT_READER", "SYSTEM-ROLE"))).isEmpty();
    }

    @Test
    @DisplayName("counts both profiles as granted")
    void grantedProfiles() {
        assertThat(DeedPollRoleMapping.granted())
            .containsExactlyInAnyOrder(DEED_POLL_SYSTEM, DEED_POLL_DOCUMENT_READER);
    }
}
