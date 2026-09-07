/**
 * Request and response types for {@code /caseworker/**}.
 *
 * <p>Separate types per operation, and <strong>never an entity returned from a controller</strong>: the
 * entities are a map of the ported schema, so returning one would publish every column the migration happens
 * to carry -- including the ones a caseworker screen never shows.
 *
 * <p>Nothing here is shared with {@link uk.gov.hmcts.reform.deedpoll.api.citizen.dto}, which
 * {@code NamespaceSeparationSecurityTest} asserts. The one thing both namespaces do share is
 * {@link uk.gov.hmcts.reform.deedpoll.api.ApiError}, which carries no case data and is one package up for
 * exactly that reason.
 *
 * <p>"Never an entity" is asserted rather than reviewed for the largest of these:
 * {@code CaseDetailResponseFieldsTest} and {@code SavedCaseResponseFieldsTest} pin
 * {@code CaseDetailResponse}'s and {@code SavedCaseResponse}'s field sets against {@code DeedPollFields}, so a
 * column added to the case has to be published deliberately or omitted deliberately.
 *
 * <p><strong>The case write requests are the one type here that is not a record of declared components.</strong>
 * A case patch turns on the difference between an omitted field and one explicitly set to null, which a record
 * of nullable components cannot express, so {@code CreateCaseRequest} and {@code UpdateCaseRequest} carry the
 * body as it arrived and hand it to the ported schema. {@code CaseWriteRequest} has the reasoning. The remaining
 * request types arrive with plan 01 task 15; the reads need none.
 */
package uk.gov.hmcts.reform.deedpoll.api.caseworker.dto;
