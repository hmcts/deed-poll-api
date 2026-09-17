package uk.gov.hmcts.reform.deedpoll.api.caseworker.dto;

import uk.gov.hmcts.reform.deedpoll.entity.Referral;
import uk.gov.hmcts.reform.deedpoll.entity.enums.ReferralState;
import uk.gov.hmcts.reform.deedpoll.entity.enums.Urgency;

import java.time.LocalDateTime;
import java.util.List;

/**
 * One referral of a case to the judiciary, as the caseworker surface publishes it.
 *
 * <p>Every column of {@code deedpoll_referral} except the foreign key, for the reason {@link NoteResponse}
 * gives: the caller named the case in the path, and a referral carrying its case -- which carries its
 * referrals -- is a cycle a serialiser has to be told about.
 *
 * <p><strong>It does not restate the application's own data.</strong> The case detail endpoint already carries
 * the applicant, the dates and the legacy flat referral columns, so a referral read is one round trip for the
 * referral and not a second copy of the case that would then have two versions of the truth. The referrals tab
 * has the case detail already; the judicial read surface, which does not, is a later plan and gets its own
 * view rather than widening this one.
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
 * @param createdBy the caseworker who raised it, or the service account for a migrated one
 * @param modifiedBy the caseworker who last moved it -- who withdrew it, where it was withdrawn
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
            referral.getCreatedBy(),
            referral.getModifiedBy(),
            referral.getCreatedOn(),
            referral.getModifiedOn());
    }

    /** A case's referrals, preserving the order the domain fetched them in -- newest first. */
    public static List<ReferralResponse> from(List<Referral> referrals) {
        return referrals.stream().map(ReferralResponse::from).toList();
    }
}
