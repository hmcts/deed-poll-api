package uk.gov.hmcts.reform.deedpoll.domain.auth;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AmRolesTest {

    private static final Instant NOW = Instant.parse("2026-10-14T12:00:00Z");
    private static final String JURISDICTION = "DEEDPOLL";
    private static final String SKILL = "DEEDPOLL_GAZETTE";

    private static OrganisationalRoleAssignment assignment(String roleName, String... authorisations) {
        return new OrganisationalRoleAssignment(roleName, "ORGANISATION", "STANDARD", null, null,
            Map.of("jurisdiction", JURISDICTION, "primaryLocation", "123456"), List.of(authorisations));
    }

    private static Set<Role> roles(OrganisationalRoleAssignment... assignments) {
        return AmRoles.rolesFrom(List.of(assignments), JURISDICTION, SKILL, NOW);
    }

    @Test
    void ctscIsCaseworkerWhateverItsCategory() {
        assertThat(roles(assignment("ctsc"))).containsExactly(Role.CASEWORKER);
        OrganisationalRoleAssignment legalOps = new OrganisationalRoleAssignment("ctsc", "ORGANISATION", "STANDARD",
            null, null, Map.of("jurisdiction", JURISDICTION, "roleCategory", "LEGAL_OPERATIONS"), null);
        assertThat(roles(legalOps)).containsExactly(Role.CASEWORKER);
    }

    @Test
    void ctscTeamLeaderIsTeamLeader() {
        assertThat(roles(assignment("ctsc-team-leader"))).containsExactly(Role.TEAM_LEADER);
    }

    @Test
    void ctscTeamLeaderWithTheGazetteSkillIsAlsoDeliveryManager() {
        assertThat(roles(assignment("ctsc-team-leader", "OTHER_SKILL", SKILL)))
            .containsExactlyInAnyOrder(Role.TEAM_LEADER, Role.DELIVERY_MANAGER);
    }

    @ParameterizedTest
    @ValueSource(strings = {"judge", "fee-paid-judge"})
    void judgesAreJudicial(String roleName) {
        assertThat(roles(assignment(roleName))).containsExactly(Role.JUDICIAL);
    }

    @ParameterizedTest
    @ValueSource(strings = {"hmcts-ctsc", "hmcts-judiciary", "case-allocator", "senior-tribunal-caseworker", ""})
    void otherNamesGrantNothing(String roleName) {
        assertThat(roles(assignment(roleName, SKILL))).isEmpty();
    }

    @Test
    void nullRoleNameGrantsNothing() {
        assertThat(roles(assignment(null, SKILL))).isEmpty();
    }

    @Test
    void anotherJurisdictionGrantsNothing() {
        OrganisationalRoleAssignment probate = new OrganisationalRoleAssignment("ctsc-team-leader", "ORGANISATION",
            "STANDARD", null, null, Map.of("jurisdiction", "PROBATE"), List.of(SKILL));
        assertThat(roles(probate)).isEmpty();
    }

    @Test
    void missingJurisdictionGrantsNothing() {
        OrganisationalRoleAssignment noJurisdiction = new OrganisationalRoleAssignment("ctsc", "ORGANISATION",
            "STANDARD", null, null, Map.of("primaryLocation", "123456"), List.of());
        assertThat(roles(noJurisdiction)).isEmpty();
    }

    @Test
    void nullAttributesGrantNothing() {
        OrganisationalRoleAssignment noAttributes = new OrganisationalRoleAssignment("ctsc", "ORGANISATION",
            "STANDARD", null, null, null, List.of());
        assertThat(roles(noAttributes)).isEmpty();
    }

    @Test
    void blankConfiguredJurisdictionMatchesNothing() {
        OrganisationalRoleAssignment blank = new OrganisationalRoleAssignment("ctsc", "ORGANISATION",
            "STANDARD", null, null, Map.of("jurisdiction", ""), List.of());
        assertThat(AmRoles.rolesFrom(List.of(blank), "", SKILL, NOW)).isEmpty();
        assertThat(AmRoles.rolesFrom(List.of(assignment("ctsc")), null, SKILL, NOW)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"CASE", "organisation"})
    void roleTypeOtherThanOrganisationGrantsNothing(String roleType) {
        OrganisationalRoleAssignment caseRole = new OrganisationalRoleAssignment("judge", roleType, "STANDARD",
            null, null, Map.of("jurisdiction", JURISDICTION), List.of());
        assertThat(roles(caseRole)).isEmpty();
    }

    @Test
    void nullRoleTypeGrantsNothing() {
        OrganisationalRoleAssignment untyped = new OrganisationalRoleAssignment("judge", null, "STANDARD",
            null, null, Map.of("jurisdiction", JURISDICTION), List.of());
        assertThat(roles(untyped)).isEmpty();
    }

    @Test
    void specificGrantGrantsNothing() {
        OrganisationalRoleAssignment specific = new OrganisationalRoleAssignment("ctsc-team-leader", "ORGANISATION",
            "SPECIFIC", null, null, Map.of("jurisdiction", JURISDICTION), List.of(SKILL));
        assertThat(roles(specific)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"STANDARD", "CHALLENGED", "BASIC", "EXCLUDED"})
    void otherGrantTypesPassTheFilter(String grantType) {
        OrganisationalRoleAssignment granted = new OrganisationalRoleAssignment("ctsc", "ORGANISATION", grantType,
            null, null, Map.of("jurisdiction", JURISDICTION), List.of());
        assertThat(roles(granted)).containsExactly(Role.CASEWORKER);
    }

    @Test
    void futureBeginGrantsNothingUntilItArrives() {
        OrganisationalRoleAssignment future = new OrganisationalRoleAssignment("ctsc", "ORGANISATION", "STANDARD",
            NOW.plusSeconds(1), null, Map.of("jurisdiction", JURISDICTION), List.of());
        assertThat(roles(future)).isEmpty();
        OrganisationalRoleAssignment beginsNow = new OrganisationalRoleAssignment("ctsc", "ORGANISATION", "STANDARD",
            NOW, null, Map.of("jurisdiction", JURISDICTION), List.of());
        assertThat(roles(beginsNow)).containsExactly(Role.CASEWORKER);
    }

    @Test
    void pastOrPresentEndGrantsNothing() {
        OrganisationalRoleAssignment ended = new OrganisationalRoleAssignment("ctsc", "ORGANISATION", "STANDARD",
            null, NOW.minusSeconds(1), Map.of("jurisdiction", JURISDICTION), List.of());
        assertThat(roles(ended)).isEmpty();
        OrganisationalRoleAssignment endsNow = new OrganisationalRoleAssignment("ctsc", "ORGANISATION", "STANDARD",
            null, NOW, Map.of("jurisdiction", JURISDICTION), List.of());
        assertThat(roles(endsNow)).isEmpty();
        OrganisationalRoleAssignment current = new OrganisationalRoleAssignment("ctsc", "ORGANISATION", "STANDARD",
            NOW.minusSeconds(60), NOW.plusSeconds(60), Map.of("jurisdiction", JURISDICTION), List.of());
        assertThat(roles(current)).containsExactly(Role.CASEWORKER);
    }

    @Test
    void anEmptyOrBlankGazetteSkillNeverYieldsDeliveryManager() {
        List<OrganisationalRoleAssignment> leader = List.of(assignment("ctsc-team-leader", "", " ", SKILL));
        assertThat(AmRoles.rolesFrom(leader, JURISDICTION, "", NOW)).containsExactly(Role.TEAM_LEADER);
        assertThat(AmRoles.rolesFrom(leader, JURISDICTION, " ", NOW)).containsExactly(Role.TEAM_LEADER);
        assertThat(AmRoles.rolesFrom(leader, JURISDICTION, null, NOW)).containsExactly(Role.TEAM_LEADER);
    }

    @Test
    void nullAuthorisationsAreNoSkills() {
        OrganisationalRoleAssignment leader = new OrganisationalRoleAssignment("ctsc-team-leader", "ORGANISATION",
            "STANDARD", null, null, Map.of("jurisdiction", JURISDICTION), null);
        assertThat(roles(leader)).containsExactly(Role.TEAM_LEADER);
    }

    @Test
    void theSkillOnAnOrdinaryCtscAssignmentIsNotDeliveryManager() {
        assertThat(roles(assignment("ctsc", SKILL))).containsExactly(Role.CASEWORKER);
    }

    @Test
    void theSkillOnOneAssignmentDoesNotCarryToAnother() {
        assertThat(roles(assignment("ctsc", SKILL), assignment("ctsc-team-leader")))
            .containsExactlyInAnyOrder(Role.CASEWORKER, Role.TEAM_LEADER);
    }

    @Test
    void duplicatesCollapseAndRolesUnion() {
        assertThat(roles(assignment("ctsc"), assignment("ctsc"), assignment("judge"), assignment("fee-paid-judge"),
            assignment("ctsc-team-leader", SKILL), assignment("ctsc-team-leader", SKILL)))
            .containsExactlyInAnyOrder(Role.CASEWORKER, Role.JUDICIAL, Role.TEAM_LEADER, Role.DELIVERY_MANAGER);
    }

    @Test
    void filteredAndAcceptedAssignmentsMix() {
        OrganisationalRoleAssignment probate = new OrganisationalRoleAssignment("ctsc-team-leader", "ORGANISATION",
            "STANDARD", null, null, Map.of("jurisdiction", "PROBATE"), List.of(SKILL));
        assertThat(roles(probate, assignment("ctsc"))).containsExactly(Role.CASEWORKER);
    }

    @Test
    void noAssignmentsGrantNothingAndTheResultIsImmutable() {
        assertThat(roles()).isEmpty();
        Set<Role> result = roles(assignment("ctsc"));
        assertThatThrownBy(() -> result.add(Role.DELIVERY_MANAGER)).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void teamLeaderWithTheSkillHasEveryTeamLeaderCapabilityPlusGazette() {
        Set<Role> roles = roles(assignment("ctsc-team-leader", SKILL));
        Set<Capability> leader = RoleCapabilities.forRoles(List.of(Role.TEAM_LEADER));
        Set<Capability> granted = RoleCapabilities.forRoles(roles);

        EnumSet<Capability> expected = EnumSet.copyOf(leader);
        expected.add(Capability.ACCESS_GAZETTE);
        expected.add(Capability.MANAGE_PROCESS_CONFIG);
        assertThat(granted).containsExactlyInAnyOrderElementsOf(expected);

        CurrentUser user = CurrentUser.currentUser(List.copyOf(roles), "Team leader", "subject");
        assertThat(user.can(Capability.ACCESS_GAZETTE)).isTrue();
        for (Capability capability : leader) {
            assertThat(user.can(capability)).as(capability.name()).isTrue();
        }
        assertThat(RoleCapabilities.forRoles(roles(assignment("ctsc-team-leader"))))
            .doesNotContain(Capability.ACCESS_GAZETTE, Capability.MANAGE_PROCESS_CONFIG);
    }
}
