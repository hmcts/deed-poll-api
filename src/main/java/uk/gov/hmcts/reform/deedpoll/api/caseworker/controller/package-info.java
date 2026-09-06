/**
 * The caseworker HTTP surface: {@code /caseworker/**}.
 *
 * <p>Every route here requires a forwarded IDAM access token, and does so by omission rather than by
 * declaration -- see {@code SecurityConfiguration}.
 *
 * <p><strong>Eleven operations, and only eleven</strong>, derived from what the sibling
 * {@code deed-poll-admin}'s data-access files actually call: no deletes, no witness writes, no document-type
 * writes and no {@code validate} endpoint, because they have no consumer.
 *
 * <p>{@code CaseworkerReadController} holds the four that only read -- the case list, one case, the field
 * rules and the document types. The seven writes arrive with plan 01 tasks 14 and 15, one controller per
 * resource, so that each write resource's controller is about that resource.
 */
package uk.gov.hmcts.reform.deedpoll.api.caseworker.controller;
