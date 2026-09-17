package uk.gov.hmcts.reform.deedpoll.domain.referrals;

/**
 * Why a case is being referred to the judiciary. A referral carries one or more.
 *
 * <p><strong>A domain enum, and deliberately not a database type.</strong> {@code ReferralState} is a
 * Postgres enum because every guard queries it; these are stored as a JSON array of names in
 * {@code "deedpoll_referral"."reasons"}, exactly as a saved query stores its conditions, and are
 * validated on write against this enum. That is why this lives here rather than in
 * {@code entity.enums} -- nothing in the schema refers to it, and adding a member is a code change
 * with no migration.
 *
 * <p>Names are the wire form and the stored form at once, so renaming one silently reinterprets every
 * row already written. Add members; do not rename them.
 *
 * <p>{@link #Other} is the one member with a rule attached: it requires the referral's {@code notes},
 * which is the single free-text field rather than an explanation column of its own.
 *
 * <p>{@link #Minor} is a manually selectable reason like any other. Every minor application is
 * referred as a matter of policy, but auto-raising that referral needs a dependable "relates to a
 * minor" signal and a firing point in the case lifecycle, which is a follow-up.
 */
public enum ReferralReason {

    /** The applicant is a child. Policy refers every one of these. */
    Minor,

    /** Domestic abuse, where publication or disclosure carries a risk. */
    DomesticAbuse,

    /** A request not to publish in the London Gazette. */
    NonPublication,

    /** The declarant does not meet the requirements. */
    DeclarantRequirementsNotMet,

    /** Separated or estranged parties, where consent is contested or absent. */
    SeparatedOrEstranged,

    /** Anything else -- requires the referral's notes to say what. */
    Other
}
