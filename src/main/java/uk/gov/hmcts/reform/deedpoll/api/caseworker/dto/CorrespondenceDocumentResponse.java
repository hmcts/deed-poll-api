package uk.gov.hmcts.reform.deedpoll.api.caseworker.dto;

import uk.gov.hmcts.reform.deedpoll.notify.CorrespondenceDocument;

import java.util.List;

/**
 * A case document carried by an email.
 *
 * @param id the supporting-document id
 * @param name the document name recorded on the case
 */
public record CorrespondenceDocumentResponse(String id, String name) {

    static CorrespondenceDocumentResponse from(CorrespondenceDocument document) {
        return new CorrespondenceDocumentResponse(document.id(), document.name());
    }

    static List<CorrespondenceDocumentResponse> from(List<CorrespondenceDocument> documents) {
        return documents.stream().map(CorrespondenceDocumentResponse::from).toList();
    }
}
