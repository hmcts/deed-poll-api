package uk.gov.hmcts.reform.deedpoll.auth;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;
import org.springframework.mock.env.MockEnvironment;
import uk.gov.hmcts.reform.deedpoll.auth.IdamDiscovery.IdamMetadata;

import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Ports the assertions {@code discover()} in {@code apps/api/src/auth/idam.ts} makes, which the reference tests
 * through {@code idam.test.ts}.
 *
 * <p>Against a stub issuer, never a real one: there are no IDAM credentials in this plan.
 */
class IdamDiscoveryTest {

    private static final StubIdam IDAM = new StubIdam();

    @BeforeAll
    static void startIdam() {
        IDAM.start();
    }

    @AfterAll
    static void stopIdam() {
        IDAM.stop();
    }

    @BeforeEach
    void resetIdam() {
        IDAM.stubDefaults();
    }

    private static IdamDiscovery discovery(String issuerUrl, String... profiles) {
        MockEnvironment environment = new MockEnvironment();
        if (profiles.length > 0) {
            environment.setProperty("spring.profiles.active", String.join(",", profiles));
        }
        return discovery(issuerUrl, environment);
    }

    private static IdamDiscovery discovery(String issuerUrl, Environment environment) {
        return new IdamDiscovery(new IdamProperties(issuerUrl, List.of(StubIdam.AUDIENCE)), environment);
    }

    @Test
    @DisplayName("reads the key set and userinfo endpoint out of the issuer's metadata")
    void discoversTheEndpoints() {
        IdamMetadata metadata = discovery(IDAM.issuerUrl(), "test").metadata();

        assertThat(metadata.issuer()).isEqualTo(IDAM.issuerUrl());
        assertThat(metadata.jwksUri()).isEqualTo(IDAM.baseUrl() + "/o/jwks");
        assertThat(metadata.userInfoEndpoint()).isEqualTo(IDAM.baseUrl() + "/o/userinfo");
    }

    @Test
    @DisplayName("tolerates a trailing slash on the configured issuer")
    void toleratesATrailingSlash() {
        assertThat(discovery(IDAM.issuerUrl() + "/", "test").metadata().issuer()).isEqualTo(IDAM.issuerUrl());
    }

    @Test
    @DisplayName("discovers once and remembers it, rather than per request")
    void discoversOnce() {
        IdamDiscovery discovery = discovery(IDAM.issuerUrl(), "test");

        discovery.metadata();
        discovery.metadata();

        IDAM.server().verify(1, getRequestedFor(urlPathEqualTo("/o/.well-known/openid-configuration")));
    }

    @Test
    @DisplayName("does not remember a failure, so one blip does not outlive itself")
    void doesNotCacheAFailure() {
        // The reference learned this the expensive way: a rejected promise cached in place of the metadata
        // failed every subsequent request until the pod cycled.
        IDAM.server().stubFor(get(urlPathEqualTo("/o/.well-known/openid-configuration"))
                                  .willReturn(aResponse().withStatus(503)));
        IdamDiscovery discovery = discovery(IDAM.issuerUrl(), "test");
        assertThatThrownBy(discovery::metadata).isInstanceOf(IllegalStateException.class);

        IDAM.stubDefaults();

        assertThat(discovery.metadata().issuer()).isEqualTo(IDAM.issuerUrl());
    }

    @Test
    @DisplayName("refuses an unset issuer rather than accepting tokens from anywhere")
    void refusesAnUnsetIssuer() {
        assertThatThrownBy(() -> discovery("", "test").metadata())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("IDAM_API_ISSUER_URL");
    }

    @Test
    @DisplayName("refuses a cleartext issuer outside the permitted profiles")
    void refusesACleartextIssuer() {
        // The keys every signature is checked against, and the roles authorisation is derived from, would
        // otherwise cross a connection anything on the path can rewrite.
        assertThatThrownBy(() -> discovery(IDAM.issuerUrl(), "aat").metadata())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("cleartext");
    }

    @Test
    @DisplayName("refuses a cleartext issuer when no profile is active at all")
    void refusesACleartextIssuerByDefault() {
        assertThatThrownBy(() -> discovery(IDAM.issuerUrl()).metadata())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("cleartext");
    }

    @Test
    @DisplayName("refuses metadata that is missing an endpoint it needs")
    void refusesIncompleteMetadata() {
        IDAM.server().stubFor(get(urlPathEqualTo("/o/.well-known/openid-configuration"))
                                  .willReturn(okJson("{\"issuer\": \"" + IDAM.issuerUrl() + "\"}")));

        assertThatThrownBy(() -> discovery(IDAM.issuerUrl(), "test").metadata())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("jwks_uri");
    }

    @Test
    @DisplayName("refuses an issuer that advertises a different one, naming both")
    void refusesAMismatchedIssuer() {
        // OIDC Discovery 1.0 section 4.3 requires them to match, and if they do not then every token's `iss`
        // will disagree with the configured value too -- so failing once here beats failing per request.
        IDAM.stubDiscovery("https://idam.example.invalid/o");

        assertThatThrownBy(() -> discovery(IDAM.issuerUrl(), "test").metadata())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("https://idam.example.invalid/o");
    }

    @Test
    @DisplayName("refuses an issuer that is not a URL")
    void refusesANonUrlIssuer() {
        assertThatThrownBy(() -> discovery("not a url", "test").metadata())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("not a URL");
    }

    @Test
    @DisplayName("fails with the issuer named when discovery itself fails")
    void namesTheIssuerWhenDiscoveryFails() {
        IDAM.server().stubFor(get(urlPathEqualTo("/o/.well-known/openid-configuration"))
                                  .willReturn(aResponse().withStatus(500)));

        assertThatThrownBy(() -> discovery(IDAM.issuerUrl(), "test").metadata())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining(IDAM.issuerUrl() + "/.well-known/openid-configuration");
    }
}
