package uk.gov.hmcts.reform.deedpoll.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentStoreEnvironmentGuardTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withInitializer(context -> {
            // Exercise application.yaml's real ENVIRONMENT_NAME binding without inheriting the test host's values.
            context.getEnvironment().getPropertySources()
                .remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
            context.getEnvironment().getPropertySources()
                .remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        })
        .withInitializer(new ConfigDataApplicationContextInitializer())
        .withUserConfiguration(DocumentStoreConfiguration.class);

    @ParameterizedTest
    @ValueSource(strings = {"aat", "preview", "demo", "ithc", "perftest"})
    void filesystemStartsOnlyInNamedNonProductionEnvironments(String name) {
        runner.withPropertyValues("DOCUMENT_STORE=filesystem", "ENVIRONMENT_NAME=" + name,
                "CLUSTER_NAME=test-cluster")
            .run(context -> assertThat(context).hasNotFailed());
    }

    @ParameterizedTest
    @ValueSource(strings = {"prod", "production", "PROD", "staging", "development", "test", "AAT", "aat ", " "})
    void filesystemRefusesUnknownNamesEvenWithoutACluster(String name) {
        runner.withInitializer(context -> context.getEnvironment().getPropertySources()
                .addFirst(new MapPropertySource("deploymentEnvironment", Map.of("ENVIRONMENT_NAME", name))))
            .run(context -> assertThat(context).hasFailed()
                .getFailure().hasRootCauseInstanceOf(IllegalStateException.class)
                .hasRootCauseMessage("Filesystem document storage requires an allowed non-production "
                    + "ENVIRONMENT_NAME, or an empty name with CLUSTER_NAME unset for local use"));
    }

    @Test
    void missingEnvironmentNameIsRefusedInACluster() {
        runner.withPropertyValues("CLUSTER_NAME=test-cluster")
            .run(context -> assertThat(context).hasFailed());
    }

    @ParameterizedTest
    @ValueSource(strings = {"test-cluster", "", " "})
    void emptyEnvironmentNameIsRefusedWheneverClusterNameIsPresent(String cluster) {
        runner.withInitializer(context -> context.getEnvironment().getPropertySources()
                .addFirst(new MapPropertySource("deploymentEnvironment",
                    Map.of("ENVIRONMENT_NAME", "", "CLUSTER_NAME", cluster))))
            .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void explicitFilesystemSelectionCannotStartInProduction() {
        runner.withPropertyValues("DOCUMENT_STORE=filesystem", "ENVIRONMENT_NAME=prod", "CLUSTER_NAME=prod-cluster")
            .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void localRunMayOmitBothNames() {
        runner.run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void localRunMayHaveAnEmptyEnvironmentName() {
        runner.withPropertyValues("ENVIRONMENT_NAME=")
            .run(context -> assertThat(context).hasNotFailed());
    }

    @ParameterizedTest
    @ValueSource(strings = {"test", "cftlib", "config-gen"})
    void profilesDoNotBypassTheDeploymentGuard(String profile) {
        runner.withPropertyValues("spring.profiles.active=" + profile, "ENVIRONMENT_NAME=prod")
            .run(context -> assertThat(context).hasFailed());
    }

    @ParameterizedTest
    @ValueSource(strings = {"prod", "production", "staging", ""})
    void cdamIsNotRestrictedByTheFilesystemAllowlist(String name) {
        runner.withPropertyValues("DOCUMENT_STORE=cdam", "ENVIRONMENT_NAME=" + name,
                "CLUSTER_NAME=test-cluster")
            .run(context -> assertThat(context).hasNotFailed());
    }
}
