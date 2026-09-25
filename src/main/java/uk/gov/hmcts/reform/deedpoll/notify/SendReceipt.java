package uk.gov.hmcts.reform.deedpoll.notify;

import uk.gov.hmcts.reform.deedpoll.entity.Notification;

import java.util.UUID;

/**
 * What a caseworker gets back for pressing send: the outbox row that now owes the applicant an email.
 *
 * <p>The row rather than the email, because that is what exists at the end of the request. A send enqueues and
 * then drains, and the drain may or may not have got to this row before the response was written -- so the
 * honest answer is the row's id and the state it was last known to be in, which a client can read again.
 *
 * <p><strong>{@link #enqueued()} is false for a second click, and that is a success rather than an
 * error.</strong> Two unique indexes can refuse the second insert -- the caller's own request id for this case,
 * and one email of an exclusion group per referral -- so the second send does nothing and answers with the first
 * send's row: the caseworker's screen is out of date, not wrong, and telling them their email failed would invite
 * them to try a third time. A client wanting to say "this was already sent on Tuesday" has the id to read it
 * with.
 *
 * <p><strong>The request id is deliberately not published here.</strong> It is transport idempotency and the
 * caller supplied it, so echoing it would add nothing a client does not have while inviting it to be treated as
 * the email's identity, which {@link #id} already is.
 *
 * @param id the outbox row, which is also the {@code reference} Notify is given -- so a row and a notification
 *     are mutually traceable
 * @param recipient the address the email is owed to, resolved when the row was written and frozen there
 * @param enqueued whether this request wrote the row, or found that a previous one already had
 */
public record SendReceipt(UUID id, String recipient, boolean enqueued) {

    /** A row this request wrote. */
    public static SendReceipt enqueued(UUID id, String recipient) {
        return new SendReceipt(id, recipient, true);
    }

    /** The row a previous send wrote -- for the same request, or for the same referral and exclusion group. */
    public static SendReceipt alreadyOwed(Notification existing) {
        return new SendReceipt(existing.getId(), existing.getRecipient(), false);
    }
}
