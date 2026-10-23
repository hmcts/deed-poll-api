package uk.gov.hmcts.reform.deedpoll.domain.documents.preview;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import uk.gov.hmcts.reform.deedpoll.domain.documents.UploadPolicy;

import java.net.URI;
import java.time.Duration;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PreviewPropertiesTest {

    private static PreviewProperties defaults() {
        return new PreviewProperties(null, null, null, null, null, null, null, null);
    }

    private static Consumer<PreviewError> failure(PreviewFailure expected) {
        return error -> assertThat(error.failure()).isEqualTo(expected);
    }

    @Test
    @DisplayName("unset is the plan's engineering defaults, with the upload's own input ceiling")
    void defaultsAreThePlans() {
        PreviewProperties properties = defaults();
        assertThat(properties.requestBudget()).isEqualTo(Duration.ofSeconds(25));
        assertThat(properties.maxOutputBytes()).isEqualTo(50L * 1024 * 1024);
        assertThat(properties.maxPages()).isEqualTo(100);
        assertThat(properties.maxFramePixels()).isEqualTo(25_000_000L);
        assertThat(properties.maxConcurrentConversions()).isEqualTo(3);
        assertThat(properties.maxConcurrentPreviews()).isEqualTo(8);
        assertThat(properties.busyRetryAfter()).isEqualTo(Duration.ofSeconds(5));
        assertThat(properties.maxInputBytes()).isEqualTo(UploadPolicy.MAX_UPLOAD_BYTES).isEqualTo(25L * 1024 * 1024);
        assertThat(properties.converter().configured()).isFalse();
        assertThat(properties.converter().connectTimeout()).isEqualTo(Duration.ofSeconds(3));
        assertThat(properties.converter().readTimeout()).isEqualTo(Duration.ofSeconds(20));
    }

    @Test
    @DisplayName("a budget that would outlast the gateways, or a limit switched off, refuses to start")
    void invalidLimitsRefuseToStart() {
        Duration overGateways = Duration.ofSeconds(30);
        assertThatThrownBy(() -> new PreviewProperties(overGateways, null, null, null, null, null, null, null))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PreviewProperties(Duration.ZERO, null, null, null, null, null, null, null))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PreviewProperties(null, 0L, null, null, null, null, null, null))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PreviewProperties(null, null, 0, null, null, null, null, null))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PreviewProperties(null, null, null, -1L, null, null, null, null))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PreviewProperties(null, null, null, null, 0, null, null, null))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PreviewProperties(null, null, null, null, null, 0, null, null))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PreviewProperties(null, null, null, null, null, null, Duration.ZERO, null))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PreviewProperties(null, null, null, null, null, null, Duration.ofMinutes(2), null))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("the converter's connect and read deadlines must fit inside the request budget")
    void converterDeadlinesFitTheBudget() {
        PreviewProperties.Converter slow =
            new PreviewProperties.Converter(null, null, Duration.ofSeconds(5), Duration.ofSeconds(21));
        assertThatThrownBy(() -> new PreviewProperties(null, null, null, null, null, null, null, slow))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PreviewProperties.Converter(null, null, Duration.ZERO, null))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("the limits refuse at their edges with the contract's failures")
    void limitChecks() {
        PreviewProperties properties = defaults();
        properties.requireOutputBytes(50L * 1024 * 1024);
        assertThatThrownBy(() -> properties.requireOutputBytes(50L * 1024 * 1024 + 1))
            .isInstanceOfSatisfying(PreviewError.class, failure(PreviewFailure.LIMIT_EXCEEDED));
        properties.requirePages(100);
        assertThatThrownBy(() -> properties.requirePages(101))
            .isInstanceOfSatisfying(PreviewError.class, failure(PreviewFailure.LIMIT_EXCEEDED));
        assertThatThrownBy(() -> properties.requirePages(0))
            .isInstanceOfSatisfying(PreviewError.class, failure(PreviewFailure.INVALID_DOCUMENT));
        properties.requireFrame(5000, 5000);
        assertThatThrownBy(() -> properties.requireFrame(5000, 5001))
            .isInstanceOfSatisfying(PreviewError.class, failure(PreviewFailure.LIMIT_EXCEEDED));
        assertThatThrownBy(() -> properties.requireFrame(0, 10))
            .isInstanceOfSatisfying(PreviewError.class, failure(PreviewFailure.INVALID_DOCUMENT));
    }

    @Test
    @DisplayName("two huge declared dimensions cannot overflow past the pixel check")
    void pixelCheckDoesNotOverflow() {
        assertThatThrownBy(() -> defaults().requireFrame(Integer.MAX_VALUE, Integer.MAX_VALUE))
            .isInstanceOfSatisfying(PreviewError.class, failure(PreviewFailure.LIMIT_EXCEEDED));
        assertThatThrownBy(() -> defaults().requireFrame(1L << 32, 1L << 32))
            .isInstanceOfSatisfying(PreviewError.class, failure(PreviewFailure.LIMIT_EXCEEDED));
    }

    @Nested
    @DisplayName("the converter")
    class ConverterSettings {

        @Test
        @DisplayName("is configured only with both an endpoint and a key, and posts to /rs/convert")
        void configuredWithBoth() {
            PreviewProperties.Converter converter = new PreviewProperties.Converter(
                " https://docmosis.aat.platform.hmcts.net/ ", "key", null, null);
            assertThat(converter.configured()).isTrue();
            assertThat(converter.convertUri())
                .isEqualTo(URI.create("https://docmosis.aat.platform.hmcts.net/rs/convert"));

            assertThat(new PreviewProperties.Converter("https://docmosis.aat.platform.hmcts.net", " ", null, null)
                           .configured()).isFalse();
            assertThat(new PreviewProperties.Converter("", "key", null, null).configured()).isFalse();
        }

        @Test
        @DisplayName("unconfigured answers unavailable rather than failing to start")
        void unconfiguredIsUnavailable() {
            PreviewProperties.Converter converter = new PreviewProperties.Converter(null, null, null, null);
            assertThatThrownBy(converter::convertUri)
                .isInstanceOfSatisfying(PreviewError.class, failure(PreviewFailure.UNAVAILABLE));
        }

        @Test
        @DisplayName("a set url that is not an https base refuses to start; loopback http is WireMock's")
        void urlsAreValidated() {
            for (String bad : new String[] {"http://docmosis.aat.platform.hmcts.net", "docmosis.aat",
                "https://user:pass@docmosis.aat.platform.hmcts.net", "https://docmosis.aat.platform.hmcts.net?x=1",
                "https://docmosis.aat.platform.hmcts.net#f", "ftp://docmosis", "https:///path", "not a url"}) {
                assertThatThrownBy(() -> new PreviewProperties.Converter(bad, "key", null, null))
                    .as(bad).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageNotContaining("pass");
            }
            assertThat(new PreviewProperties.Converter("http://localhost:8089", "key", null, null).convertUri())
                .isEqualTo(URI.create("http://localhost:8089/rs/convert"));
            assertThat(new PreviewProperties.Converter("http://127.0.0.1:8089", "key", null, null).configured())
                .isTrue();
        }

        @Test
        @DisplayName("the access key is never printed")
        void keyIsRedacted() {
            PreviewProperties properties = new PreviewProperties(null, null, null, null, null, null, null,
                new PreviewProperties.Converter("https://docmosis.aat.platform.hmcts.net", "s3cret-key", null, null));
            assertThat(properties.toString()).doesNotContain("s3cret-key").contains("redacted");
            assertThat(properties.converter().toString()).doesNotContain("s3cret-key");
        }
    }

    @Nested
    @DisplayName("bound from configuration")
    class Binding {

        private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(PreviewConfiguration.class);

        @Test
        @DisplayName("starts with nothing set: no converter, the defaults, and three permits")
        void startsUnconfigured() {
            runner.run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context.getBean(PreviewProperties.class).converter().configured()).isFalse();
                assertThat(context.getBean(ConversionCapacity.class).available()).isEqualTo(3);
            });
        }

        @Test
        @DisplayName("binds the operator settings, the key included, without printing it")
        void bindsTheSettings() {
            runner.withPropertyValues(
                "deedpoll.documents.preview.request-budget=20s",
                "deedpoll.documents.preview.max-concurrent-conversions=2",
                "deedpoll.documents.preview.max-concurrent-previews=5",
                "deedpoll.documents.preview.converter.url=https://docmosis.aat.platform.hmcts.net",
                "deedpoll.documents.preview.converter.access-key=s3cret-key",
                "deedpoll.documents.preview.converter.read-timeout=15s").run(context -> {
                    PreviewProperties properties = context.getBean(PreviewProperties.class);
                    assertThat(properties.requestBudget()).isEqualTo(Duration.ofSeconds(20));
                    assertThat(properties.converter().configured()).isTrue();
                    assertThat(properties.converter().accessKey()).isEqualTo("s3cret-key");
                    assertThat(context.getBean(ConversionCapacity.class).available()).isEqualTo(2);
                    assertThat(context.getBean(ConversionCapacity.class).availableWorkspaces()).isEqualTo(5);
                });
        }

        @Test
        @DisplayName("a malformed converter url fails start-up rather than each preview")
        void malformedUrlFailsStartup() {
            runner.withPropertyValues("deedpoll.documents.preview.converter.url=http://docmosis.example")
                .run(context -> assertThat(context).hasFailed());
        }
    }
}
