package uk.gov.hmcts.reform.deedpoll.domain.documents;

import uk.gov.hmcts.reform.deedpoll.domain.PatchField;
import uk.gov.hmcts.reform.deedpoll.entity.enums.DocumentDirection;

/**
 * Update payload for a document's metadata: the create rules, applied only to the fields that are present.
 *
 * <p>Ported from the {@code UpdateDocumentInput} type in {@code reference/ts/documents/schemas.ts}, which
 * is {@code createDocumentSchema.partial()}.
 *
 * <p>{@link PatchField} throughout rather than nullable fields, because this patch is the one place in the
 * documents port where all three PATCH states are reachable. The three storage columns are nullable, so
 * clearing {@code storageKey} -- detaching a blob from its metadata row -- is a legitimate write that must
 * stay distinct from not mentioning it. {@code direction}, {@code name} and {@code documentTypeId} back
 * NOT NULL columns, so {@link DocumentSchemas#parseUpdateDocument} refuses a null for those rather than
 * storing one; they use the same type so the record reads in one idiom rather than two.
 *
 * <p>{@code deedPollId} is not expressible here, which is how a patch is prevented from moving a document
 * to another case -- structurally, rather than by a rule in the service a later method could forget. Nor is
 * {@code sizeBytes}: a stated size that disagrees with the stored blob would make the download's own
 * headers wrong, so it is only ever set from the file.
 */
public record UpdateDocumentInput(
    PatchField<DocumentDirection> direction,
    PatchField<String> name,
    PatchField<String> documentTypeId,
    PatchField<String> storageKey,
    PatchField<String> originalName,
    PatchField<String> mimeType) {

    /** An empty patch, which is accepted and changes nothing. */
    public static final UpdateDocumentInput EMPTY = new UpdateDocumentInput(
        PatchField.absent(),
        PatchField.absent(),
        PatchField.absent(),
        PatchField.absent(),
        PatchField.absent(),
        PatchField.absent());

    /** True when the patch names no field at all, so a caller can tell a no-op from a change. */
    public boolean isEmpty() {
        return !direction.present()
            && !name.present()
            && !documentTypeId.present()
            && !storageKey.present()
            && !originalName.present()
            && !mimeType.present();
    }
}
