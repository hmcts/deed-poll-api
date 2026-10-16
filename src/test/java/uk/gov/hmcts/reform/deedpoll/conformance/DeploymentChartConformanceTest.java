package uk.gov.hmcts.reform.deedpoll.conformance;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Asserts the Helm chart and the application configuration still agree about where secrets arrive.
 *
 * <p>Every check here exists because the corresponding mistake is <strong>silent in a deployed
 * environment and invisible locally</strong>. {@code spring.config.import} is prefixed
 * {@code optional:}, deliberately, so that a local run with nothing mounted starts -- which also
 * means a pod whose chart mounts the vault at a path the import does not name starts perfectly
 * happily, falls back to every {@code application.yaml} default, and reports a database it cannot
 * reach. That surfaces as a 503 from readiness looking like an infrastructure fault, several layers
 * away from the one-word chart typo that caused it.
 *
 * <p>The sibling {@code hmcts/deed-poll-web} pins the same agreement in
 * {@code src/deployment-chart.test.ts}, for the same reason and after the same surprise.
 * {@code docs/plans/jenkins-pipeline-cutover.md} is the change these guard.
 */
class DeploymentChartConformanceTest {

    private static final Path CHART_DIR = Path.of("charts/deed-poll-api");
    private static final Path APPLICATION_YAML = Path.of("src/main/resources/application.yaml");
    private static final Path AGENT_CONFIG = Path.of("lib/applicationinsights.json");
    private static final Path JENKINSFILE = Path.of("Jenkinsfile_CNP");

    /**
     * {@code spring.config.import: "optional:configtree:/mnt/secrets/<segment>/"}.
     */
    private static final Pattern CONFIGTREE = Pattern.compile("configtree:/mnt/secrets/([^/\"\\s]+)/");

    /**
     * {@code "${file:/mnt/secrets/<segment>/app-insights-connection-string}"}.
     */
    private static final Pattern AGENT_SECRET_FILE =
        Pattern.compile("\\$\\{file:/mnt/secrets/([^/}]+)/([^}]+)}");

    @Test
    @DisplayName("the chart is named {product}-{component}, as helmInstall resolves it from Jenkinsfile_CNP")
    void chartNameMatchesPipelineIdentity() throws IOException {
        String jenkinsfile = read(JENKINSFILE);

        String product = groovyString(jenkinsfile, "product");
        String component = groovyString(jenkinsfile, "component");

        assertThat(CHART_DIR.getFileName())
            .as("helmInstall looks for charts/${product}-${component}, so a rename here is a failed deploy")
            .hasToString(product + "-" + component);
        assertThat(chart().get("name"))
            .as("Chart.yaml name must match its directory")
            .isEqualTo(product + "-" + component);
    }

    @Test
    @DisplayName("the chart mounts the vault at the path spring.config.import reads")
    void vaultMountPathMatchesConfigtreeImport() throws IOException {
        String importedSegment = firstGroup(CONFIGTREE, read(APPLICATION_YAML),
            "application.yaml declares no configtree import of /mnt/secrets");

        assertThat(keyVaults().keySet())
            .as("the java chart mounts each keyVaults key at /mnt/secrets/<key>/, and the configtree "
                + "import names exactly one path, so a second vault would be mounted and never read")
            .containsExactly(importedSegment);
    }

    @Test
    @DisplayName("the Application Insights agent reads the same mount, and a secret the chart supplies")
    void agentConfigMatchesChart() throws IOException {
        Matcher matcher = AGENT_SECRET_FILE.matcher(read(AGENT_CONFIG));
        assertThat(matcher.find())
            .as("lib/applicationinsights.json should read its connection string from the vault mount")
            .isTrue();
        String segment = matcher.group(1);
        String secretName = matcher.group(2);

        assertThat(keyVaults()).as("the agent reads /mnt/secrets/%s/, which no keyVaults key mounts", segment)
            .containsKey(segment);
        assertThat(secretNames(segment))
            .as("the agent reads a file the chart does not fetch, so telemetry would go nowhere")
            .contains(secretName);
        assertThat(aliasOf(segment, secretName))
            .as("%s must stay un-aliased: an alias makes it an environment variable instead of the file "
                + "the agent and the configtree import both read", secretName)
            .isNull();
    }

    @Test
    @DisplayName("every secret alias the chart sets is a placeholder application.yaml actually reads")
    void everySecretAliasIsRead() throws IOException {
        String applicationYaml = read(APPLICATION_YAML);
        List<String> unread = new ArrayList<>();

        keyVaults().forEach((vault, secrets) -> secrets.forEach(secret -> {
            String alias = (String) secret.get("alias");
            if (alias != null && !applicationYaml.contains("${" + alias)) {
                unread.add(alias);
            }
        }));

        assertThat(unread)
            .as("an alias nothing reads is either a typo or a leftover -- the property it was meant to "
                + "supply silently keeps its default")
            .isEmpty();
    }

