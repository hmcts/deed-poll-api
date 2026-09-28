package uk.gov.hmcts.reform.deedpoll.api.caseworker.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import uk.gov.hmcts.reform.deedpoll.api.caseworker.dto.CcdAttemptListResponse;
import uk.gov.hmcts.reform.deedpoll.api.caseworker.dto.CcdAttemptResponse;
import uk.gov.hmcts.reform.deedpoll.auth.CurrentUsers;
import uk.gov.hmcts.reform.deedpoll.ccd.CcdAttemptRepairs;
import uk.gov.hmcts.reform.deedpoll.domain.deedpoll.CasePaging;

/**
 * The CCD creation attempts that are not linked, and the two ways an admin moves one out of {@code NeedsRepair}
 * (plan 02b, Task 4).
 *
 * <p><strong>Admins only, and the gate is not here.</strong> {@code CcdAttemptRepairs} requires
 * {@code Role.DEEDPOLL_ADMIN} on every operation, as {@code JudicialReferralService} requires {@code Role.JUDICIAL},
 * so a caller without it is the domain's 403 however it arrives.
 *
 * <p><strong>Both moves are sub-resources taking no body</strong>, like a referral's withdrawal: the move is the
 * whole request. Neither calls CCD and neither writes a binding; each is one conditional statement from
 * {@code NeedsRepair}, and the next case-list sweep does the rest. An unknown attempt is a 404, one that is not
 * {@code NeedsRepair} a 409, and so is a retry from scratch of one that may already have reached CCD -- with a
 * message saying why and what to do instead.
 *
 * <p>Outside any transaction, as every caller of the linking code must be; the ledger commits each statement itself.
 */
@RestController
@RequestMapping("/caseworker/ccd-attempts")
public class CaseworkerCcdAttemptController {

    private final CcdAttemptRepairs repairs;

    public CaseworkerCcdAttemptController(CcdAttemptRepairs repairs) {
        this.repairs = repairs;
    }

    /**
     * The counts per status, and one page of the attempts that are not linked, longest-unmoved first.
     *
     * <p>{@code status} narrows the page to one status other than {@code Linked}; anything else is a 400 naming the
     * statuses it accepts. {@code page} and {@code size} are read by {@code CasePaging}, as the case list's are: 25
     * rows by default and never more than 100.
     */
    @GetMapping
    public CcdAttemptListResponse attempts(@RequestParam(name = "status", required = false) String status,
                                           @RequestParam(name = "page", required = false) String page,
                                           @RequestParam(name = "size", required = false) String size) {
        return CcdAttemptListResponse.of(
            repairs.overview(CurrentUsers.required(), status, CasePaging.parse(page, size)));
    }

    /**
     * Reconcile again: {@code NeedsRepair -> AwaitingConfirmation}. Always open to an attempt that needs repair.
     *
     * <p>200 with the attempt as it now stands.
     */
    @PostMapping("/{attemptId}/reconciliation")
    public CcdAttemptResponse reconciliation(@PathVariable("attemptId") String attemptId) {
        return CcdAttemptResponse.from(repairs.reconcileAgain(attemptId, CurrentUsers.required()));
    }

    /**
     * Try again from scratch: {@code NeedsRepair -> Pending}, its tries reset. Open only while nothing may have been
     * submitted to CCD, or it is proven that nothing submitted created a case, which the list publishes as
     * {@code canRetryFromScratch}.
     *
     * <p>200 with the attempt as it now stands.
     */
    @PostMapping("/{attemptId}/retry")
    public CcdAttemptResponse retry(@PathVariable("attemptId") String attemptId) {
        return CcdAttemptResponse.from(repairs.retryFromScratch(attemptId, CurrentUsers.required()));
    }
}
