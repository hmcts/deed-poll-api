package uk.gov.hmcts.reform.deedpoll.auth;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import uk.gov.hmcts.reform.deedpoll.domain.auth.OrganisationalRoleAssignment;

import java.io.IOException;
import java.net.ServerSocket;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The RAS lookup against a stub: what it sends, what it reads, and which answers are failures.
 *
 * <p>The failures matter as much as the parse. Each must be a {@link RoleAssignmentUnavailable}, because that is the
 * one exception the caller answers by falling back to the IDAM roles; anything else would turn a RAS outage into a
 * 500 on every request. The exception is a 404, which RAS gives an actor it has nothing for.
 */
class RoleAssignmentClientTest {

    private static final WireMockServer RAS = new WireMockServer(WireMockConfiguration.options().dynamicPort());
    private static final String SUBJECT = "0b8d9a4e-4a8c-4f42-9a62-5c1f3e4d2b10";
    private static final String ACTOR_PATH = "/am/role-assignments/actors/" + SUBJECT;

    @BeforeAll
    static void startRas() {
        RAS.start();
    }

    @AfterAll
    static void stopRas() {
        RAS.stop();
    }

    @BeforeEach
    void resetRas() {
        RAS.resetAll();
    }

    private static RoleAssignmentClient client() {
        return client(RAS.baseUrl());
    }

    private static RoleAssignmentClient client(String url) {
        return new RoleAssignmentClient(new RoleAssignmentProperties(true, url, null, null), () -> "s2s-token");
    }

    private static void answer(int status, String body) {
        RAS.stubFor(get(urlPathMatching("/am/role-assignments/actors/.*")).willReturn(aResponse()
            .withStatus(status)
            .withHeader("Content-Type", "application/json")
            .withBody(body)));
    }

    @Test
    @DisplayName("sends the caller's own token and this service's S2S token, to the subject's actor path")
    void sendsBothTokensToTheActorPath() {
        answer(200, """
            {"roleAssignmentResponse":[]}""");

        client().assignments("user-token", SUBJECT);

        RAS.verify(getRequestedFor(urlPathEqualTo(ACTOR_PATH))
                       .withHeader("Authorization", equalTo("Bearer user-token"))
                       .withHeader("ServiceAuthorization", equalTo("s2s-token")));
    }

    @Test
    @DisplayName("reads every field the mapping uses")
    void readsTheFieldsTheMappingUses() {
        answer(200, """
            {"roleAssignmentResponse":[{
              "roleName":"ctsc-team-leader",
              "roleType":"ORGANISATION",
              "grantType":"STANDARD",
              "beginTime":"2026-01-01T00:00:00Z",
              "endTime":"2027-01-01T09:30:00+01:00",
              "attributes":{"jurisdiction":"DEEDPOLL","primaryLocation":"123"},
              "authorisations":["SKILL:DEEDPOLL:GAZETTE"]
            }]}""");

        List<OrganisationalRoleAssignment> assignments = client().assignments("token", SUBJECT);

        assertThat(assignments).containsExactly(new OrganisationalRoleAssignment(
            "ctsc-team-leader", "ORGANISATION", "STANDARD",
            Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2027-01-01T08:30:00Z"),
            Map.of("jurisdiction", "DEEDPOLL", "primaryLocation", "123"),
            List.of("SKILL:DEEDPOLL:GAZETTE")));
    }

    @Test
    @DisplayName("reads a time carrying a bracketed zone, as a ZonedDateTime serialises")
    void readsAZonedTime() {
        answer(200, """
            {"roleAssignmentResponse":[{"roleName":"ctsc","endTime":"2027-06-01T00:00:00+01:00[Europe/London]"}]}""");

        assertThat(client().assignments("token", SUBJECT).getFirst().endTime())
            .isEqualTo(Instant.parse("2027-05-31T23:00:00Z"));
    }

    @Test
    @DisplayName("ignores fields it does not read, and reads absent ones as null")
    void ignoresUnknownFields() {
        // RAS's document has many more fields than this, and gains them: none may fail the parse.
        answer(200, """
            {"roleAssignmentResponse":[{
              "id":"a1","actorIdType":"IDAM","actorId":"%s","classification":"PUBLIC",
              "readOnly":false,"created":"2026-01-01T00:00:00Z","notes":[{"comment":"x"}],
              "roleName":"ctsc","roleType":"ORGANISATION","roleCategory":"CTSC",
              "attributes":{"jurisdiction":"DEEDPOLL","substantive":true,"region":{"nested":"value"}}
            }],"somethingNew":{"a":1}}""".formatted(SUBJECT));

        OrganisationalRoleAssignment assignment = client().assignments("token", SUBJECT).getFirst();

        assertThat(assignment.roleName()).isEqualTo("ctsc");
        assertThat(assignment.grantType()).isNull();
        assertThat(assignment.beginTime()).isNull();
        assertThat(assignment.endTime()).isNull();
        assertThat(assignment.authorisations()).isEmpty();
        assertThat(assignment.attributes()).isEqualTo(Map.of("jurisdiction", "DEEDPOLL"));
    }

