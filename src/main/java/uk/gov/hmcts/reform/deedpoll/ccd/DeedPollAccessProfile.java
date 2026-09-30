package uk.gov.hmcts.reform.deedpoll.ccd;

import uk.gov.hmcts.ccd.sdk.api.HasRole;
import uk.gov.hmcts.ccd.sdk.api.Permission;

import java.util.Set;

/**
 * The definition's access profiles: one, the actor the linking operation and the document event run as.
 *
 * <p>An access profile is the definition's own name for a set of grants, not an IDAM role, so the name here is
 * fixed and the IDAM role that maps onto it is {@link CcdProperties#systemRole()} -- configuration, because it has
 * not been onboarded. {@link DeedPollCaseType} writes that mapping. The name is an implementation choice agreed
 * 2026-09-27, not an onboarded identifier.
 *
 * <p>Every constant here gets an {@code AuthorisationCaseType} row with its {@link #getCaseTypePermissions()}, so a
 * new one is a new grant on the whole case type -- which {@code DeedPollDefinitionTest} refuses until it is added to
 * the pinned definition deliberately.
 */
public enum DeedPollAccessProfile implements HasRole {

    /**
     * Creates, reads and updates the case type. Update since plan 03a: the document event runs on an existing case,
     * which data-store refuses without U on the case type. Never D, which CCD has no use for at this level.
     */
    DEED_POLL_SYSTEM("DEED_POLL_SYSTEM", Permission.CRU);

    private final String role;
    private final Set<Permission> caseTypePermissions;

    DeedPollAccessProfile(String role, Set<Permission> caseTypePermissions) {
        this.role = role;
        this.caseTypePermissions = caseTypePermissions;
    }

    @Override
    public String getRole() {
        return role;
    }

    @Override
    public String getCaseTypePermissions() {
        return Permission.toString(caseTypePermissions);
    }
}
