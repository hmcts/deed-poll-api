package uk.gov.hmcts.reform.deedpoll.api.caseworker.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import uk.gov.hmcts.reform.deedpoll.domain.referrals.DirectionDue;
import uk.gov.hmcts.reform.deedpoll.entity.DeedPoll;
import uk.gov.hmcts.reform.deedpoll.entity.Referral;
import uk.gov.hmcts.reform.deedpoll.entity.enums.DeedPollType;
import uk.gov.hmcts.reform.deedpoll.entity.enums.Nationality;
import uk.gov.hmcts.reform.deedpoll.entity.enums.ReferralState;
import uk.gov.hmcts.reform.deedpoll.entity.enums.Urgency;
import uk.gov.hmcts.reform.deedpoll.entity.enums.WindowSlot;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * One referral in the queue (plan F1): what a Team leader or Delivery Manager compares before sending it to a
 * Master, and, once sent, where it went.
 *
 * <p>Its own type, not {@link ReferralResponse} and never a judicial DTO: the queue spans cases, so each row carries
 * its case's id and reference and the applicant's names, type and nationality, and nothing else of the case -- no
 * contact details. Never a subject or an email address: the Master is {@link AssignedMasterResponse}.
 */
public record ReferralQueueItem(
    String id,
    ReferralState state,
    @Schema(description = "The case the referral was raised on, which the caseworker case routes address.")
    String caseId,
    String displayReference,
    String newForename,
    String newMiddleName,
    String newSurname,
    String oldForename,
    String oldMiddleName,
    String oldSurname,
    DeedPollType type,
    Nationality nationality,
    @Schema(description = "Why the case was referred, as the stored ReferralReason names; empty for a migrated "
        + "referral.")
    List<String> reasons,
    Urgency urgency,
    String urgencyReason,
    @Schema(description = "Who raised the referral.")
    String createdBy,
    @Schema(description = "When the referral was raised; the queue's order after urgency.")
    LocalDateTime createdOn,
    @Schema(nullable = true, description = "The Master it was sent to, as the Masters-list entry's id and the name "
        + "it was sent under. Null while it is waiting to be sent.")
    AssignedMasterResponse assignedMaster,
    @Schema(nullable = true, description = "When it was sent or last reassigned, which starts the Master's "
        + "processing window. Null while it is waiting to be sent.")
    LocalDateTime assignedAt,
    @Schema(nullable = true, description = "The working day, in Europe/London, of the window it was sent in. Null "
        + "while it is waiting to be sent.")
    LocalDate windowDate,
    @Schema(nullable = true, description = "The morning (AM) or afternoon (PM) of windowDate.")
    WindowSlot windowSlot,
    @Schema(nullable = true, description = "When it returns to the queue if the Master has given no direction. Only "
        + "while it is Claimed; null otherwise.")
    LocalDateTime directionDueBy) {

    /**
     * One queued referral, its case loaded.
     *
     * @param processingWindow how long a Master has to give a direction, as set now
     */
    public static ReferralQueueItem from(Referral referral, Duration processingWindow) {
        DeedPoll application = referral.getDeedPoll();
        return new ReferralQueueItem(
            referral.getId().toString(),
            referral.getState(),
            application.getId(),
            application.getDisplayReference(),
            application.getNewForename(),
            application.getNewMiddleName(),
            application.getNewSurname(),
            application.getOldForename(),
            application.getOldMiddleName(),
            application.getOldSurname(),
            application.getType(),
            application.getNationality(),
            List.copyOf(referral.getReasons()),
            referral.getUrgency(),
            referral.getUrgencyReason(),
            referral.getCreatedBy(),
            referral.getCreatedOn(),
            referral.getAssignedMasterId() == null ? null
                : new AssignedMasterResponse(referral.getAssignedMasterId().toString(), referral.getClaimedBy()),
            referral.getAssignedAt(),
            referral.getWindowDate(),
            referral.getWindowSlot(),
            DirectionDue.by(referral, processingWindow));
    }

    /** The rows in the order the database gave them. */
    public static List<ReferralQueueItem> from(List<Referral> referrals, Duration processingWindow) {
        return referrals.stream().map(referral -> from(referral, processingWindow)).toList();
    }
}
