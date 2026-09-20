package uk.gov.hmcts.reform.deedpoll.api.caseworker.dto;

import uk.gov.hmcts.reform.deedpoll.notify.EmailAvailability;
import uk.gov.hmcts.reform.deedpoll.notify.EmailSlot;
import uk.gov.hmcts.reform.deedpoll.notify.EmailTemplate;

import java.util.List;

/**
 * One email type a caseworker could send about a case, as the Email tab reads it.
 *
 * <p>Every type is published, applicable or not. A tab sent only the applicable ones would show a caseworker a
 * shorter list and no reason for it, so {@link #reason()} carries the sentence that answers "why can I not send
 * the refusal letter" before it is asked.
 *
 * <p><strong>{@link #template()} is the key, and it is the token the other two operations take in their
 * path.</strong> One string for the configuration key, the URL and this response deliberately: a third spelling
 * of the same idea would be a mapping table nobody maintains. The outbox stores the enum's member name instead,
 * which is a different thing on purpose -- see {@code EmailTemplate}.
 *
 * <p><strong>{@link #slots()} is what a form needs to render itself</strong>: which fields the caseworker fills,
 * which is required, and how long each may be. Published rather than hard-coded in the tab because they are the
 * template's fields and the template is not the tab's.
 *
 * <p><strong>The pre-populated text is deliberately not here.</strong> {@code judges_reason} opens with the
 * judge's direction, and the tab already has it: {@link ReferralResponse#directionsText()} publishes it on the
 * referral read the same screen makes. A second copy in this response would be a second thing to keep in step,
 * and the service pre-populates the slot itself for a request that sends none.
 *
 * @param template the key, which is the {@code {template}} path token of the preview and the send
 * @param applicable whether the case is in a state this email is sent from
 * @param reason why it is not applicable, absent when it is
 * @param provisioned whether this environment has a real GOV.UK Notify template for it. False everywhere until
 *     the Notify service is provisioned, and a send or preview of an unprovisioned template fails at Notify --
 *     so a tab offering it should say this instead
 * @param slots the fields the caseworker fills, in the order a form should show them
 */
public record EmailTypeResponse(String template,
                                boolean applicable,
                                String reason,
                                boolean provisioned,
                                List<EmailSlotResponse> slots) {

    public static EmailTypeResponse from(EmailAvailability availability) {
        return new EmailTypeResponse(
            availability.template().key(),
            availability.applicable(),
            availability.reason(),
            availability.provisioned(),
            slotsOf(availability.template()));
    }

    public static List<EmailTypeResponse> from(List<EmailAvailability> availabilities) {
        return availabilities.stream().map(EmailTypeResponse::from).toList();
    }

    private static List<EmailSlotResponse> slotsOf(EmailTemplate template) {
        return template.slots().stream().map(EmailSlotResponse::from).toList();
    }

    /**
     * One field of the email a caseworker fills in.
     *
     * <p>The placeholder name is the request-body key as well as the name the Notify template uses, which is why
     * it is not renamed to Java or JavaScript style anywhere: one spelling, from the template to the form.
     *
     * @param name the key to send this slot under
     * @param required whether the email can be sent without it
     * @param maxLength the longest value accepted, in characters after trimming. An over-long value is refused
     *     rather than truncated, because a truncated quotation of a judge is worse than being asked to shorten one
     */
    public record EmailSlotResponse(String name, boolean required, int maxLength) {

        static EmailSlotResponse from(EmailSlot slot) {
            return new EmailSlotResponse(slot.placeholder(), slot.required(), slot.maxLength());
        }
    }
}
