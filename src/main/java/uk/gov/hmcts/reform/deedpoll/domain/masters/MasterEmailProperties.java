package uk.gov.hmcts.reform.deedpoll.domain.masters;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * How the Master email jobs pace themselves (plan D3). Not identifiers, so working defaults.
 *
 * @param emailsEnabled whether the digest, reminder and drain timers run at all. Switched off only by tests, which
 *     run the jobs explicitly; an unprovisioned template is the deployed way to send nothing
 * @param digestInterval how often the digest job looks for a closed referral window
 * @param reminderIntervalCheck how often the reminder job looks for referrals due a reminder. Not the reminder
 *     interval itself, which is the Delivery Manager's setting
 * @param drainInterval how often the ledger's pending rows are sent, beside the send each job makes after enqueueing
 */
@ConfigurationProperties(prefix = "deedpoll.masters")
public record MasterEmailProperties(@DefaultValue("true") boolean emailsEnabled,
                                    @DefaultValue("PT5M") Duration digestInterval,
                                    @DefaultValue("PT15M") Duration reminderIntervalCheck,
                                    @DefaultValue("PT1M") Duration drainInterval) {

    public MasterEmailProperties {
        digestInterval = positive(digestInterval, "digest-interval");
        reminderIntervalCheck = positive(reminderIntervalCheck, "reminder-interval-check");
        drainInterval = positive(drainInterval, "drain-interval");
    }

    private static Duration positive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalStateException("deedpoll.masters." + name + " must be a positive duration");
        }
        return value;
    }
}
