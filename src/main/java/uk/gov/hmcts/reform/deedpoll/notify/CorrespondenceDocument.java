package uk.gov.hmcts.reform.deedpoll.notify;

import uk.gov.hmcts.reform.deedpoll.entity.DeedPollSupportingDocument;
import uk.gov.hmcts.reform.deedpoll.entity.Notification;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A case document carried by an email.
 *
 * @param id the case-scoped supporting-document id
 * @param name the document name recorded on the case
 */
public record CorrespondenceDocument(String id, String name) {

    static CorrespondenceDocument from(DeedPollSupportingDocument document) {
        return new CorrespondenceDocument(document.getId(), document.getName());
    }

    static List<CorrespondenceDocument> from(
        Notification notification,
        List<String> documentKeys,
        Map<String, DeedPollSupportingDocument> caseDocuments
    ) {
        return documentKeys.stream()
            .map(notification.getPersonalisation()::get)
            .filter(String.class::isInstance)
            .map(String.class::cast)
            .map(caseDocuments::get)
            .filter(Objects::nonNull)
            .map(CorrespondenceDocument::from)
            .toList();
    }
}
