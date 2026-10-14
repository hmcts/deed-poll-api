/**
 * The caller's own surface: {@code GET /me}, what this service will let the caller do.
 *
 * <p><strong>Outside both staff areas on purpose.</strong> A caller the caseworker or judicial area refuses still
 * needs to learn that, so the admin app can say "signed in, but no access" rather than guess from a 403. The route
 * is in {@code SecurityConfiguration.ANY_AUTHENTICATED_PATHS}, the reviewed allowlist {@code StaffAccessRulesTest}
 * reads, rather than slipping through {@code anyRequest().authenticated()}. It is not public: no token is 401.
 */
package uk.gov.hmcts.reform.deedpoll.api.caller.controller;
