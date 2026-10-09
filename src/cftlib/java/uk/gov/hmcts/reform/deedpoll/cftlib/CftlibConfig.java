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
 * <p>Staff hold only their own IDAM roles, never the system role. This makes document journeys exercise the
 * deployed read-only mapping. The separate unmapped user proves refusal without a deed-poll role.
 */
@Component
public class CftlibConfig implements CFTLibConfigurer {

    /**
     * An admin caseworker, holding {@link Role#DEEDPOLL_ADMIN} and nothing else.
     */
    public static final String ADMIN_USER = "deed-poll-admin@localhost";

    /** A local staff reader, without the system role. */
    public static final String DOCUMENT_READER = "deed-poll-reader@localhost";

    public static final String TEAM_LEADER_USER = "deed-poll-team-leader@localhost";
    public static final String DELIVERY_MANAGER_USER = "deed-poll-delivery-manager@localhost";

    public static final String JUDICIAL_USER = "deed-poll-judge@localhost";
    public static final String UNMAPPED_USER = "deed-poll-unmapped@localhost";

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
        // External roles and internal profiles are registered PUBLIC. Data-store derives an
        // `idam:` role assignment from the IDAM role, taking its security classification from this table, keyed
        // on the IDAM role's own name -- without the row it refuses the case with "null SecurityClassification".
        // Wherever the case type is onboarded, the same role has to be registered in that definition store.
        lib.createRoles(DeedPollAccessProfile.DEED_POLL_SYSTEM.getRole(), identifiers.systemRole(),
            DeedPollAccessProfile.DEED_POLL_READER.getRole(), Role.DEEDPOLL_ADMIN.claimValue(),
            Role.JUDICIAL.claimValue(), Role.CASEWORKER.claimValue(),
            Role.TEAM_LEADER.claimValue(), Role.DELIVERY_MANAGER.claimValue());
        lib.createIdamUser(systemUser, identifiers.systemRole());
        lib.createIdamUser(ADMIN_USER, Role.DEEDPOLL_ADMIN.claimValue());
        lib.createIdamUser(TEAM_LEADER_USER, Role.TEAM_LEADER.claimValue());
        lib.createIdamUser(DELIVERY_MANAGER_USER, Role.DELIVERY_MANAGER.claimValue());
        lib.createIdamUser(DOCUMENT_READER, Role.CASEWORKER.claimValue());
        lib.createIdamUser(JUDICIAL_USER, Role.JUDICIAL.claimValue());
        lib.createIdamUser(UNMAPPED_USER, "caseworker");
        for (String staff : java.util.List.of(DOCUMENT_READER, ADMIN_USER, JUDICIAL_USER, UNMAPPED_USER,
            TEAM_LEADER_USER, DELIVERY_MANAGER_USER)) {
            lib.createProfile(staff, identifiers.jurisdictionId(), identifiers.caseTypeId(),
                DeedPollCaseState.Registered.name());
        }
        lib.createProfile(systemUser, identifiers.jurisdictionId(), identifiers.caseTypeId(),
            DeedPollCaseState.Registered.name());

        generator.generateAllCaseTypesToJSON(DEFINITIONS);
        lib.importJsonDefinition(new File(DEFINITIONS, identifiers.caseTypeId()));
    }
}
