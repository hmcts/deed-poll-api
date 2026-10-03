package uk.gov.hmcts.reform.deedpoll.storage;

import org.springframework.stereotype.Service;
import uk.gov.hmcts.reform.deedpoll.domain.NotFoundError;
import uk.gov.hmcts.reform.deedpoll.domain.documents.DocumentDownload;
import uk.gov.hmcts.reform.deedpoll.domain.documents.DocumentService;
import uk.gov.hmcts.reform.deedpoll.entity.Notification;
import uk.gov.hmcts.reform.deedpoll.notify.EmailTemplate;
import uk.gov.hmcts.reform.deedpoll.repository.NotificationRepository;

import java.util.Optional;

/** Internal authority for frozen correspondence and deed verification; never exposed by a controller. */
@Service
public class CorrespondenceDocumentReader {
    private final DocumentService documents;
    private final Optional<CdamDocumentStore> remote;
    private final NotificationRepository notifications;

    public CorrespondenceDocumentReader(DocumentService documents, Optional<CdamDocumentStore> remote,
                                         NotificationRepository notifications) {
        this.documents = documents;
        this.remote = remote;
        this.notifications = notifications;
    }

    /** Re-read the durable reference, and refuse any mismatch with the claimed send snapshot. */
    public DocumentDownload forNotification(Notification snapshot, String key) {
        Notification stored = notifications.findById(snapshot.getId()).orElseThrow(
            () -> new NotFoundError("Correspondence not found"));
        if (!stored.getDeedPollId().equals(snapshot.getDeedPollId())
            || !stored.getTemplate().equals(snapshot.getTemplate())
            || !stored.getPersonalisation().equals(snapshot.getPersonalisation())
            || !EmailTemplate.byName(stored.getTemplate()).map(template -> template.documentKeys().contains(key))
                .orElse(false)
            || !(stored.getPersonalisation().get(key) instanceof String id) || id.isBlank()) {
            throw new NotFoundError("Correspondence document reference is unavailable");
        }
        return downloadDocument(stored.getDeedPollId(), id);
    }

    /** Also used to verify a prepared deed before releasing its durable temporary bytes. */
    public DocumentDownload downloadDocument(String caseId, String documentId) {
        if (remote.isEmpty()) {
            return documents.downloadDocument(caseId, documentId);
        }
        var document = documents.readableDocument(caseId, documentId);
        if (document.getCdamDocumentId() == null) {
            throw new NotFoundError("This document has no attached file");
        }
        return new DocumentDownload(document, remote.orElseThrow().openForService(
            document.getCdamDocumentId().toString()));
    }
}
