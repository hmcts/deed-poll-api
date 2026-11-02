package uk.gov.hmcts.reform.deedpoll.domain.masters;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import uk.gov.hmcts.reform.deedpoll.notify.NotifyConfiguration;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The Master emails' configuration (plans D3 and F1): the two template keys, the digest's retirement, the admin
 * app's base URL, the start-up rule that joins them, and the job settings. Container-free, as
 * {@code NotifyConfigurationTest} is.
 */
class MasterEmailConfigurationTest {

    private static final Path CONFIGURATION = Path.of("src/main/resources/application.yaml");

    private static final Path CHART_VALUES = Path.of("charts/deed-poll-api/values.yaml");

    private static final String TEMPLATE_ID = "6f1c2b0e-3d4a-4e5f-8a9b-0c1d2e3f4a5b";

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withUserConfiguration(NotifyConfiguration.class, MasterEmailConfiguration.class);

    @ParameterizedTest
    @EnumSource(value = MasterEmailKind.class, names = "DIGEST", mode = EnumSource.Mode.EXCLUDE)
    @DisplayName("each template key is declared under notify.templates with its NOTIFY_TEMPLATE_ variable")
    void templateKeysAreDeclared(MasterEmailKind kind) {
        String variable = "NOTIFY_TEMPLATE_" + kind.templateKey().toUpperCase().replace('-', '_');

        assertThat(configuration())
            .contains("    " + kind.templateKey() + ": ${" + variable + ":DEED_POLL_PLACEHOLDER}");
    }

    @Test
    @DisplayName("the two kinds still sent use the plan's template keys")
    void templateKeys() {
        assertThat(MasterEmailKind.SENT.templateKey()).isEqualTo("master-referral-sent");
        assertThat(MasterEmailKind.REMINDER.templateKey()).isEqualTo("master-referral-reminder");
        assertThat(MasterEmailKind.SENT.retired()).isFalse();
        assertThat(MasterEmailKind.REMINDER.retired()).isFalse();
    }

    @Test
    @DisplayName("the digest is retired: no job, no key, no variable, no interval, and never provisioned (plan F1)")
    void digestIsGone() {
        assertThat(MasterEmailKind.DIGEST.retired()).isTrue();
        for (String job : new String[] {"MasterDigests", "MasterDigestTimer"}) {
            assertThatThrownBy(() -> Class.forName(MasterEmailKind.class.getPackageName() + "." + job))
                .isInstanceOf(ClassNotFoundException.class);
        }
        assertThatThrownBy(MasterEmailKind.DIGEST::templateKey).isInstanceOf(IllegalStateException.class);
        assertThat(configuration()).doesNotContain("master-referral-digest", "MASTER_REFERRAL_DIGEST",
                                                   "digest-interval", "MASTER_DIGEST_INTERVAL");
        contextRunner
            .withPropertyValues("notify.templates.master-referral-digest=" + TEMPLATE_ID,
                                "deedpoll.admin.base-url=https://deed-poll-admin.aat.platform.hmcts.net")
            .run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context.getBean(MasterEmailLinks.class).isProvisioned(MasterEmailKind.DIGEST)).isFalse();
            });
    }

    @Test
    @DisplayName("the chart sets the SENT template and no longer the digest's")
    void chart() throws IOException {
        String values = Files.readString(CHART_VALUES);
        assertThat(values).contains("NOTIFY_TEMPLATE_MASTER_REFERRAL_SENT: DEED_POLL_PLACEHOLDER",
                                    "NOTIFY_TEMPLATE_MASTER_REFERRAL_REMINDER: DEED_POLL_PLACEHOLDER");
        assertThat(values).doesNotContain("MASTER_REFERRAL_DIGEST", "MASTER_DIGEST_INTERVAL");
    }

    @Test
    @DisplayName("the base URL is declared from ADMIN_BASE_URL, with no default to send a Master to")
    void baseUrlIsDeclared() {
        assertThat(configuration()).contains("    base-url: ${ADMIN_BASE_URL:}");
    }

    @Test
    @DisplayName("the committed defaults: unprovisioned, no base URL, the jobs on at their intervals")
    void committedDefaults() {
        contextRunner
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .run(context -> {
                assertThat(context).hasNotFailed();
                MasterEmailLinks links = context.getBean(MasterEmailLinks.class);
                assertThat(links.isProvisioned(MasterEmailKind.SENT)).isFalse();
                assertThat(links.isProvisioned(MasterEmailKind.REMINDER)).isFalse();

                MasterEmailProperties properties = context.getBean(MasterEmailProperties.class);
                assertThat(properties.emailsEnabled()).isTrue();
                assertThat(properties.reminderIntervalCheck()).isEqualTo(Duration.ofMinutes(15));
                assertThat(properties.drainInterval()).isEqualTo(Duration.ofMinutes(1));
            });
    }

    @ParameterizedTest
    @EnumSource(value = MasterEmailKind.class, names = "DIGEST", mode = EnumSource.Mode.EXCLUDE)
    @DisplayName("refuses to start with a provisioned template and no base URL, in fixed words")
    void refusesAProvisionedTemplateWithNoBaseUrl(MasterEmailKind kind) {
        contextRunner
            .withPropertyValues("notify.templates." + kind.templateKey() + "=" + TEMPLATE_ID)
            .run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure()).rootCause()
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage(MasterEmailLinks.MISSING_BASE_URL);
            });
    }

    @ParameterizedTest
    @ValueSource(strings = {"deed-poll-admin.aat.platform.hmcts.net", "ftp://deed-poll-admin.example", "https://",
                            "https://admin.example/path?query=1"})
    @DisplayName("refuses a base URL that is not an absolute http(s) address, without echoing it")
    void refusesAMalformedBaseUrl(String baseUrl) {
        contextRunner
            .withPropertyValues("deedpoll.admin.base-url=" + baseUrl)
            .run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure()).rootCause()
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage(MasterEmailLinks.INVALID_BASE_URL);
            });
    }

    @Test
    @DisplayName("starts with a provisioned template and a base URL, and links to My referrals")
    void linksToMyReferrals() {
        contextRunner
            .withPropertyValues("notify.templates.master-referral-sent=" + TEMPLATE_ID,
                                "deedpoll.admin.base-url=  https://deed-poll-admin.aat.platform.hmcts.net/ ")
            .run(context -> {
                assertThat(context).hasNotFailed();
                MasterEmailLinks links = context.getBean(MasterEmailLinks.class);
                assertThat(links.isProvisioned(MasterEmailKind.SENT)).isTrue();
                assertThat(links.isProvisioned(MasterEmailKind.REMINDER)).isFalse();
                assertThat(links.templateId(MasterEmailKind.SENT)).isEqualTo(TEMPLATE_ID);
                assertThat(links.myReferralsLink()).isEqualTo(
                    "https://deed-poll-admin.aat.platform.hmcts.net/judicial/referrals/mine");
            });
    }

    @Test
    @DisplayName("a template set to blank is unprovisioned, so it needs no base URL")
    void blankIsUnprovisioned() {
        contextRunner
            .withPropertyValues("notify.templates.master-referral-reminder= ")
            .run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context.getBean(MasterEmailLinks.class).isProvisioned(MasterEmailKind.REMINDER))
                    .isFalse();
            });
    }

    private static String configuration() {
        try {
            return Files.readString(CONFIGURATION);
        } catch (IOException unreadable) {
            throw new UncheckedIOException("Could not read " + CONFIGURATION, unreadable);
        }
    }
}
