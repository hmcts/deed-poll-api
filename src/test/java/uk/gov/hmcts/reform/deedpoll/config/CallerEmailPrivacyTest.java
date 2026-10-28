package uk.gov.hmcts.reform.deedpoll.config;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import uk.gov.hmcts.reform.deedpoll.auth.StubIdam;
import uk.gov.hmcts.reform.deedpoll.domain.auth.Role;
import uk.gov.hmcts.reform.deedpoll.repository.DeedPollNoteRepository;
import uk.gov.hmcts.reform.deedpoll.repository.DeedPollRepository;
import uk.gov.hmcts.reform.deedpoll.repository.ReferenceCounterRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * The caller's email address reaches {@code CurrentUser} and goes nowhere else (plan D1).
 *
 * <p>Through the real filter chain against real Postgres: a caller whose userinfo carries an address signs in, reads
 * {@code /me}, creates a case, writes a note, views the case and is refused a Delivery Manager route; then a judge
 * with the same address, listed as a Master, is linked by a judicial request and makes another. The address is then
 * looked for, in any case, in every response body, every log line the requests produced and every row of the audit
 * trail and the notes, the {@code MASTER_LINKED} event included. Linking is its one reader.
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CallerEmailPrivacyTest extends AbstractPostgresContainerTest {

    private static final String ADDRESS = "Distinctive.Caseworker@Example.GOV.UK";
    private static final String NEEDLE = ADDRESS.toLowerCase(Locale.ROOT);

    private static final StubIdam IDAM = new StubIdam();

    static {
        IDAM.start();
    }

    @AfterAll
    static void stopIdam() {
        IDAM.stop();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("idam.api.issuer-url", IDAM::issuerUrl);
        registry.add("idam.api.audiences", () -> StubIdam.AUDIENCE);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private DeedPollNoteRepository notes;

    @Autowired
    private DeedPollRepository deedPolls;

    @Autowired
    private ReferenceCounterRepository referenceCounters;

    private final Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private final List<String> bodies = new ArrayList<>();

    @BeforeEach
    void setUp() {
        IDAM.stubDefaults();
        IDAM.stubUserInfoBody("""
            {"sub": "%s", "name": "A Caseworker", "email": "  %s ", "roles": ["%s"]}
            """.formatted(StubIdam.SUBJECT, ADDRESS, Role.TEAM_LEADER.claimValue()));
        logs.start();
        root.addAppender(logs);
    }

    @AfterEach
    void tearDown() {
        root.detachAppender(logs);
        logs.stop();
        notes.deleteAll();
        deedPolls.deleteAll();
        referenceCounters.deleteAll();
        jdbc.update("DELETE FROM deedpoll_master");
    }

    @Test
    @DisplayName("the address appears in no response, log line, audit row or note")
    void appearsNowhere() throws Exception {
        perform(200, get("/me"));
        String created = perform(201, post("/caseworker/cases").contentType(MediaType.APPLICATION_JSON).content("""
            {"dateReceived": "2026-07-01T00:00:00.000Z", "type": "Adult", "newForename": "Ada"}"""));
        String caseId = objectMapper.readTree(created).get("id").asText();
        perform(201, post("/caseworker/cases/" + caseId + "/notes").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"note\": \"A note\"}"));
        perform(200, get("/caseworker/cases/" + caseId));
        perform(403, get("/caseworker/process-settings"));
        jdbc.update("INSERT INTO deedpoll_master (id, name, email, available, created_at, created_by_id, "
                        + "created_by_name, revision) VALUES (gen_random_uuid(), 'Master', ?, true, now(), 's', "
                        + "'A Delivery Manager', 0)", NEEDLE);
        IDAM.stubUserInfoBody("""
            {"sub": "%s", "name": "A Master", "email": "  %s ", "roles": ["%s"]}
            """.formatted(StubIdam.SUBJECT, ADDRESS, Role.JUDICIAL.claimValue()));
        perform(200, get("/judicial/referrals"));
        perform(200, get("/judicial/referrals"));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM deedpoll_master WHERE idam_subject = ?", Integer.class,
                                       StubIdam.SUBJECT)).isOne();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM deedpoll_audit_event WHERE action = 'MASTER_LINKED'",
                                       Integer.class)).isPositive();
        assertThat(bodies).hasSize(7).allSatisfy(body -> assertThat(body.toLowerCase(Locale.ROOT))
            .doesNotContain(NEEDLE));
        assertThat(logs.list).extracting(event -> event.getFormattedMessage().toLowerCase(Locale.ROOT))
            .noneMatch(line -> line.contains(NEEDLE));
        assertThat(rowsCarryingTheAddress("deedpoll_audit_event")).isZero();
        assertThat(rowsCarryingTheAddress("deedpoll_audit_event_value")).isZero();
        assertThat(rowsCarryingTheAddress("deedpoll_notes")).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM deedpoll_audit_event WHERE case_id = ?",
                                       Integer.class, caseId)).isPositive();
    }

    private String perform(int expected, MockHttpServletRequestBuilder request) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(IDAM.accessToken());
        MvcResult result = mockMvc.perform(request.headers(headers)).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(expected);
        String body = result.getResponse().getContentAsString();
        bodies.add(body);
        return body;
    }

    private int rowsCarryingTheAddress(String table) {
        return jdbc.queryForObject(
            "SELECT count(*) FROM " + table + " t WHERE lower(t::text) LIKE ?", Integer.class, "%" + NEEDLE + "%");
    }
}
