package uk.gov.hmcts.reform.deedpoll.notify;

import uk.gov.hmcts.reform.deedpoll.domain.notes.NoteForm;

/**
 * The text of the case notes an email writes: one when it is sent, and one when it terminally fails.
 *
 * <p><strong>Why an email writes a note at all.</strong> {@code ReferralNotes}' answer transfers unchanged --
 * there is no timeline concept in this domain, only the human-authored Notes grid, and an email has to be
 * visible where caseworkers already look. The correspondence history behind the Email tab is the record of
 * <em>what was sent</em>; the note is the marker that says <em>it happened</em>, on the one timeline the case
 * has.
 *
 * <p><strong>The note is a marker and the Emails tab is the record.</strong> Agreed with the product owner on
 * 2026-09-23. So a note names which email and points at the tab, and repeats none of the content, the
 * recipient, the personalisation or the provider's reason -- each of which the tab already shows against the
 * row, in more detail than a sentence could and without a second copy of personal data on a timeline nothing
 * re-renders.
 *
 * <p><strong>The pointer is prose, not a hyperlink, and that is what keeps this change inside this
 * repo.</strong> {@code deed-poll-admin} renders a note through {@code govukTable}'s {@code { text: … }}, so
 * markup in a note is escaped and shows as literal characters. Rendering note text as HTML is an XSS hole --
 * notes are caseworker-authored free text -- and having admin detect these notes would mean parsing the prose,
 * which the note model forbids. So the tab is named in words.
 *
 * <p><strong>Composed from {@link EmailTemplate#displayName()} and never per template.</strong> The display
 * name is documented as "the name a caseworker sees for this email type", which is exactly the varying part
 * and the only one. There are two templates today and five planned, so a composer that switched on the
 * template would need editing three more times, in a place somebody would forget; this one gets them for
 * free. The same two sentences are used for the templates that carry documents, deliberately: Notify does not
 * attach a file, it inserts a time-limited link, and a permanent note saying "attached" would be wrong in a
 * way nobody would revisit.
 *
 * <p><strong>"Sent", with the second sentence disclaiming delivery.</strong> The note is written when the email
 * is enqueued, so at that instant it has been accepted by this service and not yet by Notify -- and even
 * {@code state = Sent} only means Notify accepted it. No note here could claim delivery, because there is no
 * delivery signal until Notify's callbacks exist. Pointing at the tab "for its delivery status" is what tells
 * the reader this note is not the record of delivery, and it stays correct when callbacks land.
 *
 * <p><strong>Composed here rather than in a service, for the reason {@code ReferralNotes} is: the note has to
 * be unrefusable.</strong> The notes column is bounded at {@link NoteForm#MAX_NOTE_LENGTH}, and the failure
 * note is written inside the drain's batch transaction, which covers rows Notify has already accepted -- so a
 * note the notes validator refused would roll those back and the next drain would send them again. Every
 * ingredient of these two sentences is therefore either fixed at compile time or bounded here, the provider's
 * {@code failureReason} is not among them, and {@code EmailNotesTest} pins the arithmetic across the whole
 * enum rather than against one member.
 *
 * <p>The text is prose for a caseworker and not a wire format. Nothing parses it, so it may be reworded
 * freely, and a change here does not migrate the notes already written.
 */
public final class EmailNotes {

    /**
     * How much of a stored template name a note will quote.
     *
     * <p>Needed because {@code Notification.template} is unbounded {@code text} in the database and the notes
     * column is not. The value stored there is an enum member name written by this service, so a pathological
     * one is not reachable today -- but "not reachable" is the wrong standard for the failure note, which is
     * written inside the drain's batch transaction: a note the validator refused would roll back rows Notify
     * had already accepted. Truncating is the safe failure, exactly as {@code ReferralNotes.claimLapsed}
     * truncates a judge's name, and the bound is the same 200 the audit columns use.
     */
    private static final int MAX_TEMPLATE_NAME_LENGTH = 200;

    /**
     * What a note calls an email whose stored template name is missing altogether.
     *
     * <p>Unreachable -- the column is {@code NOT NULL} -- but a composer that simply interpolated the stored
     * value would write " email sent." or the word "null" onto a timeline that is never revised, and this one
     * must not throw inside the transaction it is called from.
     */
    private static final String UNNAMED_TEMPLATE = "Unrecognised";

    private EmailNotes() {
    }

    /**
     * The note for an email this service has accepted and owes to Notify.
     *
     * <p>Takes the template rather than its stored name, because the caller that writes this note is the one
     * that enqueued the row and so has the member in hand.
     */
    public static String sent(EmailTemplate template) {
        return sentNote(template.displayName());
    }

    /**
     * The note for an email that has terminally failed: not retryable, or retryable and out of attempts.
     *
     * <p>Says nothing about why. The provider's reason is unbounded external text that would put the length
     * bound beyond proof, and the tab holds it along with the verdict -- which is what the pointer is for.
     */
    public static String couldNotBeSent(EmailTemplate template) {
        return couldNotBeSentNote(template.displayName());
    }

    /**
     * The note for a terminally failed email named by the string an outbox row stores.
     *
     * <p>The one overload keyed on a stored name, and the drain has to use it because a row carries the member
     * name and not the member. There is deliberately no sent-note twin: the sent note is written at enqueue by
     * the caller that holds the member, so nothing reaches a sent note from a row.
     *
     * <p>A name that no longer resolves is used as the display name as it stands -- which is how the Email tab
     * beside this note already degrades, so the two read consistently. The case is reachable rather than
     * theoretical: a member renamed after rows were written is exactly what {@code EmailTemplate.byName} answers
     * empty for, and the sender reports it as a permanent failure naming the row -- so the unresolvable case and
     * the failure-note case coincide. See {@link #couldNotBeSent(EmailTemplate)} for why the failure itself is
     * not quoted.
     */
    public static String couldNotBeSent(String storedTemplateName) {
        return couldNotBeSentNote(displayName(storedTemplateName));
    }

    private static String sentNote(String email) {
        return email + " email sent. See the Emails tab for its delivery status.";
    }

    private static String couldNotBeSentNote(String email) {
        return email + " email could not be sent. See the Emails tab for the reason.";
    }

    /**
     * The name a caseworker sees for the email a stored template name refers to, bounded so the note it
     * composes cannot be refused -- see {@link #MAX_TEMPLATE_NAME_LENGTH} and {@link #UNNAMED_TEMPLATE}.
     */
    private static String displayName(String storedTemplateName) {
        if (storedTemplateName == null || storedTemplateName.isBlank()) {
            return UNNAMED_TEMPLATE;
        }
        String stored = storedTemplateName.trim();
        return EmailTemplate.byName(stored)
            .map(EmailTemplate::displayName)
            .orElseGet(() -> stored.substring(0, Math.min(stored.length(), MAX_TEMPLATE_NAME_LENGTH)));
    }
}
