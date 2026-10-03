package uk.gov.hmcts.reform.deedpoll.notify;

import uk.gov.service.notify.NotificationClientException;

/**
 * What a failed Notify call means: try it again, never try it again, or nobody predicted this.
 *
 * <p>A first-class unit with its own tests rather than {@code catch} blocks spread through the sender, because
 * it is the rule that decides whether an applicant eventually hears from the court or never does. The outbox
 * reads nothing but this verdict:
 * {@link #PERMANENT} fails the row terminally, {@link #TEMPORARY} leaves it claimable with a backed-off
 * {@code nextAttemptAt}, and {@link #TECHNICAL} fails it loudly.
 *
 * <p><strong>This is a hand-maintained reading of Notify's documented status codes, not a quotation of them.</strong>
 * It is taken from {@code apps/pcs/pcs-api/.../notify/config/NotificationErrorHandler.java}, which every HMCTS
 * service sending through Notify follows -- {@code docs.notifications.service.gov.uk} is blocked from the
 * devcontainer this was written in, so the mapping was inherited rather than checked at source. If the client's
 * error table is reachable, check it against {@link #classify(int)}: a status Notify treats as retryable that
 * lands in {@link #PERMANENT} here is an applicant who is never told, and the reverse is a retry loop against a
 * request Notify will refuse every time.
 */
public enum NotificationFailure {

    /**
     * The request is wrong or refused, and resending it resends the same wrong request.
     *
     * <p>400 is a malformed send -- a personalisation key the template does not declare, a template ID that is
     * not a UUID, an address Notify will not accept. 403 is authentication: no API key, a revoked one, or a key
     * for another service. Both are true of the request rather than of the moment, so a retry produces the same
     * answer and the row is failed for good.
     */
    PERMANENT,

    /**
     * Notify would probably have accepted this at a different moment.
     *
     * <p>429 is a known rate-limit refusal. The row stays {@code Pending} and backs off, so
     * the next drain tries it again.
     */
    TEMPORARY,

    /**
     * An ambiguous answer (including HTTP 500), or something nobody enumerated. Failed and logged at error:
     * no automatic retry is safe without evidence that Notify refused the send.
     */
    TECHNICAL;

    /**
     * Whether a row in this state should be tried again. Only {@link #TEMPORARY} is worth another attempt.
     */
    public boolean retryable() {
        return this == TEMPORARY;
    }

    /**
     * The verdict on an HTTP status from Notify.
     *
     * <p>A {@code switch} over the statuses the documentation names, with everything else technical. Deliberately
     * not a range test: {@code 4xx is permanent} would put 429 -- the rate limit, the one 4xx that is certainly
     * worth retrying -- in the branch that never retries, and {@code 5xx is temporary} would retry a 501 forever.
     */
    public static NotificationFailure classify(int status) {
        return switch (status) {
            case 400, 403 -> PERMANENT;
            case 429 -> TEMPORARY;
            default -> TECHNICAL;
        };
    }

    /**
     * The verdict on what the Notify client threw.
     *
     * <p>{@code NotificationClientException.getHttpResult()} is {@code 0} when no HTTP response was received:
     * a DNS failure, a connection reset, a read timeout. That is not a status code, so it falls to
     * {@link #TECHNICAL} by the default branch rather than being mapped, which is the conservative reading: the
     * request may or may not have been delivered, and this plan has no delivery query to ask with (see the plan's
     * "delivery receipts" exclusion). A caseworker seeing a failed row for an email that did go out is recoverable;
     * a second rejection letter to an applicant is not.
     */
    public static NotificationFailure classify(NotificationClientException exception) {
        return classify(exception.getHttpResult());
    }
}
