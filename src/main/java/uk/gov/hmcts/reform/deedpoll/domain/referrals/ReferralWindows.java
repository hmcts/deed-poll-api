package uk.gov.hmcts.reform.deedpoll.domain.referrals;

import uk.gov.hmcts.reform.deedpoll.domain.settings.ReferralWindowTimes;
import uk.gov.hmcts.reform.deedpoll.entity.enums.WindowSlot;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * The referral windows (D-5 to D-7), as pure functions with no database and no clock: which is open at a moment, and
 * which opens next (plan F1, which sends referrals only while one is). Read in {@link ReferralWindowTimes#ZONE}; a
 * day that is not a working day has no window open, and its next is the next working day's morning (D-7).
 *
 * <p>A moment is an {@link Instant}, so the local time it reads as is never ambiguous, even in the hour the clocks go
 * back. A window time that is itself skipped or repeated by a clock change resolves by {@link ZonedDateTime#of}'s
 * rules in {@link #closesAt}: a skipped time moves forward by the length of the gap, and a repeated one takes the
 * earlier offset.
 *
 * <p>A window is open from its start, inclusive, to its end, exclusive, to the minute and below.
 */
public final class ReferralWindows {

    /**
     * How far ahead the next working day is looked for. A year of holidays is a misconfigured list, and an
     * unbounded search over one would never return.
     */
    static final int SEARCH_DAYS = 366;

    private ReferralWindows() {
    }

    /**
     * The window open at a moment, or empty when none is: between the windows, before the morning's or after the
     * afternoon's, and all day on a day that is not a working day (plan F1, decision F-4).
     */
    public static Optional<ReferralWindow> openAt(Instant at, ReferralWindowTimes times,
                                                  Predicate<LocalDate> isWorkingDay) {
        ZonedDateTime local = at.atZone(ReferralWindowTimes.ZONE);
        LocalDate day = local.toLocalDate();
        if (!isWorkingDay.test(day)) {
            return Optional.empty();
        }
        LocalTime time = local.toLocalTime();
        if (!time.isBefore(times.morningStart()) && time.isBefore(times.morningEnd())) {
            return Optional.of(new ReferralWindow(day, WindowSlot.AM));
        }
        if (!time.isBefore(times.afternoonStart()) && time.isBefore(times.afternoonEnd())) {
            return Optional.of(new ReferralWindow(day, WindowSlot.PM));
        }
        return Optional.empty();
    }

    /**
     * The first window to open after a moment, strictly: inside a window, its successor. Before the morning's start
     * on a working day, that morning; from it until the afternoon's start, that afternoon; from then, or on a day
     * that is not a working day, the next working day's morning.
     */
    public static ReferralWindow nextToOpen(Instant at, ReferralWindowTimes times, Predicate<LocalDate> isWorkingDay) {
        ZonedDateTime local = at.atZone(ReferralWindowTimes.ZONE);
        LocalDate day = local.toLocalDate();
        if (isWorkingDay.test(day)) {
            LocalTime time = local.toLocalTime();
            if (time.isBefore(times.morningStart())) {
                return new ReferralWindow(day, WindowSlot.AM);
            }
            if (time.isBefore(times.afternoonStart())) {
                return new ReferralWindow(day, WindowSlot.PM);
            }
        }
        return new ReferralWindow(nextWorkingDay(day, isWorkingDay), WindowSlot.AM);
    }

    /** The moment a window opens: its date at the morning's or the afternoon's start time, in the zone. */
    public static Instant opensAt(ReferralWindow window, ReferralWindowTimes times) {
        LocalTime start = window.slot() == WindowSlot.AM ? times.morningStart() : times.afternoonStart();
        return ZonedDateTime.of(window.date(), start, ReferralWindowTimes.ZONE).toInstant();
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
