package uk.gov.hmcts.reform.deedpoll.domain.auth;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Mapping an IDAM identity onto the domain's {@link CurrentUser}.
 *
 * <p>Ported from {@code reference/ts/auth/idam-claims.ts}. The workspace standard is explicit that these
 * are separate concerns: <em>"OpenID Connect establishes the user identity. Services are responsible for
 * their own authorization decisions."</em> So this does the narrowest possible job -- take whatever IDAM
 * asserts and reduce it to the roles and display name this service recognises. Every authorization
 * decision downstream is made against that.
 *
 * <p>In the domain rather than beside the resource-server wiring because it is the same reduction
 * wherever it happens: the API resolves it from a bearer token and a citizen session would resolve it
 * from an ID token, and both must agree on which asserted roles count. It takes a plain claims map, so
 * the domain needs no OIDC library.
 *
 * <p>A {@code Map<String, ?>} where the reference has a deliberately loose {@code IdamClaims} interface,
 * and for the same reason: the claims are a document controlled elsewhere, its exact shape varies by IDAM
 * configuration, and asserting a tight type over it would fail the first time a claim moved. Everything
 * is narrowed defensively below.
 */
public final class IdamClaims {

    private IdamClaims() {
    }

    /**
     * Reduce IDAM's claims to a {@link CurrentUser}.
     *
     * <p><strong>Unrecognised roles are dropped, not carried.</strong> IDAM issues roles across the whole
     * CFT estate, so a caseworker's token can name roles from other services entirely. Keeping them would
     * put strings this service has no rules for into the object its authorization decisions are made from,
     * and {@code isAdmin} would then depend on a list nobody here maintains.
     */
    public static CurrentUser userFromClaims(Map<String, ?> claims) {
        return CurrentUser.currentUser(rolesFrom(claims.get("roles")), displayNameFrom(claims));
    }

    /**
     * IDAM may send roles as an array or, on some configurations, a single comma-separated string. Both
     * are accepted because getting it wrong yields an empty role list, which fails closed but looks like
     * a permissions bug rather than a parsing one -- the expensive kind to diagnose.
     *
     * <p>Anything else -- absent, a number, an object -- is no roles at all.
     *
     * <p>The reference filters against a {@code KNOWN_ROLES} list; here {@link Role} <em>is</em> that
     * list, so {@link Role#fromClaimValue} is the filter and there is nothing to keep in step.
     */
    private static List<Role> rolesFrom(Object raw) {
        return values(raw)
            .map(value -> String.valueOf(value).trim())
            .flatMap(value -> Role.fromClaimValue(value).stream())
            .toList();
    }

    private static Stream<?> values(Object raw) {
        if (raw instanceof Iterable<?> iterable) {
            List<Object> members = new ArrayList<>();
            iterable.forEach(members::add);
            return members.stream();
        }
        if (raw instanceof String single) {
            return Arrays.stream(single.split(","));
        }
        return Stream.empty();
    }

    /**
     * A display name for the audit columns, in descending order of preference.
     *
     * <p>Falls through to {@code sub} before giving up, because {@code createdBy} reading as an opaque
     * identifier is still traceable, whereas the service-account fallback would attribute a real person's
     * write to an automated one. {@link CurrentUser#currentUser} supplies that fallback if everything here
     * is absent.
     */
    private static String displayNameFrom(Map<String, ?> claims) {
        Optional<String> name = string(claims.get("name"));
        if (name.isPresent()) {
            return name.get();
        }

        Optional<String> given = string(claims.get("given_name"));
        Optional<String> family = string(claims.get("family_name"));
        if (given.isPresent() || family.isPresent()) {
            return Stream.of(given, family).flatMap(Optional::stream).reduce((a, b) -> a + " " + b).orElseThrow();
        }

        return string(claims.get("sub")).orElse(null);
    }

    /** A claim as a non-blank string, or empty -- so a blank name is ignored rather than stored. */
    private static Optional<String> string(Object value) {
        if (!(value instanceof String text)) {
            return Optional.empty();
        }
        String trimmed = text.trim();
        return trimmed.isEmpty() ? Optional.empty() : Optional.of(trimmed);
    }
}
