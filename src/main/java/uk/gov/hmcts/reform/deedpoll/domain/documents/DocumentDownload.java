package uk.gov.hmcts.reform.deedpoll.domain.documents;

import uk.gov.hmcts.reform.deedpoll.entity.DeedPollSupportingDocument;

import java.io.InputStream;

/**
 * A document's metadata row and a stream over its stored blob.
 *
 * <p>Ported from the {@code {doc, stream}} pair {@code downloadDocument} returns in
 * {@code reference/ts/documents/service.ts}. Both halves are needed at once: the stream is the body, and the
 * row is where the filename and the declared MIME type come from -- which is what task 15's
 * {@code Content-Disposition} and its inline allowlist read.
 *
 * <p><strong>The caller closes the stream.</strong> It is open when this record is handed over, so a caller
 * that discards one leaks a file handle. Task 15's controller hands it to Spring as the response body, which
 * closes it once written.
 *
 * @param document the metadata row, already scoped to its parent case
 * @param stream the blob, open and positioned at its start
 */
public record DocumentDownload(DeedPollSupportingDocument document, InputStream stream) {
}
