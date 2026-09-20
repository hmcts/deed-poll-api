package uk.gov.hmcts.reform.deedpoll.domain;

/**
 * A service this one depends on refused or could not answer, on an operation that has to answer synchronously.
 *
 * <p><strong>Not a port</strong>, for the reason {@link IncompleteDataError} is not: the reference had no
 * outbound dependency to fail. It arrives with the email preview, which asks GOV.UK Notify to render a template
 * while a caseworker waits, and it is deliberately the only synchronous Notify call in the service -- a send is
 * enqueued and drained, so a Notify failure there is a row's state rather than a status code.
 * {@code DomainErrorHandler} maps this to a 502.
 *
 * <p><strong>502 rather than 500, and one status rather than two.</strong> 500 would say this service is broken
 * and send whoever is paged to read its logs, when what happened is that Notify answered something. 502 names
 * where to look. A temporary Notify failure -- the rate limit, an outage -- is answered with the same 502 rather
 * than a 503, because the distinction would not change what the caller does: a preview is a read with a human
 * waiting, and their retry is pressing the button again. The taxonomy's verdict is in the message so the
 * difference is legible to whoever reads it, and the outbox is where a retry is actually modelled.
 *
 * <p><strong>The message is composed here, never copied from the dependency.</strong> Nothing a client or a
 * library said about a failed call reaches a caller: {@code DomainErrorHandler} makes the same point about
 * framework messages, and the stake is higher for an outbound call, whose exception text contains the
 * dependency's response. The full detail is logged by whoever throws this.
 */
public class UpstreamFailureError extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public UpstreamFailureError(String message) {
        super(message);
    }
}
