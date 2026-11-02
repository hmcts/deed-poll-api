package uk.gov.hmcts.reform.deedpoll.domain.referrals;

import uk.gov.hmcts.reform.deedpoll.entity.enums.Urgency;

import java.util.List;
import java.util.Optional;

/**
 * Create payload for a referral to the judiciary, already validated.
 *
 * <p><strong>Not a port</strong> -- referrals are new behaviour, so unlike {@code CreateNoteInput} and
 * {@code CreateWitnessInput} there is no {@code reference/ts} module behind this and none to look for.
 *
 * <p>Only reachable through {@link ReferralSchemas#parseCreateReferral}, which is what makes "unknown keys
 * are stripped" structural rather than a rule someone has to remember. Two things in particular have
 * nowhere to land, and each is a rule rather than an omission:
 *
 * <ul>
 *   <li><strong>The case</strong> -- the parent is the path, as it is for a note or a witness, so a caller
 *       cannot post a referral onto a case other than the one they addressed.</li>
 *   <li><strong>The state</strong> -- a created referral is {@code Open}, and every later state is reached
 *       through a transition that has its own guard. A body that could name {@code Closed} would let a
 *       caller skip assignment entirely.</li>
 * </ul>
 *
 * <p><strong>No Master either</strong> (plan F1, decision F-1). Plan D2 let the caseworker raising a referral name
 * one; F1 takes that back, so every referral waits {@code Open} in the queue until a Team leader or Delivery Manager
 * sends it, and a {@code masterId} in the body is stripped as any unknown key is.
 *
 * <p>Types, not wire strings, which is the one place this differs from {@code CreateSavedQueryInput}: a
 * saved query stores the strings it was sent, so its input carries them through unparsed, whereas a
 * referral's reasons are an enum in the domain and only their names are stored. So the parse result is the
 * enum, and {@code ReferralService} names them on the way to the column.
 *
 * @param reasons at least one {@link ReferralReason}, de-duplicated, in the order the caller sent them
 * @param notes the single free-text field -- the explanation {@link ReferralReason#Other} requires and the
 *     general referral note at once -- absent where the caller sent none or sent only whitespace
 * @param urgency the referral's own urgency, defaulting to {@link Urgency#Normal}. Separate from the case's,
 *     and a primitive value rather than an {@link Optional} because the column has a default and no null
 * @param urgencyReason why it jumped the queue, required when {@code urgency} is {@link Urgency#Urgent}
 */
public record CreateReferralInput(
    List<ReferralReason> reasons,
    Optional<String> notes,
    Urgency urgency,
    Optional<String> urgencyReason) {

    public CreateReferralInput {
        reasons = List.copyOf(reasons);
    }
}
