package uk.gov.hmcts.reform.deedpoll.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import uk.gov.hmcts.reform.authorisation.generators.AuthTokenGenerator;
import uk.gov.hmcts.reform.deedpoll.domain.auth.OrganisationalRoleAssignment;

import java.io.IOException;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Asks the Role Assignment Service which assignments a caller holds: {@code GET /am/role-assignments/actors/{id}},
 * as {@code pcs-api}'s {@code RoleAssignmentApi} does.
 *
 * <p><strong>The caller's own token, plus this service's S2S.</strong> RAS answers a user about themselves, so the
 * bearer token is the one the caller presented, forwarded as {@link IdamUserInfoClient} forwards it to IDAM. No
 * system user's token is minted for this.
 *
 * <p><strong>Not cached, deliberately</strong>, for {@link IdamUserInfoClient}'s reason: a cache of authorisation data
 * lets a removed role survive for the cache's lifetime.
 *
 * <p><strong>Only the fields {@link OrganisationalRoleAssignment} holds are read</strong>, from a tree rather than
 * into a bound type, so a field RAS adds, renames or reshapes elsewhere in the document cannot fail the parse.
 * Which assignments count is {@link uk.gov.hmcts.reform.deedpoll.domain.auth.AmRoles}'s decision, not this class's.
 *
 * <p>Every failure is a {@link RoleAssignmentUnavailable}, which the caller answers by falling back to the IDAM
 * roles. The one exception is 404, which RAS gives for an actor it has no assignments for: that is an answer, and the
 * answer is none.
 */
public class RoleAssignmentClient {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String SERVICE_AUTHORIZATION = "ServiceAuthorization";

    private final RoleAssignmentProperties properties;
    private final AuthTokenGenerator serviceTokens;
    private final RestClient restClient;

    public RoleAssignmentClient(RoleAssignmentProperties properties, AuthTokenGenerator serviceTokens) {
        this(properties, serviceTokens, IdamHttp.restClient());
    }

    public RoleAssignmentClient(RoleAssignmentProperties properties, AuthTokenGenerator serviceTokens,
                                RestClient restClient) {
        this.properties = properties;
        this.serviceTokens = serviceTokens;
        this.restClient = restClient;
    }

    /**
     * Every assignment RAS holds for {@code subject}, unfiltered.
     *
     * @param token   the caller's raw access token, forwarded as their own credential
     * @param subject the caller's IDAM subject, which is their RAS actor id
     * @throws RoleAssignmentUnavailable when RAS could not be asked, or its answer could not be read
     */
    public List<OrganisationalRoleAssignment> assignments(String token, String subject) {
        String serviceToken;
        try {
            serviceToken = serviceTokens.generate();
        } catch (RuntimeException e) {
            throw new RoleAssignmentUnavailable("Could not mint an S2S token for RAS", null, e);
        }

        byte[] body;
        try {
            body = restClient.get()
                .uri(properties.url() + "/am/role-assignments/actors/{actorId}", subject)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .header(SERVICE_AUTHORIZATION, serviceToken)
                .retrieve()
                .body(byte[].class);
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 404) {
                return List.of();
            }
            // No cause: a response exception's message carries RAS's body, which is never to reach a log.
            throw new RoleAssignmentUnavailable("RAS answered " + e.getStatusCode().value(),
                                                e.getStatusCode().value(), null);
        } catch (RestClientException e) {
            throw new RoleAssignmentUnavailable("RAS could not be reached", null, e);
        }

        return parse(body);
    }

    private static List<OrganisationalRoleAssignment> parse(byte[] body) {
        JsonNode list;
        try {
            list = body == null ? null : JSON.readTree(body).get("roleAssignmentResponse");
        } catch (IOException e) {
            throw new RoleAssignmentUnavailable("RAS returned an unreadable body", null, e);
        }
        if (list == null || !list.isArray()) {
            throw new RoleAssignmentUnavailable("RAS returned no roleAssignmentResponse list", null, null);
        }

        List<OrganisationalRoleAssignment> assignments = new ArrayList<>(list.size());
        for (JsonNode assignment : list) {
            if (!assignment.isObject()) {
                throw new RoleAssignmentUnavailable("RAS returned an assignment that is not an object", null, null);
            }
            assignments.add(new OrganisationalRoleAssignment(
                text(assignment, "roleName"),
                text(assignment, "roleType"),
                text(assignment, "grantType"),
                time(assignment, "beginTime"),
                time(assignment, "endTime"),
                attributes(assignment),
                authorisations(assignment)));
        }
        return List.copyOf(assignments);
    }

    private static String text(JsonNode assignment, String field) {
        JsonNode value = assignment.get(field);
        return value == null || !value.isTextual() ? null : value.asText();
    }

    /**
     * RAS serialises a {@code ZonedDateTime}: an offset, and possibly a bracketed zone after it.
     *
     * <p>A time that is present but unreadable fails the whole answer rather than reading as absent, because an
     * absent end time means "never ends" -- a garbled expiry must not become a permanent role.
     */
    private static Instant time(JsonNode assignment, String field) {
        JsonNode value = assignment.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        try {
            return ZonedDateTime.parse(value.asText(), DateTimeFormatter.ISO_ZONED_DATE_TIME).toInstant();
        } catch (DateTimeParseException e) {
            throw new RoleAssignmentUnavailable("RAS returned an unreadable " + field, null, e);
        }
    }

    private static Map<String, ?> attributes(JsonNode assignment) {
        JsonNode attributes = assignment.get("attributes");
        if (attributes == null || !attributes.isObject()) {
            return null;
        }
        Map<String, String> read = new LinkedHashMap<>();
        attributes.properties().forEach(entry -> {
            if (entry.getValue().isTextual()) {
                read.put(entry.getKey(), entry.getValue().asText());
            }
        });
        return Collections.unmodifiableMap(read);
    }

    private static List<String> authorisations(JsonNode assignment) {
        JsonNode authorisations = assignment.get("authorisations");
        if (authorisations == null || !authorisations.isArray()) {
            return List.of();
        }
        List<String> read = new ArrayList<>();
        authorisations.forEach(value -> {
            if (value.isTextual()) {
                read.add(value.asText());
            }
        });
        return List.copyOf(read);
    }
}
