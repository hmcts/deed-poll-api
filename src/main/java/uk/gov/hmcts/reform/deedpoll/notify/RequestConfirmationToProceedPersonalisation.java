package uk.gov.hmcts.reform.deedpoll.notify;

import uk.gov.hmcts.reform.deedpoll.entity.DeedPoll;
import uk.gov.hmcts.reform.deedpoll.entity.Referral;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Everything the <em>Request confirmation to proceed</em> template needs, resolved from the case and the
 * referral plus the caseworker's two slots.
 *
 * <p>This is the email a caseworker sends when a judge has <strong>refused a request not to publish in The
 * Gazette but enrolment may still go ahead</strong>: it tells the applicant when the direction came back, quotes
 * it, and asks them to reply saying whether they still want the deed enrolled or would rather be refunded.
 * Template ④ in {@code NOTIFY_TEMPLATE_TAXONOMY.md}.
 *
 * <p><strong>Not the opposite of template ③, and that is why a caseworker chooses between them.</strong> Both
 * are sent off a {@code Rejected} referral, but ③ says enrolment is refused and this one asks whether to proceed
 * with enrolment. {@code MasterDecision} cannot tell them apart -- see {@link CaseEmailService} for the rule
 * that only a referral raised for {@code NonPublication} offers this one at all.
 *
 * <p><strong>The referral is a parameter here, unlike ③'s, and the rule it was protecting still holds.</strong>
 * {@link ApplicationRejectedPersonalisation} refuses the referral so a second, un-edited copy of the judge's
 * words cannot reach the same email. This email needs {@link Referral#getDecisionDate()} for "On ... we received
 * instructions from the judge", which is not the judge's words and has no slot -- so the referral is passed and
 * the rule narrows to this: <strong>{@code directionsText} is never read here.</strong> The judge's direction
 * reaches Notify exactly once, through {@link EmailSlot#JUDGES_REASON}, which {@link EmailSlots#parse}
 * pre-populated from it and the caseworker may have edited.
 *
 * <p><strong>A record, so the personalisation is enumerable</strong> -- the same property ③'s has, and
 * {@code NotificationSecurityTest} asserts {@link #toMap()} against the components, so a component added here
 * without a key fails a test rather than silently never reaching the applicant.
 *
 * @param referenceNumber the case reference as the applicant has seen it
 * @param onlineApplicationReference the reference the applicant quoted when applying online
 * @param oldForename the name being changed from
 * @param oldSurname the surname being changed from
 * @param appliedOnline which refund paragraph the template renders, from
 *     {@link ResolvedValues#appliedOnline} -- ③'s rule, shared rather than restated
 * @param isMinor whether the template says "your child's details" rather than "your details", from
 *     {@link ResolvedValues#isMinor}
 * @param decisionDate the day the judge's direction came back, formatted for an applicant to read
 * @param judgesReason the judge's reason for refusing the request, from the caseworker's slot
 * @param additionalNotes anything the caseworker added, empty when they added nothing
 */
public record RequestConfirmationToProceedPersonalisation(
    String referenceNumber,
    String onlineApplicationReference,
    String oldForename,
    String oldSurname,
    boolean appliedOnline,
    boolean isMinor,
    String decisionDate,
    String judgesReason,
    String additionalNotes) {

    public static final String REFERENCE_NUMBER = "reference_number";
    public static final String ONLINE_APPLICATION_REFERENCE = "online_application_reference";
    public static final String OLD_FORENAME = "old_forename";
    public static final String OLD_SURNAME = "old_surname";
    public static final String APPLIED_ONLINE = "applied_online";
    /**
     * The inverse of {@link #APPLIED_ONLINE}, because Notify's conditional has no {@code else}.
     *
     * <p>③'s finding, applied here because this template carries the same two refund paragraphs. Derived in
     * {@link #toMap()} rather than taken as a component, so the two can never disagree.
     */
    public static final String APPLIED_ON_PAPER = "applied_on_paper";
    public static final String IS_MINOR = "is_minor";
    public static final String DECISION_DATE = "decision_date";

    /**
     * The keys a client may never set, in the order {@link #toMap()} writes them.
     *
     * <p>Declared here rather than on {@link EmailTemplate} so there is one list: the enum reads this one, and
     * it is what {@link EmailSlots#parse} names a request back when the request tries to supply one of them.
     */
    public static final List<String> RESOLVED_KEYS = List.of(
        REFERENCE_NUMBER, ONLINE_APPLICATION_REFERENCE, OLD_FORENAME, OLD_SURNAME, APPLIED_ONLINE,
        APPLIED_ON_PAPER, IS_MINOR, DECISION_DATE);

    /**
     * Resolves the email's content from the case, the referral and the validated slots.
     *
     * @param deedPoll the case the email is about
     * @param referral the refused referral behind it, read <em>only</em> for its decision date
     * @param slots what {@link EmailSlots#parse} accepted, keyed by slot
     */
    public static RequestConfirmationToProceedPersonalisation resolve(
        DeedPoll deedPoll, Referral referral, Map<EmailSlot, String> slots) {

        return new RequestConfirmationToProceedPersonalisation(
            // displayReference, never the `referencenumber` integer: the serial is an internal component of
            // the rendered reference and is not what the applicant was ever shown.
            ResolvedValues.text(deedPoll.getDisplayReference()),
            ResolvedValues.text(deedPoll.getOnlineApplicationReference()),
            ResolvedValues.text(deedPoll.getOldForename()),
            ResolvedValues.text(deedPoll.getOldSurname()),
            ResolvedValues.appliedOnline(deedPoll.getPostCounter()),
            ResolvedValues.isMinor(deedPoll.getType()),
            // The referral's one contribution. getDirectionsText() is deliberately not read: it reaches this
            // email through the judges_reason slot, once, in whatever form the caseworker approved.
            ResolvedValues.date(referral.getDecisionDate()),
            ResolvedValues.text(slots.get(EmailSlot.JUDGES_REASON)),
            ResolvedValues.text(slots.get(EmailSlot.ADDITIONAL_NOTES)));
    }

    /**
     * The personalisation exactly as Notify receives it, and exactly as the outbox row stores it.
     *
     * <p>A {@link LinkedHashMap} so the stored {@code jsonb} reads in this order rather than in hash order,
     * which matters when the question being asked of the row is "what was this applicant told".
     *
     * <p><strong>Every key is present, even when the underlying value was null.</strong> Notify refuses a send
     * whose personalisation is missing a placeholder the template uses, so an absent key would turn a blank
     * column into a permanent failure on the drain; an empty string renders as nothing, which is what a blank
     * column means. That includes {@link #DECISION_DATE}: a referral with no decision date recorded renders the
     * sentence without one rather than costing the applicant the whole email.
     */
    public Map<String, Object> toMap() {
        Map<String, Object> personalisation = new LinkedHashMap<>();
        personalisation.put(REFERENCE_NUMBER, referenceNumber);
        personalisation.put(ONLINE_APPLICATION_REFERENCE, onlineApplicationReference);
        personalisation.put(OLD_FORENAME, oldForename);
        personalisation.put(OLD_SURNAME, oldSurname);
        // Booleans rather than "yes"/"no": Notify's conditional syntax reads a JSON boolean directly, and a
        // string would make each branch depend on which words somebody chose. Unproven for a JSON boolean and
        // silent if wrong -- see ApplicationRejectedPersonalisation.toMap, which carries the full note.
        personalisation.put(APPLIED_ONLINE, appliedOnline);
        // Notify's conditional has no `else`, so the paper branch needs its own flag -- ③'s finding, applied
        // here because this template offers the same refund and carries the same two paragraphs. ④ is not
        // authored yet; doing it now is what stops the same defect being rediscovered when it is.
        personalisation.put(APPLIED_ON_PAPER, !appliedOnline);
        personalisation.put(IS_MINOR, isMinor);
        personalisation.put(DECISION_DATE, decisionDate);
        personalisation.put(EmailSlot.JUDGES_REASON.placeholder(), judgesReason);
        personalisation.put(EmailSlot.ADDITIONAL_NOTES.placeholder(), additionalNotes);
        return personalisation;
    }
}
