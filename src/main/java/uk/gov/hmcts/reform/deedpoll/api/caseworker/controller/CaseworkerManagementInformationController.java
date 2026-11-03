package uk.gov.hmcts.reform.deedpoll.api.caseworker.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import uk.gov.hmcts.reform.deedpoll.api.caseworker.dto.MiCompletionResponse;
import uk.gov.hmcts.reform.deedpoll.api.caseworker.dto.MiReferralOutcomesResponse;
import uk.gov.hmcts.reform.deedpoll.api.caseworker.dto.MiStageDurationsResponse;
import uk.gov.hmcts.reform.deedpoll.api.caseworker.dto.MiVolumeResponse;
import uk.gov.hmcts.reform.deedpoll.auth.CurrentUsers;
import uk.gov.hmcts.reform.deedpoll.domain.mi.ManagementInformationService;
import uk.gov.hmcts.reform.deedpoll.domain.mi.MiParameters;

/**
 * Management information (plan 20261103-mi-dashboard): four read-only, aggregate-only figures for a Team leader or
 * Delivery Manager ({@code VIEW_MANAGEMENT_INFORMATION}).
 *
 * <p>The four query parameters bind as single strings and are read by {@link MiParameters#parse} after the
 * capability check, so a caller without it is a 403 whatever they sent, and a malformed value is a 400 attributed
 * to its parameter. No response names a case, so there is no read audit (plan D5), and these are reads: nothing here
 * is a write route for {@code WriteEndpointAuditCoverageTest}.
 */
@RestController
@RequestMapping("/caseworker/management-information")
public class CaseworkerManagementInformationController {

    private static final String COMMON = "Requires caseworker-deedpoll-team-leader or "
        + "caseworker-deedpoll-delivery-manager (VIEW_MANAGEMENT_INFORMATION); anyone else is 403 before any "
        + "parameter is read. from and to (yyyy-MM-dd, both inclusive and required, to no earlier than from and no "
        + "more than 2 years after it) and granularity (WEEK, an ISO week from Monday; MONTH; QUARTER) are required; "
        + "includeMigrated (default true) false leaves migrated cases out and counts them under excluded. Every "
        + "period of the range is reported in order, empty ones included, the first and last clipped to the range. "
        + "A date is bucketed by its stored value's calendar day, with no time-zone conversion. Aggregates only: no "
        + "case or referral is identified. The figures reflect the case's dates as they stand now, which staff can "
        + "edit; they are not a history of when a date was entered. ";

    private static final String FROM_DOC = "The first day of the range, yyyy-MM-dd, inclusive.";
    private static final String TO_DOC = "The last day of the range, yyyy-MM-dd, inclusive: no earlier than from and "
        + "no more than 2 years after it.";
    private static final String GRANULARITY_DOC = "WEEK (an ISO week from Monday), MONTH or QUARTER.";
    private static final String MIGRATED_DOC = "Whether migrated cases count (default true); false leaves them out "
        + "and counts them under excluded.";

    private final ManagementInformationService service;

    public CaseworkerManagementInformationController(ManagementInformationService service) {
        this.service = service;
    }

    @Operation(operationId = "getMiVolume", summary = "Cases received, and the open caseload, per period",
        description = COMMON + "received counts cases whose dateReceived falls in the period, split by type (a case "
            + "with no type counts as Unknown). openAtEnd counts cases received on or before the period's end with "
            + "neither dateEnroled nor dateReturned on or before it. Not counted: a case with no dateReceived, in "
            + "any period (excluded.noDateReceived, over all time, migrated or not).")
    @GetMapping("/volume")
    public MiVolumeResponse volume(
        @Parameter(name = MiParameters.FROM, required = true, description = FROM_DOC,
            schema = @Schema(type = "string", format = "date"))
        @RequestParam(name = MiParameters.FROM, required = false) String from,
        @Parameter(name = MiParameters.TO, required = true, description = TO_DOC,
            schema = @Schema(type = "string", format = "date"))
        @RequestParam(name = MiParameters.TO, required = false) String to,
        @Parameter(name = MiParameters.GRANULARITY, required = true,
            description = GRANULARITY_DOC,
            schema = @Schema(type = "string", allowableValues = {"WEEK", "MONTH", "QUARTER"}))
        @RequestParam(name = MiParameters.GRANULARITY, required = false) String granularity,
        @Parameter(name = MiParameters.INCLUDE_MIGRATED, description = MIGRATED_DOC,
            schema = @Schema(type = "boolean", defaultValue = "true"))
        @RequestParam(name = MiParameters.INCLUDE_MIGRATED, required = false) String includeMigrated) {
        return MiVolumeResponse.from(service.volume(admit(from, to, granularity, includeMigrated)));
    }

