package uk.gov.hmcts.reform.deedpoll.ccdimport;

import java.util.List;
import java.util.Locale;
import uk.gov.hmcts.befta.dse.ccd.CcdEnvironment;
import uk.gov.hmcts.befta.dse.ccd.CcdRoleConfig;
import uk.gov.hmcts.befta.dse.ccd.DataLoaderToDefinitionStore;
import uk.gov.hmcts.befta.util.BeftaUtils;

/**
 * The DEEDPOLL definition import that Jenkins' "High Level Data Setup" stage runs, as pcs-api's does.
 *
 * <p>Registers the eight roles docs/CCD-SMOKE.md step 1 lists, then imports the spreadsheet bin/create-xlsx.sh
 * built for this environment. BEFTA submits the import with its own job id and polls the job to COMPLETED,
 * which is the runbook's "choose the id first, then read the job back" done for us.
 *
 * <p>Nothing here refers to the application's classes. This source set's classpath is deliberately separate
 * from main's (build.gradle, the ccdImport source set), so the case type is restated rather than read from
 * {@code CCD_CASE_TYPE_ID}. The Jenkinsfile sets that same value for {@code generateCCDConfig}.
 */
public class HighLevelDataSetupApp extends DataLoaderToDefinitionStore {

    private static final String CASE_TYPE = "DEEDPOLL";

    // BEFTA registers these with PUT /api/user-role, which restates the classification. Every one is
    // deed-poll's own and PUBLIC, so restating it changes nothing.
    private static final CcdRoleConfig[] CCD_ROLES = {
        new CcdRoleConfig("deed-poll-system-update", "PUBLIC"),
        new CcdRoleConfig("DEED_POLL_SYSTEM", "PUBLIC"),
        new CcdRoleConfig("caseworker-deedpoll", "PUBLIC"),
        new CcdRoleConfig("caseworker-deedpoll-admin", "PUBLIC"),
        new CcdRoleConfig("caseworker-deedpoll-judicial", "PUBLIC"),
        new CcdRoleConfig("caseworker-deedpoll-team-leader", "PUBLIC"),
        new CcdRoleConfig("caseworker-deedpoll-delivery-manager", "PUBLIC"),
        new CcdRoleConfig("DEED_POLL_READER", "PUBLIC")
    };

    private final CcdEnvironment environment;

    public HighLevelDataSetupApp(CcdEnvironment dataSetupEnvironment) {
        super(dataSetupEnvironment);
        environment = dataSetupEnvironment;
    }

    public static void main(String[] args) throws Throwable {
        DataLoaderToDefinitionStore.main(HighLevelDataSetupApp.class, args);
    }

    @Override
    public void addCcdRoles() {
        for (CcdRoleConfig role : CCD_ROLES) {
            BeftaUtils.defaultLog("Adding CCD role " + role);
            addCcdRole(role);
        }
    }

    @Override
    protected List<String> getAllDefinitionFilesToLoadAt(String definitionsPath) {
        return List.of("build/definitions/CCD_Definition_" + CASE_TYPE + "_"
            + environment.name().toLowerCase(Locale.UK) + ".xlsx");
    }

    // Role assignments are AM's, and this service's AM role source is off; the import creates none.
    @Override
    public void createRoleAssignments() {
        BeftaUtils.defaultLog("Will NOT create role assignments");
    }
}
