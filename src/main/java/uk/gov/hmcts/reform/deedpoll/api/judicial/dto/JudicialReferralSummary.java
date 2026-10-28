package uk.gov.hmcts.reform.deedpoll.api.judicial.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import uk.gov.hmcts.reform.deedpoll.domain.referrals.DirectionDue;
import uk.gov.hmcts.reform.deedpoll.entity.Referral;
import uk.gov.hmcts.reform.deedpoll.entity.enums.ReferralState;
import uk.gov.hmcts.reform.deedpoll.entity.enums.Urgency;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * One row of a judge's list of their own referrals (and of the former shared pool, empty since plan D2): enough to
 * choose which referral to open, and nothing about the application behind it.
 *
 * <p><strong>No case fields at all, which is the difference between this and a case list row.</strong> A judge
 * choosing their next piece of work is choosing between <em>referrals</em> -- why each was raised, how urgent it
 * is and how long it has waited -- and a pool row carrying the applicant's names would make the pool a list of
 * applications a judge had not been asked to see. Reaching the application is the detail read, which runs the
 * visibility check; the pool is a queue, so {@code CaseSummaryResponse} is not merely the wrong type here, it is
 * the wrong shape.
 *
 * <p>{@code createdOn} is published rather than an age in days. Age is what a judge reads -- "waiting eleven
 * days" -- but rendering is the consuming service's job and a computed age cannot be sorted, compared or
 * re-rendered tomorrow, which is the same reason the case list publishes raw dates.
 *
 * <p>{@code state} is published even though the pool only ever contains {@code Open} referrals. It is what makes
 * a row self-describing when this same type carries a judge's own claimed referral later, and a client that had
 * to infer the state from the endpoint it called would be inferring it from the wrong thing.
 *
 * <p>{@code urgencyReason} travels with {@code urgency} because the urgency band is the pool's first sort key and
 * a band with no reason is half a fact: two urgent referrals in front of a judge are ordered by age, and why one
 * of them jumped the queue is what tells them whether that order is right. Part of the referral's own confirmed
 * field set (decision 8) rather than an addition to it -- it is the referral speaking about itself, not the
 * application.
 *
 * @param id the referral id, which the detail, claim and direction routes address
 * @param state where the referral has got to: {@code Claimed} waiting for this Master's direction, or
 *     {@code Decided}
 * @param reasons why the case was referred, as stored, in the order the caseworker ticked them; empty for a
 *     referral backfilled from the case's legacy columns
 * @param notes the caseworker's free text for the judiciary, or null
 * @param urgency the referral's own urgency, which is the pool's first sort key
 * @param urgencyReason why it jumped the queue, or null when it did not
 * @param createdOn when the referral was raised, which is the second sort key -- oldest first within a band
 * @param assignedAt when the referral was assigned to this Master, which starts the processing window, or null
 * @param directionDueBy when the processing window ends while the referral waits for a direction, else null
 */
public record JudicialReferralSummary(
    String id,
    ReferralState state,
    List<String> reasons,
    String notes,
    Urgency urgency,
    String urgencyReason,
    LocalDateTime createdOn,
    @Schema(nullable = true, description = "When the referral was assigned to this Master, which starts the "
        + "processing window; null for a claim made before assignment existed.")
    LocalDateTime assignedAt,
    @Schema(nullable = true, description = "When the Master's direction is due: the processing window's start "
        + "plus its length as configured now. Null once directed.")
    LocalDateTime directionDueBy) {

    /** One referral as a row. */
    public static JudicialReferralSummary from(Referral referral, Duration processingWindow) {
        return new JudicialReferralSummary(
            referral.getId().toString(),
            referral.getState(),
            List.copyOf(referral.getReasons()),
            referral.getNotes(),
            referral.getUrgency(),
            referral.getUrgencyReason(),
            referral.getCreatedOn(),
            referral.getAssignedAt(),
            DirectionDue.by(referral, processingWindow));
    }

    /**
     * A page, preserving the order the database produced.
     *
     * <p>The order is not re-established here, and must not be: it is the query's, so a sort at this layer would
     * only be able to reorder the page it was handed rather than the list it came from.
     */
    public static List<JudicialReferralSummary> from(List<Referral> referrals, Duration processingWindow) {
        return referrals.stream().map(referral -> from(referral, processingWindow)).toList();
    }
}
