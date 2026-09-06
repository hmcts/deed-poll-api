/**
 * The HTTP layer, and the two things both namespaces share.
 *
 * <p>{@code DomainErrorHandler} and {@code ApiError} live here rather than in
 * {@code caseworker} or {@code citizen}, because a refusal has the same shape whoever it is addressed to and
 * two copies of the mapping would be two things to keep in step. Everything else -- every controller, every
 * request and response type -- belongs to exactly one of the sub-packages, which is what
 * {@code NamespaceSeparationSecurityTest} enforces.
 *
 * <p>Nothing here is a place for logic. A controller resolves parameters, calls a domain service and maps the
 * result to a DTO; the rules about deed polls are in {@code uk.gov.hmcts.reform.deedpoll.domain}, where the CCD
 * callbacks of plan 02 and the citizen journey of a later plan will find them.
 */
package uk.gov.hmcts.reform.deedpoll.api;
