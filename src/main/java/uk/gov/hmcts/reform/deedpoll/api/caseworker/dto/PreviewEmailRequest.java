package uk.gov.hmcts.reform.deedpoll.api.caseworker.dto;

import uk.gov.hmcts.reform.deedpoll.api.KeyedRequestBody;

/**
 * The slots to render a preview from: the caseworker's text as it stands, and nothing else.
 *
 * <p>A keyed body like every other write payload here, and for one of the two reasons {@code KeyedRequestBody}
 * gives rather than both: nothing in an email is cleared by an explicit null, but which keys the caller actually
 * sent is what {@code EmailSlots.parse} refuses a request by name for -- a record with declared components could
 * not tell {@code reference_number: "made up"} from a key nobody sent.
 *
 * <p><strong>Only the slots are the caller's.</strong> The reference number, the applicant's former names and the
 * refund branch are resolved from the case, and a request naming one of them is refused rather than having it
 * stripped -- which is the one place this service refuses an unknown key rather than ignoring it, because these
 * are exactly the keys a client must not send.
 *
 * <p>Distinct from {@link SendEmailRequest} even though it accepts the identical body, for the reason
 * {@code KeyedRequestBody} subclasses per operation: a preview is a read that renders what the caseworker has
 * typed so far, a send is the act of telling an applicant something, and keeping them separate is what lets one
 * gain a rule without the other inheriting it.
 *
 * <p><strong>A {@code POST} for a read, on purpose.</strong> The caseworker's text is the input: it is prose, it
 * may be long, and a query string would put a judge's reason in an access log.
 */
public class PreviewEmailRequest extends KeyedRequestBody {
}
