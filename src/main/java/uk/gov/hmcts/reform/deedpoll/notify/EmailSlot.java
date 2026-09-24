package uk.gov.hmcts.reform.deedpoll.notify;

import uk.gov.hmcts.reform.deedpoll.domain.referrals.ReferralSchemas;

/**
 * A named placeholder in a Notify template that a <em>caseworker</em> fills in, as opposed to one this
 * service resolves from the case.
 *
 * <p><strong>The slots are the only client input to an email.</strong> Everything else in the
 * personalisation is resolved server-side -- see {@link ApplicationRejectedPersonalisation} -- and
 * {@link EmailSlots#parse} refuses a request that tries to set one of those. The reason is not tidiness: a
 * caseworker altering the reference number an applicant is told, or flipping the branch that decides which
 * refund process the applicant is asked to follow, is a correctness and a fraud problem rather than a
 * convenience.
 *
 * <p>The names are the snake_case placeholder names as a Notify template spells them, because they are the
 * keys of the personalisation map and the keys of the request body at once. They are not renamed to Java
 * style anywhere: a second spelling would be a mapping table to keep in step with a template a content
 * designer owns.
 *
 * <p><strong>Every slot is bounded</strong>, for the reason {@code ReferralNotes} truncates a judge's name:
 * a caseworker-authored string that reaches an applicant needs a limit, because an unbounded value composes a
 * payload Notify refuses and the refusal then lands somewhere unhelpful -- on the drain rather than on the
 * form that produced it. The caps are the existing referral caps rather than new numbers, which is what makes
 * {@link #JUDGES_REASON} able to carry any direction a judge could have recorded.
 *
 * <p><strong>Requiredness is a property of the slot here, not of the template</strong>, and that holds for
 * the whole proposed template set rather than only for the one template this plan ships: the two emails that
 * quote the judge both need the quotation, and the notes slot is an optional addition in all four. If a later
 * template wants {@link #JUDGES_REASON} optional, the flag moves onto {@link EmailTemplate} -- it does not
 * get defaulted away here.
 *
 * <p><strong>Requiredness is spelled as the sentence a caseworker is refused with, and that is deliberate
 * rather than terse.</strong> A required slot needs a reason a form can show against its own input -- "this
 * email quotes the judge" and "only a caseworker can say which documents are missing" send somebody to two
 * different places -- and a boolean beside a message is two fields that can disagree. So one field carries
 * both: {@link #OPTIONAL} is the empty sentence, and {@link #required()} is "there is a sentence". A required
 * slot with no reason is then not expressible.
 */
public enum EmailSlot {

    /**
     * The judge's reason, pre-populated from {@code Referral.directionsText} and editable.
     *
     * <p>Capped at {@link ReferralSchemas#MAX_DIRECTIONS_LENGTH} <em>because</em> that is the cap a direction
     * was accepted under, so a direction that was recordable is always sendable unedited. A narrower cap here
     * would mean a judge could write something a caseworker could not pass on.
     */
    JUDGES_REASON("judges_reason", ReferralSchemas.MAX_DIRECTIONS_LENGTH,
                  "this email quotes the judge, and there is no direction recorded to pre-populate it from"),

    /**
     * Anything the caseworker wants to add, and the one slot an email can go out without.
     *
     * <p>Capped at {@link ReferralSchemas#MAX_NOTES_LENGTH}, the narrower of the two: this is an addition to
     * approved wording rather than a quotation of a judge.
     */
    ADDITIONAL_NOTES("additional_notes", ReferralSchemas.MAX_NOTES_LENGTH, EmailSlot.OPTIONAL),

    /**
     * What the office is still waiting for, in words the applicant reads: the one slot of the chase email.
     *
     * <p>Required and with nothing to pre-populate it from, which makes it the first slot in this enum whose
     * absence is a caseworker's omission rather than a missing direction -- this service cannot derive which
     * documents are outstanding, and {@code awaitingFurtherDocsInfo} is a date rather than a list. An email
     * asking an applicant for nothing in particular would produce a phone call rather than a document.
     *
     * <p>Capped at {@link #MAX_EMAIL_PROSE_LENGTH} and deliberately <em>not</em> at
     * {@link ReferralSchemas#MAX_DIRECTIONS_LENGTH}, though the two numbers are the same today: a direction's
     * cap is a fact about what a judge may record on a referral, and this is a fact about how much prose an
     * email carries. Tying them together would make a change to referral recording silently change what a
     * caseworker may write to an applicant.
     */
    DOCUMENTS_REQUIRED("documents_required", EmailSlot.MAX_EMAIL_PROSE_LENGTH,
                       "this email exists to tell the applicant what is still needed, and only a caseworker "
                           + "can say what that is");

    /**
     * The longest caseworker-authored prose any email carries, in characters after trimming.
     *
     * <p>4,000, which is the same ceiling a judicial direction has -- long enough for several paragraphs and a
     * list, short enough that the composed payload is one Notify will accept. Declared here as a property of
     * email content rather than read from {@code ReferralSchemas}, for the reason
     * {@link #DOCUMENTS_REQUIRED} gives: the equality is a coincidence of two independent decisions, and
     * writing it as a dependency would make one of them govern the other.
     */
    public static final int MAX_EMAIL_PROSE_LENGTH = 4000;

    /**
     * The sentence an optional slot has, which is none: a slot an email can go out without is never refused
     * for being absent, so there is nothing to tell anybody.
     */
    private static final String OPTIONAL = "";

    private final String placeholder;
    private final int maxLength;
    private final String requiredBecause;

    EmailSlot(String placeholder, int maxLength, String requiredBecause) {
        this.placeholder = placeholder;
        this.maxLength = maxLength;
        this.requiredBecause = requiredBecause;
    }

    /** The placeholder name, which is the request-body key and the personalisation key at once. */
    public String placeholder() {
        return placeholder;
    }

    /** The longest value this slot accepts, in characters after trimming. */
    public int maxLength() {
        return maxLength;
    }

    /** Whether an email can be composed without this slot. */
    public boolean required() {
        return !requiredBecause.isEmpty();
    }

    /**
     * Why this slot cannot be left out, as the second half of the sentence a refusal carries.
     *
     * <p>Empty for an optional slot, which is never refused for being absent. {@link EmailSlots} is the one
     * caller: the message belongs to the slot because the answer differs per slot, and a form renders it
     * against the input the caseworker has to fill in.
     */
    String requiredBecause() {
        return requiredBecause;
    }
}
