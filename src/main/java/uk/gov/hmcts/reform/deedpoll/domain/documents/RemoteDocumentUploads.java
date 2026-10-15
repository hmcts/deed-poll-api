package uk.gov.hmcts.reform.deedpoll.domain.documents;

import uk.gov.hmcts.reform.deedpoll.domain.audit.AuditActor;
import uk.gov.hmcts.reform.deedpoll.entity.DeedPollSupportingDocument;

/**
 * An upload to a store that is a remote service, which cannot be one step inside a transaction.
 *
 * <p>The filesystem store's write is local, so {@link DocumentService} stores the bytes and records the row in one
 * transaction. A remote store cannot be called inside one (plan 03a: no remote call inside a database transaction),
 * so an implementation commits a row that says what it is about to do, calls out, and commits where that got to --
 * each step its own transaction, and the row's {@code storageState} the record of it. Present only when such a store
 * is selected; {@link DocumentService} uses it when it is.
 */
public interface RemoteDocumentUploads {

    /**
     * Uploads, attaches and records one document, answering its row as it stands at the end. The commit that first
     * writes the row records its audit event, attributed to {@code actor}.
     *
     * @throws IllegalStateException if called inside a transaction, where no remote call may be made
     * @throws uk.gov.hmcts.reform.deedpoll.domain.NotFoundError if there is no such case, or no such document type
     * @throws uk.gov.hmcts.reform.deedpoll.domain.ConflictError if the case is not ready for documents
     * @throws uk.gov.hmcts.reform.deedpoll.domain.UpstreamFailureError if the store did not take the document, or
     *     its answer was lost; the row records which
     */
    DeedPollSupportingDocument upload(DocumentUpload upload, AuditActor actor);

    /**
     * What the store needs done once the delete of this row commits, beyond removing the file: read now, inside the
     * delete's transaction, and run after it commits, ahead of the file's removal.
     *
     * @return work that never throws; it does nothing when there is nothing to do
     */
    Runnable removal(DeedPollSupportingDocument document);
}
