package uk.gov.hmcts.reform.deedpoll.ccd;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * How the linking operation paces itself: {@code deedpoll.ccd.linking}.
 *
 * <p>None of the three is an external identifier, so none is a placeholder; each has a working default in
 * {@code application.yaml}, and a value that would make the operation unsafe refuses to start instead.
 *
 * @param worker      who holds a claim, for attribution and diagnosis -- the pod's name in a deployment, never a
 *                    user's
 * @param lease       how long a claim holds before reconciliation may take the attempt off its worker. It has to
 *                    outlast one whole conversation with CCD -- two tokens, a start and a submit -- or a live
 *                    worker's attempt is taken from under it
 * @param settleAfter how long an attempt may sit in {@code AwaitingConfirmation} with nothing to confirm before it
 *                    becomes {@code NeedsRepair}: long enough for a submission still in flight to have reached this
 *                    service's persistence API and committed its binding
 * @param maxAttempts how many claims an attempt gets for refusals that created nothing, before it becomes
 *                    {@code NeedsRepair} rather than going round again
 */
@ConfigurationProperties(prefix = "deedpoll.ccd.linking")
public record CcdLinkingProperties(String worker, Duration lease, Duration settleAfter, int maxAttempts) {

    public CcdLinkingProperties {
        if (worker == null || worker.isBlank()) {
            throw new IllegalArgumentException("deedpoll.ccd.linking.worker must be set");
        }
        worker = worker.trim();
        requirePositive("lease", lease);
        requirePositive("settle-after", settleAfter);
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("deedpoll.ccd.linking.max-attempts must be at least 1");
        }
    }

    private static void requirePositive(String key, Duration value) {
        if (value == null || value.isNegative() || value.isZero()) {
            throw new IllegalArgumentException("deedpoll.ccd.linking." + key + " must be a positive duration");
        }
    }
}
