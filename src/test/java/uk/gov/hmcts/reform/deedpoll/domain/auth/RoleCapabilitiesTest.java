package uk.gov.hmcts.reform.deedpoll.domain.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import uk.gov.hmcts.reform.deedpoll.domain.ForbiddenError;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RoleCapabilitiesTest {

    static Stream<JsonNode> contract() throws IOException {
        try (var input = RoleCapabilitiesTest.class.getResourceAsStream("/auth/staff-permission-contract.json")) {
            List<JsonNode> cases = new ArrayList<>();
            new ObjectMapper().readTree(input).get("cases").forEach(cases::add);
            return cases.stream();
        }
    }

    @ParameterizedTest
    @MethodSource("contract")
    void enforcesContractForArrayAndCommaSeparatedClaims(JsonNode example) {
        List<String> claims = new ArrayList<>();
        example.get("claims").forEach(value -> claims.add(value.asText()));
        List<Capability> expected = new ArrayList<>();
        example.get("capabilities").forEach(value -> expected.add(Capability.valueOf(value.asText())));
        for (Object raw : List.of(claims, String.join(", ", claims))) {
            CurrentUser user = IdamClaims.userFromClaims(Map.of(
                "roles", raw, "name", "Audit name", "sub", "stable-subject",
                "isAdmin", true, "capabilities", List.of("ACCESS_GAZETTE")));
            assertThat(RoleCapabilities.forRoles(user.roles())).containsExactlyInAnyOrderElementsOf(expected);
            for (Capability capability : Capability.values()) {
                assertThat(user.can(capability)).as(example.get("name").asText() + ": " + capability)
                    .isEqualTo(expected.contains(capability));
                if (expected.contains(capability)) {
                    assertThatCode(() -> user.require(capability)).doesNotThrowAnyException();
                } else {
                    assertThatThrownBy(() -> user.require(capability)).isInstanceOf(ForbiddenError.class);
                }
            }
            assertThat(user.roles().contains(Role.JUDICIAL)).isEqualTo(example.get("judicial").asBoolean());
            assertThat(user.isAdmin()).isEqualTo(expected.contains(Capability.OVERRIDE_PRIVACY));
            assertThat(user.name()).isEqualTo("Audit name");
            assertThat(user.noteAuthor().author()).isEqualTo("Audit name");
            assertThat(user.requireUserId()).isEqualTo("stable-subject");
        }
    }

    @Test
    void deliveryManagerInheritsExactlyTeamLeaderPlusGazetteAndProcessConfig() {
        Set<Capability> leader = RoleCapabilities.forRoles(List.of(Role.TEAM_LEADER));
        Set<Capability> manager = RoleCapabilities.forRoles(List.of(Role.DELIVERY_MANAGER));
        assertThat(manager).containsAll(leader);
        assertThat(manager.stream().filter(capability -> !leader.contains(capability)))
            .containsExactlyInAnyOrder(Capability.ACCESS_GAZETTE, Capability.MANAGE_PROCESS_CONFIG);
        assertThat(leader).doesNotContain(Capability.MANAGE_PROCESS_CONFIG);
        assertThat(RoleCapabilities.forRoles(List.of(Role.DEEDPOLL_ADMIN))).isEqualTo(leader);
        assertThat(Role.fromClaimValue("caseworker-deedpoll-admin")).contains(Role.DEEDPOLL_ADMIN);
    }

    @Test
    void teamLeadersDeliveryManagersAndTheLegacyAdminAllocateReferralsAndNobodyElseDoes() {
        for (Role role : Role.values()) {
            assertThat(RoleCapabilities.forRoles(List.of(role)).contains(Capability.ALLOCATE_REFERRALS))
                .as(role.name())
                .isEqualTo(role == Role.TEAM_LEADER || role == Role.DELIVERY_MANAGER || role == Role.DEEDPOLL_ADMIN);
        }
        assertThat(Capability.values()[Capability.values().length - 1]).isEqualTo(Capability.ALLOCATE_REFERRALS);
        assertThat(Capability.ALLOCATE_REFERRALS.ordinal()).isEqualTo(Capability.MANAGE_PROCESS_CONFIG.ordinal() + 1);
    }

    @Test
    void rolesAndCapabilitiesCannotBeMutatedOrIndependentlyAsserted() {
        List<Role> roles = new ArrayList<>(List.of(Role.CASEWORKER));
        CurrentUser user = new CurrentUser(roles, true, "Audit name", "stable-subject", null);
        roles.add(Role.DELIVERY_MANAGER);
        assertThat(user.isAdmin()).isFalse();
        assertThat(user.can(Capability.ACCESS_GAZETTE)).isFalse();
        assertThatThrownBy(() -> user.roles().add(Role.DELIVERY_MANAGER))
            .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> RoleCapabilities.forRoles(List.of(Role.TEAM_LEADER))
            .add(Capability.ACCESS_GAZETTE)).isInstanceOf(UnsupportedOperationException.class);
        CurrentUser manager =
            new CurrentUser(List.of(Role.DELIVERY_MANAGER), false, "Audit name", "stable-subject", null);
        assertThat(manager.isAdmin()).isTrue();
        assertThat(manager.name()).isEqualTo("Audit name");
        assertThat(manager.requireUserId()).isEqualTo("stable-subject");
    }

    @Test
    void missingCapabilityFailsClosedForEveryRole() {
        for (Role role : Role.values()) {
            CurrentUser user = CurrentUser.currentUser(List.of(role), "User", "subject");
            assertThat(user.can(null)).isFalse();
            assertThatThrownBy(() -> user.require(null)).isInstanceOf(ForbiddenError.class);
        }
        assertThat(Arrays.stream(Capability.values()).anyMatch(CurrentUser.ANONYMOUS::can)).isFalse();
    }
}
