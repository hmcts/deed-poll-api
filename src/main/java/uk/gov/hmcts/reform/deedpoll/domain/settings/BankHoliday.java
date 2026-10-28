package uk.gov.hmcts.reform.deedpoll.domain.settings;

import java.time.LocalDate;

/** One England and Wales bank holiday on the Delivery Manager's list. */
public record BankHoliday(LocalDate date, String name) {
}