    @Test
    @DisplayName("every plain environment value the chart sets is a placeholder application.yaml reads")
    void everyEnvironmentValueIsRead() throws IOException {
        String applicationYaml = read(APPLICATION_YAML);

        assertThat(environment().keySet())
            .isNotEmpty()
            .allSatisfy(name -> assertThat(applicationYaml)
                .as("the chart sets %s, which application.yaml never reads", name)
                .contains("${" + name));
    }

    @Test
    @DisplayName("no values.template.yaml: it would overwrite the chart defaults rather than override them")
    void overrideTemplatesAreEnvironmentScoped() {
        assertThat(CHART_DIR.resolve("values.template.yaml"))
            .as("the pipeline renders values.template.yaml over values.yaml, discarding every default in "
                + "it -- including keyVaults. Name it values.${environment}.template.yaml, which renders "
                + "to its own file and is appended to helm's -f list instead")
            .doesNotExist();
        assertThat(CHART_DIR.resolve("values.aat.template.yaml"))
            .as("aat is where this service first deploys; without an override template the pipeline "
                + "cannot set the image or the ingress host")
            .exists();
    }

    @Test
    void storageGuardReceivesTheDeploymentEnvironment() throws IOException {
        assertThat(environment()).containsEntry("ENVIRONMENT_NAME", "{{ .Values.global.environment }}");
        assertThat(read(APPLICATION_YAML)).contains("environment-name: ${ENVIRONMENT_NAME:}");
    }

    @Test
    @SuppressWarnings("unchecked")
    void previewNamesItsEnvironmentExplicitly() throws IOException {
        Map<String, Object> values = new Yaml().loadAs(
            read(CHART_DIR.resolve("values.preview.template.yaml")), Map.class);
        Map<String, Object> java = (Map<String, Object>) values.get("java");
        Map<String, Object> environment = (Map<String, Object>) java.get("environment");
        assertThat(environment).containsEntry("ENVIRONMENT_NAME", "preview");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> chart() throws IOException {
        return new Yaml().loadAs(read(CHART_DIR.resolve("Chart.yaml")), Map.class);
    }

    /**
     * The chart's {@code java.keyVaults} block: vault name to its list of secrets.
     */
    @SuppressWarnings("unchecked")
    private static Map<String, List<Map<String, Object>>> keyVaults() throws IOException {
        Map<String, Object> java = javaValues();
        Map<String, Map<String, Object>> vaults =
            (Map<String, Map<String, Object>>) java.get("keyVaults");
        assertThat(vaults).as("charts/deed-poll-api/values.yaml declares no keyVaults block").isNotNull();

        Map<String, List<Map<String, Object>>> secretsByVault = new LinkedHashMap<>();
        vaults.forEach((vault, body) ->
            secretsByVault.put(vault, (List<Map<String, Object>>) body.get("secrets")));
        return secretsByVault;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> environment() throws IOException {
        Map<String, Object> environment = (Map<String, Object>) javaValues().get("environment");
        return environment == null ? Map.of() : environment;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> javaValues() throws IOException {
        Map<String, Object> values = new Yaml().loadAs(read(CHART_DIR.resolve("values.yaml")), Map.class);
        Map<String, Object> java = (Map<String, Object>) values.get("java");
        assertThat(java).as("values.yaml must key its overrides on the java subchart").isNotNull();
        return java;
    }

    private static List<String> secretNames(String vault) throws IOException {
        return keyVaults().get(vault).stream().map(secret -> (String) secret.get("name")).toList();
    }

    private static String aliasOf(String vault, String secretName) throws IOException {
        return keyVaults().get(vault).stream()
            .filter(secret -> secretName.equals(secret.get("name")))
            .findFirst()
            .map(secret -> (String) secret.get("alias"))
            .orElse(null);
    }

    /**
     * Reads {@code def <name> = "value"} out of the Jenkinsfile.
     */
    private static String groovyString(String jenkinsfile, String name) {
        return firstGroup(Pattern.compile("def\\s+" + name + "\\s*=\\s*\"([^\"]+)\""), jenkinsfile,
            "Jenkinsfile_CNP declares no " + name);
    }

    private static String firstGroup(Pattern pattern, String haystack, String describeFailure) {
        Matcher matcher = pattern.matcher(haystack);
        assertThat(matcher.find()).as(describeFailure).isTrue();
        return matcher.group(1);
    }

    private static String read(Path path) throws IOException {
        assertThat(path)
            .as("expected to run from the project directory, where %s is; working directory is %s",
                path, Path.of("").toAbsolutePath())
            .exists();
        return Files.readString(path);
    }
}
