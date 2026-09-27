package uk.gov.hmcts.reform.deedpoll.auth;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;

/**
 * A stub {@code service-auth-provider}: answers {@code GET /details} with the service a token belongs to.
 *
 * <p>That endpoint is the only one an inbound check calls. The S2S library's {@code ServiceAuthFilter} asks it who
 * the {@code ServiceAuthorization} token belongs to, then compares the answer with its allowlist. So a test controls
 * the whole of inbound S2S by deciding which token names which service. A token nobody stubbed gets the 401 that
 * the real provider gives an unverifiable one. There are no S2S credentials in this plan, and no test talks to a
 * real provider.
 */
public final class StubS2s {

    private final WireMockServer server = new WireMockServer(WireMockConfiguration.options().dynamicPort());

    public void start() {
        server.start();
        reset();
    }

    public void stop() {
        server.stop();
    }

    /** Forgets every token: from here, each one is unverifiable. */
    public void reset() {
        server.resetAll();
        server.stubFor(get(urlPathEqualTo("/details")).atPriority(10).willReturn(aResponse().withStatus(401)));
    }

    /**
     * Makes {@code token} a valid S2S token for {@code serviceName}. The library sends it as a bearer token, and
     * adds the prefix itself when the caller left it off.
     */
    public void token(String token, String serviceName) {
        server.stubFor(get(urlPathEqualTo("/details"))
            .atPriority(1)
            .withHeader("Authorization", equalTo("Bearer " + token))
            .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "text/plain").withBody(serviceName)));
    }

    /**
     * Answers {@code POST /lease} -- the outbound half, which this service calls to mint its own token -- with
     * {@code jwt}. The library decodes the lease to read its expiry, so it has to be a JWT.
     */
    public void lease(String jwt) {
        server.stubFor(post(urlPathEqualTo("/lease"))
            .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "text/plain").withBody(jwt)));
    }

    public String baseUrl() {
        return server.baseUrl();
    }

    public WireMockServer server() {
        return server;
    }
}
