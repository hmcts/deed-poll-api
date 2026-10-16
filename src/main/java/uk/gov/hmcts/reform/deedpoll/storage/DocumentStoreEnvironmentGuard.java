package uk.gov.hmcts.reform.deedpoll.storage;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;

import java.util.Set;

/** Prevents ephemeral filesystem storage outside explicitly permitted non-production environments. */
public class DocumentStoreEnvironmentGuard {

    private static final Set<String> FILESYSTEM_ENVIRONMENTS = Set.of("aat", "preview", "demo", "ithc", "perftest");

    public DocumentStoreEnvironmentGuard(DocumentStoreProperties properties,
                                         @Value("${deedpoll.environment-name:}") String environmentName,
                                         Environment environment) {
        if (properties.isCdam()) {
            return;
        }
        // Flux supplies CLUSTER_NAME. Even a present-but-empty value must not masquerade as a local run.
        boolean local = environmentName.isEmpty() && !environment.containsProperty("CLUSTER_NAME");
        if (!local && !FILESYSTEM_ENVIRONMENTS.contains(environmentName)) {
            throw new IllegalStateException("Filesystem document storage requires an allowed non-production "
                + "ENVIRONMENT_NAME, or an empty name with CLUSTER_NAME unset for local use");
        }
    }
}
