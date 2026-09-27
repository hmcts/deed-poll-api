package uk.gov.hmcts.reform.deedpoll.cftlib;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import uk.gov.hmcts.reform.authorisation.generators.AuthTokenGenerator;
import uk.gov.hmcts.reform.ccd.client.CoreCaseDataApi;
import uk.gov.hmcts.reform.ccd.client.model.CaseDetails;
import uk.gov.hmcts.reform.deedpoll.ccd.CcdCreationAttempts;
import uk.gov.hmcts.reform.deedpoll.ccd.CcdLinkingService;
import uk.gov.hmcts.reform.deedpoll.ccd.CcdLinkingService.Result;
import uk.gov.hmcts.reform.deedpoll.ccd.CcdLinkingSweep;
import uk.gov.hmcts.reform.deedpoll.ccd.CcdProperties;
import uk.gov.hmcts.reform.deedpoll.ccd.DeedPollCaseState;
import uk.gov.hmcts.reform.deedpoll.ccd.SystemUserTokens;
import uk.gov.hmcts.reform.deedpoll.entity.CcdCreationAttempt;
import uk.gov.hmcts.reform.deedpoll.entity.DeedPoll;
import uk.gov.hmcts.reform.deedpoll.entity.enums.CcdCreationAttemptStatus;
import uk.gov.hmcts.reform.deedpoll.repository.DeedPollRepository;
import uk.gov.hmcts.reform.idam.client.IdamClient;
import uk.gov.hmcts.rse.ccd.lib.test.CftlibTest;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
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
 * <p>The other two tests are plan 02b's journeys, driven over HTTP as {@link CftlibConfig#ADMIN_USER} with the sweep
 * switched on by the cftlib profile. One is a record created through {@code POST /caseworker/cases} and linked by the
 * next {@code GET /caseworker/cases}. The other is an attempt forced into {@code NeedsRepair} before anything was
 * submitted, retried from scratch through the repair endpoint, and then linked by the next list request. Each
 * checks that the table of cases grew by exactly one, which is the only way a second case for one record would show.
 * The first logs how long its list request took with one record to link and with none.
 *
 * <p>Local only: {@code ./gradlew cftlibTest}, which neither {@code build} nor {@code check} runs.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
