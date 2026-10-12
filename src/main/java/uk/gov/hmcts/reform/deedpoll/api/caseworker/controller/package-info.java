/**
 * The caseworker HTTP surface: {@code /caseworker/**}.
 *
 * <p>Every route here requires a forwarded IDAM access token, and does so by omission rather than by
 * declaration -- see {@code SecurityConfiguration}.
 *
 * <p><strong>Only the operations something actually calls</strong>, derived from what the sibling
 * {@code deed-poll-admin} does: no document-type writes, no document metadata create or patch, and no
 * {@code validate} endpoint, because they have no consumer. Two deletes require explicit capabilities, a note's and a
 * witness's: {@code DELETE_NOTE} and {@code DELETE_WITNESS}, enforced in {@code NoteService.removeNote}
 * and {@code WitnessService.removeWitness} rather than here, so the rule holds for a caller that is not a route.
 * A saved query's delete is not role-gated but <em>ownership</em>-gated, with a
 * {@code MANAGE_SHARED_QUERIES} override for a shared one, in {@code SavedQueryService}; unselecting one
 * is the caller's own privilege.
 * The witness writes joined on the same test: the admin renders a case's witnesses and had no way to record
 * one, so the consumer arrived and the routes followed.
 *
 * <p>One controller per written resource, plus one for the reads -- which share theirs because they are
 * one thing to a consumer, everything a caseworker screen needs before it can render:
 *
 * <ul>
 *   <li>{@code CaseworkerReadController} -- the case list, one case, the field rules, the document types.</li>
 *   <li>{@code CaseworkerCaseController} -- create and patch a case.</li>
 *   <li>{@code CaseworkerNoteController} -- list, add, read, amend and delete a case's notes.</li>
 *   <li>{@code CaseworkerWitnessController} -- list, add, read, amend and delete a case's witnesses.</li>
 *   <li>{@code CaseworkerDocumentController} -- upload a file, and download one.</li>
 *   <li>{@code CaseworkerSavedQueryController} -- list, save, read, amend and delete a saved query, and select
 *       or unselect one. The only resource here not nested under a case, because a saved query is a way of
 *       finding cases rather than a thing belonging to one.</li>
 *   <li>{@code CaseworkerReferralController} -- list, raise, read, withdraw and close a referral to the
 *       judiciary. No amend: a referral is not edited in place, so withdrawal and closure are sub-resources
 *       rather than a patch or a delete. The one resource here with no role gate anywhere below it,
 *       deliberately -- there is no "may refer" permission and no "may close" one. The judge's own operations,
 *       claiming a referral and recording a direction, are {@code ReferralService} transitions gated on
 *       {@code Role#JUDICIAL} with no route in this package at all: they belong to a judicial surface rather
 *       than to the caseworker one.</li>
 *   <li>{@code CaseworkerCcdAttemptController} -- the CCD creation attempts that are not linked, and the two
 *       ways out of {@code NeedsRepair}. {@code REPAIR_CCD} is enforced in {@code CcdAttemptRepairs}; these routes
 *       cover this service's own plumbing rather than a case, so they sit beside the cases and not under one.</li>
 *   <li>The three Gazette controllers -- history, settings, preview, submission, request status and members;
 *       all require {@code ACCESS_GAZETTE}, as does the named {@code readyForPublication} case-list filter.</li>
 * </ul>
 *
 * <p><strong>Every route in this package is one of those bullets; there is no route here that no controller
 * above describes.</strong> That is the property worth stating -- a running total is not, because it goes stale
 * the moment a route is added, which is exactly how the count recorded here came to say "twelve" after the note
 * delete made it thirteen -- and the four witness routes made it seventeen, the witness delete eighteen, the
 * seven saved-query routes twenty-five, the four referral routes twenty-nine and the referral closure thirty.
 * Read the count off the mappings when you need it.
 *
 * <p>The reason a count was ever written down: plan 01's prose says eleven, while its own task list enumerates
 * more than that -- so the shortfall was a miscount rather than a decision, the same kind its task 4 records
 * about "two indexes" where the reference declares three. Nothing was dropped to reach eleven and nothing was
 * smuggled in to exceed it.
 */
package uk.gov.hmcts.reform.deedpoll.api.caseworker.controller;
