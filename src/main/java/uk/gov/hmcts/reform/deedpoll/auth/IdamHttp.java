package uk.gov.hmcts.reform.deedpoll.auth;

import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

/**
 * The HTTP clients every outbound IDAM call uses, with the timeouts that make them safe to make per request.
 *
 * <p><strong>Why this exists at all.</strong> {@code RestClient.create()} and a bare {@code RestTemplate} apply
 * <em>no</em> read timeout, and they bypass the Boot-auto-configured builder -- so
 * {@code spring.http.client.read-timeout} cannot be used to give them one either. That is a per-request hazard
 * rather than a start-up one: {@link IdamUserInfoClient} is deliberately uncached, so every authenticated request
 * makes a userinfo call, and an IDAM that accepts the connection and then stops answering would hold a Tomcat
 * worker for each one until the OS gave up. The pool fills, the actuator shares the connector, and the probes stop
 * answering too -- so Kubernetes restarts pods that immediately re-block, turning one slow dependency into a
 * self-sustaining outage. Failing fast instead becomes a {@code RestClientException}, which
 * {@link IdamUserInfoClient} and {@link IdamDiscovery} already turn into a 500: IDAM's fault, reported as such.
 *
 * <p>Constants rather than properties. A timeout an environment could raise to "no timeout" is a timeout that can
 * be configured back into the outage above, and there is no legitimate IDAM response these do not accommodate.
 */
public final class IdamHttp {

    /** How long to wait for IDAM to accept a connection. */
    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);

    /**
     * How long to wait for IDAM to answer.
     *
     * <p>Generous against a real issuer and far below the point at which a caseworker's request is worth
     * holding: the browser has given up long before the worker would.
     */
    static final Duration READ_TIMEOUT = Duration.ofSeconds(10);

    private IdamHttp() {
    }

    /**
     * A {@link RestClient} that will not wait forever -- discovery and userinfo both use one.
     */
    public static RestClient restClient() {
        return RestClient.builder().requestFactory(requestFactory()).build();
    }

    /**
     * The same timeouts for the JWKS fetch, which Nimbus makes through {@link org.springframework.web.client
     * .RestOperations} rather than a {@code RestClient}.
     */
    public static RestTemplate restTemplate() {
        return new RestTemplate(requestFactory());
    }

    private static ClientHttpRequestFactory requestFactory() {
        return ClientHttpRequestFactoryBuilder.detect()
            .build(ClientHttpRequestFactorySettings.defaults().withTimeouts(CONNECT_TIMEOUT, READ_TIMEOUT));
    }
}