// `development` is PlainHttp's allowlisted name for a cleartext local IDAM, and the simulator is plain HTTP; no
// yaml of that name exists, so it relaxes that one check and nothing else.
@ActiveProfiles({"cftlib", "development"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CcdLinkingCftlibTest extends CftlibTest {

    private final HttpClient http = HttpClient.newHttpClient();

    @LocalServerPort
    private int port;

    @Autowired
    private DeedPollRepository deedPolls;

    @Autowired
    private CcdLinkingService linking;

    @Autowired
    private CcdLinkingSweep sweep;

    @Autowired
    private CcdCreationAttempts attempts;

    @Autowired
    private IdamClient idam;

    @Autowired
    private ObjectMapper json;

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

    @Test
    @DisplayName("a record created through the API is linked by the next case list request, and CCD agrees on it")
    void theCaseListLinksANewRecord() throws Exception {
        clearTheQueue();
        final int casesBefore = cases();

        String id = createRecord();
        // The create waits on nothing from CCD (plan 02b, decision 2).
        assertThat(deedPolls.findCcdCaseReference(id)).isEmpty();

        final long linkingList = timedListCases();
        String reference = deedPolls.findCcdCaseReference(id).orElseThrow();
        assertThat(attemptStatus(id)).isEqualTo(CcdCreationAttemptStatus.Linked.name());
        assertReadsBackThroughCcd(reference, id);
        assertThat(cases()).isEqualTo(casesBefore + 1);

        // The same request with nothing left to link, so the difference is what linking one record cost it.
        long idleList = timedListCases();
        // Printed, not logged: this service's log lines do not reach the cftlibTest console.
        System.out.printf("cftlib: the case list that linked one record took %d ms; with nothing to link, %d ms%n",
            linkingList, idleList);
        assertThat(cases()).isEqualTo(casesBefore + 1);
    }

    @Test
    @DisplayName("a NeedsRepair attempt that submitted nothing is retried by an admin, then linked by the next sweep")
    void anAdminRetryIsLinkedByTheNextSweep() throws Exception {
        clearTheQueue();
        final int casesBefore = cases();

        String id = createRecord();
        // Forced, before any list request can link it: an attempt that never reached CCD, parked for a person.
        CcdCreationAttempt attempt = attempts.open(id).orElseThrow();
        jdbc.update("""
            UPDATE deedpoll_ccdcreationattempt
               SET "status" = 'NeedsRepair', "attempts" = 3, "failureDetail" = 'Forced by CcdLinkingCftlibTest'
             WHERE id = ?
            """, attempt.getId());
        assertThat(submissionMayHaveReached(id)).isFalse();

        HttpResponse<String> retried = send(HttpRequest.newBuilder(uri(
                "/caseworker/ccd-attempts/" + attempt.getId() + "/retry"))
            .POST(HttpRequest.BodyPublishers.noBody()));
        assertThat(retried.statusCode()).isEqualTo(200);
        assertThat(json.readTree(retried.body()).path("status").asText())
            .isEqualTo(CcdCreationAttemptStatus.Pending.name());
        assertThat(attemptStatus(id)).isEqualTo(CcdCreationAttemptStatus.Pending.name());
        // The move itself calls nothing.
        assertThat(deedPolls.findCcdCaseReference(id)).isEmpty();
        assertThat(cases()).isEqualTo(casesBefore);

        System.out.printf("cftlib: the case list that linked the retried record took %d ms%n", timedListCases());

        String reference = deedPolls.findCcdCaseReference(id).orElseThrow();
        assertThat(attemptStatus(id)).isEqualTo(CcdCreationAttemptStatus.Linked.name());
        assertThat(jdbc.queryForObject(
            "SELECT count(*) FROM deedpoll_ccdcreationattempt WHERE \"deedPollId\" = ?", Integer.class, id)).isOne();
        assertReadsBackThroughCcd(reference, id);
        assertThat(cases()).isEqualTo(casesBefore + 1);
    }

    /**
     * Links whatever earlier runs left due, so the record a test creates is the head of the queue: the stack's
     * database outlives a run, and the sweep takes the oldest first.
     */
    private void clearTheQueue() {
        for (int run = 0; run < 20 && !attempts.dueForLinking(1).isEmpty(); run++) {
            sweep.sweep();
        }
        assertThat(attempts.dueForLinking(1)).isEmpty();
    }

    /** POST /caseworker/cases as the admin caseworker, which must answer 201; the new record's id. */
    private String createRecord() throws Exception {
        HttpResponse<String> created = send(HttpRequest.newBuilder(uri("/caseworker/cases"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString("{\"dateReceived\":\"2026-07-01T00:00:00.000Z\"}")));
        assertThat(created.statusCode()).isEqualTo(201);
        return json.readTree(created.body()).path("id").asText();
    }

    /** GET /caseworker/cases as the admin caseworker, which must answer 200; how long it took, in milliseconds. */
    private long timedListCases() throws Exception {
        long started = System.nanoTime();
        HttpResponse<String> listed = send(HttpRequest.newBuilder(uri("/caseworker/cases")).GET());
        long elapsed = Duration.ofNanos(System.nanoTime() - started).toMillis();
        assertThat(listed.statusCode()).isEqualTo(200);
        return elapsed;
    }

    private HttpResponse<String> send(HttpRequest.Builder request) throws Exception {
        return http.send(request.header("Authorization", idam.getAccessToken(CftlibConfig.ADMIN_USER, "password"))
            .build(), HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private void assertReadsBackThroughCcd(String reference, String deedPollId) {
        CaseDetails read = ccd.getCase(systemUser.accessToken(), serviceTokens.generate(), reference);
        assertThat(read.getId()).isEqualTo(Long.valueOf(reference));
        assertThat(read.getCaseTypeId()).isEqualTo(identifiers.caseTypeId());
        assertThat(read.getState()).isEqualTo(DeedPollCaseState.Registered.name());
        assertThat(read.getData()).containsEntry("deedPollId", deedPollId);
    }

    /**
     * Every case this service holds for the placeholder case type. The SDK stores {@code {}} as each case's data, so
     * a second case for one record shows only as one row too many.
     */
    private int cases() {
        return jdbc.queryForObject("SELECT count(*) FROM ccd.case_data WHERE case_type_id = ?", Integer.class,
            identifiers.caseTypeId());
    }

    private boolean submissionMayHaveReached(String deedPollId) {
        return jdbc.queryForObject(
            "SELECT \"submissionMayHaveReached\" FROM deedpoll_ccdcreationattempt WHERE \"deedPollId\" = ?",
            Boolean.class, deedPollId);
    }

    private String attemptStatus(String deedPollId) {
        return jdbc.queryForObject(
            "SELECT \"status\"::text FROM deedpoll_ccdcreationattempt WHERE \"deedPollId\" = ?",
            String.class, deedPollId);
    }
}
