package uk.gov.hmcts.reform.deedpoll.auth;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;

/**
 * A stub IDAM: discovery, a key set, userinfo, and tokens signed with the key it publishes.
 *
 * <p>There are no IDAM credentials in this plan and there will not be, so every test that needs an issuer gets
 * this one. It is also the only way to assert the interesting cases at all -- an expired token, one minted for
 * another service, an ID token presented as an access token -- since a real issuer will not produce them to
 * order.
 *
 * <p>Signing keys are generated per instance rather than checked in, so nothing here is a key that also exists
 * somewhere real.
 */
public final class StubIdam {

    /** The audience the stub mints tokens for, and the one a service under test should be configured with. */
    public static final String AUDIENCE = "deed_poll_api";

    /** The subject the stub's tokens and userinfo responses agree on. */
    public static final String SUBJECT = "idam-user-123";

    private final WireMockServer server =
        new WireMockServer(WireMockConfiguration.options().dynamicPort());

    private final RSAKey signingKey;
    private final RSAKey otherKey;

    public StubIdam() {
        try {
            signingKey = new RSAKeyGenerator(2048).keyID("idam-test").algorithm(JWSAlgorithm.RS256).generate();
            // A second key with the same `kid`, so a token signed by it reaches the signature check rather
            // than being rejected for naming a key the set does not contain.
            otherKey = new RSAKeyGenerator(2048).keyID("idam-test").algorithm(JWSAlgorithm.RS256).generate();
        } catch (JOSEException e) {
            throw new IllegalStateException("could not generate a stub IDAM signing key", e);
        }
    }

    public void start() {
        server.start();
        stubDefaults();
    }

    public void stop() {
        server.stop();
    }

    /** Discovery, the key set and a userinfo response for a caseworker who is not an admin. */
    public void stubDefaults() {
        server.resetAll();
        stubDiscovery(issuerUrl());
        server.stubFor(get(urlPathEqualTo("/o/jwks")).willReturn(okJson(jwks())));
        stubUserInfo(SUBJECT, "A Caseworker", List.of("caseworker-civil"));
    }

    public void stubDiscovery(String advertisedIssuer) {
        server.stubFor(get(urlPathEqualTo("/o/.well-known/openid-configuration")).willReturn(okJson("""
            {
              "issuer": "%s",
              "jwks_uri": "%s/o/jwks",
              "userinfo_endpoint": "%s/o/userinfo"
            }
            """.formatted(advertisedIssuer, server.baseUrl(), server.baseUrl()))));
    }

    /**
     * Whatever should come back from {@code /o/userinfo}, verbatim.
     */
    public void stubUserInfoBody(String json) {
        server.stubFor(get(urlPathEqualTo("/o/userinfo")).willReturn(okJson(json)));
    }

    /**
     * Whatever IDAM should answer {@code /o/userinfo} with, when the answer is a refusal or an outage.
     */
    public void stubUserInfoStatus(int status) {
        server.stubFor(get(urlPathEqualTo("/o/userinfo")).willReturn(aResponse().withStatus(status)));
    }

    /**
     * What AAT's IDAM answered on 2026-10-07 for a still-valid token whose testing-support user had been deleted:
     * a 400, not a 401. Copied from the measured response, body and all.
     */
    public void stubUserInfoForDeletedUser() {
        server.stubFor(get(urlPathEqualTo("/o/userinfo")).willReturn(aResponse()
            .withStatus(400)
            .withHeader("Content-Type", "application/json; charset=utf-8")
            .withBody("{\"error_description\":\"Not able to read user information.\",\"error\":\"unauthorized_client\"}")));
    }

    public void stubUserInfo(String subject, String name, List<String> roles) {
        String quotedRoles = roles.stream().map(role -> '"' + role + '"').collect(Collectors.joining(","));
        stubUserInfoBody("""
            {
              "sub": "%s",
              "name": "%s",
              "roles": [%s]
            }
            """.formatted(subject, name, quotedRoles));
    }

    /**
     * Answers the password grant on {@code POST /o/token} with {@link #accessToken()}, as IDAM answers a system
     * user signing in. Call it after {@link #stubDefaults()}, which forgets it.
     */
    public void stubPasswordGrant() {
        server.stubFor(post(urlPathEqualTo("/o/token")).willReturn(okJson("""
            {"access_token": "%s", "token_type": "Bearer", "expires_in": 300}
            """.formatted(accessToken()))));
    }

    /** The stub's own base URL, for stubbing a response that has to name it. */
    public WireMockServer server() {
        return server;
    }

    public String baseUrl() {
        return server.baseUrl();
    }

    /** The issuer a service under test should be configured with. */
    public String issuerUrl() {
        return server.baseUrl() + "/o";
    }

    public String jwks() {
        return new JWKSet(signingKey.toPublicJWK()).toString();
    }

    /**
     * An ordinary, valid access token for {@link #SUBJECT}.
     */
    public String accessToken() {
        return accessToken(claims -> {
        });
    }

    /** An access token with something about it changed -- an audience, an expiry, a missing claim. */
    public String accessToken(Consumer<JWTClaimsSet.Builder> customise) {
        return sign(signingKey, customise);
    }

    /** A token that is well-formed and correctly addressed, but signed by a key IDAM does not publish. */
    public String tokenSignedByAnotherKey() {
        return sign(otherKey, claims -> {
        });
    }

    private String sign(RSAKey key, Consumer<JWTClaimsSet.Builder> customise) {
        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
            .issuer(issuerUrl())
            .subject(SUBJECT)
            .audience(AUDIENCE)
            .claim("tokenName", "access_token")
            .issueTime(Date.from(Instant.now()))
            .expirationTime(Date.from(Instant.now().plusSeconds(300)));

        customise.accept(claims);

        SignedJWT token = new SignedJWT(
            new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(), claims.build());
        try {
            token.sign(new RSASSASigner(key));
        } catch (JOSEException e) {
            throw new IllegalStateException("could not sign a stub IDAM token", e);
        }
        return token.serialize();
    }
}
