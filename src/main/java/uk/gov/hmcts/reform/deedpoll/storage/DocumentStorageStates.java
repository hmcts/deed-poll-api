package uk.gov.hmcts.reform.deedpoll.storage;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import uk.gov.hmcts.reform.deedpoll.ccd.SupportingDocumentReference;
import uk.gov.hmcts.reform.deedpoll.domain.NotFoundError;
import uk.gov.hmcts.reform.deedpoll.domain.documents.DocumentOwner;
import uk.gov.hmcts.reform.deedpoll.domain.documents.DocumentUpload;
import uk.gov.hmcts.reform.deedpoll.entity.AuditClock;
import uk.gov.hmcts.reform.deedpoll.entity.DeedPoll;
import uk.gov.hmcts.reform.deedpoll.entity.DeedPollSupportingDocument;
import uk.gov.hmcts.reform.deedpoll.entity.DeedPollSupportingDocumentType;
import uk.gov.hmcts.reform.deedpoll.entity.enums.DocumentStorageState;
import uk.gov.hmcts.reform.deedpoll.repository.DeedPollRepository;
import uk.gov.hmcts.reform.deedpoll.repository.DeedPollSupportingDocumentRepository;
import uk.gov.hmcts.reform.deedpoll.repository.DeedPollSupportingDocumentTypeRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Where one CDAM upload has got to, each step committed on its own (plan 03a, task 4).
 *
 * <p>Every method is one short transaction, and {@link CdamDocumentUploads} calls CDAM and data-store between them and
 * never inside one. Each move locks the row, checks it against {@link DocumentStorageState#successors()}, and says
 * nothing else: which move to make is the orchestrator's decision, and {@link DocumentStorageRule} is the part of it
 * that depends on the row.
 *
 * <p><strong>The hash token is written by {@link #uploaded} and cleared by every move that leaves the two states
 * that hold it</strong> ({@link DocumentStorageState#holdsHashToken()}), as V25's CHECK demands. It is returned only
 * inside {@link #references}, for the one event that must carry it, and never logged here.
 *
 * <p>A move whose row has gone -- deleted by a caseworker while the upload was in flight -- is a 404 naming the
 * document, in {@code DocumentService}'s words. Its CDAM copy, if any, is never attached, so it expires on CDAM's
 * upload TTL.
 */
@Component
public class DocumentStorageStates {

    private final DeedPollRepository deedPolls;
    private final DeedPollSupportingDocumentRepository documents;
    private final DeedPollSupportingDocumentTypeRepository types;

    public DocumentStorageStates(DeedPollRepository deedPolls,
                                 DeedPollSupportingDocumentRepository documents,
                                 DeedPollSupportingDocumentTypeRepository types) {
        this.deedPolls = deedPolls;
        this.documents = documents;
        this.types = types;
    }

    /**
     * An upload's row, and what the rest of the upload needs to know about its owner.
     *
     * @param deedPollId the case, which scopes every later move
     * @param documentId the row
     * @param owner      the record as it stood when the row was claimed, with its CCD case reference
     */
    public record Claim(String deedPollId, String documentId, DocumentOwner owner) {

        public Claim {
            Objects.requireNonNull(deedPollId, "deedPollId");
            Objects.requireNonNull(documentId, "documentId");
            Objects.requireNonNull(owner, "owner");
        }

        /** The CCD case the document is filed under; a claim is only ever made for a record that has one. */
        public String caseReference() {
            return owner.ccdCaseReference().orElseThrow();
        }
    }

    /**
     * Commits the upload's row as {@code UploadPending}, having sent nothing yet, with its first upload counted.
     *
     * <p>The case, the type and the case's readiness are all checked first, in that order, so a refused upload
     * leaves no row behind and sends nothing.
     *
     * @throws NotFoundError if there is no such case, or no such document type
     * @throws uk.gov.hmcts.reform.deedpoll.domain.ConflictError if the case has no CCD case yet
     */
    @Transactional
    public Claim claim(DocumentUpload upload) {
        DeedPoll parent = deedPolls.findById(upload.deedPollId())
            .orElseThrow(() -> new NotFoundError("DeedPoll " + upload.deedPollId() + " not found"));
        DeedPollSupportingDocumentType type = types.findById(upload.documentTypeId())
            .orElseThrow(() -> new NotFoundError("Document type " + upload.documentTypeId() + " not found"));
        DocumentOwner owner = DocumentOwner.of(parent);
        DocumentStorageRule.requireCaseReady(owner.ccdCaseReference());

        DeedPollSupportingDocument row = upload.newRow(parent, type);
        row.setStorageState(DocumentStorageState.UploadPending);
        row.setStorageUploadAttempts((short) 1);
        row.setStorageAttemptedOn(AuditClock.now());
        DeedPollSupportingDocument saved = documents.save(row);
        return new Claim(parent.getId(), saved.getId(), owner);
    }

    /** The upload's answer was lost: it may have reached CDAM. */
    @Transactional
    public void uploadUncertain(Claim claim) {
        move(claim, DocumentStorageState.UploadUncertain, row -> {
        });
    }

    /**
     * Moves an uncertain upload back to {@code UploadPending} for one fresh upload, counting it, if the rule allows.
     *
     * @return whether a fresh upload may now be sent; if not, the row stays {@code UploadUncertain}
     */
    @Transactional
    public boolean uploadAgain(Claim claim) {
        DeedPollSupportingDocument row = lock(claim);
        if (!DocumentStorageRule.mayUploadAgain(row.getStorageState(), row.getStorageUploadAttempts())) {
            return false;
        }
        move(row, DocumentStorageState.UploadPending);
        row.setStorageUploadAttempts((short) (row.getStorageUploadAttempts() + 1));
        row.setStorageAttemptedOn(AuditClock.now());
        return true;
    }

    /** CDAM took the upload: its id, and the hash token the attach must carry. */
    @Transactional
    public void uploaded(Claim claim, UUID cdamDocumentId, String hashToken) {
        Objects.requireNonNull(cdamDocumentId, "cdamDocumentId");
        Objects.requireNonNull(hashToken, "hashToken");
        move(claim, DocumentStorageState.Uploaded, row -> {
            row.setCdamDocumentId(cdamDocumentId);
            row.setCdamHashToken(hashToken);
        });
    }

    /**
     * Nothing is stored against the case: the upload was refused, or the attach was refused before anything reached
     * the case. A CDAM copy that was uploaded and not attached keeps its id here, so it can be found, and expires.
     */
    @Transactional
    public void failed(Claim claim) {
        move(claim, DocumentStorageState.Failed, row -> row.setCdamHashToken(null));
    }

    /** The attach's answer was lost: the case may already carry the document. The token is kept for the retry. */
    @Transactional
    public void attachUncertain(Claim claim) {
        move(claim, DocumentStorageState.AttachUncertain, row -> {
        });
    }

    /**
     * The case carries the document: the token goes, and the row's storage key becomes the CDAM id, which is what
     * makes the row read as having a file.
     *
     * @return the row, with its type loaded for the response
     */
    @Transactional
    public DeedPollSupportingDocument attached(Claim claim) {
        DeedPollSupportingDocument row = move(claim, DocumentStorageState.Attached, attached -> {
            attached.setCdamHashToken(null);
            attached.setStorageKey(attached.getCdamDocumentId().toString());
        });
        row.getDocumentType().getName();
        return row;
    }

    /** The row's state as it stands. */
    @Transactional(readOnly = true)
    public DocumentStorageState stateOf(Claim claim) {
        return documents.findByIdAndDeedPollId(claim.documentId(), claim.deedPollId())
            .map(DeedPollSupportingDocument::getStorageState)
            .orElseThrow(() -> gone(claim));
    }

    /**
     * What the case's document event sets: every document the case already carries, oldest first, then this one with
     * its hash token.
     *
     * <p>The whole collection, because the event sets the field rather than adding to it. Only {@code Attached} rows
     * besides this one, so nothing uncertain or failed is ever put on the case, and none of them carries a token:
     * data-store attaches the document with one and requires none for a document the case already has.
     *
     * @throws IllegalStateException if the row may not be attached
     */
    @Transactional(readOnly = true)
    public List<SupportingDocumentReference> references(Claim claim) {
        DeedPollSupportingDocument row = documents.findByIdAndDeedPollId(claim.documentId(), claim.deedPollId())
            .orElseThrow(() -> gone(claim));
        if (!DocumentStorageRule.mayAttach(row.getStorageState())) {
            throw new IllegalStateException(
                "Document " + row.getId() + " cannot be attached from " + row.getStorageState());
        }
        List<SupportingDocumentReference> references = new ArrayList<>();
        for (DeedPollSupportingDocument attached : documents.findByDeedPollIdAndStorageStateOrderByCreatedOnAsc(
            claim.deedPollId(), DocumentStorageState.Attached)) {
            references.add(reference(attached, Optional.empty()));
        }
        references.add(reference(row, Optional.of(row.getCdamHashToken())));
        return references;
    }

    private static SupportingDocumentReference reference(DeedPollSupportingDocument row, Optional<String> hashToken) {
        return new SupportingDocumentReference(row.getId(), row.getCdamDocumentId(), row.getOriginalName(), hashToken);
    }

    private DeedPollSupportingDocument move(Claim claim,
                                            DocumentStorageState to,
                                            Consumer<DeedPollSupportingDocument> change) {
        DeedPollSupportingDocument row = lock(claim);
        move(row, to);
        change.accept(row);
        return row;
    }

    private static void move(DeedPollSupportingDocument row, DocumentStorageState to) {
        if (!row.getStorageState().canMoveTo(to)) {
            throw new IllegalStateException(
                "Document " + row.getId() + " cannot move from " + row.getStorageState() + " to " + to);
        }
        row.setStorageState(to);
    }

    private DeedPollSupportingDocument lock(Claim claim) {
        return documents.findByIdAndDeedPollIdForUpdate(claim.documentId(), claim.deedPollId())
            .orElseThrow(() -> gone(claim));
    }

    private static NotFoundError gone(Claim claim) {
        return new NotFoundError("Document " + claim.documentId() + " not found");
    }
}
