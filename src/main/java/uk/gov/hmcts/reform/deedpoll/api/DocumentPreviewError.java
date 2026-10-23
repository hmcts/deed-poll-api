package uk.gov.hmcts.reform.deedpoll.api;

import uk.gov.hmcts.reform.deedpoll.domain.documents.preview.PreviewError;
import uk.gov.hmcts.reform.deedpoll.domain.documents.preview.PreviewFailure;

/**
 * The body of a document preview that was not produced: the fixed sentence and its stable code, and nothing else.
 *
 * <p>Shared by the caseworker and judicial previews, as {@link ApiError} is, because it carries no case data. A
 * refusal before the preview -- a 404 for a document or referral the caller cannot reach, a 401 or a 403 -- keeps
 * {@link ApiError}; this shape is only ever a failure of the rendition itself, so a client can tell "this file
 * cannot be previewed" from "this file is not yours" by the body as well as the status.
 *
 * @param error the failure's fixed sentence, which never names the file, a parser's diagnostic or the converter
 * @param code the machine-readable code, one of {@link PreviewFailure}'s; {@code OpenAPIDocumentPreviews} publishes
 *     the list from that enum, so the document and the refusals cannot drift
 */
public record DocumentPreviewError(String error, String code) {

    public static DocumentPreviewError from(PreviewError error) {
        PreviewFailure failure = error.failure();
        return new DocumentPreviewError(failure.message(), failure.code());
    }
}
