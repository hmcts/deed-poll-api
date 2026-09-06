package uk.gov.hmcts.reform.deedpoll.api.caseworker.dto;

import uk.gov.hmcts.reform.deedpoll.entity.DeedPollSupportingDocument;
import uk.gov.hmcts.reform.deedpoll.entity.enums.DocumentDirection;

import java.time.LocalDateTime;
import java.util.List;

/**
 * One supporting document's metadata, as the caseworker surface publishes it.
 *
 * <p><strong>{@code storageKey} is deliberately not published, and {@link #hasFile} replaces it.</strong> The
 * key is an internal locator -- a filename under {@code LocalFilesystemDocumentStore}'s root today, a CDAM
 * document id once plan 03 lands -- so publishing it would put a value that is about to change shape into a
 * caller's hands, and would name a path on the server for no gain. The only question a consumer asks of it is
 * whether there is anything to download: a metadata row can legitimately exist before its file arrives, so a
 * download link on such a row would 404.
 *
 * <p>The type is nested rather than reduced to its id, because the documents subgrid shows the type's name and
 * a second call per row to resolve it would be four queries to render four rows.
 *
 * @param id the document id, which is what a download addresses
 * @param name the document's name as recorded on the case
 * @param documentType the type this document was filed as
 * @param direction whether the document came in or went out
 * @param originalName the filename as uploaded, or null for a row with no file yet
 * @param mimeType the content type the client declared on upload, or null for a row with no file yet
 * @param sizeBytes the stored size, or null for a row with no file yet
 * @param hasFile whether a file is actually attached, so a caller links only when there is something to fetch
 * @param createdOn when the document was added
 * @param modifiedOn when its metadata was last changed
 */
public record DocumentResponse(
    String id,
    String name,
    DocumentTypeResponse documentType,
    DocumentDirection direction,
    String originalName,
    String mimeType,
    Integer sizeBytes,
    boolean hasFile,
    LocalDateTime createdOn,
    LocalDateTime modifiedOn) {

    /** One stored document as the response carries it. */
    public static DocumentResponse from(DeedPollSupportingDocument document) {
        return new DocumentResponse(
            document.getId(),
            document.getName(),
            // Never null in a stored row -- the foreign key is required -- but the relation is lazy, so this
            // depends on the service having initialised it inside its transaction. It does; see
            // DeedPollService.findDeedPoll and DocumentService.findDocuments.
            document.getDocumentType() == null ? null : DocumentTypeResponse.from(document.getDocumentType()),
            document.getDirection(),
            document.getOriginalName(),
            document.getMimeType(),
            document.getSizeBytes(),
            document.getStorageKey() != null && !document.getStorageKey().isBlank(),
            document.getCreatedOn(),
            document.getModifiedOn());
    }

    /** A case's documents, preserving the order the domain fetched them in. */
    public static List<DocumentResponse> from(List<DeedPollSupportingDocument> documents) {
        return documents.stream().map(DocumentResponse::from).toList();
    }
}
