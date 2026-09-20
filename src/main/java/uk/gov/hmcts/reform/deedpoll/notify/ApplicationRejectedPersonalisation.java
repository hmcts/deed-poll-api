package uk.gov.hmcts.reform.deedpoll.notify;

import uk.gov.hmcts.reform.deedpoll.entity.DeedPoll;
import uk.gov.hmcts.reform.deedpoll.entity.enums.PostCounter;

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
 * @param appliedOnline which refund paragraph the template renders
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
     * The keys a client may never set, in the order {@link #toMap()} writes them.
     *
     * <p>Declared here rather than on {@link EmailTemplate} so there is one list: the enum reads this one, and
     * it is what {@link EmailSlots#parse} names a request back when the request tries to supply one of them.
     */
    public static final List<String> RESOLVED_KEYS = List.of(
        REFERENCE_NUMBER, ONLINE_APPLICATION_REFERENCE, OLD_FORENAME, OLD_SURNAME, APPLIED_ONLINE);

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
            text(deedPoll.getDisplayReference()),
            text(deedPoll.getOnlineApplicationReference()),
            text(deedPoll.getOldForename()),
            text(deedPoll.getOldSurname()),
            appliedOnline(deedPoll.getPostCounter()),
            text(slots.get(EmailSlot.JUDGES_REASON)),
            text(slots.get(EmailSlot.ADDITIONAL_NOTES)));
    }

    /**
     * Which refund branch the template renders, from how the application reached the office.
     *
     * <p>{@link PostCounter#Online} is the only value that means the applicant paid online, so it is the only
     * one that gets the "we will refund the account you paid from" paragraph. {@link PostCounter#Counter},
     * {@link PostCounter#Post}, {@link PostCounter#Unknown} and an unset channel all get the paper paragraph,
     * <strong>and that asymmetry is the safe direction rather than an oversight</strong>: the paper paragraph
     * asks the applicant how they paid and where to send the refund, which recovers from a wrong guess, whereas
     * promising an automatic refund to a card nobody paid with leaves an applicant waiting for money that never
     * arrives.
     */
    static boolean appliedOnline(PostCounter postCounter) {
        return postCounter == PostCounter.Online;
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
        personalisation.put(APPLIED_ONLINE, appliedOnline);
        personalisation.put(EmailSlot.JUDGES_REASON.placeholder(), judgesReason);
        personalisation.put(EmailSlot.ADDITIONAL_NOTES.placeholder(), additionalNotes);
        return personalisation;
    }

    /** A nullable column as a value Notify accepts: the text, or nothing at all. */
    private static String text(String value) {
        return value == null ? "" : value;
    }
}
