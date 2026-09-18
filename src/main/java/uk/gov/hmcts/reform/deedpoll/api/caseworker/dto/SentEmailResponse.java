package uk.gov.hmcts.reform.deedpoll.api.caseworker.dto;

import uk.gov.hmcts.reform.deedpoll.notify.SendReceipt;

/**
 * What a caseworker gets back for sending an email: the outbox row that now owes it.
 *
 * <p>The row and not the delivery, because the row is what exists when the response is written. An email is
 * enqueued in the same transaction as the action that caused it and handed to Notify by a drain, so "sent" here
 * means the court owes the applicant this email and will keep trying until Notify accepts it or the attempts run
 * out.
 *
 * <p><strong>No state field, deliberately.</strong> The drain runs immediately after the enqueue commits, so any
 * state put in this response would have been read before that and could say {@code Pending} for an email already
 * accepted by Notify. A client that wants the current state reads the row; a client that wants to know the send
 * was accepted has this response's existence.
 *
 * <p><strong>{@link #enqueued()} is false when this send was redundant, and the request still succeeded.</strong>
 * One <em>Application Rejected</em> per referral is a unique index, so a second click writes nothing and answers
 * with the first row's id -- the caseworker's screen was out of date rather than wrong, and telling them their
 * email had failed would invite a third attempt.
 *
 * @param id the outbox row, which is also the {@code reference} Notify is given, so the row and the notification
 *     are mutually traceable
 * @param recipient where it will be sent, resolved and frozen when the row was written -- so a later edit to the
 *     case cannot redirect an email already owed
 * @param enqueued whether this request wrote the row, or found that an earlier send already had
 */
public record SentEmailResponse(String id, String recipient, boolean enqueued) {

    public static SentEmailResponse from(SendReceipt receipt) {
        return new SentEmailResponse(receipt.id().toString(), receipt.recipient(), receipt.enqueued());
    }
}
