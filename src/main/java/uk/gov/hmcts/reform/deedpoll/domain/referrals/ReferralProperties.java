package uk.gov.hmcts.reform.deedpoll.domain.referrals;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * What is tunable about referrals. One value so far: how long a judge's claim survives their inactivity.
 *
 * <p>Wired the way {@code IdamProperties} is -- a record with constructor binding, registered by
 * {@link ReferralConfiguration} rather than component-scanned -- and read by {@code ReferralService}'s
 * stale-claim sweep. <strong>The window is not hard-coded in the service on purpose.</strong> Two hours
 * is a guess about how judges will actually work: long enough that reading the case papers does not lose
 * the referral, short enough that a judge who closed their laptop does not hold it overnight. It is the
 * sort of number that gets adjusted once there is real behaviour to look at, and a configuration key
 * makes that a Helm value rather than a release.
 *
 * @param claimExpiry how long a claimed referral may go untouched before the sweep returns it to the
 *     shared pool, measured from the judge's last interaction with it and not from the claim. Defaults
 *     to two hours when unset, so a local run and a not-yet-configured environment behave like a
 *     configured one -- unlike an audience list, there is no fail-closed reading of "no window": zero
 *     would reclaim every claim the instant it was made and a very long one would strand them, so an
 *     absent value takes the intended default and a nonsensical one refuses to start.
 */
@ConfigurationProperties(prefix = "deedpoll.referrals")
public record ReferralProperties(Duration claimExpiry) {

    /** Two hours, per the agreed behaviour. Applied when the key is absent or empty. */
    public static final Duration DEFAULT_CLAIM_EXPIRY = Duration.ofHours(2);

    public ReferralProperties(Duration claimExpiry) {
        this.claimExpiry = claimExpiry == null ? DEFAULT_CLAIM_EXPIRY : claimExpiry;
        if (this.claimExpiry.isZero() || this.claimExpiry.isNegative()) {
            // Fail at startup rather than at the first pool load. A zero or negative window makes the
            // sweep's `"lastTouchedAt" < now - window` true for every claim, so claiming a referral
            // would release it again immediately -- the feature would look broken with nothing in the
            // logs to say a configuration value did it.
            throw new IllegalArgumentException(
                "deedpoll.referrals.claim-expiry must be a positive duration, not " + this.claimExpiry);
        }
    }
}
