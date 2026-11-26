package uk.gov.hmcts.reform.deedpoll.domain.documents.deletion;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import uk.gov.hmcts.reform.deedpoll.domain.audit.AuditAction;
import uk.gov.hmcts.reform.deedpoll.domain.audit.AuditActor;
import uk.gov.hmcts.reform.deedpoll.domain.audit.AuditEntities;
import uk.gov.hmcts.reform.deedpoll.domain.audit.AuditEntry;
import uk.gov.hmcts.reform.deedpoll.domain.audit.AuditTrail;
import uk.gov.hmcts.reform.deedpoll.domain.audit.AuditValue;
import uk.gov.hmcts.reform.deedpoll.domain.notes.CreateNoteInput;
import uk.gov.hmcts.reform.deedpoll.domain.notes.NoteAuthor;
import uk.gov.hmcts.reform.deedpoll.domain.notes.NoteService;
import uk.gov.hmcts.reform.deedpoll.repository.DocumentDeletionRequestRepository;
import uk.gov.hmcts.reform.deedpoll.repository.DocumentDeletionRequestRepository.DocumentRecord;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The local completion of a permanent document deletion (plan 20261125-permanent-document-deletion, Task 4): once
 * every remote step is confirmed, in the caller's one transaction and with no network call, it deletes the document's
 * row, records one {@code DOCUMENT_DELETED} event and one case note under the <strong>original requester</strong>, and
 * turns the request into its receipt.
 *
 * <p><strong>Only inside an owned transaction.</strong> {@link Propagation#MANDATORY}, and the caller has already
 * locked the case and checked, with the request locked, that its claim is unexpired ({@link DocumentDeletionWork}).
 * So two completions cannot both happen: the second finds the request {@code Completed}, which no claim holds. Any
 * failure (the note, the audit insert, the receipt) rolls the whole of it back, and the confirmed remote steps stay
 * recorded, so the next pass resumes here without repeating them.
 *
 * <p><strong>What it writes.</strong> {@code DOCUMENT_DELETED} carries the reason and the request id in its detail,
 * where a later application disposal of the case's audit values cannot erase them, and the document's last metadata
 * as values. The note is the one the plan suggests: "Document [name] ([id]) permanently deleted. Reason: [reason]",
 * written through {@link NoteService}, so it is stamped and audited ({@code NOTE_CREATED}) as any other note. No V50
 * tombstone is written: nothing is left to inventory, and the request is the evidence (docs/DOCUMENT-DELETION.md).
 */
@Component
public class DocumentDeletionCompletion {

    private final DocumentDeletionRequestRepository requests;
    private final NoteService notes;
    private final AuditTrail audit;

    public DocumentDeletionCompletion(DocumentDeletionRequestRepository requests, NoteService notes,
                                      AuditTrail audit) {
        this.requests = requests;
        this.notes = notes;
        this.audit = audit;
    }

    /** Completion refused because what it needs is not there; thrown so the caller's transaction rolls back. */
    public static final class Refused extends RuntimeException {
        private static final long serialVersionUID = 1L;

        Refused(String message) {
            super(message, null, false, false);
        }
    }

    /**
     * Completes {@code request}, whose case and document the caller has locked in that order.
     *
     * @param document the document's row, locked; never empty for a request still holding its fence
     * @throws Refused if the request holds no reason or the receipt could not be written
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void complete(DocumentDeletionRequest request, DocumentRecord document, LocalDateTime now) {
        DocumentDeletionReason reason = request.reason()
            .orElseThrow(() -> new Refused("A document deletion request to complete holds no reason"));
        AuditActor requester = new AuditActor(request.requestedBySubject(), request.requestedByName(), false);

        if (requests.deleteDocument(request.caseId(), request.documentId()) != 1) {
            throw new Refused("The document to delete was not there");
        }
        Map<String, Object> metadata = metadata(document);
        List<AuditValue> values = metadata.entrySet().stream()
            .map(field -> AuditValue.of(field.getKey(), field.getValue(), null))
            .toList();
        AuditEntry deleted = AuditEntry.of(AuditAction.DOCUMENT_DELETED, requester, AuditEntities.DOCUMENT,
                                           request.documentId(), request.caseId())
            .withFields(metadata.keySet())
            .withDocumentDeletion(reason, request.id());
        if (document.documentTypeId() != null) {
            deleted = deleted.withDetail("documentTypeId", document.documentTypeId());
        }
        audit.record(deleted, values);
        notes.createNote(request.caseId(), new CreateNoteInput(noteText(request, reason)),
                         NoteAuthor.user(request.requestedByName(), request.requestedBySubject()));
        if (!requests.complete(request.id(), now)) {
            throw new Refused("The document deletion request could not be completed");
        }
    }

    /** The case note, from the name the requester confirmed, the document's id and their own words. */
    static String noteText(DocumentDeletionRequest request, DocumentDeletionReason reason) {
        return "Document " + request.documentName() + " (" + request.documentId() + ") permanently deleted. Reason: "
            + reason.value();
    }

    /**
     * The fields {@code DocumentUploadAudit} records of a document, in the same order.
     */
    private static Map<String, Object> metadata(DocumentRecord document) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("name", document.name());
        metadata.put("direction", document.direction());
        metadata.put("documentTypeId", document.documentTypeId());
        metadata.put("originalName", document.originalName());
        metadata.put("mimeType", document.mimeType());
        metadata.put("sizeBytes", document.sizeBytes());
        return metadata;
    }
}
