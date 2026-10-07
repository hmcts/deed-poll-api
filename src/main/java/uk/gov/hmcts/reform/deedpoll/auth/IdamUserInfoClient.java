package uk.gov.hmcts.reform.deedpoll.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import uk.gov.hmcts.reform.deedpoll.domain.UnauthorizedError;
import uk.gov.hmcts.reform.deedpoll.domain.auth.CurrentUser;
import uk.gov.hmcts.reform.deedpoll.domain.auth.IdamClaims;

import java.io.IOException;
import java.util.Map;
import java.util.Objects;

/**
 * Asks IDAM who a validated token belongs to.
 *
 * <p>Ported from {@code fetchUserInfo()} in {@code apps/api/src/auth/idam.ts}.
 *
 * <p><strong>Roles come from {@code /o/userinfo}, not from the token.</strong> An IDAM access token carries
 * {@code sub}, {@code aud}, {@code scope}, {@code tokenName} and similar, and no roles at all -- checked
 * against {@code hmcts/rse-idam-simulator}'s {@code JwTokenGeneratorService}, whose {@code IdamUserInfo} is
 * what holds {@code roles}. The sibling web app learned this the expensive way: it read roles from the ID
 * token, passed against a stub that put them there, and would have found every caseworker role-less against
 * real IDAM.
 *
 * <p><strong>Not cached, deliberately.</strong> A cache here is a cache of authorisation data: a caseworker
 * whose admin role is removed would keep it for the cache's lifetime, and the operations it gates are the
 * ones worth removing it for. Nothing about a back-office API's traffic makes one extra round trip per
 * request worth that.
 *
 * <p>The subject is checked against the token's. Without it, a response for the wrong user would be accepted
 * as this one's identity.
 */
public class IdamUserInfoClient {

    private static final ParameterizedTypeReference<Map<String, Object>> CLAIMS =
        new ParameterizedTypeReference<>() {
        };
    private static final String UNKNOWN_USER = "unauthorized_client";
    private static final ObjectMapper JSON = new ObjectMapper();

    private final IdamDiscovery discovery;
    private final RestClient restClient;

    public IdamUserInfoClient(IdamDiscovery discovery) {
        this(discovery, IdamHttp.restClient());
    }

    public IdamUserInfoClient(IdamDiscovery discovery, RestClient restClient) {
        this.discovery = discovery;
        this.restClient = restClient;
    }

    /**
     * The caller a validated token identifies.
     *
     * @param token   the raw access token, forwarded to IDAM as the caller's own credential
     * @param subject the {@code sub} the validated token claims, which the response must agree with
     * @throws UnauthorizedError for anything the caller could fix by presenting a different token
     */
    public CurrentUser currentUser(String token, String subject) {
        return IdamClaims.userFromClaims(claims(token, subject));
    }

    /**
     * The userinfo claims for a validated token, checked against its subject just as {@link #currentUser} checks
     * them, but not yet reduced.
     *
     * <p>Only for a caller that needs a role {@link IdamClaims#userFromClaims} would drop -- today that is the CCD
     * persistence chain, which maps IDAM roles onto the case definition's access profiles. Any other reader
     * should use {@link #currentUser}.
     *
     * @throws UnauthorizedError for anything the caller could fix by presenting a different token
     */
    public Map<String, Object> claims(String token, String subject) {
        Map<String, Object> claims = fetch(token);

        if (!Objects.equals(claims.get("sub"), subject)) {
            throw new UnauthorizedError("IDAM userinfo describes a different subject to the access token");
        }

        return claims;
    }

    private Map<String, Object> fetch(String token) {
        Map<String, Object> claims;
        try {
            claims = restClient.get()
                .uri(discovery.metadata().userInfoEndpoint())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .retrieve()
                // A token that verified but that IDAM will not resolve has been revoked, or belongs to a
                // user who no longer exists. Caller-fixable, so 401 -- anything else is IDAM's problem
                // rather than theirs, and is left to become a 500 below.
                .onStatus(status -> status.value() == 401 || status.value() == 403, (request, response) -> {
                    throw new UnauthorizedError("IDAM rejected the access token");
                })
                // Except that a deleted user is not a 401. Measured against AAT on 2026-10-07: a token that
                // still verifies, for a user testing-support has since deleted, gets 400 `unauthorized_client`
                // ("Not able to read user information."), and every AAT persona is such a user within three
                // hours. Narrowed to that error code because any other 400 may be this request's fault.
                .onStatus(status -> status.value() == 400, (request, response) -> {
                    if (UNKNOWN_USER.equals(errorCode(response.getBody().readAllBytes()))) {
                        throw new UnauthorizedError("IDAM no longer recognises the access token's user");
                    }
                    throw new IllegalStateException("IDAM userinfo answered 400");
                })
                .body(CLAIMS);
        } catch (RestClientException e) {
            throw new IllegalStateException("IDAM userinfo failed", e);
        }

        if (claims == null) {
            throw new IllegalStateException("IDAM userinfo returned no claims");
        }
        return claims;
    }

    private static String errorCode(byte[] body) {
        try {
            return JSON.readTree(body).path("error").asText(null);
        } catch (IOException e) {
            return null;
        }
    }
}
