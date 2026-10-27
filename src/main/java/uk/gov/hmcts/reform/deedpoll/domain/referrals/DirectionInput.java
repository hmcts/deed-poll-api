package uk.gov.hmcts.reform.deedpoll.domain.referrals;

import uk.gov.hmcts.reform.deedpoll.entity.enums.MasterDecision;

import java.util.Optional;

/**
 * A judge's direction on a referral, already validated: what they decided, what they directed, and whether
 * anything changes about the standard publication in the London Gazette.
 *
 * <p><strong>Not a port.</strong> The source store had no direction record -- a Master replied by email and a
 * caseworker typed the answer into the case's flat {@code masterDecision} / {@code masterDirections} /
 * {@code publishToLondonGazette} columns. Those columns are still on {@code DeedPoll} and still the
 * pre-migration snapshot; nothing here writes them.
 *
 * <p>{@link MasterDecision} is reused rather than a referral-specific vocabulary being minted. It is the
 * ported decision vocabulary, it is already published on the case DTOs, and the referral's own column is the
 * same Postgres enum type -- so a second Approved/Rejected enum would be two names for one fact.
 *
 * <p><strong>One complete direction, not a draft.</strong> There is nothing on this record a judge can fill in
 * later: the check-your-answers page and the "Signed" attestation are the admin's, and this service receives
 * the confirmed answer once and records it atomically. So there is no partial shape to represent and no patch
 * to apply -- which is also why, unlike a case, a referral's direction has no update sibling.
 *
 * <p>Only reachable through {@link ReferralSchemas#parseDirection}, which is what makes "unknown keys are
 * stripped" structural rather than a rule to remember. Three things in particular have nowhere to land:
 *
 * <ul>
 *   <li><strong>The referral and its case</strong> -- both are the path, so a judge cannot direct a referral
 *       other than the one they addressed.</li>
 *   <li><strong>The state</strong> -- recording a direction moves the referral to {@code Decided} and nothing
 *       else does. A body that could name {@code Closed} would let a judge close their own referral, which is
 *       the caseworker's acknowledgement to make.</li>
 *   <li><strong>{@code decidedBy} and {@code decisionDate}</strong> -- both come from the token and the clock.
 *       A direction that could name its own author would be a signature anybody could forge.</li>
 * </ul>
 *
 * @param decision the direction itself, required -- there is no third answer and no "not yet"
 * @param directionsText what the judge directed, absent where they sent none or sent only whitespace. Optional on
 *     a standard publication, because an approval that needs no comment is a real answer; required when
 *     {@code changesToStandardPublication} is true, because then it is the only place that says what changes
 * @param changesToStandardPublication the Master's answer to "Are there any changes to the standard publications
 *     in the Gazette?": true for do not publish, or publish only as the direction text says (the last name only,
 *     for example), false for publish as standard. <strong>Required, and the judge's to decide</strong> -- never
 *     derived from a caseworker's {@code NonPublication} referral reason or from any other field. A primitive
 *     rather than an {@link Optional} because a direction that did not answer it is not a direction; the column is
 *     nullable for the referrals that have no direction at all
 */
public record DirectionInput(
    MasterDecision decision,
    Optional<String> directionsText,
    boolean changesToStandardPublication) {
}
