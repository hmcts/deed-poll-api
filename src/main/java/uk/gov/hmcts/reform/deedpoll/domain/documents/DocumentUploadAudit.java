package uk.gov.hmcts.reform.deedpoll.domain.documents;

import org.springframework.stereotype.Component;
import uk.gov.hmcts.reform.deedpoll.domain.audit.AuditAction;
import uk.gov.hmcts.reform.deedpoll.domain.audit.AuditActor;
import uk.gov.hmcts.reform.deedpoll.domain.audit.AuditEntities;
import uk.gov.hmcts.reform.deedpoll.domain.audit.AuditEntry;
import uk.gov.hmcts.reform.deedpoll.domain.audit.AuditTrail;
import uk.gov.hmcts.reform.deedpoll.domain.audit.AuditValue;
import uk.gov.hmcts.reform.deedpoll.entity.DeedPollSupportingDocument;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The audit event for an uploaded document's row, which both stores' upload paths record.
 *
 * <p>Its metadata only, and never the file: the content is not a value anybody needs to compare, it is applicant
 * data far beyond what the trail holds, and it already has a store. Recorded in the transaction that first writes
 * the row -- the filesystem upload's one, or a CDAM upload's claim -- so a row cannot commit without its event and a
 * remote upload whose answer is lost still has one. Never the CDAM id or hash token, which a CDAM row gains later.
 */
@Component
public class DocumentUploadAudit {

    private final AuditTrail audit;

    public DocumentUploadAudit(AuditTrail audit) {
        this.audit = audit;
    }

    /** Records that this new row was uploaded by this actor. */
    public void uploaded(DeedPollSupportingDocument row, AuditActor actor) {
        String typeId = row.getDocumentType().getId();
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("name", row.getName());
        metadata.put("direction", row.getDirection());
        metadata.put("documentTypeId", typeId);
        metadata.put("originalName", row.getOriginalName());
        metadata.put("mimeType", row.getMimeType());
        metadata.put("sizeBytes", row.getSizeBytes());
        List<AuditValue> values = metadata.entrySet().stream()
            .map(field -> AuditValue.created(field.getKey(), field.getValue()))
            .toList();
        audit.record(AuditEntry.of(AuditAction.DOCUMENT_UPLOADED, actor, AuditEntities.DOCUMENT, row.getId(),
                                   row.getDeedPoll().getId())
                         .withFields(metadata.keySet())
                         .withDetail("documentTypeId", typeId),
                     values);
    }
}
