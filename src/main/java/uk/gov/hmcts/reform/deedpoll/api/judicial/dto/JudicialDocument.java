package uk.gov.hmcts.reform.deedpoll.api.judicial.dto;

import uk.gov.hmcts.reform.deedpoll.entity.DeedPollSupportingDocument;
import uk.gov.hmcts.reform.deedpoll.entity.enums.DocumentDirection;

import java.time.LocalDateTime;
import java.util.List;

/**
 * One supporting document of the application, as a judge is offered it (business decision 3 of 2026-10-19).
 *
 * <p><strong>Only a document with a file a download would serve is ever one of these.</strong> The domain lists
 * {@code DocumentService.readableDocuments}, which applies the same check its download does, so there is no
 * {@code hasFile} flag: a judge is never shown a row whose link would 404.
 *
 * <p><strong>The id is published, unlike a note's or a witness's</strong>, because it is what a download
 * addresses -- and it is safe to publish because it is only ever accepted under a referral the caller can see,
 * and resolved within that referral's case, so a document id from another case is the same 404 as an unknown
 * one. No storage key, original filename, CDAM id or hash token: those are the store's, not the court's.
 *
 * @param id the document id, which the judicial download addresses under its referral
 * @param name the document's name as recorded on the case
 * @param documentType the name of the type it was filed as
 * @param direction whether it came in or went out
 * @param mimeType the content type recorded on upload, or null for a row that predates the column
 * @param sizeBytes the stored size, or null for a row that predates the column
 * @param uploadedOn when the document was added to the case
 */
public record JudicialDocument(
    String id,
    String name,
    String documentType,
    DocumentDirection direction,
    String mimeType,
    Integer sizeBytes,
    LocalDateTime uploadedOn) {

    /** One readable document, narrowed to what a judge is shown. Its type must already be initialised. */
    public static JudicialDocument from(DeedPollSupportingDocument document) {
        return new JudicialDocument(
            document.getId(),
            document.getName(),
            document.getDocumentType() == null ? null : document.getDocumentType().getName(),
            document.getDirection(),
            document.getMimeType(),
            document.getSizeBytes(),
            document.getCreatedOn());
    }

    /** The application's readable documents, in the order the domain fetched them -- oldest first. */
    public static List<JudicialDocument> from(List<DeedPollSupportingDocument> documents) {
        return documents.stream().map(JudicialDocument::from).toList();
    }
}
