package uk.gov.hmcts.reform.deedpoll.domain.referrals;

import uk.gov.hmcts.reform.deedpoll.entity.enums.ConfirmationOutcome;

import java.util.Optional;

/**
 * The applicant's answer to {@code REQUEST_CONFIRMATION_TO_PROCEED}, as a caseworker records it: what they said,
 * and anything the caseworker wants the case to say about it.
 *
 * <p><strong>Not a port.</strong> The TypeScript store neither sent that email nor recorded a reply, so there is
 * no {@code reference/ts} schema behind this and none to look for -- the same position {@link DirectionInput} and
 * {@link CreateReferralInput} are in.
 *
 * <p>Only reachable through {@link ReferralSchemas#parseConfirmation}, which is what makes "unknown keys are
 * stripped" structural rather than a rule to remember. Four things in particular have nowhere to land, and each
 * absence is a decision:
 *
 * <ul>
 *   <li><strong>The referral and its case</strong> -- both are the path, so a caseworker cannot record an answer
 *       against a referral other than the one they addressed, and the case-scoped lookup is what makes another
 *       case's referral a 404.</li>
 *   <li><strong>{@code confirmationRecordedBy}</strong> -- it comes from the token. An author a body could name
 *       is an attribution anybody could forge, which is the rule every author in this service follows.</li>
 *   <li><strong>{@code confirmationReceivedAt}</strong> -- from the clock, and it is when the caseworker recorded
 *       the answer rather than when the applicant sent it. Nothing here knows the latter, so a field for it would
 *       invite a guess to be written down as a fact.</li>
 *   <li><strong>Anything about the case's dates.</strong> Recording an answer does not set {@code dateEnroled},
 *       clear {@code dateReturned} or otherwise reach into the date cascade; the caseworker then does whatever
 *       the process requires through the ordinary case edit, audited as their action rather than as a side effect
 *       of this one.</li>
 * </ul>
 *
 * @param outcome what the applicant said, required -- there is no member for "no reply yet", because silence is
 *     the absence of an outcome and that is a null column rather than a value somebody records
 * @param note what the caseworker wants the case timeline to say about the reply, absent where they sent none or
 *     sent only whitespace. Optional because the outcome is the fact and a required field whose usual value is
 *     empty is a field every caller fills in without reading
 */
public record ConfirmationInput(ConfirmationOutcome outcome, Optional<String> note) {
}
