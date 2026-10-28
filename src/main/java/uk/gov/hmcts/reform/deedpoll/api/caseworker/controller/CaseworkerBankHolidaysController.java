package uk.gov.hmcts.reform.deedpoll.api.caseworker.controller;

import io.swagger.v3.oas.annotations.Operation;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import uk.gov.hmcts.reform.deedpoll.api.AuditedAs;
import uk.gov.hmcts.reform.deedpoll.api.caseworker.dto.BankHolidayResponse;
import uk.gov.hmcts.reform.deedpoll.api.caseworker.dto.CreateBankHolidayRequest;
import uk.gov.hmcts.reform.deedpoll.auth.CurrentUsers;
import uk.gov.hmcts.reform.deedpoll.domain.audit.AuditAction;
import uk.gov.hmcts.reform.deedpoll.domain.settings.BankHolidays;

import java.util.List;

/** The England and Wales bank holidays the referral windows and digests skip. */
@RestController
@RequestMapping("/caseworker/process-settings/bank-holidays")
public class CaseworkerBankHolidaysController {

    private final BankHolidays service;

    public CaseworkerBankHolidaysController(BankHolidays service) {
        this.service = service;
    }

    @Operation(operationId = "listBankHolidays", summary = "List the bank holidays",
        description = "Any deed poll staff role. Every listed England and Wales bank holiday, earliest first. "
            + "Seeded with GOV.UK's published dates for 2026, 2027 and 2028; nothing is fetched from GOV.UK.")
    @GetMapping
    public List<BankHolidayResponse> list() {
        return service.list(CurrentUsers.required()).stream().map(BankHolidayResponse::from).toList();
    }

    @Operation(operationId = "addBankHoliday", summary = "Add a bank holiday",
        description = "Requires caseworker-deedpoll-delivery-manager (MANAGE_PROCESS_CONFIG). date (yyyy-MM-dd) and "
            + "name are required. A date already listed is 409. The referral windows and digests skip it from "
            + "their next decision.")
    @AuditedAs(AuditAction.BANK_HOLIDAY_ADDED)
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public BankHolidayResponse add(@RequestBody CreateBankHolidayRequest request) {
        return BankHolidayResponse.from(service.add(CurrentUsers.required(), request.fields()));
    }

    @Operation(operationId = "removeBankHoliday", summary = "Remove a bank holiday",
        description = "Requires caseworker-deedpoll-delivery-manager (MANAGE_PROCESS_CONFIG). 204 with no body; a "
            + "date that is not listed, or is not a yyyy-MM-dd date, is 404.")
    @AuditedAs(AuditAction.BANK_HOLIDAY_REMOVED)
    @DeleteMapping("/{date}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remove(@PathVariable("date") String date) {
        service.remove(CurrentUsers.required(), date);
    }
}
