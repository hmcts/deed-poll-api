package uk.gov.hmcts.reform.deedpoll.api.caseworker.controller;

import io.swagger.v3.oas.annotations.Operation;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import uk.gov.hmcts.reform.deedpoll.api.caseworker.dto.ReferralQueueResponse;
import uk.gov.hmcts.reform.deedpoll.auth.CurrentUsers;
import uk.gov.hmcts.reform.deedpoll.domain.referrals.ReferralQueue;
import uk.gov.hmcts.reform.deedpoll.domain.settings.ProcessSettingsService;

/**
 * The referral queue (plan F1): referrals waiting to be sent to a Master, and those sent and not yet directed, for a
 * Team leader or Delivery Manager ({@code ALLOCATE_REFERRALS}).
 *
 * <p>{@code page} and {@code size} bind as single strings and are read by {@code CasePaging}, after the capability
 * check, so a caller without it is a 403 whatever they sent.
 */
@RestController
@RequestMapping("/caseworker/referral-queue")
public class CaseworkerReferralQueueController {

    private final ReferralQueue queue;
    private final ProcessSettingsService settings;

    public CaseworkerReferralQueueController(ReferralQueue queue, ProcessSettingsService settings) {
        this.queue = queue;
        this.settings = settings;
    }

    @Operation(operationId = "listReferralQueue", summary = "List the referral queue",
        description = "Requires caseworker-deedpoll-team-leader or caseworker-deedpoll-delivery-manager "
            + "(ALLOCATE_REFERRALS); anyone else is 403 before anything is read. state=Open (the default) lists the "
            + "referrals waiting to be sent to a Master, urgent first, then the oldest raised, then id; state=Claimed "
            + "lists those sent and not yet directed, for reassigning, the oldest sent first, then id. Any other "
            + "state is 400 on state. page (1-based) and size (default 25, at most 100) as the case list takes them. "
            + "Across every case. window says whether a referral window is open now (current, null outside one), "
            + "which opens next, the cap across all Masters, how many referrals were sent in the open window "
            + "whatever happened to them since, and each available Master's share. No subject or email address is "
            + "published.")
    @GetMapping
    public ReferralQueueResponse list(@RequestParam(name = "state", required = false) String state,
                                      @RequestParam(name = "page", required = false) String page,
                                      @RequestParam(name = "size", required = false) String size) {
        return ReferralQueueResponse.of(queue.list(CurrentUsers.required(), state, page, size),
                                        settings.processingWindow());
    }
}
