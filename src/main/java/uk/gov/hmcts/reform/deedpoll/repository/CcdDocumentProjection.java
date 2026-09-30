package uk.gov.hmcts.reform.deedpoll.repository;

import java.util.UUID;

/**
 * What a CCD case shows of one supporting document: the item's id, where CDAM holds it, and its file name.
 *
 * <p>A projection for {@link CcdCaseProjection}'s reason: the case view hands what it is given to CCD, so the row's
 * other columns -- the hash token above all, which is transient (plan 03a, decision 8) -- are never selected, and no
 * entity is loaded to answer it. Only {@code Attached} rows are ever selected into one.
 *
 * @param documentId     the deed-poll document id, which is the collection item's id
 * @param cdamDocumentId the CDAM document id the URLs are built from
 * @param filename       the file's own name
 */
public record CcdDocumentProjection(String documentId, UUID cdamDocumentId, String filename) {
}
