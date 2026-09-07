package uk.gov.hmcts.reform.deedpoll.domain.deedpoll;

import uk.gov.hmcts.reform.deedpoll.entity.DeedPoll;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Derived (non-stored) columns.
 *
 * <p>Ported from {@code reference/ts/deed-poll/computed.ts}, which ports the source's calculated
 * fields in {@code Formulas/deedpoll_deedpoll-FormulaDefinitions.yaml}:
 *
 * <pre>
 *   deedpoll_datetoday              = Now()
 *   deedpoll_daysreceivedtoenrolled = DateDiff(datereceived, dateenrolled, Days)
 *   deedpoll_daysreceivedtoreturned = DateDiff(datereceived, datereturned, Days)
 * </pre>
 *
 * <p>The migration plan calls for these to be derived in the app layer rather than stored, so there
 * is no column to drift out of date. They are attached on the read path, which is why both the REST
 * API and the SSR pages see them without either having to compute anything.
 *
 * <p>{@code dateToday} is deliberately NOT materialised: it is just now(), a template concern, and
 * persisting it per-record is what made the source formula useless. {@code testofdatediffyears} is
 * dropped too -- a leftover probe in the source, referenced by no form, view or workflow.
 */
public final class Computed {

    private Computed() {
    }

    /**
     * Whole days from {@code from} to {@code to}, or {@code null} when either end is missing or
     * unreadable.
     *
     * <p>Calendar days, compared in UTC, so a clock time on either date cannot round a same-day pair
     * to a non-zero difference -- all these columns are date-only. Negative when the end precedes the
     * start, faithfully to {@code DateDiff}: a data-entry error stays visible to the caseworker
     * instead of being silently clamped.
     */
    public static Integer daysBetween(Object from, Object to) {
        LocalDateTime start = Dates.toDateTime(from);
        LocalDateTime end = Dates.toDateTime(to);
        if (start == null || end == null) {
            return null;
        }
        LocalDate dayA = start.toLocalDate();
        LocalDate dayB = end.toLocalDate();
        return (int) ChronoUnit.DAYS.between(dayA, dayB);
    }

    /** Computes the derived columns for one case. Never mutates the input. */
    public static ComputedFields computedFor(DeedPoll record) {
        return computedFor(record.getDateReceived(), record.getDateEnroled(), record.getDateReturned());
    }

    /**
     * Computes the derived columns from the three dates they depend on.
     *
     * <p>The three-date overload exists because the reference's {@code computedFor} takes a structural
     * subset of a case, which Java cannot express -- and because a caller holding only the dates (a
     * projection, a form body) should not have to build an entity to ask this question.
     */
    public static ComputedFields computedFor(LocalDateTime dateReceived,
                                             LocalDateTime dateEnroled,
                                             LocalDateTime dateReturned) {
        return new ComputedFields(
            daysBetween(dateReceived, dateEnroled),
            daysBetween(dateReceived, dateReturned));
    }

    /** Pairs a case with its derived columns, leaving the case itself untouched. */
    public static DeedPollWithComputed withComputed(DeedPoll record) {
        return new DeedPollWithComputed(record, computedFor(record));
    }

    /** Pairs every case in a list with its derived columns, preserving order. */
    public static List<DeedPollWithComputed> withComputedAll(List<DeedPoll> records) {
        return records.stream().map(Computed::withComputed).toList();
    }
}
