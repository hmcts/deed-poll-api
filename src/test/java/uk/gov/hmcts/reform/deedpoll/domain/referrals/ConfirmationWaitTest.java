package uk.gov.hmcts.reform.deedpoll.domain.referrals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The waiting-list rule, container-free -- which is the point of it existing as a function.
 *
 * <p>Container-backed tests are excluded on the Jenkins agent ({@code INTEGRATION_TESTS_ENABLED=false}), so if the
 * rule that decides whether a case is still waiting, and for how long, lived only in a query and a DTO, CI would
 * assert none of it. {@code DeedPollRepositoryTest} and {@code ReferralRepositoryTest} cover the SQL that feeds it.
 */
class ConfirmationWaitTest {

    private static final LocalDateTime ASKED = LocalDateTime.of(2026, 5, 3, 9, 30);

    @Nested
    @DisplayName("earliestOutstanding")
    class EarliestOutstanding {

        @Test
        @DisplayName("is empty for a case waiting for nothing, which is a real answer and not a missing one")
        void isEmptyForACaseWaitingForNothing() {
            // Both shapes of nothing: never asked, and asked and answered -- the caller's query excludes each of
            // them, so both arrive here as no rows at all. Empty is what takes a case out of the waiting list.
            assertThat(ConfirmationWait.earliestOutstanding(List.of())).isEmpty();
        }

        @Test
        @DisplayName("is the one outstanding request when there is one")
        void isTheOneOutstandingRequest() {
            assertThat(ConfirmationWait.earliestOutstanding(List.of(ASKED))).contains(ASKED);
        }

        @Test
        @DisplayName("is the earliest of several, so the row shows the longest wait rather than the latest ask")
        void isTheEarliestOfSeveral() {
            // Two outstanding requests is reachable: a referral can be asked and then closed, and a second raised
            // and asked, because recording an answer is ungated on referral state. The case has waited since the
            // first of them, and it is the ordering of the list that makes this matter -- the latest date would
            // put the longest-waiting case lowest, which is the one failure the list exists to prevent.
            assertThat(ConfirmationWait.earliestOutstanding(
                List.of(ASKED.plusDays(40), ASKED, ASKED.plusDays(9))))
                .contains(ASKED);
        }

        @Test
        @DisplayName("ignores a null rather than throwing on one")
        void ignoresANull() {
            // The query cannot produce one -- it excludes a null request date -- but this is the site whose job is
            // to keep a column honest, and Comparator.naturalOrder() throws on a null. A repair that failed with a
            // NullPointerException would leave the column at whatever it already said.
            List<LocalDateTime> withNull = new ArrayList<>(Arrays.asList(null, ASKED, null));

            assertThat(ConfirmationWait.earliestOutstanding(withNull)).contains(ASKED);
            assertThat(ConfirmationWait.earliestOutstanding(Arrays.asList((LocalDateTime) null))).isEmpty();
        }
    }

    @Nested
    @DisplayName("daysWaiting")
    class DaysWaiting {

        @Test
        @DisplayName("is 0 on the day the email was sent, not 1")
        void isZeroOnTheDayItWasSent() {
            // The assertion this method was written for. A caseworker seeing "1 day" against a case asked this
            // morning would read the list as a day older than it is, and an off-by-one at the bottom of the scale
            // is invisible in exactly the cases nobody is worried about yet.
            assertThat(ConfirmationWait.daysWaiting(ASKED, ASKED)).isZero();
            assertThat(ConfirmationWait.daysWaiting(ASKED, ASKED.plusHours(13))).isZero();
        }

        @Test
        @DisplayName("counts calendar days, so the clock time on either end does not round the count")
        void countsCalendarDays() {
            // 23:59 the next day is one day later, and 00:01 the next day is too: the columns either end are
            // date-bearing, and a duration-based count would answer 0 for the first and 1 for the second.
            assertThat(ConfirmationWait.daysWaiting(ASKED, ASKED.withHour(23).withMinute(59).plusDays(1))).isOne();
            assertThat(ConfirmationWait.daysWaiting(ASKED, ASKED.withHour(0).withMinute(1).plusDays(1))).isOne();
        }

        @Test
        @DisplayName("is the figure the row shows: 43 days after the ask")
        void isTheFigureTheRowShows() {
            assertThat(ConfirmationWait.daysWaiting(ASKED, ASKED.plusDays(43))).isEqualTo(43);
        }

        @Test
        @DisplayName("is null for a case waiting for nothing, rather than 0")
        void isNullForACaseWaitingForNothing() {
            // 0 is a real answer -- asked today -- so the absence has to be distinguishable from it. A row
            // answering 0 for every case that was never asked would read as "asked today" on the whole table.
            assertThat(ConfirmationWait.daysWaiting(null, ASKED)).isNull();
        }

        @Test
        @DisplayName("reports a stamp in the future as negative rather than clamping it to 0")
        void reportsAFutureStampAsNegative() {
            // Faithful to the arithmetic it borrows, and deliberately not hidden: a future stamp is a clock or a
            // repaired row, and a 0 would make it unreportable. Computed's day counts make the same choice.
            assertThat(ConfirmationWait.daysWaiting(ASKED.plusDays(2), ASKED)).isEqualTo(-2);
        }
    }
}
