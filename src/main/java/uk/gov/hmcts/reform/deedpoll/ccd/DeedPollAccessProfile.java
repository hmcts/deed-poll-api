package uk.gov.hmcts.reform.deedpoll.ccd;

import uk.gov.hmcts.ccd.sdk.api.HasRole;
import uk.gov.hmcts.ccd.sdk.api.Permission;

import java.util.Set;

/**
 * The definition's access profiles: the system actor and staff document readers.
 *
 * <p>An access profile is the definition's own name for a set of grants, not an IDAM role, so the name here is
 * fixed. {@link DeedPollRoleMapping} maps the configured system role and the confirmed admin role onto separate
 * profiles; {@link DeedPollCaseType} writes those mappings. Profile names are internal implementation choices,
 * not new IDAM roles to register.
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
    DEED_POLL_SYSTEM("DEED_POLL_SYSTEM", Permission.CRU),

    /** Reads documents as the staff caller; it cannot create or change a CCD case. */
    DEED_POLL_DOCUMENT_READER("DEED_POLL_DOCUMENT_READER", Set.of(Permission.R));

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
