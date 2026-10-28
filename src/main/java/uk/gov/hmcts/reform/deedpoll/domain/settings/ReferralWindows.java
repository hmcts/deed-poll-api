package uk.gov.hmcts.reform.deedpoll.domain.settings;

import java.time.LocalTime;
import java.time.ZoneId;

/**
 * The two daily windows in which referrals reach the Masters, as local times in {@link #ZONE}. V42's checks hold
 * each start before its end and the morning window ending before the afternoon one starts.
 */
public record ReferralWindows(LocalTime morningStart, LocalTime morningEnd, LocalTime afternoonStart,
                              LocalTime afternoonEnd) {

    /** The zone every window time is read in. */
    public static final ZoneId ZONE = ZoneId.of("Europe/London");
}
