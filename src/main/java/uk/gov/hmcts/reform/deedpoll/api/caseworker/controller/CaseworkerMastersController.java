package uk.gov.hmcts.reform.deedpoll.api.caseworker.controller;

import io.swagger.v3.oas.annotations.Operation;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import uk.gov.hmcts.reform.deedpoll.api.AuditedAs;
import uk.gov.hmcts.reform.deedpoll.api.caseworker.dto.CreateMasterRequest;
import uk.gov.hmcts.reform.deedpoll.api.caseworker.dto.MasterListResponse;
import uk.gov.hmcts.reform.deedpoll.api.caseworker.dto.MasterResponse;
import uk.gov.hmcts.reform.deedpoll.api.caseworker.dto.UpdateMasterRequest;
import uk.gov.hmcts.reform.deedpoll.auth.CurrentUsers;
import uk.gov.hmcts.reform.deedpoll.domain.audit.AuditAction;
import uk.gov.hmcts.reform.deedpoll.domain.masters.Master;
import uk.gov.hmcts.reform.deedpoll.domain.masters.MasterService;
import uk.gov.hmcts.reform.deedpoll.domain.referrals.ReferralService;

import java.util.List;

/** The Delivery Manager's Masters list, which every caseworker reads to choose a Master. */
@RestController
@RequestMapping("/caseworker/masters")
public class CaseworkerMastersController {

    private final MasterService service;
    private final ReferralService referrals;

    public CaseworkerMastersController(MasterService service, ReferralService referrals) {
        this.service = service;
        this.referrals = referrals;
    }

    @Operation(operationId = "listMasters", summary = "List the Masters",
        description = "Any deed poll staff role. Every Master, available or not, ordered by name and then id. "
            + "linked says whether the Master has signed in and been linked to their IDAM account; the account "
            + "itself is never published. window is the referral window a referral assigned now counts towards "
            + "(Europe/London: today's morning or afternoon, or the next working day's morning outside the windows), "
            + "cap is how many referrals one Master may be assigned per window, and each Master's assignedInWindow "
            + "is their count in that window.")
    @GetMapping
    public MasterListResponse list() {
        // The role check first, so a refused caller reads no counts.
        List<Master> masters = service.list(CurrentUsers.required());
        return MasterListResponse.from(masters, referrals.currentWindowLoad());
    }

    @Operation(operationId = "createMaster", summary = "Add a Master to the list",
        description = "Requires caseworker-deedpoll-delivery-manager (MANAGE_PROCESS_CONFIG). name and email are "
            + "required and available defaults to true. The email is trimmed and lower-cased; one already on the "
            + "list, in any case, is 409. The Master is linked to their IDAM account when they first sign in with "
            + "that address. There is no delete: a Master who leaves is marked unavailable.")
    @AuditedAs(AuditAction.MASTER_ADDED)
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public MasterResponse create(@RequestBody CreateMasterRequest request) {
        return MasterResponse.from(service.create(CurrentUsers.required(), request.fields()));
    }

    @Operation(operationId = "updateMaster", summary = "Change a Master's name, email or availability",
        description = "Requires caseworker-deedpoll-delivery-manager (MANAGE_PROCESS_CONFIG). A keyed patch of "
            + "name, email and available, at least one of them, with the revision read; a stale revision is 409 "
            + "with no change, and saving the current values is a no-op. An email another Master holds is 409. "
            + "Changing the email of a linked Master unlinks it: the new address must sign in to link again.")
    @AuditedAs(AuditAction.MASTER_CHANGED)
    @PatchMapping("/{id}")
    public MasterResponse update(@PathVariable("id") String id, @RequestBody UpdateMasterRequest request) {
        return MasterResponse.from(service.update(CurrentUsers.required(), id, request.fields()));
    }
}
