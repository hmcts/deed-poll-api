/**
 * The caseworker HTTP surface: {@code /caseworker/**}.
 *
 * <p>Every route here requires a forwarded IDAM access token, and does so by omission rather than by
 * declaration -- see {@code SecurityConfiguration}.
 *
 * <p>The controllers themselves arrive with plan 01 tasks 13 to 15. <strong>Eleven operations, and only
 * eleven</strong>, derived from what the sibling {@code deed-poll-admin}'s data-access files actually call:
 * no deletes, no witness writes, no document-type writes and no {@code validate} endpoint, because they have
 * no consumer.
 */
package uk.gov.hmcts.reform.deedpoll.api.caseworker.controller;
