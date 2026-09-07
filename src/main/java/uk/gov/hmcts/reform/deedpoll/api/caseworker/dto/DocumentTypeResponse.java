package uk.gov.hmcts.reform.deedpoll.api.caseworker.dto;

import uk.gov.hmcts.reform.deedpoll.entity.DeedPollSupportingDocumentType;

import java.util.Arrays;
import java.util.List;

/**
 * One supporting-document type -- global reference data, not nested under a case.
 *
 * <p>Serves both {@code GET /caseworker/document-types} and the {@code documentType} of a document nested in
 * a case, because they are the same resource: a second, narrower shape for the nested case would be a second
 * answer to "what is a document type called".
 *
 * <p>The two option-set lists are published as JSON arrays of the Dynamics integers, which is what they are
 * in the column. They are how the upload form decides which types to offer for a given case, so a caller that
 * could not read them would have to be told the applicability rules some other way.
 *
 * @param id the type id, which is the foreign key a document holds
 * @param name the type's name, as shown in the upload form's options and the documents subgrid
 * @param applicableMaritalStatuses the marital statuses this type applies to, as option-set integers
 * @param applicableTypes the case types this type applies to, as option-set integers
 */
public record DocumentTypeResponse(
    String id,
    String name,
    List<Integer> applicableMaritalStatuses,
    List<Integer> applicableTypes) {

    /** One stored type as the response carries it. */
    public static DocumentTypeResponse from(DeedPollSupportingDocumentType type) {
        return new DocumentTypeResponse(
            type.getId(),
            type.getName(),
            integers(type.getApplicableMaritalStatuses()),
            integers(type.getApplicableTypes()));
    }

    /** Every type, preserving the order the domain returned them in -- which is alphabetical. */
    public static List<DocumentTypeResponse> from(List<DeedPollSupportingDocumentType> types) {
        return types.stream().map(DocumentTypeResponse::from).toList();
    }

    /**
     * An {@code integer[]} column as a JSON array.
     *
     * <p>The entity holds {@code Integer[]} because that is what Hibernate maps a Postgres array to. Null
     * becomes an empty list rather than a JSON null: the column is nullable only because a required Prisma
     * list produces a nullable column, and "no statuses selected" is what a caller has to handle either way.
     */
    private static List<Integer> integers(Integer[] values) {
        return values == null ? List.of() : Arrays.asList(values);
    }
}
