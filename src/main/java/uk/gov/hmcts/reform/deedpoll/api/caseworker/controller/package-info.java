/**
 * The caseworker HTTP surface: {@code /caseworker/**}.
 *
 * <p>Every route here requires a forwarded IDAM access token, and does so by omission rather than by
 * declaration -- see {@code SecurityConfiguration}.
 *
 * <p><strong>Only the operations something actually calls</strong>, derived from what the sibling
 * {@code deed-poll-admin} does: no document-type writes, no document metadata create or patch, and no
 * {@code validate} endpoint, because they have no consumer. The one delete is a note's, which
 * {@code deed-poll-admin}'s note edit form offers; {@code NoteService.removeNote} restricts it to
 * {@code Role#DEEDPOLL_ADMIN}. The witness writes joined on the same test: the admin renders a case's
 * witnesses and had no way to record one, so the consumer arrived and the routes followed. A witness
 * <em>delete</em> is still absent, and is the one witness operation the business restricts.
 *
 * <p>Five controllers, one per written resource, plus one for the reads -- which share theirs because they are
 * one thing to a consumer, everything a caseworker screen needs before it can render:
 *
 * <ul>
 *   <li>{@code CaseworkerReadController} -- the case list, one case, the field rules, the document types.</li>
 *   <li>{@code CaseworkerCaseController} -- create and patch a case.</li>
 *   <li>{@code CaseworkerNoteController} -- list, add, read, amend and delete a case's notes.</li>
 *   <li>{@code CaseworkerWitnessController} -- list, add, read and amend a case's witnesses.</li>
 *   <li>{@code CaseworkerDocumentController} -- upload a file, and download one.</li>
 * </ul>
 *
 * <p><strong>Every route in this package is one of those bullets; there is no route here that no controller
 * above describes.</strong> That is the property worth stating -- a running total is not, because it goes stale
 * the moment a route is added, which is exactly how the count recorded here came to say "twelve" after the note
 * delete made it thirteen -- and the four witness routes have since made it seventeen. Read the count off the
 * mappings when you need it.
 *
 * <p>The reason a count was ever written down: plan 01's prose says eleven, while its own task list enumerates
 * more than that -- so the shortfall was a miscount rather than a decision, the same kind its task 4 records
 * about "two indexes" where the reference declares three. Nothing was dropped to reach eleven and nothing was
 * smuggled in to exceed it.
 */
package uk.gov.hmcts.reform.deedpoll.api.caseworker.controller;
