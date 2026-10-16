package uk.gov.hmcts.reform.deedpoll.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * How {@link RateLimitFilter} spends and refuses: {@code deedpoll.rate-limit}.
 *
 * <p>A bucket the configuration does not name keeps {@link RateLimitBucket#defaultBudget()}, so an environment sets
 * only what it changes. A limit or window that cannot be a budget stops start-up rather than silently becoming one.
 *
 * @param mode    {@code off}, {@code observe} or {@code enforce}; unset is {@code observe}
 * @param buckets        per-bucket limits, keyed by {@link RateLimitBucket#key()} in the property name
 * @param uploadInFlight the uploads one pod admits at once, whatever the mode; unset is {@value #UPLOAD_IN_FLIGHT}
 */
@ConfigurationProperties(prefix = "deedpoll.rate-limit")
public record RateLimitProperties(Mode mode, Map<RateLimitBucket, Budget> buckets, Integer uploadInFlight) {

    /** Decision 7's default: four uploads, each up to 25 MB in memory, per pod. */
    public static final int UPLOAD_IN_FLIGHT = 4;

    public RateLimitProperties {
        mode = mode == null ? Mode.OBSERVE : mode;
        uploadInFlight = uploadInFlight == null ? UPLOAD_IN_FLIGHT : uploadInFlight;
        if (uploadInFlight < 1) {
            throw new IllegalArgumentException("the in-flight upload cap must be at least 1");
        }
        Map<RateLimitBucket, Budget> resolved = new EnumMap<>(RateLimitBucket.class);
        for (RateLimitBucket bucket : RateLimitBucket.values()) {
            Budget configured = buckets == null ? null : buckets.get(bucket);
            resolved.put(bucket, configured == null ? bucket.defaultBudget() : configured);
        }
        buckets = Collections.unmodifiableMap(resolved);
    }

    public Budget budget(RateLimitBucket bucket) {
        return buckets.get(bucket);
    }

    /** What the filter does with a request over its budget. */
    public enum Mode {
        /** Counts nothing and refuses nothing. */
        OFF,
        /**
         * Counts, and logs exactly what {@link #ENFORCE} would refuse, but refuses nothing.
         */
        OBSERVE,
        /** Counts, logs and refuses with 429. */
        ENFORCE;

        String key() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /**
     * One bucket's allowance: {@code limit} requests per fixed {@code window}.
     *
     * @param limit  the requests admitted per window, at least 1
     * @param window the window's length, positive and in whole milliseconds
     */
    public record Budget(int limit, Duration window) {

        public Budget {
            Objects.requireNonNull(window, "a rate-limit bucket needs a window");
            if (limit < 1) {
                throw new IllegalArgumentException("a rate-limit bucket's limit must be at least 1");
            }
            if (window.toMillis() <= 0) {
                throw new IllegalArgumentException("a rate-limit bucket's window must be at least one millisecond");
            }
        }
    }
}
