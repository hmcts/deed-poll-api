/**
 * The citizen HTTP surface: {@code /citizen/**}.
 *
 * <p><strong>Empty on purpose.</strong> This service will later also serve {@code deed-poll-web}, the
 * citizen-facing service, which is why the namespace exists from the start rather than being carved out of
 * the caseworker one when the first citizen endpoint is written. Nothing in it yet -- anonymous submission
 * and payments are a later plan.
 *
 * <p>It exists now so that plan cannot accidentally share a caseworker DTO. The two audiences are not the
 * same shape of the same thing: a caseworker sees every field on a case including the admin-only ones, and a
 * citizen sees their own application. A DTO reused across both is one field away from exposing the first to
 * the second, and the reuse would look like sensible deduplication at the time.
 *
 * <p>{@code NamespaceSeparationSecurityTest} asserts the separation rather than trusting it. Every route here
 * is authenticated by default -- see {@code SecurityConfiguration} -- so an endpoint added here is protected
 * before anybody remembers to protect it.
 */
package uk.gov.hmcts.reform.deedpoll.api.citizen.controller;
