package uk.gov.hmcts.reform.deedpoll.domain.documents;

import uk.gov.hmcts.reform.deedpoll.entity.enums.DocumentDirection;

/**
 * Create payload for a supporting-document metadata row ({@code deedpoll_supportingdocument}).
 *
 * <p>Ported from the {@code CreateDocumentInput} type in {@code reference/ts/documents/schemas.ts}.
 * {@code direction}, {@code name} and the {@code documentTypeId} foreign key are required, because all
 * three columns are NOT NULL. The file itself is attached separately, which is why the three storage
 * fields are optional -- {@link DocumentService#createDocument} legitimately makes a row with no blob
 * behind it, and {@code DocumentsView} renders such a row as not downloadable rather than linking to a
 * 404.
 *
 * <p><strong>The storage fields are plain nullable references, not {@link
 * uk.gov.hmcts.reform.deedpoll.domain.PatchField}.</strong> The reference declares them {@code nullish()},
 * so a caller may omit them or send null -- but on a create those two are the same outcome, an empty
 * column, so there is nothing for a three-state field to distinguish. {@link UpdateDocumentInput} is where
 * they differ and where that type is needed.
 *
 * <p>{@code sizeBytes} is deliberately absent: it is derived from the file's own bytes by
 * {@link DocumentService#uploadDocument} rather than declared by a caller, so a stated size can never
 * disagree with the stored blob. {@code deedPollId} is absent for the reason
 * {@code UpdateWitnessInput} gives -- the parent is the method's argument, so a payload cannot move a
 * document to another case.
 *
 * <p>Only reachable through {@link DocumentSchemas#parseCreateDocument}, which is what makes "unknown keys
 * are stripped" structural rather than a rule to remember.
 */
public record CreateDocumentInput(
    DocumentDirection direction,
    String name,
    String documentTypeId,
    String storageKey,
    String originalName,
    String mimeType) {

    /** Metadata with no file behind it yet, which is the only shape any caller in this plan creates. */
    public static CreateDocumentInput metadata(DocumentDirection direction, String name, String documentTypeId) {
        return new CreateDocumentInput(direction, name, documentTypeId, null, null, null);
    }
}