    @Operation(operationId = "getMiStageDurations", summary = "How long each stage took, per period",
        description = COMMON + "Durations are whole calendar days from date to date, each cast to a day before "
            + "subtracting, so 23:00 to 09:00 the next day is 1. Each interval is bucketed by the period its end "
            + "falls in, with count, medianDays and p95Days (percentile_cont, interpolated; published however small "
            + "count is, and null when it is 0). RECEIVED_TO_READY_FOR_MASTER is dateReceived to dateReadyForMaster; "
            + "READY_TO_REFERRED is dateReadyForMaster to dateReferred; REFERRED_TO_DECISION is dateReferred to the "
            + "latest non-null decisionDate of the case's referrals, or the case's own masterDateOfDecision when no "
            + "referral of it is decided (for every case, migrated or not); SIGNATURE_REQUESTED_TO_RECEIVED is "
            + "signatureRequestedDate to signaturerecieveddate; SIGNATURE_RECEIVED_TO_ENROLED is "
            + "signaturerecieveddate to dateEnroled. Not counted: an interval with either end missing, which is not "
            + "an interval and is not reported as excluded; an interval ending before it starts "
            + "(excluded.negativeInterval). There is no duration for Awaiting further documents: "
            + "awaitingFurtherDocsInfo records when documents were asked for, and nothing records when they arrived.")
    @GetMapping("/stage-durations")
    public MiStageDurationsResponse stageDurations(
        @Parameter(name = MiParameters.FROM, required = true, description = FROM_DOC,
            schema = @Schema(type = "string", format = "date"))
        @RequestParam(name = MiParameters.FROM, required = false) String from,
        @Parameter(name = MiParameters.TO, required = true, description = TO_DOC,
            schema = @Schema(type = "string", format = "date"))
        @RequestParam(name = MiParameters.TO, required = false) String to,
        @Parameter(name = MiParameters.GRANULARITY, required = true,
            description = GRANULARITY_DOC,
            schema = @Schema(type = "string", allowableValues = {"WEEK", "MONTH", "QUARTER"}))
        @RequestParam(name = MiParameters.GRANULARITY, required = false) String granularity,
        @Parameter(name = MiParameters.INCLUDE_MIGRATED, description = MIGRATED_DOC,
            schema = @Schema(type = "boolean", defaultValue = "true"))
        @RequestParam(name = MiParameters.INCLUDE_MIGRATED, required = false) String includeMigrated) {
        return MiStageDurationsResponse.from(service.stageDurations(admit(from, to, granularity, includeMigrated)));
    }

    @Operation(operationId = "getMiReferralOutcomes", summary = "Masters' decisions and applicants' confirmations, "
        + "per period",
        description = COMMON + "decisions counts referrals whose decisionDate falls in the period by decision "
            + "(Approved or Rejected), with how many of each were urgent. confirmations counts referrals whose "
            + "confirmationReceivedAt falls in the period by confirmationOutcome (Confirmed or Withdrawn: the "
            + "applicant's response to the Master's directions). Counts only; percentages are the reader's. Not "
            + "counted: staff withdrawing a referral, which no column dates; a decisionDate with no decision "
            + "(excluded.decisionDateWithoutDecision); a confirmationReceivedAt with no outcome "
            + "(excluded.confirmationWithoutOutcome).")
    @GetMapping("/referral-outcomes")
    public MiReferralOutcomesResponse referralOutcomes(
        @Parameter(name = MiParameters.FROM, required = true, description = FROM_DOC,
            schema = @Schema(type = "string", format = "date"))
        @RequestParam(name = MiParameters.FROM, required = false) String from,
        @Parameter(name = MiParameters.TO, required = true, description = TO_DOC,
            schema = @Schema(type = "string", format = "date"))
        @RequestParam(name = MiParameters.TO, required = false) String to,
        @Parameter(name = MiParameters.GRANULARITY, required = true,
            description = GRANULARITY_DOC,
            schema = @Schema(type = "string", allowableValues = {"WEEK", "MONTH", "QUARTER"}))
        @RequestParam(name = MiParameters.GRANULARITY, required = false) String granularity,
        @Parameter(name = MiParameters.INCLUDE_MIGRATED, description = MIGRATED_DOC,
            schema = @Schema(type = "boolean", defaultValue = "true"))
        @RequestParam(name = MiParameters.INCLUDE_MIGRATED, required = false) String includeMigrated) {
        return MiReferralOutcomesResponse.from(
            service.referralOutcomes(admit(from, to, granularity, includeMigrated)));
    }

    @Operation(operationId = "getMiCompletion", summary = "Time from receipt to completion, by intake cohort",
        description = COMMON + "A cohort is the period dateReceived falls in. Per cohort: received; completed (cases "
            + "with dateEnroled or dateReturned), split into enroled and returned; stillOpen (neither); and "
            + "daysToCompletion, calendar days from dateReceived to the completion date (count, medianDays, p95Days "
            + "as stage-durations publishes them). A recent cohort looks fast because its slow cases are still open, "
            + "so show stillOpen beside the percentiles. Not counted: a case with no dateReceived "
            + "(excluded.noDateReceived, over all time, migrated or not); a case with both dateEnroled and "
            + "dateReturned, a contradiction counted in none of completed, enroled, returned or stillOpen "
            + "(excluded.contradiction); and, in daysToCompletion only, a completion dated before receipt "
            + "(excluded.negativeInterval), which still counts as completed.")
    @GetMapping("/completion")
    public MiCompletionResponse completion(
        @Parameter(name = MiParameters.FROM, required = true, description = FROM_DOC,
            schema = @Schema(type = "string", format = "date"))
        @RequestParam(name = MiParameters.FROM, required = false) String from,
        @Parameter(name = MiParameters.TO, required = true, description = TO_DOC,
            schema = @Schema(type = "string", format = "date"))
        @RequestParam(name = MiParameters.TO, required = false) String to,
        @Parameter(name = MiParameters.GRANULARITY, required = true,
            description = GRANULARITY_DOC,
            schema = @Schema(type = "string", allowableValues = {"WEEK", "MONTH", "QUARTER"}))
        @RequestParam(name = MiParameters.GRANULARITY, required = false) String granularity,
        @Parameter(name = MiParameters.INCLUDE_MIGRATED, description = MIGRATED_DOC,
            schema = @Schema(type = "boolean", defaultValue = "true"))
        @RequestParam(name = MiParameters.INCLUDE_MIGRATED, required = false) String includeMigrated) {
        return MiCompletionResponse.from(service.completion(admit(from, to, granularity, includeMigrated)));
    }

    private MiParameters admit(String from, String to, String granularity, String includeMigrated) {
        return service.admit(CurrentUsers.required(), from, to, granularity, includeMigrated);
    }
}
