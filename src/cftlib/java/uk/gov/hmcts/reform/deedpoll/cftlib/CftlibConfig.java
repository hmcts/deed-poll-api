package uk.gov.hmcts.reform.deedpoll.cftlib;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import uk.gov.hmcts.ccd.sdk.CCDDefinitionGenerator;
import uk.gov.hmcts.reform.deedpoll.ccd.CcdProperties;
import uk.gov.hmcts.reform.deedpoll.ccd.DeedPollAccessProfile;
import uk.gov.hmcts.reform.deedpoll.ccd.DeedPollCaseState;
import uk.gov.hmcts.reform.deedpoll.domain.auth.Role;
import uk.gov.hmcts.rse.ccd.lib.api.CFTLib;
import uk.gov.hmcts.rse.ccd.lib.api.CFTLibConfigurer;

import java.io.File;

/**
 * Prepares the in-process CFT stack for this service: the system user, the access profile and the definition.
 *
 * <p>cftlib finds this bean and runs it once every service has started. Every identifier comes from the same
 * configuration the application reads -- {@link CcdProperties} and {@code idam.system-user.username} -- so the stack
 * is set up with whatever case type, jurisdiction and role the application would put in its definition, which today
 * is the {@code DEED_POLL_PLACEHOLDER} literal. Nothing here is onboarded, and nothing leaves this machine.
 *
 * <p>The system user holds only its configured system role. Admin caseworkers hold only the confirmed
 * admin role, mapped to the separate read-only document profile. An unrelated IDAM user exercises refusal.
 */
@Component
public class CftlibConfig implements CFTLibConfigurer {

    /**
     * An admin caseworker, holding {@link Role#DEEDPOLL_ADMIN} and nothing else.
     */
    public static final String ADMIN_USER = "deed-poll-admin@localhost";

    /** A local admin caseworker with read-only CCD access and no system role. */
    public static final String DOCUMENT_READER = "deed-poll-reader@localhost";

    public static final String UNMAPPED_USER = "unmapped-reader@localhost";

    private static final File DEFINITIONS = new File("build/cftlib/definitions");

    private final CCDDefinitionGenerator generator;
    private final CcdProperties identifiers;
    private final String systemUser;

    public CftlibConfig(@Lazy CCDDefinitionGenerator generator,
                        CcdProperties identifiers,
                        @Value("${idam.system-user.username}") String systemUser) {
        this.generator = generator;
        this.identifiers = identifiers;
        this.systemUser = systemUser;
    }

    @Override
    public void configure(CFTLib lib) throws Exception {
        // The mapped roles and profiles are registered, PUBLIC. For each IDAM role data-store derives an
        // `idam:` role assignment from it and takes that assignment's security classification from this table, keyed
        // on the IDAM role's own name -- without the row it refuses the case with "null SecurityClassification".
        // Wherever the case type is onboarded, the same role has to be registered in that definition store.
        lib.createRoles(DeedPollAccessProfile.DEED_POLL_SYSTEM.getRole(), identifiers.systemRole(),
            DeedPollAccessProfile.DEED_POLL_DOCUMENT_READER.getRole(), Role.DEEDPOLL_ADMIN.claimValue());
        lib.createIdamUser(systemUser, identifiers.systemRole());
        lib.createIdamUser(ADMIN_USER, Role.DEEDPOLL_ADMIN.claimValue());
        lib.createIdamUser(DOCUMENT_READER, Role.DEEDPOLL_ADMIN.claimValue());
        lib.createIdamUser(UNMAPPED_USER, "caseworker");
        lib.createProfile(ADMIN_USER, identifiers.jurisdictionId(), identifiers.caseTypeId(),
            DeedPollCaseState.Registered.name());
        lib.createProfile(DOCUMENT_READER, identifiers.jurisdictionId(), identifiers.caseTypeId(),
            DeedPollCaseState.Registered.name());
        lib.createProfile(systemUser, identifiers.jurisdictionId(), identifiers.caseTypeId(),
            DeedPollCaseState.Registered.name());

        generator.generateAllCaseTypesToJSON(DEFINITIONS);
        lib.importJsonDefinition(new File(DEFINITIONS, identifiers.caseTypeId()));
    }
}
