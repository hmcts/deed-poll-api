package uk.gov.hmcts.reform.deedpoll.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RateLimitPropertiesTest {

    @Test
    @DisplayName("a bucket is configured by its hyphenated key, and the buckets left unnamed keep their defaults")
    void bindsByBucketKey() {
        RateLimitProperties properties = bind(Map.of(
            "deedpoll.rate-limit.mode", "enforce",
            "deedpoll.rate-limit.buckets.email-preview.limit", "7",
            "deedpoll.rate-limit.buckets.email-preview.window", "90s"));

        assertThat(properties.mode()).isEqualTo(RateLimitProperties.Mode.ENFORCE);
        assertThat(properties.budget(RateLimitBucket.EMAIL_PREVIEW))
            .isEqualTo(new RateLimitProperties.Budget(7, Duration.ofSeconds(90)));
        assertThat(properties.budget(RateLimitBucket.SEARCH))
            .isEqualTo(new RateLimitProperties.Budget(120, Duration.ofMinutes(1)));
    }

    @Test
    @DisplayName("nothing configured is observe mode with decision 1's numbers")
    void defaults() {
        RateLimitProperties properties = new RateLimitProperties(null, null);

        assertThat(properties.mode()).isEqualTo(RateLimitProperties.Mode.OBSERVE);
        assertThat(properties.budget(RateLimitBucket.SEARCH))
            .isEqualTo(new RateLimitProperties.Budget(120, Duration.ofMinutes(1)));
        assertThat(properties.budget(RateLimitBucket.EMAIL_PREVIEW))
            .isEqualTo(new RateLimitProperties.Budget(30, Duration.ofMinutes(10)));
        assertThat(properties.budget(RateLimitBucket.EMAIL_SEND))
            .isEqualTo(new RateLimitProperties.Budget(20, Duration.ofHours(1)));
        assertThat(properties.budget(RateLimitBucket.UPLOAD))
            .isEqualTo(new RateLimitProperties.Budget(30, Duration.ofMinutes(10)));
    }

    @Test
    @DisplayName("a limit below one or an empty window stops start-up rather than becoming a budget")
    void refusesWhatCannotBeABudget() {
        assertThatThrownBy(() -> bind(Map.of(
            "deedpoll.rate-limit.buckets.upload.limit", "0",
            "deedpoll.rate-limit.buckets.upload.window", "10m")))
            .isInstanceOf(BindException.class);
        assertThatThrownBy(() -> new RateLimitProperties.Budget(5, Duration.ZERO))
            .isInstanceOf(IllegalArgumentException.class);
    }

    private static RateLimitProperties bind(Map<String, String> values) {
        return new Binder(new MapConfigurationPropertySource(values))
            .bindOrCreate("deedpoll.rate-limit", RateLimitProperties.class);
    }
}
