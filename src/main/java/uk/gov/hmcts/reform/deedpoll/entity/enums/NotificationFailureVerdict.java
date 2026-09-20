package uk.gov.hmcts.reform.deedpoll.entity.enums;

/**
 * The durable category of a failed Notify attempt.
 *
 * <p>Separate from {@code NotificationFailure}, which understands HTTP and retryability, because an entity and
 * its database enum must not depend on an HTTP client's exception type. The names deliberately match that
 * taxonomy and {@code NotificationOutbox} maps them exhaustively, so adding a fourth runtime verdict cannot
 * silently store the wrong durable one.
 */
public enum NotificationFailureVerdict {
    PERMANENT,
    TEMPORARY,
    TECHNICAL
}
