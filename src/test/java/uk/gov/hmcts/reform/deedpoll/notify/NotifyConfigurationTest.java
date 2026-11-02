package uk.gov.hmcts.reform.deedpoll.notify;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import uk.gov.hmcts.reform.deedpoll.domain.masters.MasterEmailKind;
import uk.gov.service.notify.NotificationClient;

import java.util.Arrays;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * One Notify client, built from the two configuration values, and startable in an environment that has none.
 *
 * <p>Container-free deliberately -- a {@code @SpringBootTest} here would need Postgres and so would be excluded
 * in CI, and none of what this asserts is about the database. {@link ConfigDataApplicationContextInitializer}
 * loads the real {@code application.yaml}, so the defaults asserted below are the committed ones rather than
 * values restated in a test.
 */
class NotifyConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withUserConfiguration(NotifyConfiguration.class);

    @Test
    @DisplayName("builds one client, with the key as the key and the URL as the URL")
    void buildsOneClientFromTheTwoProperties() {
        contextRunner
            .withPropertyValues(
                "notify.api-url=https://notify.example",
                "notify.api-key=deed-poll-api-8e0a5a1b-6ba2-4b0a-9a24-9f2a19a3aa11"
                    + "-4d0f1c73-8c1b-4d9b-9f54-0b4a1f77bb22")
            .run(context -> {
                assertThat(context).hasSingleBean(NotificationClient.class);
                NotificationClient client = context.getBean(NotificationClient.class);

                // The constructor takes the key first and the base URL second, which is the opposite order to
                // how the two properties read -- exactly the kind of thing that silently sends a URL as a
                // credential and then fails with an authentication error nobody can explain.
                assertThat(client.getBaseUrl()).isEqualTo("https://notify.example");
                assertThat(client.getServiceId()).isEqualTo("8e0a5a1b-6ba2-4b0a-9a24-9f2a19a3aa11");
                assertThat(client.getApiKey()).isEqualTo("4d0f1c73-8c1b-4d9b-9f54-0b4a1f77bb22");
            });
    }

    @Test
    @DisplayName("starts with no API key, so a feature nobody uses yet cannot stop the service starting")
    void startsWithNoApiKey() {
        // No Notify service exists for deed poll, so this is every environment today. The client validates
        // nothing and calls nothing when constructed; the failure arrives at the first send, as a 403, which
        // NotificationFailure calls permanent.
        contextRunner
            .withPropertyValues("notify.api-url=https://notify.example", "notify.api-key=")
            .run(context -> {
                assertThat(context).hasNotFailed();
                assertThatCode(() -> context.getBean(NotificationClient.class)).doesNotThrowAnyException();
            });
    }

    @Test
    @DisplayName("the committed defaults are the real Notify URL, no key, and the placeholder template")
    void committedDefaultsAreUnprovisioned() {
        contextRunner
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .run(context -> {
                NotifyProperties properties = context.getBean(NotifyProperties.class);

                assertThat(properties.apiUrl()).isEqualTo("https://api.notifications.service.gov.uk");
                // A committed placeholder that happened to work somewhere is the failure worth designing out,
                // the same reason idam.s2s-auth.totp-secret has no default.
                assertThat(properties.apiKey()).isEmpty();
                // Every template this service sends is listed, and every one of them is the placeholder: the map
                // is what an environment overrides, so a template missing from it is an environment variable
                // nobody reads. One grep for the literal lists both.
                var expected = Arrays.stream(EmailTemplate.values()).collect(Collectors.toMap(
                    EmailTemplate::key, template -> "DEED_POLL_PLACEHOLDER"));
                // The Master emails (plan D3) are not EmailTemplate members, because they are not a case's. The
                // retired digest (plan F1) has no template at all.
                for (MasterEmailKind kind : MasterEmailKind.values()) {
                    if (!kind.retired()) {
                        expected.put(kind.templateKey(), "DEED_POLL_PLACEHOLDER");
                    }
                }
                for (String key : java.util.List.of("gazette-adult", "gazette-minor", "gazette-mixed")) {
                    expected.put(key, "DEED_POLL_PLACEHOLDER");
                }
                assertThat(properties.templates()).containsExactlyInAnyOrderEntriesOf(expected);
                assertThat(context.getEnvironment().getProperty("deedpoll.gazette.enabled", Boolean.class)).isFalse();
                // And no reply-to, which is the other half of what notify-0 supplies. So the email whose words
                // ask the applicant to reply is unprovisioned twice over, and says so in the Email tab.
                assertThat(properties.emailReplyToId()).isEmpty();
                assertThat(Arrays.stream(EmailTemplate.values()))
                    .noneMatch(properties::isProvisioned);
            });
    }
}
