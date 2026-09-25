package uk.gov.hmcts.reform.deedpoll.notify;

import uk.gov.hmcts.reform.deedpoll.entity.DeedPoll;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Everything the <em>Chase missing documents</em> template needs, resolved from the case plus the caseworker's
 * one slot.
 *
 * <p>This is the email a caseworker sends when an application cannot go forward until the applicant supplies
 * something: it names the case, names what is still needed in the caseworker's own words, and invites a reply.
 * Template ⑤ in {@code NOTIFY_TEMPLATE_TAXONOMY.md}, and the first one that is <strong>case-driven</strong>
 * rather than referral-driven.
 *
 * <p><strong>The referral is not a parameter, and here that is a fact about the email rather than a rule about
 * duplication.</strong> {@link ApplicationRejectedPersonalisation} refuses the referral so a second copy of the
 * judge's words cannot reach the same email; this template has no judge in it at all. Nothing here is decided by
 * a Master, no direction is quoted, and there is no refund branch -- so there is no referral to take and
 * {@link EmailCause} lets the template say so instead of being handed one that happens to exist.
 *
 * <p><strong>Five keys, which is the smallest set this service sends.</strong> The four resolved ones are the
 * common applicant/case values every template carries, spelled identically so a content designer moving a
 * paragraph between templates does not have to rename anything. There is deliberately no
 * {@code applied_online} / {@code applied_on_paper} pair: this email starts no refund, so the flag would be a
 * value the template could not use and a reader could not account for.
 *
 * <p><strong>A record, so the personalisation is enumerable</strong> -- ③'s property, and
 * {@code NotificationSecurityTest} asserts {@link #toMap()} against the components, so a component added here
 * without a key fails a test rather than silently never reaching the applicant.
 *
 * @param referenceNumber the case reference as the applicant has seen it
 * @param onlineApplicationReference the reference the applicant quoted when applying online
 * @param oldForename the name being changed from, for the "Regarding..." line
 * @param oldSurname the surname being changed from
 * @param documentsRequired what the office is still waiting for, from the caseworker's slot. Required, because
 *     this service cannot derive it and an email asking for nothing in particular produces a phone call
 */
public record MissingDocumentsPersonalisation(
    String referenceNumber,
    String onlineApplicationReference,
    String oldForename,
    String oldSurname,
    String documentsRequired) {

    public static final String REFERENCE_NUMBER = "reference_number";
    public static final String ONLINE_APPLICATION_REFERENCE = "online_application_reference";
    public static final String OLD_FORENAME = "old_forename";
    public static final String OLD_SURNAME = "old_surname";

    /**
     * The keys a client may never set, in the order {@link #toMap()} writes them.
     *
     * <p>Declared here rather than on {@link EmailTemplate} so there is one list: the enum reads this one, and
     * it is what {@link EmailSlots#parse} names a request back when the request tries to supply one of them.
     */
    public static final List<String> RESOLVED_KEYS = List.of(
        REFERENCE_NUMBER, ONLINE_APPLICATION_REFERENCE, OLD_FORENAME, OLD_SURNAME);

    /**
     * Resolves the email's content from the case and the validated slots.
     *
     * @param deedPoll the case the email is about
     * @param slots what {@link EmailSlots#parse} accepted, keyed by slot
     */
    public static MissingDocumentsPersonalisation resolve(DeedPoll deedPoll, Map<EmailSlot, String> slots) {
        return new MissingDocumentsPersonalisation(
            // displayReference, never the `referencenumber` integer: the serial is an internal component of
            // the rendered reference and is not what the applicant was ever shown.
            ResolvedValues.text(deedPoll.getDisplayReference()),
            ResolvedValues.text(deedPoll.getOnlineApplicationReference()),
            ResolvedValues.text(deedPoll.getOldForename()),
            ResolvedValues.text(deedPoll.getOldSurname()),
            ResolvedValues.text(slots.get(EmailSlot.DOCUMENTS_REQUIRED)));
    }

    /**
     * The personalisation exactly as Notify receives it, and exactly as the outbox row stores it.
     *
     * <p>A {@link LinkedHashMap} so the stored {@code jsonb} reads in this order rather than in hash order,
     * which matters when the question being asked of the row is "what was this applicant asked for".
     *
     * <p><strong>Every key is present, even when the underlying value was null.</strong> Notify refuses a send
     * whose personalisation is missing a placeholder the template uses, so an absent key would turn a blank
     * column into a permanent failure on the drain; an empty string renders as nothing, which is what a blank
     * column means. The slot cannot be blank here -- it is required, and {@link EmailSlots#parse} has already
     * refused an empty one -- so its empty string is unreachable rather than a rendering decision.
     */
    public Map<String, Object> toMap() {
        Map<String, Object> personalisation = new LinkedHashMap<>();
        personalisation.put(REFERENCE_NUMBER, referenceNumber);
        personalisation.put(ONLINE_APPLICATION_REFERENCE, onlineApplicationReference);
        personalisation.put(OLD_FORENAME, oldForename);
        personalisation.put(OLD_SURNAME, oldSurname);
        personalisation.put(EmailSlot.DOCUMENTS_REQUIRED.placeholder(), documentsRequired);
        return personalisation;
    }
}
