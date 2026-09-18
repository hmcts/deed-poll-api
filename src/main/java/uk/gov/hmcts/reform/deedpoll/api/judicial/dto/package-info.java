/**
 * Request and response types for {@code /judicial/**}.
 *
 * <p><strong>Separate from the caseworker DTOs on purpose, and not by convention.</strong> The judicial view of
 * an application is a narrow projection -- the reference, the application type, the old and new names, and the
 * referral's own reasons, notes and urgency -- and it excludes the caseworker-only and administrative fields:
 * payment and fee references, internal audit trails, and the admin-only privacy editability. A DTO reused from
 * {@code api.caseworker.dto} would carry all of those, so reuse here is a disclosure dressed as
 * deduplication. {@code NamespaceSeparationSecurityTest} is an ArchUnit bytecode rule over this package for
 * exactly that reason: it catches a fully-qualified reference, a field type or a generic parameter, not only an
 * import.
 *
 * <p>The field set above is confirmed rather than provisional (Steve, 2026-09-17). Any <em>addition</em> to it
 * is a fresh legal and policy decision -- "what must a judge see to make a lawful direction, and what must
 * they not?" -- and not an engineering one.
 *
 * <p>See {@link uk.gov.hmcts.reform.deedpoll.api.judicial.controller} for the namespace's boundary rules.
 */
package uk.gov.hmcts.reform.deedpoll.api.judicial.dto;
