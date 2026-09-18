package uk.gov.hmcts.reform.deedpoll.notify;

import java.util.UUID;

/**
 * What came back from one attempt to send one notification: Notify accepted it, or it did not.
 *
 * <p>Sealed, with a record per answer, so the two are not both expressible at once. A single record
 * carrying a nullable provider id and a nullable failure would admit "accepted, and here is why it
 * failed" -- and the outbox's state machine reads this to decide whether an applicant is ever told
 * anything, so a shape that can say two things is a shape that can silently say the wrong one.
 *
 * <p>It carries no HTTP status, deliberately. Reading a status is {@link NotificationFailure}'s job and
 * it happens once, in the sender, so the outbox is a state machine over a verdict rather than a second
 * place that knows what 429 means.
 */
public sealed interface SendOutcome {

    /**
     * Notify accepted the send.
     *
     * <p>Accepted, not delivered: this plan has no delivery callback and no polling, which is why the
     * provider id is worth storing.
     *
     * @param providerNotificationId the id Notify gave the notification, or null if it gave none
     */
    record Sent(UUID providerNotificationId) implements SendOutcome {
    }

    /**
     * The send did not happen.
     *
     * @param failure the taxonomy's verdict, which is what decides whether the row is retried
     * @param reason  what to record on the row: the verdict plus whatever Notify said, for a human
     *                reading the Email tab rather than for code to branch on
     */
    record Failed(NotificationFailure failure, String reason) implements SendOutcome {
    }

    /**
     * {@link Sent}, as a factory so a caller reads {@code SendOutcome.sent(id)}.
     */
    static SendOutcome sent(UUID providerNotificationId) {
        return new Sent(providerNotificationId);
    }

    /**
     * {@link Failed}, as a factory so a caller reads {@code SendOutcome.failed(PERMANENT, "...")}.
     */
    static SendOutcome failed(NotificationFailure failure, String reason) {
        return new Failed(failure, reason);
    }
}
