/**
 * The caseworker HTTP surface: {@code /caseworker/**}.
 *
 * <p>Every route here requires a forwarded IDAM access token, and does so by omission rather than by
 * declaration -- see {@code SecurityConfiguration}.
 *
 * <p><strong>Only the operations something actually calls</strong>, derived from what the sibling
 * {@code deed-poll-admin}'s data-access files do: no deletes, no witness writes, no document-type writes, no
 * document metadata create or patch, and no {@code validate} endpoint, because they have no consumer.
 *
 * <p>Four controllers, one per written resource, plus one for the reads -- which share theirs because they are
 * one thing to a consumer, everything a caseworker screen needs before it can render:
 *
 * <ul>
 *   <li>{@code CaseworkerReadController} -- the case list, one case, the field rules, the document types.</li>
 *   <li>{@code CaseworkerCaseController} -- create and patch a case.</li>
 *   <li>{@code CaseworkerNoteController} -- list, add, read and amend a case's notes.</li>
 *   <li>{@code CaseworkerDocumentController} -- upload a file, and download one.</li>
 * </ul>
 *
 * <p><strong>That is twelve routes, where plan 01's prose says eleven.</strong> The plan's own task list
 * enumerates all twelve -- four reads, two case writes, four note operations, an upload and a download -- so the
 * total is a miscount rather than a decision, the same kind its task 4 records about "two indexes" where the
 * reference declares three. Nothing was dropped to reach eleven and nothing was added to exceed it. The count is
 * recorded here so the discrepancy does not read as a twelfth route somebody slipped in.
 */
package uk.gov.hmcts.reform.deedpoll.api.caseworker.controller;
