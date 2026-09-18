package uk.gov.hmcts.reform.deedpoll.notify;

import uk.gov.hmcts.reform.deedpoll.entity.Notification;

/**
 * The seam between the outbox and Notify: one owed notification in, one verdict out.
 *
 * <p>An interface rather than a direct call so the outbox's state machine -- which row is claimed, what
 * a failure does to it, when it is tried again -- is provable without a network, a WireMock or a
 * container. {@code NotifyEmailSender} is the implementation that talks to Notify; a test's is whatever
 * the case under test needs to come back.
 *
 * <p><strong>There may be no implementation at all, and the outbox copes.</strong> Until the send lands
 * there is no bean of this type, so a drain claims nothing and every row stays {@code Pending} -- which
 * is the correct behaviour for a service with no Notify credentials rather than a gap: the alternatives
 * are failing rows that could have been sent, or claiming them and discarding them.
 *
 * <p>An implementation should not throw: the taxonomy is the vocabulary for a failure, and a
 * {@link SendOutcome.Failed} carries more than an exception the outbox would have to classify itself.
 * The outbox catches anyway, and treats it as {@link NotificationFailure#TECHNICAL}, because one row
 * that throws must not roll back the rest of the batch.
 */
public interface NotificationSender {

    /**
     * Sends one notification, using the recipient and the personalisation frozen on the row.
     *
     * @param notification the claimed row, with its attempt already counted
     * @return what happened, in the vocabulary the outbox settles rows with
     */
    SendOutcome send(Notification notification);
}
