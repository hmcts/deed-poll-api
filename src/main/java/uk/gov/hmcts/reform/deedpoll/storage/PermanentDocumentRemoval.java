package uk.gov.hmcts.reform.deedpoll.storage;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import uk.gov.hmcts.reform.deedpoll.ccd.CaseDocumentsEvent;
import uk.gov.hmcts.reform.deedpoll.ccd.CcdLinkingRule;
import uk.gov.hmcts.reform.deedpoll.ccd.SupportingDocumentReference;
import uk.gov.hmcts.reform.deedpoll.domain.NotFoundError;
import uk.gov.hmcts.reform.deedpoll.domain.documents.deletion.DocumentDeletionFailure;
import uk.gov.hmcts.reform.deedpoll.domain.documents.deletion.DocumentDeletionRemote;
import uk.gov.hmcts.reform.deedpoll.entity.enums.DocumentStorageState;
import uk.gov.hmcts.reform.deedpoll.repository.DeedPollSupportingDocumentRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/**
 * The remote steps of a permanent document deletion (plan 20261125-permanent-document-deletion, Task 4): the CCD
 * document event, CDAM's permanent delete and the filesystem's verified removal. Each answers what it established and
 * never throws for what the far side said; none runs inside a transaction.
 *
 * <p><strong>The CCD event's list is read just before it is sent</strong>, from the case's {@code Attached} rows as
 * the entity restriction shows them, so a document another deletion has fenced is absent too and two concurrent
 * removals cannot put each other back. The SDK's snapshot is composed by {@code DeedPollCaseView} from the same
 * unfenced rows, whatever the payload says, and {@code SupportingDocumentsHandler} writes nothing from it but an
 * attachment, which a list of attached documents with no hash cannot cause. {@code CdamDocumentUploads.removal}, the
 * internal soft removal, still reads its list inside its own transaction; this path never calls it.
 */
@Component
public class PermanentDocumentRemoval implements DocumentDeletionRemote {

    private final CaseDocumentsEvent event;
    private final DeedPollSupportingDocumentRepository documents;
    private final ObjectProvider<CdamPermanentDelete> cdam;
    private final ObjectProvider<LocalFilesystemDocumentStore> files;

    public PermanentDocumentRemoval(CaseDocumentsEvent event, DeedPollSupportingDocumentRepository documents,
                                    ObjectProvider<CdamPermanentDelete> cdam,
                                    ObjectProvider<LocalFilesystemDocumentStore> files) {
        this.event = event;
        this.documents = documents;
        this.cdam = cdam;
        this.files = files;
    }

    @Override
    public StepOutcome removeCcdReference(String caseId, String documentId, String caseReference,
                                          BooleanSupplier mayStillSubmit) {
        requireNoTransaction();
        List<SupportingDocumentReference> remaining = documents
            .findByDeedPollIdAndStorageStateOrderByCreatedOnAsc(caseId, DocumentStorageState.Attached).stream()
            .filter(attached -> !attached.getId().equals(documentId) && attached.getCdamDocumentId() != null)
            .map(attached -> new SupportingDocumentReference(attached.getId(), attached.getCdamDocumentId(),
                                                             attached.getOriginalName(), Optional.empty()))
            .toList();
        try {
            return event.submit(caseReference, remaining, mayStillSubmit)
                ? StepOutcome.done("SUBMITTED") : StepOutcome.notSent("OUT_OF_TIME");
        } catch (CaseDocumentsEvent.NotSubmitted nothingSent) {
            return StepOutcome.retry("NOT_SUBMITTED");
        } catch (RuntimeException failure) {
            return CcdLinkingRule.classify(failure) == CcdLinkingRule.SubmitFailure.REFUSED
                ? StepOutcome.failed("REFUSED", DocumentDeletionFailure.CCD_REFERENCE_FAILED)
                : StepOutcome.retry("UNKNOWN");
        }
    }

    @Override
    public StepOutcome deleteCdamPermanently(UUID cdamDocumentId) {
        requireNoTransaction();
        CdamPermanentDelete client = cdam.getIfAvailable();
        if (client == null) {
            // A deployment switched off CDAM after admission: nothing is sent, and the tries run out visibly.
            return StepOutcome.retry("STORE_UNAVAILABLE");
        }
        return client.delete(cdamDocumentId);
    }

    @Override
    public StepOutcome removeLocalFile(String storageKey) {
        requireNoTransaction();
        LocalFilesystemDocumentStore store = files.getIfAvailable();
        if (store == null) {
            return StepOutcome.retry("STORE_UNAVAILABLE");
        }
        try {
            return StepOutcome.done(store.remove(storageKey) ? "DELETED" : "ALREADY_ABSENT");
        } catch (NotFoundError invalidKey) {
            return StepOutcome.failed("INVALID_STORAGE_KEY", DocumentDeletionFailure.LOCAL_FILE_UNREMOVED);
        } catch (RuntimeException failed) {
            return StepOutcome.retry("REMOVAL_FAILED");
        }
    }

    private static void requireNoTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("A document deletion's remote step must run outside any transaction");
        }
    }
}
