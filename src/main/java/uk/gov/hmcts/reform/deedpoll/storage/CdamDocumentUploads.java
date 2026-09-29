package uk.gov.hmcts.reform.deedpoll.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import uk.gov.hmcts.reform.deedpoll.ccd.CaseDocumentsEvent;
import uk.gov.hmcts.reform.deedpoll.ccd.CcdLinkingRule;
import uk.gov.hmcts.reform.deedpoll.domain.UpstreamFailureError;
import uk.gov.hmcts.reform.deedpoll.domain.documents.DocumentUpload;
import uk.gov.hmcts.reform.deedpoll.domain.documents.RemoteDocumentUploads;
import uk.gov.hmcts.reform.deedpoll.domain.documents.StorageReceipt;
import uk.gov.hmcts.reform.deedpoll.entity.DeedPollSupportingDocument;
import uk.gov.hmcts.reform.deedpoll.entity.enums.DocumentStorageState;

import java.util.UUID;

/**
 * One caseworker upload into CDAM, from the row's claim to its attachment to the case (plan 03a, task 4).
 *
 * <p><strong>Nothing remote is called inside a transaction.</strong> Each step is committed by
 * {@link DocumentStorageStates} before the next call goes out, so a crash at any point leaves the row in the state
 * that says what may have happened, and a caller already in a transaction is refused rather than joined.
 *
 * <ol>
 *   <li>Claim: the row is committed {@code UploadPending}, after the case-not-ready check, which sends nothing.</li>
 *   <li>Upload: {@code Uploaded} with the CDAM id and the hash token; {@code Failed} when nothing can have been stored;
 *       {@code UploadUncertain} when the answer was lost. A lost answer earns one fresh upload, from this request,
 *       which still holds the bytes (flag 2); a second lost answer stays {@code UploadUncertain}, and there is never a
 *       third.</li>
 *   <li>Attach: the case's document event, carrying the hash, which data-store attaches with. {@code Attached} clears
 *       the token. A refusal, or an event that never reached the submission, is {@code Failed} from {@code Uploaded}
 *       -- the case cannot carry the document -- and a lost answer is {@code AttachUncertain}, which is retried once,
 *       because CDAM skips a document already attached to the same case.</li>
 * </ol>
 *
 * <p>Log lines name the document and the state and are otherwise fixed prose: never the token, never a message from
 * the far side (decision 8).
 */
@Component
@ConditionalOnProperty(name = DocumentStoreProperties.STORE, havingValue = DocumentStoreProperties.CDAM)
public class CdamDocumentUploads implements RemoteDocumentUploads {

    /** How many times one request submits the attach: the first, and one retry after a lost answer. */
    static final int MAX_ATTACH_SUBMISSIONS = 2;

    static final String UPLOAD_REFUSED =
        "The document store did not take this document, so nothing was stored. Try again.";
    static final String UPLOAD_LOST =
        "The document store's answer to this upload was lost twice. It has been kept as uncertain and will not be "
            + "used; upload the file again as a new document.";
    static final String ATTACH_REFUSED =
        "The document was uploaded but CCD did not take it onto the case, so it will not be used. Try again.";
    static final String ATTACH_LOST =
        "The document was uploaded but CCD's answer was lost. It has been kept and is not yet available.";

    private static final Logger LOG = LoggerFactory.getLogger(CdamDocumentUploads.class);

    private final DocumentStorageStates states;
    private final CdamDocumentStore store;
    private final CaseDocumentsEvent event;

    public CdamDocumentUploads(DocumentStorageStates states, CdamDocumentStore store, CaseDocumentsEvent event) {
        this.states = states;
        this.store = store;
        this.event = event;
    }

    @Override
    public DeedPollSupportingDocument upload(DocumentUpload upload) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("A CDAM upload must not be called inside a transaction");
        }
        DocumentStorageStates.Claim claim = states.claim(upload);
        send(upload, claim);
        return attach(claim);
    }

    private void send(DocumentUpload upload, DocumentStorageStates.Claim claim) {
        while (true) {
            StorageReceipt receipt;
            try {
                receipt = store.save(upload.toStore(claim.owner()));
            } catch (CdamUploadFailure failure) {
                if (!failure.mayHaveStored()) {
                    states.failed(claim);
                    LOG.warn("CDAM upload of document {} refused; now Failed: {}", claim.documentId(),
                        failure.getMessage());
                    throw new UpstreamFailureError(UPLOAD_REFUSED);
                }
                states.uploadUncertain(claim);
                LOG.warn("CDAM upload of document {} uncertain: {}", claim.documentId(), failure.getMessage());
                if (states.uploadAgain(claim)) {
                    continue;
                }
                LOG.warn("CDAM upload of document {} stays UploadUncertain", claim.documentId());
                throw new UpstreamFailureError(UPLOAD_LOST);
            }
            states.uploaded(claim, UUID.fromString(receipt.storageKey()), receipt.hashToken().orElseThrow());
            return;
        }
    }

    private DeedPollSupportingDocument attach(DocumentStorageStates.Claim claim) {
        for (int submission = 1; submission <= MAX_ATTACH_SUBMISSIONS; submission++) {
            try {
                event.submit(claim.caseReference(), states.references(claim));
            } catch (CaseDocumentsEvent.NotSubmitted notSubmitted) {
                throw notAttached(claim, notSubmitted.getMessage());
            } catch (RuntimeException failure) {
                if (CcdLinkingRule.classify(failure) == CcdLinkingRule.SubmitFailure.REFUSED) {
                    throw notAttached(claim, "CCD refused the event");
                }
                if (states.stateOf(claim) == DocumentStorageState.Uploaded) {
                    states.attachUncertain(claim);
                }
                LOG.warn("Attach of document {} uncertain after submission {} ({})", claim.documentId(), submission,
                    failure.getClass().getSimpleName());
                continue;
            }
            DeedPollSupportingDocument attached = states.attached(claim);
            LOG.info("Document {} attached to its CCD case", claim.documentId());
            return attached;
        }
        LOG.warn("Attach of document {} stays AttachUncertain", claim.documentId());
        throw new UpstreamFailureError(ATTACH_LOST);
    }

    /**
     * The attach did not happen from this attempt. From {@code Uploaded} nothing reached the case, so the row is
     * {@code Failed}; from {@code AttachUncertain} an earlier submission may have, so the row keeps that state.
     */
    private UpstreamFailureError notAttached(DocumentStorageStates.Claim claim, String why) {
        if (states.stateOf(claim) == DocumentStorageState.Uploaded) {
            states.failed(claim);
            LOG.warn("Attach of document {} not made; now Failed: {}", claim.documentId(), why);
            return new UpstreamFailureError(ATTACH_REFUSED);
        }
        LOG.warn("Attach of document {} not made; stays AttachUncertain: {}", claim.documentId(), why);
        return new UpstreamFailureError(ATTACH_LOST);
    }
}
