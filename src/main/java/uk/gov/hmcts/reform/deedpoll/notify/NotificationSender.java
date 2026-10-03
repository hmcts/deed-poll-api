package uk.gov.hmcts.reform.deedpoll.notify;

import uk.gov.hmcts.reform.deedpoll.entity.Notification;

/**
 * The seam between the outbox and Notify: one owed notification in, one verdict out.
 *
 * <p>An interface rather than a direct call so the outbox's state machine -- which row is claimed, what
 * a failure does to it, when it is tried again -- is provable without a network, a WireMock or a
 * container. {@link NotifyEmailSender} is the implementation that talks to Notify; a test's is whatever
 * the case under test needs to come back.
 *
 * <p><strong>A context may have no implementation at all, and the outbox copes.</strong> With no bean of
 * this type a drain claims nothing and every row stays {@code Pending} -- which is what lets the outbox's
 * own rules be stated without a Notify at all, and would be the right behaviour for a deployment
 * deliberately configured not to send: the alternatives are failing rows that could have been sent, or
 * claiming them and discarding them.
 *
 * <p>An implementation should not throw: the taxonomy is the vocabulary for a failure, and a
 * {@link SendOutcome.Failed} carries more than an exception the outbox would have to classify itself.
 * The outbox catches preparation failures as temporary and delivery failures as technical/uncertain.
 * One row that throws must not roll back the rest of the batch.
 */
public interface NotificationSender {

    /** Prepare without sending. Only the outbox may invoke delivery after committing send authority. */
    PreparedNotification prepare(Notification notification);
}
