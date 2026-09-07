package uk.gov.hmcts.reform.deedpoll.db;

import com.github.dockerjava.api.model.ContainerNetwork;
import org.testcontainers.containers.JdbcDatabaseContainer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Works out a JDBC URL that actually reaches a Testcontainers database, and waits until it
 * does.
 *
 * <p>Two problems make this more than {@code container.getJdbcUrl()}.
 *
 * <p><strong>Published ports are not always forwarded.</strong> This build runs inside a
 * container that shares the host's Docker daemon, so the containers it starts are siblings
 * rather than children. In that topology the published port is the fragile route -- on
 * Docker Desktop, daemon-allocated ephemeral publications are not forwarded at all, and
 * {@code getJdbcUrl()} then points at a port nothing is listening on. The container's own
 * address on the Docker bridge network is reachable directly, needs no port forwarding, and
 * is tried first for that reason. The published port stays as a fallback, because a build
 * running outside Docker cannot route to the bridge network.
 *
 * <p><strong>"Started" is not "connectable".</strong> Testcontainers' default wait strategy
 * for Postgres watches the container log, which the container emits before the published
 * port is forwarding. So each candidate is polled until the deadline rather than tried once.
 */
public final class ContainerDatabase {

    private static final Duration TIMEOUT = Duration.ofSeconds(60);
    private static final Duration RETRY_INTERVAL = Duration.ofMillis(250);
    private static final int PROBE_TIMEOUT_MILLIS = 1000;

    private ContainerDatabase() {
    }

    /** An endpoint the database is listening on: a host and the port to reach it on. */
    private record Endpoint(String description, String host, int port) {
    }

    /**
     * Returns a JDBC URL for the container, once something answers on it.
     *
     * @throws IllegalStateException if no route to the database works within the timeout,
     *                               naming every endpoint that was tried
     */
    public static String awaitJdbcUrl(JdbcDatabaseContainer<?> container) {
        int internalPort = container.getExposedPorts().get(0);
        List<Endpoint> candidates = new ArrayList<>();

        bridgeAddress(container).ifPresent(address ->
            candidates.add(new Endpoint("Docker bridge network", address, internalPort)));
        candidates.add(new Endpoint("published port", container.getHost(), container.getMappedPort(internalPort)));

        Endpoint reachable = awaitAnyReachable(candidates);
        return "jdbc:postgresql://%s:%d/%s".formatted(
            reachable.host(), reachable.port(), container.getDatabaseName());
    }

    private static Endpoint awaitAnyReachable(List<Endpoint> candidates) {
        long deadline = System.nanoTime() + TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            for (Endpoint candidate : candidates) {
                if (isReachable(candidate)) {
                    return candidate;
                }
            }
            sleep();
        }
        throw new IllegalStateException(
            "No route to the test database within %ds. Tried: %s".formatted(TIMEOUT.toSeconds(), candidates));
    }

    private static boolean isReachable(Endpoint endpoint) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(endpoint.host(), endpoint.port()), PROBE_TIMEOUT_MILLIS);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static Optional<String> bridgeAddress(JdbcDatabaseContainer<?> container) {
        return container.getContainerInfo().getNetworkSettings().getNetworks().values().stream()
            .map(ContainerNetwork::getIpAddress)
            .filter(address -> address != null && !address.isBlank())
            .findFirst();
    }

    private static void sleep() {
        try {
            Thread.sleep(RETRY_INTERVAL.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted waiting for the test database", e);
        }
    }
}
