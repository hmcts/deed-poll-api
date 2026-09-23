package uk.gov.hmcts.reform.deedpoll.notify;

import uk.gov.hmcts.reform.deedpoll.entity.DeedPoll;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Everything the <em>Application Rejected</em> template needs, resolved from the case plus the caseworker's
 * two slots.
 *
 * <p>This is the email a caseworker sends when the Master has refused enrolment: it quotes the judge and then
 * starts a refund, and the refund text branches on whether the applicant paid online or on paper. Template ③
 * in {@code NOTIFY_TEMPLATE_TAXONOMY.md}, and the only one of the four that needs no attachment -- which is
 * why it is the one this plan proves the send path with.
 *
 * <p><strong>A record, so the personalisation is enumerable.</strong> The components are the whole content of
 * the email bar the template's own words, and {@code NotificationSecurityTest} asserts {@link #toMap()}
 * against them -- so a component added here without a key fails a test rather than silently never reaching
 * the applicant, and a key added without a component fails the other way round.
 *
 * <p><strong>The referral is not a parameter, deliberately.</strong> Every resolved value below comes from the
 * case; the referral reaches this email only as {@link EmailSlot#JUDGES_REASON}, which
 * {@link EmailSlots#parse} pre-populated from {@code Referral.directionsText} and the caseworker may have
 * edited. Taking the referral here as well would invite a second, un-edited copy of the judge's words into the
 * same email, and would make "what was this applicant actually told" a question with two answers.
 *
 * @param referenceNumber the case reference as the applicant has seen it
 * @param onlineApplicationReference the reference the applicant quoted when applying online
 * @param oldForename the name being changed from, for the "Regarding..." line
 * @param oldSurname the surname being changed from
 * @param appliedOnline which refund paragraph the template renders, from
 *     {@link ResolvedValues#appliedOnline} -- shared with <em>Request confirmation to proceed</em>, which
 *     offers the same refund, so the two emails cannot drift into disagreeing about who gets one automatically
 * @param judgesReason the judge's reason for refusal, from the caseworker's slot
 * @param additionalNotes anything the caseworker added, empty when they added nothing
 */
public record ApplicationRejectedPersonalisation(
    String referenceNumber,
    String onlineApplicationReference,
    String oldForename,
    String oldSurname,
    boolean appliedOnline,
    String judgesReason,
    String additionalNotes) {

    public static final String REFERENCE_NUMBER = "reference_number";
    public static final String ONLINE_APPLICATION_REFERENCE = "online_application_reference";
    public static final String OLD_FORENAME = "old_forename";
    public static final String OLD_SURNAME = "old_surname";
    public static final String APPLIED_ONLINE = "applied_online";

    /**
     * The inverse of {@link #APPLIED_ONLINE}, because <strong>Notify's conditional has no {@code else}</strong>.
     *
     * <p>{@code ((applied_online??…))} renders its text when the value is true and there is no inverse form, so
     * a template cannot express the document's two mutually exclusive refund paragraphs from one flag. Without
     * this second key the paper paragraph either never renders or always does — and always would tell an
     * applicant who paid online to post their bank details for a refund they are getting automatically.
     *
     * <p><strong>Derived in {@link #toMap()} rather than taken as a constructor argument.</strong> A second
     * field could be set inconsistently with the first; a negation cannot. The two keys are one decision
     * expressed twice on the wire because Notify needs it that way, not two facts about the case.
     *
     * <p>Confirmed against the authored template on 2026-09-23: Notify parsed both conditionals, and a test
     * send with each branch rendered the right paragraph. What that did <em>not</em> confirm is the value's
     * type — see {@link #toMap()}.
     */
    public static final String APPLIED_ON_PAPER = "applied_on_paper";

    /**
     * The keys a client may never set, in the order {@link #toMap()} writes them.
     *
     * <p>Declared here rather than on {@link EmailTemplate} so there is one list: the enum reads this one, and
     * it is what {@link EmailSlots#parse} names a request back when the request tries to supply one of them.
     */
    public static final List<String> RESOLVED_KEYS = List.of(
        REFERENCE_NUMBER, ONLINE_APPLICATION_REFERENCE, OLD_FORENAME, OLD_SURNAME, APPLIED_ONLINE,
        APPLIED_ON_PAPER);

    /**
     * Resolves the email's content from the case and the validated slots.
     *
     * @param deedPoll the case the email is about
     * @param slots what {@link EmailSlots#parse} accepted, keyed by slot
     */
    public static ApplicationRejectedPersonalisation resolve(DeedPoll deedPoll, Map<EmailSlot, String> slots) {
        return new ApplicationRejectedPersonalisation(
            // displayReference, never the `referencenumber` integer: the serial is an internal component of
            // the rendered reference and is not what the applicant was ever shown.
            ResolvedValues.text(deedPoll.getDisplayReference()),
            ResolvedValues.text(deedPoll.getOnlineApplicationReference()),
            ResolvedValues.text(deedPoll.getOldForename()),
            ResolvedValues.text(deedPoll.getOldSurname()),
            ResolvedValues.appliedOnline(deedPoll.getPostCounter()),
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
     * column means.
     */
    public Map<String, Object> toMap() {
        Map<String, Object> personalisation = new LinkedHashMap<>();
        personalisation.put(REFERENCE_NUMBER, referenceNumber);
        personalisation.put(ONLINE_APPLICATION_REFERENCE, onlineApplicationReference);
        personalisation.put(OLD_FORENAME, oldForename);
        personalisation.put(OLD_SURNAME, oldSurname);
        // A Boolean rather than "yes"/"no": Notify's conditional syntax reads a JSON boolean directly, and a
        // string would make the branch depend on which words somebody chose.
        //
        // **This is the one part of the template contract still unproven, and it fails silently.** The authored
        // template was verified on 2026-09-23 by a test send from Notify's own UI, which takes yes/no — so both
        // branches are known to render, but not from a JSON boolean. If Notify's API wants the strings, neither
        // conditional fires on a real send: the applicant gets no refund paragraph at all and nothing errors.
        // Settle it with one real send once notify-0 supplies an API key, before anything reaches an applicant.
        personalisation.put(APPLIED_ONLINE, appliedOnline);
        // Negated here so the two can never disagree -- see APPLIED_ON_PAPER.
        personalisation.put(APPLIED_ON_PAPER, !appliedOnline);
        personalisation.put(EmailSlot.JUDGES_REASON.placeholder(), judgesReason);
        personalisation.put(EmailSlot.ADDITIONAL_NOTES.placeholder(), additionalNotes);
        return personalisation;
    }
}
