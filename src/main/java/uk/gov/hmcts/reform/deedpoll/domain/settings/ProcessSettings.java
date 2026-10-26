package uk.gov.hmcts.reform.deedpoll.domain.settings;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * The Delivery Manager's process settings; attribution excludes the private authenticated subject.
 *
 * <p>One component per live value, each its own typed column in V40, so a later value is a later migration with its
 * own check rather than a key in a bag.
 */
public record ProcessSettings(Duration referralClaimExpiry, int revision, LocalDateTime updatedAt, String updatedBy) {

    /** The claim window's name in a request, a response, the history table and the audit event. */
    public static final String REFERRAL_CLAIM_EXPIRY_MINUTES = "referralClaimExpiryMinutes";

    /** The claim window as stored and published: whole minutes. */
    public int referralClaimExpiryMinutes() {
        return Math.toIntExact(referralClaimExpiry.toMinutes());
    }
}
