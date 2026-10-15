package uk.gov.hmcts.reform.deedpoll.domain.documents;

import org.springframework.stereotype.Component;
import uk.gov.hmcts.reform.deedpoll.domain.audit.AuditAction;
import uk.gov.hmcts.reform.deedpoll.domain.audit.AuditActor;
import uk.gov.hmcts.reform.deedpoll.domain.audit.AuditEntities;
import uk.gov.hmcts.reform.deedpoll.domain.audit.AuditEntry;
import uk.gov.hmcts.reform.deedpoll.domain.audit.AuditTrail;
import uk.gov.hmcts.reform.deedpoll.domain.audit.AuditValue;
import uk.gov.hmcts.reform.deedpoll.entity.DeedPollSupportingDocument;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The audit events for a document's row: the upload both stores' paths record, and the metadata writes.
 *
 * <p>Its metadata only, and never the file: the content is not a value anybody needs to compare, it is applicant
 * data far beyond what the trail holds, and it already has a store. An upload is recorded in the transaction that
 * first writes the row -- the filesystem upload's one, or a CDAM upload's claim -- so a row cannot commit without its
 * event and a remote upload whose answer is lost still has one. Never the CDAM id or hash token, which a CDAM row
 * gains later, and never the storage key, which locates the bytes rather than describing them.
 */
@Component
public class DocumentUploadAudit {

    private final AuditTrail audit;

    public DocumentUploadAudit(AuditTrail audit) {
        this.audit = audit;
    }

    /** Records that this new row was uploaded by this actor. */
    public void uploaded(DeedPollSupportingDocument row, AuditActor actor) {
        Map<String, Object> metadata = metadata(row);
        record(AuditAction.DOCUMENT_UPLOADED, actor, row, metadata.keySet(), metadata.entrySet().stream()
            .map(field -> AuditValue.created(field.getKey(), field.getValue()))
            .toList());
    }

    /** Records that this row was created with no file, by the service account. */
    void created(DeedPollSupportingDocument row) {
        Map<String, Object> metadata = metadata(row);
        record(AuditAction.DOCUMENT_CREATED, AuditActor.system(), row, metadata.keySet(), metadata.entrySet().stream()
            .map(field -> AuditValue.created(field.getKey(), field.getValue()))
            .toList());
    }

    /**
     * Records that the service account amended this row, naming the fields the patch did, and a value for each
     * metadata field that changed from {@code before}.
     */
    void updated(Map<String, Object> before, DeedPollSupportingDocument row, Collection<String> fields) {
        Map<String, Object> after = metadata(row);
        record(AuditAction.DOCUMENT_UPDATED, AuditActor.system(), row, fields, after.entrySet().stream()
            .map(field -> AuditValue.of(field.getKey(), before.get(field.getKey()), field.getValue()))
            .toList());
    }

    /** Records that the service account deleted this row, with each metadata field's last value. */
    void deleted(DeedPollSupportingDocument row) {
        Map<String, Object> metadata = metadata(row);
        record(AuditAction.DOCUMENT_DELETED, AuditActor.system(), row, metadata.keySet(), metadata.entrySet().stream()
            .map(field -> AuditValue.of(field.getKey(), field.getValue(), null))
            .toList());
    }

    /** The fields the trail records of a document, by name, in a fixed order. */
    static Map<String, Object> metadata(DeedPollSupportingDocument row) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("name", row.getName());
        metadata.put("direction", row.getDirection());
        metadata.put("documentTypeId", row.getDocumentType().getId());
        metadata.put("originalName", row.getOriginalName());
        metadata.put("mimeType", row.getMimeType());
        metadata.put("sizeBytes", row.getSizeBytes());
        return metadata;
    }

    private void record(AuditAction action, AuditActor actor, DeedPollSupportingDocument row,
                        Collection<String> fields, List<AuditValue> values) {
        String typeId = row.getDocumentType().getId();
        audit.record(AuditEntry.of(action, actor, AuditEntities.DOCUMENT, row.getId(), row.getDeedPoll().getId())
                         .withFields(fields)
                         .withDetail("documentTypeId", typeId),
                     values);
    }
}
