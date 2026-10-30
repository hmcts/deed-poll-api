package uk.gov.hmcts.reform.deedpoll.api.caseworker.controller;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import uk.gov.hmcts.reform.deedpoll.auth.StubIdam;
import uk.gov.hmcts.reform.deedpoll.config.AbstractPostgresContainerTest;
import uk.gov.hmcts.reform.deedpoll.domain.auth.Role;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code /caseworker/masters} and linking on the judicial surface, through the full security context and PostgreSQL.
 *
 * <p>The audit rows cannot be deleted (V36), so each test counts the events on the entries it created.
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CaseworkerMastersControllerTest extends AbstractPostgresContainerTest {

    private static final String PATH = "/caseworker/masters";
    private static final String ADDRESS = "master.sullivan@judiciary.example";
    private static final String JUDGE = "judge-subject-0001";
    private static final StubIdam IDAM = new StubIdam();

    static {
        IDAM.start();
    }

    @AfterAll
    static void stopIdam() {
        IDAM.stop();
    }

    @DynamicPropertySource
    static void idamProperties(DynamicPropertyRegistry registry) {
        registry.add("idam.api.issuer-url", IDAM::issuerUrl);
        registry.add("idam.api.audiences", () -> StubIdam.AUDIENCE);
    }

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ObjectMapper json;

    private final Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();

    @BeforeEach
    void setUp() {
        IDAM.stubDefaults();
        asStaff(Role.DELIVERY_MANAGER.claimValue());
        jdbc.update("DELETE FROM deedpoll_master");
        logs.start();
        root.addAppender(logs);
    }

    @AfterEach
    void tearDown() {
        root.detachAppender(logs);
        logs.stop();
        jdbc.update("DELETE FROM deedpoll_master");
    }

    @Test
    @DisplayName("no token is 401 on every operation")
    void unauthenticated() throws Exception {
        mvc.perform(get(PATH)).andExpect(status().isUnauthorized());
        mvc.perform(post(PATH).contentType("application/json").content("{}")).andExpect(status().isUnauthorized());
        mvc.perform(patch(PATH + "/" + UUID.randomUUID()).contentType("application/json").content("{}"))
            .andExpect(status().isUnauthorized());
    }

    @ParameterizedTest
    @ValueSource(strings = {"caseworker-deedpoll", "caseworker-deedpoll-team-leader", "caseworker-deedpoll-admin",
        "caseworker-deedpoll-delivery-manager"})
    @DisplayName("every staff role reads the list")
    void staffRead(String role) throws Exception {
        String id = create("Master Sullivan", ADDRESS);
        asStaff(role);

        authorised(get(PATH)).andExpect(status().isOk()).andExpect(jsonPath("$.masters[0].id").value(id));
    }

    @ParameterizedTest
    @ValueSource(strings = {"caseworker-deedpoll", "caseworker-deedpoll-team-leader", "caseworker-deedpoll-admin",
        "caseworker-deedpoll-judicial", "caseworker-civil"})
    @DisplayName("everyone but a Delivery Manager is refused both writes, before any body check")
    void writesRefused(String role) throws Exception {
        final String id = create("Master Sullivan", ADDRESS);
        asStaff(role);

        send(post(PATH), "{\"name\":\"M\",\"email\":\"m@example.com\"}").andExpect(status().isForbidden());
        send(post(PATH), "{\"forged\":true}").andExpect(status().isForbidden());
        send(patch(PATH + "/" + id), "{\"available\":false,\"revision\":0}").andExpect(status().isForbidden());
        send(patch(PATH + "/" + UUID.randomUUID()), "{}").andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM deedpoll_master", Integer.class)).isOne();
        assertThat(jdbc.queryForObject("SELECT available FROM deedpoll_master", Boolean.class)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"caseworker-deedpoll-judicial", "caseworker-civil"})
    @DisplayName("a judge and a caller with no deed poll role are refused the list")
    void nonStaffRefusedTheList(String role) throws Exception {
        asStaff(role);

        authorised(get(PATH)).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("a Delivery Manager adds Masters, listed by name, with names-only audit and no subject anywhere")
    void createsAndLists() throws Exception {
        String sullivan = create("Master Sullivan", "  Master.Sullivan@Judiciary.EXAMPLE ");
        String brown = create("Master Brown", "master.brown@judiciary.example");

        JsonNode list = json.readTree(authorised(get(PATH)).andExpect(status().isOk()).andReturn().getResponse()
            .getContentAsString()).get("masters");
        assertThat(list).extracting(row -> row.get("id").asText()).containsExactly(brown, sullivan);
        assertThat(list.get(1).get("email").asText()).isEqualTo(ADDRESS);
        assertThat(list.get(1).get("available").asBoolean()).isTrue();
        assertThat(list.get(1).get("linked").asBoolean()).isFalse();
        assertThat(list.get(1).get("revision").asInt()).isZero();
        assertThat(list.get(1).fieldNames()).toIterable()
            .containsExactlyInAnyOrder("id", "name", "email", "available", "linked", "revision");
        assertThat(jdbc.queryForObject("""
            SELECT array_to_string(fields, ',') FROM deedpoll_audit_event
            WHERE entity_type = 'Master' AND entity_id = ? AND action = 'MASTER_ADDED'
            """, String.class, sullivan)).isEqualTo("name,email,available");
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM deedpoll_audit_event e
            WHERE e.entity_id = ? AND lower(e::text) LIKE '%judiciary.example%'
            """, Integer.class, sullivan)).isZero();
    }

    @Test
    @DisplayName("a duplicate email, in any case, is 409 on the email field without the address")
    void duplicateEmail() throws Exception {
        create("Master Sullivan", ADDRESS);

        String body = send(post(PATH), "{\"name\":\"Another\",\"email\":\"MASTER.Sullivan@judiciary.example\"}")
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.errors[0].field").value("email"))
            .andReturn().getResponse().getContentAsString();
        assertThat(body.toLowerCase(Locale.ROOT)).doesNotContain("sullivan@");
        String other = create("Master Brown", "master.brown@judiciary.example");
        send(patch(PATH + "/" + other), "{\"email\":\"" + ADDRESS.toUpperCase(Locale.ROOT) + "\",\"revision\":0}")
            .andExpect(status().isConflict()).andExpect(jsonPath("$.errors[0].field").value("email"));
        assertThat(logs.list).extracting(event -> event.getFormattedMessage().toLowerCase(Locale.ROOT))
            .noneMatch(line -> line.contains("sullivan@"));
    }

    @Test
    @DisplayName("a stale revision is 409; an unavailable Master stays listed; a no-op changes nothing")
    void updates() throws Exception {
        String id = create("Master Sullivan", ADDRESS);

        send(patch(PATH + "/" + id), "{\"available\":false,\"revision\":0}").andExpect(status().isOk())
            .andExpect(jsonPath("$.available").value(false)).andExpect(jsonPath("$.revision").value(1));
        send(patch(PATH + "/" + id), "{\"available\":true,\"revision\":0}").andExpect(status().isConflict());
        send(patch(PATH + "/" + id), "{\"available\":false,\"revision\":0}").andExpect(status().isConflict());
        send(patch(PATH + "/" + id), "{\"available\":false,\"revision\":1}").andExpect(status().isOk())
            .andExpect(jsonPath("$.revision").value(1));
        authorised(get(PATH)).andExpect(jsonPath("$.masters[0].id").value(id))
            .andExpect(jsonPath("$.masters[0].available").value(false));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM deedpoll_audit_event WHERE entity_id = ?",
                                       Integer.class, id)).isEqualTo(2);
        send(patch(PATH + "/" + id), "{\"revision\":1}").andExpect(status().isBadRequest());
        send(patch(PATH + "/" + UUID.randomUUID()), "{\"name\":\"M\",\"revision\":0}")
            .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("two concurrent edits of one revision: one saves, the other is 409, and one change is audited")
    void concurrentEdits() throws Exception {
        String id = create("Master Sullivan", ADDRESS);
        String token = IDAM.accessToken();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Integer> statuses = new ArrayList<>();
        try {
            List<Future<Integer>> results = new ArrayList<>();
            for (String body : List.of("{\"name\":\"Master A\",\"revision\":0}",
                                       "{\"name\":\"Master B\",\"revision\":0}")) {
                Callable<Integer> call = () -> mvc.perform(patch(PATH + "/" + id)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token).contentType("application/json")
                    .content(body)).andReturn().getResponse().getStatus();
                results.add(pool.submit(call));
            }
            for (Future<Integer> result : results) {
                statuses.add(result.get());
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        assertThat(jdbc.queryForObject("SELECT revision FROM deedpoll_master", Integer.class)).isOne();
        assertThat(jdbc.queryForObject(
            "SELECT count(*) FROM deedpoll_audit_event WHERE entity_id = ? AND action = 'MASTER_CHANGED'",
            Integer.class, id)).isOne();
    }

    @Test
    @DisplayName("a judge whose email is listed is linked on their first judicial request, and only once")
    void linksOnFirstJudicialRequest() throws Exception {
        String id = create("Master Sullivan", ADDRESS);

        judicialRequest(JUDGE, "Master.Sullivan@Judiciary.example");
        assertLinkedTo(id, JUDGE);
        Object linkedAt = jdbc.queryForObject("SELECT linked_at FROM deedpoll_master", Object.class);
        judicialRequest(JUDGE, ADDRESS);
        assertThat(jdbc.queryForObject("SELECT linked_at FROM deedpoll_master", Object.class)).isEqualTo(linkedAt);
        assertThat(linkedEvents(id)).isOne();
        assertThat(jdbc.queryForObject(
            "SELECT actor_name FROM deedpoll_audit_event WHERE entity_id = ? AND action = 'MASTER_LINKED'",
            String.class, id)).isEqualTo("# svc_HMCTS_LCPT_Dev");

        asStaff(Role.DELIVERY_MANAGER.claimValue());
        String list = authorised(get(PATH)).andExpect(jsonPath("$.masters[0].linked").value(true)).andReturn()
            .getResponse().getContentAsString();
        assertThat(list).doesNotContain(JUDGE);
        String changed = send(patch(PATH + "/" + id), "{\"email\":\"new@judiciary.example\",\"revision\":0}")
            .andExpect(status().isOk()).andExpect(jsonPath("$.linked").value(false)).andReturn().getResponse()
            .getContentAsString();
        assertThat(changed).doesNotContain(JUDGE);
        assertThat(jdbc.queryForObject("SELECT idam_subject FROM deedpoll_master", String.class)).isNull();
        judicialRequest(JUDGE, ADDRESS);
        assertThat(jdbc.queryForObject("SELECT idam_subject FROM deedpoll_master", String.class)).isNull();
    }

    @Test
    @DisplayName("two concurrent first requests link once")
    void concurrentFirstRequests() throws Exception {
        String id = create("Master Sullivan", ADDRESS);
        stubJudge(JUDGE, ADDRESS);
        String token = IDAM.accessToken(claims -> claims.subject(JUDGE));
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                Callable<Integer> call = () -> mvc.perform(get("/judicial/referrals")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)).andReturn().getResponse().getStatus();
                results.add(pool.submit(call));
            }
            for (Future<Integer> result : results) {
                assertThat(result.get()).isEqualTo(200);
            }
        } finally {
            pool.shutdownNow();
        }
        assertLinkedTo(id, JUDGE);
        assertThat(linkedEvents(id)).isOne();
    }

    @Test
    @DisplayName("an entry linked to another account is left alone, with a fixed WARN carrying no email")
    void linkedToAnotherAccount() throws Exception {
        String id = create("Master Sullivan", ADDRESS);
        judicialRequest(JUDGE, ADDRESS);

        judicialRequest("judge-subject-0002", ADDRESS);

        assertLinkedTo(id, JUDGE);
        assertThat(linkedEvents(id)).isOne();
        assertThat(logs.list).extracting(ILoggingEvent::getFormattedMessage)
            .contains("A judicial caller's email matches a Master already linked to another account");
        assertThat(logs.list).extracting(event -> event.getFormattedMessage().toLowerCase(Locale.ROOT))
            .noneMatch(line -> line.contains("sullivan@") || line.contains("judge-subject-0002"));
    }

    @Test
    @DisplayName("a caseworker whose email is listed links nothing")
    void caseworkerLinksNothing() throws Exception {
        final String id = create("Master Sullivan", ADDRESS);
        IDAM.stubUserInfoBody("""
            {"sub": "%s", "name": "A Caseworker", "email": "%s", "roles": ["%s"]}
            """.formatted(StubIdam.SUBJECT, ADDRESS, Role.CASEWORKER.claimValue()));

        authorised(get(PATH)).andExpect(status().isOk());
        authorised(get("/judicial/referrals")).andExpect(status().isForbidden());

        assertThat(jdbc.queryForObject("SELECT idam_subject FROM deedpoll_master", String.class)).isNull();
        assertThat(linkedEvents(id)).isZero();
    }

    private void judicialRequest(String subject, String email) throws Exception {
        stubJudge(subject, email);
        mvc.perform(get("/judicial/referrals").header(HttpHeaders.AUTHORIZATION,
            "Bearer " + IDAM.accessToken(claims -> claims.subject(subject)))).andExpect(status().isOk());
    }

    private void stubJudge(String subject, String email) {
        IDAM.stubUserInfoBody("""
            {"sub": "%s", "name": "Master Sullivan", "email": "%s", "roles": ["%s"]}
            """.formatted(subject, email, Role.JUDICIAL.claimValue()));
    }

    private void assertLinkedTo(String id, String subject) {
        assertThat(jdbc.queryForObject("SELECT idam_subject FROM deedpoll_master WHERE id = ?::uuid", String.class,
                                       id)).isEqualTo(subject);
    }

    private int linkedEvents(String id) {
        return jdbc.queryForObject(
            "SELECT count(*) FROM deedpoll_audit_event WHERE entity_id = ? AND action = 'MASTER_LINKED'",
            Integer.class, id);
    }

    private String create(String name, String email) throws Exception {
        String body = send(post(PATH), "{\"name\":\"" + name + "\",\"email\":\"" + email + "\"}")
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("id").asText();
    }

    private void asStaff(String role) {
        IDAM.stubUserInfo(StubIdam.SUBJECT, "Staff", List.of(role));
    }

    private ResultActions send(MockHttpServletRequestBuilder request, String body) throws Exception {
        return authorised(request.contentType("application/json").content(body));
    }

    private ResultActions authorised(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request.header(HttpHeaders.AUTHORIZATION, "Bearer " + IDAM.accessToken()));
    }
}
