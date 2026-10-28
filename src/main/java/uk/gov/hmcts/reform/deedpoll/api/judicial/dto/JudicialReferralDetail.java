package uk.gov.hmcts.reform.deedpoll.api.judicial.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import uk.gov.hmcts.reform.deedpoll.domain.referrals.DirectionDue;
import uk.gov.hmcts.reform.deedpoll.domain.referrals.JudicialReferralView;
import uk.gov.hmcts.reform.deedpoll.entity.Referral;
import uk.gov.hmcts.reform.deedpoll.entity.enums.MasterDecision;
import uk.gov.hmcts.reform.deedpoll.entity.enums.ReferralState;
import uk.gov.hmcts.reform.deedpoll.entity.enums.Urgency;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * One referral as the Master who may see it reads it: the referral and the application with its readable
 * documents. Since 2026-10-27 (Master Sullivan's feedback) it carries no case notes: the Masters asked for the
 * referral, the application's names and its documents, and nothing of the office's timeline.
 *
 * <p>One response for what is one screen. A judge opening a referral is deciding what to direct, and the reasons
 * it was raised, the names it is about and what the office has recorded are all inputs to that -- so they arrive
 * together rather than as separate calls each having to repeat the visibility check.
 *
 * <p><strong>{@code notes} is the referral's free text</strong>, the column a caseworker fills in for the Master -- the
 * same field {@code ReferralResponse} publishes under that name. It is not the application's case notes, which this
 * response does not carry.
 *
 * <p><strong>The claim and the decision columns are published, and they cannot name another judge.</strong>
 * {@code claimedBy} and {@code decidedBy} are only ever this caller or null by the time a response gets here:
 * visibility admits a {@code Claimed} or {@code Decided} referral only when the caller is the judge on it. So
 * publishing them tells a judge which of their own referrals this is
 * without being able to tell them who else holds anything.
 *
 * <p><strong>No {@code createdBy}, {@code modifiedBy} or {@code modifiedOn}, and no {@code lastTouchedAt}.</strong>
 * The first three are the internal audit trail the judicial view excludes -- which caseworker raised the referral and
 * who last saved the row is not an input to a direction -- and {@code lastTouchedAt} is the inactivity sweep's
 * bookkeeping, which the caseworker response leaves out for the same reason. {@code createdOn} stays, because how
 * long a referral has waited is a fact about the referral that a judge is entitled to weigh.
 *
 * @param id the referral id, which the claim and direction routes address
 * @param state where the referral has got to -- {@code Claimed} or {@code Decided}, because only this judge's are
 *     visible
 * @param reasons why the case was referred, as stored, in the order the caseworker ticked them; empty for a
 *     backfilled referral
 * @param notes the caseworker's free text for the judiciary, or null
 * @param urgency the referral's own urgency
 * @param urgencyReason why it jumped the queue, or null when it did not
 * @param createdOn when the referral was raised, which is how long it has waited
 * @param claimedBy the judge holding it, which is this caller or null -- never another judge
 * @param claimedAt when they picked it up, or null
 * @param assignedAt when it was assigned to this Master, which starts the processing window, or null
 * @param directionDueBy when the processing window ends while it waits for a direction, else null
 * @param decision the direction, or null until this judge records one
 * @param directionsText what was directed, or null -- including on a decision that needed no comment
 * @param changesToStandardPublication the Master's answer to "Are there any changes to the standard publications in
 *     the Gazette?": true for do not publish, or publish only as {@code directionsText} says; false for publish as
 *     standard. A boxed {@link Boolean} because absent and false are different answers: a referral with no
 *     direction has not been asked. Never derived from a {@code NonPublication} referral reason
 * @param decisionDate when the direction was recorded, or null
 * @param decidedBy who recorded it, which is this caller or null
 * @param directionChangedAt when this judge last revised or withdrew their direction, or null if it stands as
 *     first given. Never cleared, so a withdrawn direction keeps it while the referral is claimed again
 * @param application the judicial view of the case, with its readable documents, resolved from the referral
 *     rather than named by the caller
 */
public record JudicialReferralDetail(
    String id,
    ReferralState state,
    List<String> reasons,
    String notes,
    Urgency urgency,
    String urgencyReason,
    LocalDateTime createdOn,
    String claimedBy,
    LocalDateTime claimedAt,
    @Schema(nullable = true, description = "When the referral was assigned to this Master, which starts the "
        + "processing window; null for a claim made before assignment existed.")
    LocalDateTime assignedAt,
    @Schema(nullable = true, description = "When the Master's direction is due: the processing window's start "
        + "plus its length as configured now. Null once directed.")
    LocalDateTime directionDueBy,
    MasterDecision decision,
    String directionsText,
    @Schema(nullable = true, description = "The Master's answer to \"Are there any changes to the standard "
        + "publications in the Gazette?\": true for do not publish, or publish only as directionsText says; false for "
        + "publish as standard; null until a direction exists.")
    Boolean changesToStandardPublication,
    LocalDateTime decisionDate,
    String decidedBy,
    @Schema(nullable = true, description = "When the judge last revised or withdrew their direction, or null if "
        + "it stands as first given. Set by both and never cleared: a fact about the referral's history, and the "
        + "earlier direction is on the audit trail.")
    LocalDateTime directionChangedAt,
    @Schema(description = "The application behind the referral, resolved from it: the names, type, nationality, a "
        + "minor's parents and its readable documents. Never a case id, contact details or case notes.")
    JudicialApplicationView application) {

    /** The referral and its application, as the domain resolved them for this Master. */
    public static JudicialReferralDetail of(JudicialReferralView view, Duration processingWindow) {
        Referral referral = view.referral();
        return new JudicialReferralDetail(
            referral.getId().toString(),
            referral.getState(),
            List.copyOf(referral.getReasons()),
            referral.getNotes(),
            referral.getUrgency(),
            referral.getUrgencyReason(),
            referral.getCreatedOn(),
            referral.getClaimedBy(),
            referral.getClaimedAt(),
            referral.getAssignedAt(),
            DirectionDue.by(referral, processingWindow),
            referral.getDecision(),
            referral.getDirectionsText(),
            referral.getChangesToStandardPublication(),
            referral.getDecisionDate(),
            referral.getDecidedBy(),
            referral.getDirectionChangedAt(),
            JudicialApplicationView.from(view.application(), view.documents()));
    }
}
