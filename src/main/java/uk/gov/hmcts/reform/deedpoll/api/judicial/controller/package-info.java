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
 * <p><strong>Six operations, on one controller, and the six are the whole judicial persona.</strong> Four reads
 * -- the shared pool ({@code GET /judicial/referrals}), this judge's own outstanding work
 * ({@code GET /judicial/referrals/mine}), one referral with its application view, witnesses, documents and the
 * case's notes ({@code GET /judicial/referrals/&#123;referralId&#125;}), and one of those documents' files
 * ({@code GET .../documents/&#123;documentId&#125;/file}) -- and two writes, claiming a pool
 * referral ({@code POST .../claim}) and recording a direction on one they hold ({@code POST .../direction}).
 * Stated as a number here, unlike the caseworker package's deliberate refusal to keep a running total, because
 * six is not a tally of what has accumulated: it is decision 13's boundary, widened deliberately by the business
 * on 2026-10-19 to the whole application and its documents, and a seventh route is a change to that boundary
 * rather than a route.
 *
 * <p>The personal list was the boundary's missing half rather than an addition to it: {@code visibleTo} always
 * admitted a judge's own {@code Claimed} and {@code Decided} referrals, and until there was a list of them a
 * successful claim took the referral off the only page that linked to it. It is a literal segment and not an
 * {@code ?owner=} parameter, so whose list it is comes from the token and cannot be typed -- a list keyed on
 * anything a caller sent would be the enumeration this surface's 404s exist to prevent. Its rows are referral
 * fields only, exactly as the pool's are.
 *
 * <p>In particular there is no judicial <em>closure</em> -- closing a referral is
 * the office's acknowledgement that the direction has been actioned -- no judicial <em>note</em> route, no case
 * list and no case search. A judge cannot enumerate deed poll applications, and cannot compose note text: the
 * claim and direction transitions write a note apiece under the judge's name, but {@code ReferralNotes} composes
 * it and no request body carries any of it.
 *
 * <p>Neither write adds transition logic: both call a {@code ReferralService} transition, which owns the
 * {@code Open}-only rule, the holder-only rule and every column either stamps. So the 409s a judge can meet
 * -- claiming a referral somebody got to first, directing one they no longer hold -- are that service's, stated
 * there once rather than re-implemented here.
 *
 * <p>Every route here is authenticated by default -- see {@code SecurityConfiguration}, whose allowlist is
 * deliberately not per-namespace -- so {@code /judicial/**} was protected the moment it existed, and an
 * unauthenticated caller gets 401 before routing.
 */
package uk.gov.hmcts.reform.deedpoll.api.judicial.controller;