    @Test
    @DisplayName("reads a 404 as an actor with no assignments")
    void readsNotFoundAsNone() {
        answer(404, """
            {"errorMessage":"Role Assignment not found"}""");

        assertThat(client().assignments("token", SUBJECT)).isEmpty();
    }

    @Test
    @DisplayName("fails a 500 with its status, and without RAS's body anywhere in the exception")
    void failsAServerError() {
        answer(500, """
            {"errorDescription":"secret-detail"}""");

        assertThatThrownBy(() -> client().assignments("token", SUBJECT))
            .isInstanceOfSatisfying(RoleAssignmentUnavailable.class, e -> {
                assertThat(e.status()).hasValue(500);
                assertThat(e.getCause()).isNull();
            })
            .hasMessageNotContaining("secret-detail");
    }

    @Test
    @DisplayName("fails a refusal of this service's credentials, rather than reading it as no roles")
    void failsAForbidden() {
        // Until RAS admits deed_poll_api, this is the answer every lookup gets.
        answer(403, "");

        assertThatThrownBy(() -> client().assignments("token", SUBJECT))
            .isInstanceOfSatisfying(RoleAssignmentUnavailable.class, e -> assertThat(e.status()).hasValue(403));
    }

    @Test
    @DisplayName("fails a body that is not JSON")
    void failsAMalformedBody() {
        answer(200, "<html>not json</html>");

        assertThatThrownBy(() -> client().assignments("token", SUBJECT))
            .isInstanceOfSatisfying(RoleAssignmentUnavailable.class, e -> assertThat(e.status()).isEmpty());
    }

    @Test
    @DisplayName("fails a JSON body without the assignment list, rather than reading it as none")
    void failsABodyWithoutTheList() {
        answer(200, """
            {"something":"else"}""");

        assertThatThrownBy(() -> client().assignments("token", SUBJECT))
            .isInstanceOf(RoleAssignmentUnavailable.class);
    }

    @Test
    @DisplayName("fails an unreadable end time, rather than reading it as a role that never ends")
    void failsAnUnreadableTime() {
        answer(200, """
            {"roleAssignmentResponse":[{"roleName":"ctsc","endTime":"next Tuesday"}]}""");

        assertThatThrownBy(() -> client().assignments("token", SUBJECT))
            .isInstanceOf(RoleAssignmentUnavailable.class)
            .hasMessageContaining("endTime");
    }

    @Test
    @DisplayName("fails when RAS refuses the connection")
    void failsARefusedConnection() throws IOException {
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }

        assertThatThrownBy(() -> client("http://localhost:" + port).assignments("token", SUBJECT))
            .isInstanceOfSatisfying(RoleAssignmentUnavailable.class, e -> assertThat(e.status()).isEmpty());
    }

    @Test
    @DisplayName("fails without calling RAS when no S2S token can be minted")
    void failsWithoutAServiceToken() {
        RoleAssignmentClient client = new RoleAssignmentClient(
            new RoleAssignmentProperties(true, RAS.baseUrl(), null, null),
            () -> {
                throw new IllegalStateException("no secret");
            });

        assertThatThrownBy(() -> client.assignments("token", SUBJECT))
            .isInstanceOf(RoleAssignmentUnavailable.class);
        RAS.verify(0, getRequestedFor(urlPathMatching(".*")));
    }

    @Test
    @DisplayName("asks RAS every time, rather than caching a caller's roles")
    void doesNotCache() {
        answer(200, """
            {"roleAssignmentResponse":[]}""");
        RoleAssignmentClient client = client();

        client.assignments("token", SUBJECT);
        client.assignments("token", SUBJECT);

        RAS.verify(2, getRequestedFor(urlPathEqualTo(ACTOR_PATH)));
    }

    @Nested
    @DisplayName("settings")
    class Settings {

        @Test
        @DisplayName("default to off, a local RAS, the DEEDPOLL jurisdiction and no Gazette skill")
        void defaults() {
            RoleAssignmentProperties properties = new RoleAssignmentProperties(false, null, " ", null);

            assertThat(properties.enabled()).isFalse();
            assertThat(properties.url()).isEqualTo("http://localhost:4096");
            assertThat(properties.jurisdiction()).isEqualTo("DEEDPOLL");
            assertThat(properties.gazetteSkill()).isEmpty();
        }

        @Test
        @DisplayName("trim values, and drop a trailing slash from the URL")
        void normalises() {
            RoleAssignmentProperties properties =
                new RoleAssignmentProperties(true, " https://ras.example/ ", " DEEDPOLL ", " SKILL ");

            assertThat(properties.url()).isEqualTo("https://ras.example");
            assertThat(properties.jurisdiction()).isEqualTo("DEEDPOLL");
            assertThat(properties.gazetteSkill()).isEqualTo("SKILL");
        }
    }
}
