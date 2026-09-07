package uk.gov.hmcts.reform.deedpoll.auth;

import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.env.Environment;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Map;

/**
 * Where IDAM's keys and userinfo endpoint are, discovered from the issuer.
 *
 * <p>Ported from {@code discover()} in {@code apps/api/src/auth/idam.ts}. <strong>Endpoints are
 * discovered, not configured</strong>, for the reason the reference gives: this was written without access
 * to a real CFT IDAM, so any hardcoded path would have been a guess.
 *
 * <p>Cached for the process's lifetime, because discovery would otherwise be a network round trip on every
 * request -- putting IDAM's availability in front of every call rather than just the token check. Not
 * cached across restarts, so a pod picks up endpoint changes when it cycles. <strong>A failed discovery is
 * not cached</strong>: otherwise one blip -- IDAM restarting, a DNS hiccup -- would fail every subsequent
 * request until the pod cycled, which is the reference's own hard-won note. Key <em>rotation</em> is a
 * different and correctly shorter lifetime, handled inside the JWKS source {@link IdamJwtDecoder} builds.
 */
public class IdamDiscovery {

    private static final ParameterizedTypeReference<Map<String, Object>> JSON_OBJECT =
        new ParameterizedTypeReference<>() {
        };

    private final IdamProperties properties;
    private final Environment environment;
    private final RestClient restClient;

    private volatile IdamMetadata cached;

    public IdamDiscovery(IdamProperties properties, Environment environment) {
        this(properties, environment, IdamHttp.restClient());
    }

    public IdamDiscovery(IdamProperties properties, Environment environment, RestClient restClient) {
        this.properties = properties;
        this.environment = environment;
        this.restClient = restClient;
    }

    /** The issuer's metadata, discovered once and then remembered. */
    public IdamMetadata metadata() {
        IdamMetadata existing = cached;
        if (existing != null) {
            return existing;
        }

        synchronized (this) {
            if (cached == null) {
                cached = discover();
            }
            return cached;
        }
    }

    private IdamMetadata discover() {
        String issuer = requireIssuer();
        Map<String, Object> document = fetch(issuer + "/.well-known/openid-configuration");

        String advertised = trimTrailingSlash(string(document, "issuer"));
        String jwksUri = string(document, "jwks_uri");
        String userInfoEndpoint = string(document, "userinfo_endpoint");

        if (advertised.isEmpty() || jwksUri.isEmpty() || userInfoEndpoint.isEmpty()) {
            throw new IllegalStateException(
                "IDAM discovery at " + issuer + " is missing issuer, jwks_uri or userinfo_endpoint");
        }

        // OIDC Discovery 1.0 section 4.3 requires these to match. They will not if the issuer is behind a
        // proxy that rewrites its base URL, and in that case every token's `iss` would also disagree with
        // the configured value -- so failing here, with both values named, beats failing per request.
        if (!advertised.equals(issuer)) {
            throw new IllegalStateException(
                "IDAM discovery at " + issuer + " advertises a different issuer, " + advertised);
        }

        return new IdamMetadata(advertised, jwksUri, userInfoEndpoint);
    }

    /**
     * The configured issuer, refusing an unset one and a cleartext one.
     *
     * <p>Cleartext is refused here rather than left to the HTTP client, which has no such scruple: without
     * it the service would silently take its keys, and the roles it authorises against, over a connection
     * anything on the path can rewrite. See {@link PlainHttp} for the polarity of the exemption.
     */
    private String requireIssuer() {
        String issuerUrl = properties.issuerUrl();
        if (issuerUrl.isEmpty()) {
            throw new IllegalStateException(
                "No IDAM issuer is configured (IDAM_API_ISSUER_URL), so no access token can be accepted");
        }

        URI uri;
        try {
            uri = new URI(issuerUrl);
        } catch (URISyntaxException e) {
            throw new IllegalStateException("The configured IDAM issuer is not a URL: " + issuerUrl, e);
        }

        if (!"https".equalsIgnoreCase(uri.getScheme()) && !PlainHttp.plainHttpPermitted(environment)) {
            throw new IllegalStateException("Refusing to use a cleartext IDAM issuer (" + issuerUrl
                                                + ") outside the " + PlainHttp.PLAIN_HTTP_PROFILES + " profiles");
        }

        return trimTrailingSlash(issuerUrl);
    }

    private Map<String, Object> fetch(String url) {
        Map<String, Object> document;
        try {
            document = restClient.get().uri(url).retrieve().body(JSON_OBJECT);
        } catch (RestClientException e) {
            throw new IllegalStateException("IDAM discovery failed: " + url, e);
        }

        if (document == null) {
            throw new IllegalStateException("IDAM discovery returned no document: " + url);
        }
        return document;
    }

    private static String string(Map<String, Object> document, String key) {
        Object value = document.get(key);
        return value instanceof String text ? text.trim() : "";
    }

    private static String trimTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    /**
     * The three things this service reads out of the issuer's metadata.
     *
     * @param issuer           the issuer as the document advertises it, which every token's {@code iss} is
     *                         checked against
     * @param jwksUri          where the signing keys live
     * @param userInfoEndpoint where the caller's roles and name are read from -- see
     *                         {@link IdamUserInfoClient} for why not from the token
     */
    public record IdamMetadata(String issuer, String jwksUri, String userInfoEndpoint) {
    }
}
