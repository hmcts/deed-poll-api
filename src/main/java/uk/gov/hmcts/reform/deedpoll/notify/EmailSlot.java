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
 */
public enum EmailSlot {

    /**
     * The judge's reason, pre-populated from {@code Referral.directionsText} and editable.
     *
     * <p>Capped at {@link ReferralSchemas#MAX_DIRECTIONS_LENGTH} <em>because</em> that is the cap a direction
     * was accepted under, so a direction that was recordable is always sendable unedited. A narrower cap here
     * would mean a judge could write something a caseworker could not pass on.
     */
    JUDGES_REASON("judges_reason", ReferralSchemas.MAX_DIRECTIONS_LENGTH, true),

    /**
     * Anything the caseworker wants to add, and the one slot an email can go out without.
     *
     * <p>Capped at {@link ReferralSchemas#MAX_NOTES_LENGTH}, the narrower of the two: this is an addition to
     * approved wording rather than a quotation of a judge.
     */
    ADDITIONAL_NOTES("additional_notes", ReferralSchemas.MAX_NOTES_LENGTH, false);

    private final String placeholder;
    private final int maxLength;
    private final boolean required;

    EmailSlot(String placeholder, int maxLength, boolean required) {
        this.placeholder = placeholder;
        this.maxLength = maxLength;
        this.required = required;
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
        return required;
    }
}
