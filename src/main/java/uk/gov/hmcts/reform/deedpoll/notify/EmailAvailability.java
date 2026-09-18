package uk.gov.hmcts.reform.deedpoll.notify;

/**
 * Whether one email may be sent about one case right now, and if not, why not.
 *
 * <p>Every template is reported, applicable or not, which is the whole point: an Email tab that were sent only
 * the applicable ones would show a caseworker a shorter list with no explanation, and "why can I not send the
 * refusal letter" is the question they would then ask somebody. The answer is that no referral on the case has
 * been refused, and it is cheaper to say so than to be asked.
 *
 * <p><strong>{@link #reason()} is prose for a caseworker, not a code.</strong> There is one reason per template
 * and it is a condition of the case rather than of the request, so a client has nothing to branch on -- and a
 * code would invite the tab to write its own wording for a rule it does not own.
 *
 * <p><strong>{@link #provisioned()} is a separate axis from {@link #applicable()} and neither implies the
 * other.</strong> Applicability is about this case; provisioning is about whether {@code notify.templates}
 * names a real Notify template in this environment. Until notify-0 lands nothing is provisioned, so a tab that
 * only asked about applicability would offer a send that answers 502 at preview and fails on the drain -- see
 * {@link NotifyProperties#isProvisioned}, which exists for exactly this sentence.
 *
 * @param template the email this is about
 * @param applicable whether the case is in a state this email is sent from
 * @param reason why it is not applicable, or null when it is
 * @param provisioned whether this environment has a real Notify template ID for it
 */
public record EmailAvailability(EmailTemplate template,
                               boolean applicable,
                               String reason,
                               boolean provisioned) {

    /** An email this case may have sent. */
    public static EmailAvailability applicable(EmailTemplate template, boolean provisioned) {
        return new EmailAvailability(template, true, null, provisioned);
    }

    /** An email this case may not have sent, and the sentence that says why. */
    public static EmailAvailability unavailable(EmailTemplate template, String reason, boolean provisioned) {
        return new EmailAvailability(template, false, reason, provisioned);
    }
}
