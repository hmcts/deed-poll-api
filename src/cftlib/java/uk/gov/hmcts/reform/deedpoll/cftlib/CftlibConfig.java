package uk.gov.hmcts.reform.deedpoll.cftlib;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import uk.gov.hmcts.ccd.sdk.CCDDefinitionGenerator;
import uk.gov.hmcts.reform.deedpoll.ccd.CcdProperties;
import uk.gov.hmcts.reform.deedpoll.ccd.DeedPollAccessProfile;
import uk.gov.hmcts.reform.deedpoll.ccd.DeedPollCaseState;
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
 * <p>Only the system user is created, holding only the system role: the definition grants {@code DEED_POLL_SYSTEM}
 * and nobody else anything, and the IDAM role reaches that profile through the definition's
 * {@code RoleToAccessProfiles} row, which data-store reads from the role assignments it derives from IDAM roles.
 */
@Component
public class CftlibConfig implements CFTLibConfigurer {

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
        // Both names are registered, PUBLIC. The IDAM role is the one that matters at runtime: data-store derives an
        // `idam:` role assignment from it and takes that assignment's security classification from this table, keyed
        // on the IDAM role's own name -- without the row it refuses the case with "null SecurityClassification".
        // Wherever the case type is onboarded, the same role has to be registered in that definition store.
        lib.createRoles(DeedPollAccessProfile.DEED_POLL_SYSTEM.getRole(), identifiers.systemRole());
        lib.createIdamUser(systemUser, identifiers.systemRole());
        lib.createProfile(systemUser, identifiers.jurisdictionId(), identifiers.caseTypeId(),
            DeedPollCaseState.Registered.name());

        generator.generateAllCaseTypesToJSON(DEFINITIONS);
        lib.importJsonDefinition(new File(DEFINITIONS, identifiers.caseTypeId()));
    }
}
