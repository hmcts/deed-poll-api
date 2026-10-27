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
 * <p><strong>The {@code Minor*} details refine {@link #Minor}</strong> (Master Sullivan's feedback, Steve,
 * 2026-10-27): the Masters asked for the kind of minor's application to be said when it is referred, as options
 * beneath "Applicant is a minor". They are members of this enum rather than a field of their own, so they are
 * stored, published and listed as reasons are, with no migration. {@link #isMinorDetail()} marks them, and
 * {@code ReferralSchemas} accepts one only beside {@link #Minor} and refuses the three contradictory pairs.
 *
 * <p>{@link #Minor} is a manually selectable reason like any other. Every minor application is
 * referred as a matter of policy, but auto-raising that referral needs a dependable "relates to a
 * minor" signal and a firing point in the case lifecycle, which is a follow-up.
 */
public enum ReferralReason {

    /** The applicant is a child. Policy refers every one of these. */
    Minor("Applicant is a minor"),

    /** Domestic abuse, where publication or disclosure carries a risk. */
    DomesticAbuse("Domestic abuse considerations"),

    /** A request not to publish in the London Gazette. */
    NonPublication("Non-publication request"),

    /** The declarant does not meet the requirements. */
    DeclarantRequirementsNotMet("Declarant requirements not met"),

    /** Separated or estranged parties, where consent is contested or absent. */
    SeparatedOrEstranged("Separated or estranged"),

    /** Anything else -- requires the referral's notes to say what. */
    Other("Other"),

    /** A minor's detail: the applicant is transgender. */
    MinorTransgender("Transgender", true),

    /**
     * A minor's detail: there is parental consent. Contradicts {@link #MinorNoParentalConsent}.
     */
    MinorParentalConsent("Have parental consent", true),

    /** A minor's detail: there is no parental consent. */
    MinorNoParentalConsent("Do not have parental consent", true),

    /**
     * A minor's detail: a stepparent has consented. Contradicts {@link #MinorNoStepparentConsent}.
     */
    MinorStepparentConsent("Stepparent parental consent", true),

    /** A minor's detail: there is no stepparent consent. */
    MinorNoStepparentConsent("Do not have stepparent consent", true),

    /** A minor's detail: there is a court order. */
    MinorCourtOrder("Court Order", true),

    /** A minor's detail: the applicant is 16 or 17 and has consented. Contradicts the next. */
    MinorAged16Or17Consented("16/17 year old applicant has consented", true),

    /** A minor's detail: the applicant is 16 or 17 and has not consented. */
    MinorAged16Or17NotConsented("16/17 year old applicant has not consented", true);

    private final String label;

    private final boolean minorDetail;

    ReferralReason(String label) {
        this(label, false);
    }

    ReferralReason(String label, boolean minorDetail) {
        this.label = label;
        this.minorDetail = minorDetail;
    }

    /**
     * The reason in the business's words, as the admin screens show it (Steve, 2026-10-27: the case notes read as
     * the screens do). The admin's {@code REFERRAL_REASON_LABEL} holds the same words.
     */
    public String label() {
        return label;
    }

    /**
     * Whether this is one of the details a caseworker gives beneath {@link #Minor} (Master Sullivan's feedback,
     * Steve, 2026-10-27), and so is accepted only beside it.
     */
    public boolean isMinorDetail() {
        return minorDetail;
    }
}
