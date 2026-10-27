package uk.gov.hmcts.reform.deedpoll.api.caseworker.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import uk.gov.hmcts.reform.deedpoll.entity.Referral;
import uk.gov.hmcts.reform.deedpoll.entity.enums.ConfirmationOutcome;
import uk.gov.hmcts.reform.deedpoll.entity.enums.MasterDecision;
import uk.gov.hmcts.reform.deedpoll.entity.enums.ReferralState;
import uk.gov.hmcts.reform.deedpoll.entity.enums.Urgency;

import java.time.LocalDateTime;
import java.util.List;

/**
 * One referral of a case to the judiciary, as the caseworker surface publishes it.
 *
 * <p>Every column of {@code deedpoll_referral} except the foreign key and {@code lastTouchedAt}. The foreign key
 * is left out for the reason {@link NoteResponse} gives: the caller named the case in the path, and a referral
 * carrying its case -- which carries its referrals -- is a cycle a serialiser has to be told about.
 *
 * <p><strong>It does not restate the application's own data.</strong> The case detail endpoint already carries
 * the applicant, the dates and the legacy flat referral columns, so a referral read is one round trip for the
 * referral and not a second copy of the case that would then have two versions of the truth. The referrals tab
 * has the case detail already; the judicial read surface, which does not, is a later plan and gets its own
 * view rather than widening this one.
 *
 * <p><strong>The confirmation columns are published, all four of them.</strong> They are what the referrals tab
 * shows after the office has asked the applicant whether to proceed: the date is how long the case has been
 * waiting, the outcome is the answer or its absence, and the last two are who recorded it and when. They are
 * caseworker fields only -- the judicial surface has its own view types and none of them carries these, because
 * the exchange is between the office and the applicant and says nothing about the judge's direction.
 *
 * <p><strong>The claim and the direction are published; {@code lastTouchedAt} is not.</strong> A caseworker
 * needs both: {@code claimedBy} and {@code claimedAt} are what answers "has the judiciary picked this up, and
 * how long have they had it", and the five direction fields are what they read before they action the direction
 * and close the referral -- a closure endpoint that answered with a referral not carrying its own direction
 * would cost a second read to render the screen the caseworker just came from. {@code lastTouchedAt} is left
 * out because it is the inactivity sweep's bookkeeping rather than a fact about the referral: it moves every
 * time a judge opens the page, and a caseworker reading it as "when the judge last worked on this" would be
 * reading a claim it does not make.
 *
 * <p><strong>{@code reasons} is published as the stored strings</strong>, which are
 * {@code ReferralReason} names, in the order the caseworker ticked them -- the same shape
 * {@link SavedQueryResponse#conditions()} publishes and for the same reason: what a client posted is what it
 * reads back, with nothing renamed in between. Strings rather than the enum because the column is a JSON
 * array validated on write, so a backfilled or hand-corrected row is published as it stands rather than
 * failing to deserialise on the way out. It is <strong>empty for a migrated referral</strong> -- the source
 * store had no such field -- which is what {@code migrated} exists to explain.
 *
 * @param id the referral id, which the get and the withdrawal sub-resource address
 * @param state where the referral has got to; {@code Closed} and {@code Withdrawn} are terminal, and at most
 *     one referral of a case is in a non-terminal state
 * @param reasons why the case was referred, as stored; empty exactly when {@code migrated} is true
 * @param notes the single free-text field -- the explanation {@code Other} requires and the general referral
 *     note at once -- or null
 * @param urgency the referral's own urgency, which may differ from the case's
 * @param urgencyReason why it jumped the queue, required when {@code urgency} is {@code Urgent} and otherwise
 *     null
 * @param migrated whether the referral was seeded from the case's legacy flat columns rather than raised
 *     in this service, which is why it may carry no reasons
 * @param claimedBy the judge currently holding it, or null -- which is every referral that is not
 *     {@code Claimed}, because releasing a claim clears the column rather than leaving the last holder behind
 * @param claimedAt when they picked it up, or null. When they last interacted with it is deliberately not
 *     published; see above
 * @param decision the direction, or null until one is recorded. Non-null exactly for a {@code Decided} or
 *     {@code Closed} referral, and for a migrated one only if the case's snapshot carried one
 * @param directionsText what the judge directed, or null -- including on a referral that has a decision, since
 *     an approval needing no comment is a real answer
 * @param changesToStandardPublication the Master's answer to "Are there any changes to the standard publications in
 *     the Gazette?": true for do not publish, or publish only as {@code directionsText} says; false for publish as
 *     standard. A boxed {@link Boolean} because absent and false are different answers: a referral with no
 *     direction has not been asked. Never derived from a {@code NonPublication} referral reason
 * @param publishToGazette the same answer under its pre-2026-10-27 name and inverted, for one release, so the admin
 *     deployed beside this api reads it as it always has. Deprecated in the document; removed once that admin is
 *     gone
 * @param decisionDate when the direction was recorded, or null
 * @param decidedBy the judge who recorded it, which is not necessarily {@code modifiedBy} -- the caseworker who
 *     closes the referral afterwards becomes that
 * @param directionChangedAt when the judge last revised or withdrew their direction, or null if it stands as first
 *     given. The flag that the direction to action is not the original one; never cleared, and the earlier values
 *     are in the audit trail
 * @param confirmationRequestedAt when the applicant was asked whether they still want the deed enrolled, or null
 *     -- which is every referral that was never asked. Stamped as the confirmation email is owed, in the same
 *     transaction as the outbox row, so it is when the office asked rather than when Notify accepted
 * @param confirmationOutcome what the applicant answered, or null while the reply is outstanding. Null is the
 *     whole of "still waiting", which is why there is no member for it
 * @param confirmationReceivedAt when a caseworker recorded that answer, or null. Not when the applicant sent it:
 *     nothing here knows that
 * @param confirmationRecordedBy the caseworker who recorded it, from their token and never client-supplied
 * @param createdBy the caseworker who raised it, or the service account for a migrated one
 * @param modifiedBy whoever last moved it -- the caseworker who withdrew or closed it, the judge who claimed or
 *     decided it, or the service account where the inactivity sweep took a claim back
 * @param createdOn when it was raised
 * @param modifiedOn when it last changed state; equal to createdOn for a referral still as raised
 */
