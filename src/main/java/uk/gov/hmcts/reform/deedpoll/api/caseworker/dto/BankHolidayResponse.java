package uk.gov.hmcts.reform.deedpoll.api.caseworker.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import uk.gov.hmcts.reform.deedpoll.domain.settings.BankHoliday;

import java.time.LocalDate;

/** One England and Wales bank holiday. */
public record BankHolidayResponse(
    @Schema(description = "The date, yyyy-MM-dd.") LocalDate date,
    String name) {

    public static BankHolidayResponse from(BankHoliday holiday) {
        return new BankHolidayResponse(holiday.date(), holiday.name());
    }
}
