package uk.gov.hmcts.reform.deedpoll.domain.mi;

import java.time.LocalDate;

/**
 * One bucket of a management-information request, both days inclusive.
 *
 * <p>Clipped to the request: the first period starts on {@code from} and the last ends on {@code to}, even where
 * the calendar week, month or quarter they belong to extends further, because nothing outside the request is
 * counted. A period's open caseload is therefore measured at its clipped {@link #end()}.
 */
public record MiPeriod(LocalDate start, LocalDate end) {
}
