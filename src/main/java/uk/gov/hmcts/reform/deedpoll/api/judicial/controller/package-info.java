/**
 * The judicial HTTP surface: {@code /judicial/**}.
 *
 * <p>The third namespace, alongside {@code caseworker} and the reserved-empty {@code citizen}, and a namespace
 * rather than a role-gated slice of {@code /caseworker/**} for the reason the citizen one exists: <strong>a
 * judge is not a caseworker with fewer permissions.</strong> They see a different, deliberately narrower
 * projection of an application, and a surface that shared the caseworker DTOs would be one added field away
 * from putting the admin-only privacy fields, the payment references and the internal audit trail in front of
 * the judiciary. {@code NamespaceSeparationSecurityTest} asserts the separation in bytecode rather than
 * trusting it.
 *
 * <p><strong>Keyed on the referral, never on the case.</strong> Every path here is
 * {@code /judicial/referrals} or {@code /judicial/referrals/{referralId}} -- there is no {@code {caseId}} in a
 * judicial URL, because a judge does not browse cases and must not be able to name one. The service resolves
 * the case <em>from</em> the referral. That is a deliberate departure from the caseworker surface's
 * case-scoped nesting, and it moves the safety boundary rather than removing it: where a caseworker read is
 * scoped by case, a judicial read is scoped by <strong>visibility</strong> --
 * {@code JudicialReferralService.visibleTo}, which admits the shared pool and the caller's own claimed and
 * decided referrals and nothing else.
 *
 * <p><strong>An invisible referral is a 404, not a 403.</strong> A withdrawn or closed referral, one another
 * judge holds, and an id that never existed all answer the same way, so no referral id is discoverable and one
 * judge cannot learn that another holds a case. The 403 on this surface is the <em>role</em> gate --
 * {@code CurrentUser.requireAnyRole(Role.JUDICIAL)}, enforced in the domain by every judicial service method,
 * including the two reads, because the pool and the judicial application view belong to the judicial persona
 * and a caseworker has their own richer surface.
 *
 * <p>Every route here is authenticated by default -- see {@code SecurityConfiguration}, whose allowlist is
 * deliberately not per-namespace -- so {@code /judicial/**} was protected the moment it existed, and an
 * unauthenticated caller gets 401 before routing.
 */
package uk.gov.hmcts.reform.deedpoll.api.judicial.controller;
