package uk.gov.hmcts.reform.deedpoll.domain.documents;

import uk.gov.hmcts.reform.deedpoll.entity.enums.DocumentDirection;

import java.util.Optional;

/**
 * The non-file fields of the upload form, which arrive alongside the file in a multipart body.
 *
 * <p>Ported from the {@code UploadFormInput} type in {@code reference/ts/documents/schemas.ts}. Separate
 * from {@link CreateDocumentInput} because an upload supplies less: {@code direction} and {@code name} are
 * optional -- the domain defaults direction to Incoming and falls back to the file's own name -- and the
 * storage fields are set from the file rather than by the caller.
 *
 * <p>The reference records why this is validated at all: the prototype read {@code documentTypeId} and
 * friends as raw body primitives, which its whitelisting validation pipe skipped, so an unrecognised
 * {@code direction} reached the database and a missing {@code documentTypeId} produced a foreign-key
 * failure. Both are 400s here.
 *
 * <p><strong>Every value arrives as a string: multipart carries no types.</strong> That only shows in
 * {@code direction}, whose enum members are strings anyway -- {@link DocumentSchemas#parseUploadForm}
 * accepts the member name and refuses anything else.
 *
 * <p>{@link Optional} rather than {@code PatchField}, because there is no clearing to do on a create: an
 * absent {@code name} means "use the file's own", not "store no name".
 */
public record UploadFormInput(
    String documentTypeId,
    Optional<String> name,
    Optional<DocumentDirection> direction) {
}
