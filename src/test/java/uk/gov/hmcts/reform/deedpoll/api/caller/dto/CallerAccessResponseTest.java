package uk.gov.hmcts.reform.deedpoll.api.caller.dto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import uk.gov.hmcts.reform.deedpoll.auth.IdamAuthentication;
import uk.gov.hmcts.reform.deedpoll.domain.auth.Capability;
import uk.gov.hmcts.reform.deedpoll.domain.auth.CurrentUser;
import uk.gov.hmcts.reform.deedpoll.domain.auth.Role;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The answer {@code GET /me} gives for each role, with the authorities built the way the filter chain builds them.
 */
@SuppressWarnings("deprecation")
class CallerAccessResponseTest {

    private static final List<Capability> TEAM_LEADER = List.of(
        Capability.OVERRIDE_PRIVACY, Capability.DELETE_NOTE, Capability.DELETE_WITNESS,
        Capability.MANAGE_SHARED_QUERIES, Capability.REPAIR_CCD);

    private static final List<Capability> DELIVERY_MANAGER = List.of(
        Capability.OVERRIDE_PRIVACY, Capability.DELETE_NOTE, Capability.DELETE_WITNESS,
        Capability.MANAGE_SHARED_QUERIES, Capability.REPAIR_CCD, Capability.ACCESS_GAZETTE,
        Capability.MANAGE_PROCESS_CONFIG);

    @Test
    @DisplayName("a caseworker is staff with no capabilities")
    void caseworker() {
        assertThat(accessFor(Role.CASEWORKER)).isEqualTo(new CallerAccessResponse(true, false, List.of()));
    }

    @Test
    @DisplayName("a Team leader is staff with the Team leader set")
    void teamLeader() {
        assertThat(accessFor(Role.TEAM_LEADER)).isEqualTo(new CallerAccessResponse(true, false, TEAM_LEADER));
    }

    @Test
    @DisplayName("a Delivery Manager is staff with the Team leader set plus ACCESS_GAZETTE and MANAGE_PROCESS_CONFIG")
    void deliveryManager() {
        assertThat(accessFor(Role.DELIVERY_MANAGER))
            .isEqualTo(new CallerAccessResponse(true, false, DELIVERY_MANAGER));
    }

    @Test
    @DisplayName("the legacy admin is staff with the Team leader set, never ACCESS_GAZETTE or MANAGE_PROCESS_CONFIG")
    void legacyAdmin() {
        assertThat(accessFor(Role.DEEDPOLL_ADMIN)).isEqualTo(new CallerAccessResponse(true, false, TEAM_LEADER));
    }

    @Test
    @DisplayName("a judge is judicial, not staff, with no capabilities")
    void judge() {
        assertThat(accessFor(Role.JUDICIAL)).isEqualTo(new CallerAccessResponse(false, true, List.of()));
    }

    @Test
    @DisplayName("a Team leader who is also a judge is both, with the Team leader set")
    void teamLeaderAndJudge() {
        assertThat(accessFor(Role.TEAM_LEADER, Role.JUDICIAL))
            .isEqualTo(new CallerAccessResponse(true, true, TEAM_LEADER));
    }

    @Test
    @DisplayName("a caller with no deed-poll role is neither, with no capabilities, rather than refused")
    void noRole() {
        assertThat(accessFor()).isEqualTo(new CallerAccessResponse(false, false, List.of()));
    }

    @Test
    @DisplayName("overlapping grants come back once each, in declaration order, whatever order the roles arrive in")
    void declarationOrderWithoutDuplicates() {
        assertThat(accessFor(Role.DELIVERY_MANAGER, Role.TEAM_LEADER).capabilities())
            .containsExactlyElementsOf(DELIVERY_MANAGER);
        assertThat(accessFor(Role.TEAM_LEADER, Role.DEEDPOLL_ADMIN, Role.DELIVERY_MANAGER).capabilities())
            .containsExactlyElementsOf(DELIVERY_MANAGER);
    }

    @Test
    @DisplayName("every role alone gets the answer the area and capability rules give it")
    void everyRoleIsCovered() {
        for (Role role : Role.values()) {
            CallerAccessResponse access = accessFor(role);
            assertThat(access.staff()).as(role + " staff").isEqualTo(role != Role.JUDICIAL);
            assertThat(access.judicial()).as(role + " judicial").isEqualTo(role == Role.JUDICIAL);
        }
    }

    private static CallerAccessResponse accessFor(Role... roles) {
        CurrentUser caller = CurrentUser.currentUser(List.of(roles), "A Caller", "subject");
        Jwt token = Jwt.withTokenValue("token").header("alg", "none").subject("subject").build();
        IdamAuthentication authentication = new IdamAuthentication(token, caller);
        return CallerAccessResponse.of(authentication.getAuthorities(), caller);
    }
}
