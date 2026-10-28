package uk.gov.hmcts.reform.deedpoll.domain.referrals;

import uk.gov.hmcts.reform.deedpoll.domain.settings.ReferralWindowTimes;
import uk.gov.hmcts.reform.deedpoll.entity.enums.WindowSlot;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.function.Predicate;

/**
 * Which referral window a moment counts towards (D-5 to D-7), as one pure function with no database and no clock.
 *
 * <p>Read in {@link ReferralWindowTimes#ZONE}. On a working day, a moment before the morning window ends is that
 * day's morning, so one raised before the window opens waits for it; a moment from the morning's end until the
 * afternoon's end is that day's afternoon, so one raised between the windows waits for the afternoon; and a moment
 * from the afternoon's end is the next working day's morning (D-6). A day that is not a working day goes to the
 * next working day's morning (D-7). The windows' ends are exclusive, to the minute and below.
 *
 * <p>A moment is an {@link Instant}, so the local time it reads as is never ambiguous, even in the hour the clocks go
 * back. A window time that is itself skipped or repeated by a clock change resolves by {@link ZonedDateTime#of}'s
 * rules in {@link #closesAt}: a skipped time moves forward by the length of the gap, and a repeated one takes the
 * earlier offset.
 */
public final class ReferralWindows {

    /**
     * How far ahead the next working day is looked for. A year of holidays is a misconfigured list, and an
     * unbounded search over one would never return.
     */
    static final int SEARCH_DAYS = 366;

    private ReferralWindows() {
    }

    /** The window a moment counts towards. */
    public static ReferralWindow windowFor(Instant at, ReferralWindowTimes times, Predicate<LocalDate> isWorkingDay) {
        ZonedDateTime local = at.atZone(ReferralWindowTimes.ZONE);
        LocalDate day = local.toLocalDate();
        if (!isWorkingDay.test(day)) {
            return new ReferralWindow(nextWorkingDay(day, isWorkingDay), WindowSlot.AM);
        }
        LocalTime time = local.toLocalTime();
        if (time.isBefore(times.morningEnd())) {
            return new ReferralWindow(day, WindowSlot.AM);
        }
        if (time.isBefore(times.afternoonEnd())) {
            return new ReferralWindow(day, WindowSlot.PM);
        }
        return new ReferralWindow(nextWorkingDay(day, isWorkingDay), WindowSlot.AM);
    }

    /** The moment a window ends: its date at the morning's or the afternoon's end time, in the zone. */
    public static Instant closesAt(ReferralWindow window, ReferralWindowTimes times) {
        LocalTime end = window.slot() == WindowSlot.AM ? times.morningEnd() : times.afternoonEnd();
        return ZonedDateTime.of(window.date(), end, ReferralWindowTimes.ZONE).toInstant();
    }

    /**
     * The first working day after {@code day}.
     */
    static LocalDate nextWorkingDay(LocalDate day, Predicate<LocalDate> isWorkingDay) {
        LocalDate candidate = day;
        for (int i = 0; i < SEARCH_DAYS; i++) {
            candidate = candidate.plusDays(1);
            if (isWorkingDay.test(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("No working day within " + SEARCH_DAYS + " days");
    }
}
