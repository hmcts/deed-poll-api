package uk.gov.hmcts.reform.deedpoll.notify;

/** Preparation never sends email. The drain commits send authority before invoking a ready delivery. */
public sealed interface PreparedNotification {
    record Refused(SendOutcome outcome) implements PreparedNotification { }

    record Ready(Delivery delivery) implements PreparedNotification { }

    @FunctionalInterface
    interface Delivery {
        SendOutcome send();
    }
}
