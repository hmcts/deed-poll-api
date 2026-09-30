package uk.gov.hmcts.reform.deedpoll.domain.documents;

import uk.gov.hmcts.reform.deedpoll.entity.DeedPoll;
import uk.gov.hmcts.reform.deedpoll.entity.DeedPollSupportingDocument;
import uk.gov.hmcts.reform.deedpoll.entity.DeedPollSupportingDocumentType;
import uk.gov.hmcts.reform.deedpoll.entity.enums.DocumentDirection;

import java.util.Objects;
import java.util.Optional;

/**
 * One upload as the row will record it: the case, the type, the file, and the name and direction already resolved.
 *
 * <p>Resolved once, here, so that the filesystem path in {@link DocumentService} and the CDAM path in
 * {@link RemoteDocumentUploads} cannot name or direct the same upload differently.
 *
 * @param deedPollId     the case the document belongs to, from the path and never from a form field
 * @param documentTypeId the type the caller filed it under
 * @param file           the bytes and what the client said about them
 * @param name           the stored name: the caller's, capped at the column's width, or the file's own
 * @param direction      the caller's, or {@link #DEFAULT_DIRECTION}
 */
public record DocumentUpload(String deedPollId,
                             String documentTypeId,
                             UploadedFile file,
                             String name,
                             DocumentDirection direction) {

    /**
     * The direction an upload gets when the caller does not say: an applicant-supplied document.
     *
     * <p>The reference defaults the same way, and the reason is which way round the traffic mostly goes --
     * the court receives far more than it sends, and a default that is wrong for the rarer case is corrected
     * by a caseworker who has to think about direction anyway when sending something out.
     */
    public static final DocumentDirection DEFAULT_DIRECTION = DocumentDirection.Incoming;

    public DocumentUpload {
        Objects.requireNonNull(deedPollId, "deedPollId");
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(direction, "direction");
    }

    /**
     * An upload with the caller's options applied.
     *
     * <p>The stored name is the caller's, capped at the column's 100 characters, falling back to the file's own
     * name when the caller gives none or gives an empty one -- the reference's {@code ||} rather than {@code ??},
     * because an empty name in the grid is no more use than no name at all.
     */
    public static DocumentUpload of(String deedPollId,
                                    String documentTypeId,
                                    UploadedFile file,
                                    Optional<String> name,
                                    Optional<DocumentDirection> direction) {
        return new DocumentUpload(deedPollId, documentTypeId, file,
            cap(name.filter(given -> !given.isEmpty()).orElse(file.originalName())),
            direction.orElse(DEFAULT_DIRECTION));
    }

    /** A new row describing this upload, with no storage recorded on it yet. */
    public DeedPollSupportingDocument newRow(DeedPoll parent, DeedPollSupportingDocumentType type) {
        DeedPollSupportingDocument document = new DeedPollSupportingDocument();
        document.setDeedPoll(parent);
        document.setDocumentType(type);
        document.setDirection(direction);
        document.setName(name);
        document.setOriginalName(file.originalName());
        document.setMimeType(file.mimeType());
        document.setSizeBytes(file.size());
        return document;
    }

    /** What a store is asked to keep for this upload, on behalf of the record as it stands. */
    public DocumentToStore toStore(DocumentOwner owner) {
        return new DocumentToStore(file.content(), file.originalName(), file.mimeType(), owner);
    }

    /**
     * The reference's {@code .slice(0, 100)}: the column's width, applied rather than left to fail.
     */
    private static String cap(String name) {
        return name.length() <= DocumentSchemas.MAX_NAME_LENGTH
            ? name
            : name.substring(0, DocumentSchemas.MAX_NAME_LENGTH);
    }
}
