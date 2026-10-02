package uk.gov.hmcts.reform.deedpoll.storage;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
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

    /** Claim a committed preparation once. A crash after this commit must never grant another upload. */
    @Transactional
    public Claim claimPrepared(String caseId, String documentId) {
        DeedPoll parent = deedPolls.findById(caseId).orElseThrow(() -> new NotFoundError("DeedPoll not found"));
        DocumentOwner owner = DocumentOwner.of(parent);
        DocumentStorageRule.requireCaseReady(owner.ccdCaseReference());
        Claim claim = new Claim(caseId, documentId, owner);
        DeedPollSupportingDocument row = lock(claim);
        if (row.getStorageState() == DocumentStorageState.Attached) {
            return claim;
        }
        if (row.getStorageState() != DocumentStorageState.UploadPending || row.getStorageUploadAttempts() != 0) {
            throw new uk.gov.hmcts.reform.deedpoll.domain.ConflictError(
                "This case already has a deed preparation that is not ready to use. "
                    + "Its storage outcome must be resolved before this email can be sent.");
        }
        row.setStorageUploadAttempts((short) 1);
        row.setStorageAttemptedOn(AuditClock.now());
        return claim;
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
     * <p>Already {@code Attached} is an answer, not an illegal move: {@link #attachedOnTheCase} makes this move inside
     * the event's own transaction, so by the time the event's answer arrives -- or fails to -- the row is usually
     * there already.
     *
     * @return the row, with its type loaded for the response
     */
    @Transactional
    public DeedPollSupportingDocument attached(Claim claim) {
        DeedPollSupportingDocument row = lock(claim);
        if (row.getStorageState() != DocumentStorageState.Attached) {
            attach(row);
        }
        row.getDocumentType().getName();
        return row;
    }

    /**
     * Moves a document the case's document event names to {@code Attached}, inside that event's transaction (plan 03a,
     * task 5).
     *
     * <p>Data-store attaches every document carrying a hash in CDAM <em>before</em> it calls this service's
     * persistence endpoint (docs/CDAM-SEQUENCE.md section 2), so by the time the event's handler runs, CDAM holds the
     * attachment. Making the move here, in the transaction that writes the event, means the SDK's snapshot of the event
     * shows the document, and a lost answer to the event leaves a row that already says it is attached -- recovery by
     * reading, as plan 02's linking operation recovers.
     *
     * <p>Only a row of this case, in a state that may be attached, whose CDAM id is the one the item names: the id in a
     * payload is compared with the row's and never written (decision 7). Anything else -- a document the case already
     * carries, one deleted since, a URL that names another document -- is left alone, because the view composes the
     * case's documents from the rows and a payload item is not truth.
     *
     * @return whether the row moved
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean attachedOnTheCase(String deedPollId, String documentId, UUID cdamDocumentId) {
        Optional<DeedPollSupportingDocument> found = documents.findByIdAndDeedPollIdForUpdate(documentId, deedPollId);
        if (found.isEmpty()) {
            return false;
        }
        DeedPollSupportingDocument row = found.get();
        if (!DocumentStorageRule.mayAttach(row.getStorageState()) || !cdamDocumentId.equals(row.getCdamDocumentId())) {
            return false;
        }
        attach(row);
        return true;
    }

    private static void attach(DeedPollSupportingDocument row) {
        move(row, DocumentStorageState.Attached);
        row.setCdamHashToken(null);
        row.setStorageKey(row.getCdamDocumentId().toString());
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

    /**
     * What the case's document event sets after a delete: every {@code Attached} document the case carries but the one
     * being deleted, oldest first, none with a token.
     *
     * <p>Read inside the delete's own transaction, which is why it is {@code MANDATORY}: the event is sent once that
     * transaction commits, and the list is the one the delete saw.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public List<SupportingDocumentReference> referencesWithout(String deedPollId, String documentId) {
        return documents.findByDeedPollIdAndStorageStateOrderByCreatedOnAsc(deedPollId, DocumentStorageState.Attached)
            .stream()
            .filter(attached -> !attached.getId().equals(documentId))
            .map(attached -> reference(attached, Optional.empty()))
            .toList();
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
