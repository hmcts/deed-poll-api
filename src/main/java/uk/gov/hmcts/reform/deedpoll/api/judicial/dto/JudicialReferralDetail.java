package uk.gov.hmcts.reform.deedpoll.api.judicial.dto;

import uk.gov.hmcts.reform.deedpoll.domain.referrals.JudicialReferralView;
import uk.gov.hmcts.reform.deedpoll.entity.Referral;
import uk.gov.hmcts.reform.deedpoll.entity.enums.MasterDecision;
import uk.gov.hmcts.reform.deedpoll.entity.enums.ReferralState;
import uk.gov.hmcts.reform.deedpoll.entity.enums.Urgency;

import java.time.LocalDateTime;
import java.util.List;

/**
 * One referral as the judge who may see it reads it: the referral, the narrow application view, and the
 * application's timeline.
 *
 * <p>One response for what is one screen. A judge opening a referral is deciding what to direct, and the reasons
 * it was raised, the names it is about and what the office has recorded are all inputs to that -- so they arrive
 * together rather than as three calls each having to repeat the visibility check.
 *
 * <p><strong>{@code notes} is the referral's free text and {@code caseNotes} is the application's timeline.</strong>
 * Two different things with confusable names, and the names are the ones each already has: {@code notes} is the
 * referral column a caseworker fills in for the judiciary -- the same field {@code ReferralResponse} publishes
 * under that name -- and {@code caseNotes} is the notes grid. Renaming either to disambiguate would make one of
 * them disagree with the resource it belongs to.
 *
 * <p><strong>The claim and the decision columns are published, and they cannot name another judge.</strong>
 * {@code claimedBy} and {@code decidedBy} are only ever this caller or null by the time a response gets here:
 * visibility admits an {@code Open} referral, which has no holder, and a {@code Claimed} or {@code Decided} one
 * only when the caller is the judge on it. So publishing them tells a judge which of their own referrals this is
 * without being able to tell them who else holds anything.
 *
 * <p><strong>No {@code createdBy}, {@code modifiedBy} or {@code modifiedOn}, and no {@code lastTouchedAt}.</strong>
 * The first three are the internal audit trail decision 8 excludes -- which caseworker raised the referral and
 * who last saved the row is not an input to a direction -- and {@code lastTouchedAt} is the inactivity sweep's
 * bookkeeping, which the caseworker response leaves out for the same reason. {@code createdOn} stays, because how
 * long a referral has waited is a fact about the referral that a judge is entitled to weigh.
 *
 * @param id the referral id, which the claim and direction routes address
 * @param state where the referral has got to -- {@code Open} in the pool, or {@code Claimed}/{@code Decided} when
 *     it is this judge's
 * @param reasons why the case was referred, as stored, in the order the caseworker ticked them; empty for a
 *     backfilled referral
 * @param notes the caseworker's free text for the judiciary, or null
 * @param urgency the referral's own urgency
 * @param urgencyReason why it jumped the queue, or null when it did not
 * @param createdOn when the referral was raised, which is how long it has waited
 * @param claimedBy the judge holding it, which is this caller or null -- never another judge
 * @param claimedAt when they picked it up, or null
 * @param decision the direction, or null until this judge records one
 * @param directionsText what was directed, or null -- including on a decision that needed no comment
 * @param publishToGazette whether publication in the London Gazette was directed; null until a direction exists,
 *     because absent and false are different answers
 * @param decisionDate when the direction was recorded, or null
 * @param decidedBy who recorded it, which is this caller or null
 * @param application the narrow judicial view of the case, resolved from the referral rather than named by the
 *     caller
 * @param caseNotes the application's notes, newest first, read-only
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
    MasterDecision decision,
    String directionsText,
    Boolean publishToGazette,
    LocalDateTime decisionDate,
    String decidedBy,
    JudicialCaseView application,
    List<JudicialCaseNote> caseNotes) {

    /** The referral, its application and its timeline, as the domain resolved them for this judge. */
    public static JudicialReferralDetail of(JudicialReferralView view) {
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
            referral.getDecision(),
            referral.getDirectionsText(),
            referral.getPublishToGazette(),
            referral.getDecisionDate(),
            referral.getDecidedBy(),
            JudicialCaseView.from(view.application()),
            JudicialCaseNote.from(view.caseNotes()));
    }
}
