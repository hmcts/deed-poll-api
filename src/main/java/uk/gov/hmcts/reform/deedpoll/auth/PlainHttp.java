package uk.gov.hmcts.reform.deedpoll.auth;

import org.springframework.core.env.Environment;

import java.util.Arrays;
import java.util.Set;

/**
 * Whether this process may talk to an IDAM issuer over plain HTTP.
 *
 * <p>Ported from {@code reference/ts/auth/plain-http.ts}. Cleartext matters because of what crosses the
 * connection: the token response carries the tokens themselves, the JWKS carries the keys every signature
 * is checked against, and {@code /o/userinfo} carries the roles authorisation is derived from. Anything on
 * the path can read all three, and rewrite the last two.
 *
 * <p><strong>This is an allowlist, and the polarity is deliberate.</strong> The reference's own words: a
 * default-open reading is fine for choosing a log level and wrong for a security relaxation, because it
 * would permit cleartext for any value that merely is not the literal string "production" -- an unset
 * variable, a Helm env override, or a deployed environment named {@code aat}. An allowlist requires the
 * relaxation to be asked for. <strong>Do not "correct" this to a negation.</strong>
 *
 * <p>The reference reads {@code NODE_ENV}; the equivalent here is the set of active Spring profiles, since
 * Java has no such variable and {@code SPRING_PROFILES_ACTIVE} is the thing a deployment actually sets.
 * The two permitted names are the reference's two, unchanged. A developer running against a cleartext
 * local IDAM therefore sets {@code SPRING_PROFILES_ACTIVE=development}; the alternative -- permitting it
 * whenever no profile is set -- is the default-open reading this exists to refuse.
 */
public final class PlainHttp {

    /** The only environments in which a cleartext issuer is tolerated. */
    public static final Set<String> PLAIN_HTTP_PROFILES = Set.of("development", "test");

    private PlainHttp() {
    }

    public static boolean plainHttpPermitted(Environment environment) {
        return Arrays.stream(environment.getActiveProfiles()).anyMatch(PLAIN_HTTP_PROFILES::contains);
    }
}