public record ReferralResponse(
    String id,
    ReferralState state,
    List<String> reasons,
    String notes,
    Urgency urgency,
    String urgencyReason,
    boolean migrated,
    String claimedBy,
    LocalDateTime claimedAt,
    MasterDecision decision,
    String directionsText,
    @Schema(nullable = true, description = "The Master's answer to \"Are there any changes to the standard "
        + "publications in the Gazette?\": true for do not publish, or publish only as directionsText says; false for "
        + "publish as standard; null until a direction exists.")
    Boolean changesToStandardPublication,
    @Schema(nullable = true, deprecated = true, description = "changesToStandardPublication inverted, under the "
        + "name it had before 2026-10-27. Kept for one release; read changesToStandardPublication.")
    Boolean publishToGazette,
    LocalDateTime decisionDate,
    String decidedBy,
    @Schema(nullable = true, description = "When the judge last revised or withdrew their direction, or null if "
        + "it stands as first given. Set by both and never cleared: a fact about the referral's history, and the "
        + "earlier direction is on the audit trail.")
    LocalDateTime directionChangedAt,
    LocalDateTime confirmationRequestedAt,
    ConfirmationOutcome confirmationOutcome,
    LocalDateTime confirmationReceivedAt,
    String confirmationRecordedBy,
    String createdBy,
    String modifiedBy,
    LocalDateTime createdOn,
    LocalDateTime modifiedOn) {

    /** One stored referral as the response carries it. */
    public static ReferralResponse from(Referral referral) {
        return new ReferralResponse(
            referral.getId().toString(),
            referral.getState(),
            List.copyOf(referral.getReasons()),
            referral.getNotes(),
            referral.getUrgency(),
            referral.getUrgencyReason(),
            referral.isMigrated(),
            referral.getClaimedBy(),
            referral.getClaimedAt(),
            referral.getDecision(),
            referral.getDirectionsText(),
            referral.getChangesToStandardPublication(),
            legacyPublishToGazette(referral),
            referral.getDecisionDate(),
            referral.getDecidedBy(),
            referral.getDirectionChangedAt(),
            referral.getConfirmationRequestedAt(),
            referral.getConfirmationOutcome(),
            referral.getConfirmationReceivedAt(),
            referral.getConfirmationRecordedBy(),
            referral.getCreatedBy(),
            referral.getModifiedBy(),
            referral.getCreatedOn(),
            referral.getModifiedOn());
    }

    /** A case's referrals, preserving the order the domain fetched them in -- newest first. */
    public static List<ReferralResponse> from(List<Referral> referrals) {
        return referrals.stream().map(ReferralResponse::from).toList();
    }

    /**
     * {@code changesToStandardPublication} inverted, null staying null: the answer as the old name meant it.
     */
    private static Boolean legacyPublishToGazette(Referral referral) {
        Boolean changes = referral.getChangesToStandardPublication();
        return changes == null ? null : !changes;
    }
}
