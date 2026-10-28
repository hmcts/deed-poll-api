package uk.gov.hmcts.reform.deedpoll.domain.settings;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

/**
 * The Delivery Manager's process settings; attribution excludes the private authenticated subject.
 *
 * <p>One component per live value, each its own typed column in V40 or V42, so a later value is a later migration
 * with its own check rather than a key in a bag.
 */
public record ProcessSettings(Duration processingWindow, Duration reminderInterval, int masterWindowCap,
                              LocalTime morningWindowStart, LocalTime morningWindowEnd,
                              LocalTime afternoonWindowStart, LocalTime afternoonWindowEnd,
                              int revision, LocalDateTime updatedAt, String updatedBy) {

    /**
     * The processing window's name in a request, a response, the history table and the audit event. It was the claim
     * window's, and keeps the wire name so the admin that sends it keeps working.
     */
    public static final String REFERRAL_CLAIM_EXPIRY_MINUTES = "referralClaimExpiryMinutes";

    public static final String REMINDER_INTERVAL_HOURS = "reminderIntervalHours";

    public static final String MASTER_WINDOW_CAP = "masterWindowCap";

    public static final String MORNING_WINDOW_START = "morningWindowStart";

    public static final String MORNING_WINDOW_END = "morningWindowEnd";

    public static final String AFTERNOON_WINDOW_START = "afternoonWindowStart";

    public static final String AFTERNOON_WINDOW_END = "afternoonWindowEnd";

    /** Every setting's name, in the order a change lists them. */
    public static final List<String> FIELDS = List.of(REFERRAL_CLAIM_EXPIRY_MINUTES, REMINDER_INTERVAL_HOURS,
        MASTER_WINDOW_CAP, MORNING_WINDOW_START, MORNING_WINDOW_END, AFTERNOON_WINDOW_START, AFTERNOON_WINDOW_END);

    /** The four window times, without the zone they are read in. */
    public static final List<String> WINDOW_FIELDS =
        List.of(MORNING_WINDOW_START, MORNING_WINDOW_END, AFTERNOON_WINDOW_START, AFTERNOON_WINDOW_END);

    /** The processing window as stored and published: whole minutes. */
    public int referralClaimExpiryMinutes() {
        return Math.toIntExact(processingWindow.toMinutes());
    }

    /** The reminder interval as stored and published: whole hours. */
    public int reminderIntervalHours() {
        return Math.toIntExact(reminderInterval.toHours());
    }

    /** These values under a new revision and attribution. */
    public ProcessSettings revised(int nextRevision, LocalDateTime at, String by) {
        return new ProcessSettings(processingWindow, reminderInterval, masterWindowCap, morningWindowStart,
                                   morningWindowEnd, afternoonWindowStart, afternoonWindowEnd, nextRevision, at, by);
    }

    public ReferralWindowTimes referralWindows() {
        return new ReferralWindowTimes(morningWindowStart, morningWindowEnd, afternoonWindowStart, afternoonWindowEnd);
    }

    /**
     * A setting as the history table and the response write it: a whole number, or a time as {@code HH:mm}.
     */
    public String valueOf(String field) {
        return switch (field) {
            case REFERRAL_CLAIM_EXPIRY_MINUTES -> String.valueOf(referralClaimExpiryMinutes());
            case REMINDER_INTERVAL_HOURS -> String.valueOf(reminderIntervalHours());
            case MASTER_WINDOW_CAP -> String.valueOf(masterWindowCap);
            case MORNING_WINDOW_START -> ProcessSettingsTimes.format(morningWindowStart);
            case MORNING_WINDOW_END -> ProcessSettingsTimes.format(morningWindowEnd);
            case AFTERNOON_WINDOW_START -> ProcessSettingsTimes.format(afternoonWindowStart);
            case AFTERNOON_WINDOW_END -> ProcessSettingsTimes.format(afternoonWindowEnd);
            default -> throw new IllegalArgumentException("Not a process setting: " + field);
        };
    }
}
