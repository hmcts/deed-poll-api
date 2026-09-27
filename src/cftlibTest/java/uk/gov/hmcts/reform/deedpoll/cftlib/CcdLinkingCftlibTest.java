package uk.gov.hmcts.reform.deedpoll.cftlib;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import uk.gov.hmcts.reform.authorisation.generators.AuthTokenGenerator;
import uk.gov.hmcts.reform.ccd.client.CoreCaseDataApi;
import uk.gov.hmcts.reform.ccd.client.model.CaseDetails;
import uk.gov.hmcts.reform.deedpoll.ccd.CcdLinkingService;
import uk.gov.hmcts.reform.deedpoll.ccd.CcdLinkingService.Result;
import uk.gov.hmcts.reform.deedpoll.ccd.CcdProperties;
import uk.gov.hmcts.reform.deedpoll.ccd.DeedPollCaseState;
import uk.gov.hmcts.reform.deedpoll.ccd.SystemUserTokens;
import uk.gov.hmcts.reform.deedpoll.entity.enums.CcdCreationAttemptStatus;
import uk.gov.hmcts.reform.deedpoll.entity.DeedPoll;
import uk.gov.hmcts.reform.deedpoll.repository.DeedPollRepository;
import uk.gov.hmcts.rse.ccd.lib.test.CftlibTest;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The linking operation against the real CCD stack, in process: data-store, definition store and user profile,
 * with the IDAM and S2S simulators. Placeholders throughout -- the case type, jurisdiction and system role are
 * {@code application.yaml}'s {@code DEED_POLL_PLACEHOLDER}, which {@link CftlibConfig} registers and imports.
 *
 * <p>What the WireMock scenarios in {@code src/test} cannot show is that CCD itself agrees: that data-store accepts
 * the generated definition, routes {@code linkDeedPoll} to this service's {@code /ccd-persistence} with a token our
 * chain admits, and answers a read of the case from what this service holds. So the case is read back through
 * data-store, as the system user, and compared with both of this service's own records of it -- the binding on
 * {@code deedpoll_deedpoll} and the SDK's {@code ccd.case_data} row.
 *
 * <p>Local only: {@code ./gradlew cftlibTest}, which neither {@code build} nor {@code check} runs.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
// `development` is PlainHttp's allowlisted name for a cleartext local IDAM, and the simulator is plain HTTP; no
// yaml of that name exists, so it relaxes that one check and nothing else.
@ActiveProfiles({"cftlib", "development"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CcdLinkingCftlibTest extends CftlibTest {

    @Autowired
    private DeedPollRepository deedPolls;

    @Autowired
    private CcdLinkingService linking;

    @Autowired
    private CoreCaseDataApi ccd;

    @Autowired
    private SystemUserTokens systemUser;

    @Autowired
    private AuthTokenGenerator serviceTokens;

    @Autowired
    private CcdProperties identifiers;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("a local record is linked to one CCD case, and CCD, the binding and ccd.case_data agree on it")
    void createsLinksAndReadsOneCase() {
        DeedPoll record = new DeedPoll();
        // Unique per run: the stack's database outlives it unless RSE_LIB_CLEAN_BOOT is set.
        String displayReference = "CFTLIB-" + UUID.randomUUID().toString().substring(0, 8);
        record.setDisplayReference(displayReference);
        String id = deedPolls.save(record).getId();

        assertThat(linking.link(id)).isEqualTo(Result.LINKED);

        String reference = deedPolls.findCcdCaseReference(id).orElseThrow();
        assertThat(attemptStatus(id)).isEqualTo(CcdCreationAttemptStatus.Linked.name());

        CaseDetails read = ccd.getCase(systemUser.accessToken(), serviceTokens.generate(), reference);
        assertThat(read.getId()).isEqualTo(Long.valueOf(reference));
        assertThat(read.getCaseTypeId()).isEqualTo(identifiers.caseTypeId());
        assertThat(read.getJurisdiction()).isEqualTo(identifiers.jurisdictionId());
        assertThat(read.getState()).isEqualTo(DeedPollCaseState.Registered.name());
        assertThat(read.getData())
            .containsEntry("deedPollId", id)
            .containsEntry("deedPollReference", displayReference);

        assertThat(jdbc.queryForObject(
            "SELECT count(*) FROM ccd.case_data WHERE reference = ? AND case_type_id = ?",
            Integer.class, Long.valueOf(reference), identifiers.caseTypeId())).isOne();

        // And nothing is sent a second time for a record already linked.
        assertThat(linking.link(id)).isEqualTo(Result.ALREADY_LINKED);
        assertThat(jdbc.queryForObject(
            "SELECT count(*) FROM ccd.case_data WHERE reference = ?", Integer.class, Long.valueOf(reference)))
            .isOne();
    }

    private String attemptStatus(String deedPollId) {
        return jdbc.queryForObject(
            "SELECT \"status\"::text FROM deedpoll_ccdcreationattempt WHERE \"deedPollId\" = ?",
            String.class, deedPollId);
    }
}
