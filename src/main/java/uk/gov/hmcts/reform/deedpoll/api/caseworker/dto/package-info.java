/**
 * Request and response types for {@code /caseworker/**}.
 *
 * <p>Separate types per operation, and <strong>never an entity returned from a controller</strong>: the
 * entities are a map of the ported schema, so returning one would publish every column the migration happens
 * to carry -- including the ones a caseworker screen never shows.
 *
 * <p>Nothing here is shared with {@link uk.gov.hmcts.reform.deedpoll.api.citizen.dto}, which
 * {@code NamespaceSeparationSecurityTest} asserts. The DTOs themselves arrive with plan 01 tasks 13 to 15.
 */
package uk.gov.hmcts.reform.deedpoll.api.caseworker.dto;
