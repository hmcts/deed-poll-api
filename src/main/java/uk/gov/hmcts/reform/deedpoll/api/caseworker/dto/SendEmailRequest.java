package uk.gov.hmcts.reform.deedpoll.api.caseworker.dto;

import uk.gov.hmcts.reform.deedpoll.api.KeyedRequestBody;

/**
 * The slots to send the email with: the caseworker's final text, and nothing else.
 *
 * <p>The same shape {@link PreviewEmailRequest} takes, and a separate type for the reason
 * {@code KeyedRequestBody} subclasses per operation -- these two are the clearest case for it in the service,
 * since one renders something to read and the other tells an applicant their application was refused.
 *
 * <p><strong>Nothing here can name the recipient, the reference number, the refund branch or the template
 * version.</strong> The recipient is the case's {@code applicantEmail} and a blank one is a 422 rather than
 * something a caller may substitute; the resolved values are read off the case; the version is whatever Notify
 * reports. A request naming one of the resolved keys is refused and told which, because a caseworker altering the
 * reference an applicant is told, or flipping the refund branch, is a correctness and a fraud problem rather than
 * a convenience.
 *
 * <p><strong>Sending one compose journey twice is a no-op, not a second email.</strong> Which journey this is comes
 * from the {@code Idempotency-Key} header rather than from anything here, and deliberately: the body is the
 * caseworker's content, and this type refuses every key that is not a declared slot -- so a transport identifier in
 * it would mean carving an exception into that rule. The outbox's unique index on the case and the request is what
 * makes a double-click answer with the first send's row.
 */
public class SendEmailRequest extends KeyedRequestBody {
}
