package uk.gov.hmcts.reform.deedpoll.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.security.authentication.AuthenticationTrustResolver;
import org.springframework.security.authentication.AuthenticationTrustResolverImpl;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;

/**
 * The one place an authentication or authorisation failure is logged (audit plan, decision 12).
 *
 * <p>Each line carries the MDC keys {@value #EVENT}, {@value #SUBJECT}, {@value #PATH} and {@value #REASON}, and
 * says the same in its text for a reader without structured logs. The keys are removed again before the call
 * returns, so nothing set here outlives the line, let alone the request.
 *
 * <p><strong>Identifiers only.</strong> The subject is the IDAM subject, an opaque id; the path is the request URI,
 * which has no query string; and the reason is a fixed code. A 401's reason is the OAuth2 error code and never the
 * error's description, whose text a decoder may build from the token. Nothing from the token, the userinfo body, a
 * cookie or a header is logged.
 */
public final class SecurityEvents {

    public static final String EVENT = "security.event";
    public static final String SUBJECT = "subject";
    public static final String PATH = "path";
    public static final String REASON = "reason";

    public static final String UNAUTHENTICATED = "auth.unauthenticated";
    public static final String DENIED = "authz.denied";

    /** A filter-chain rule refused the caller. */
    public static final String ACCESS_RULE = "access_rule";

    /** The domain refused the caller after the chain admitted them. */
    public static final String DOMAIN_RULE = "domain_rule";

    private static final Logger LOG = LoggerFactory.getLogger(SecurityEvents.class);

    private static final AuthenticationTrustResolver TRUST = new AuthenticationTrustResolverImpl();

    private SecurityEvents() {
    }

    /** A request the chain could not authenticate. */
    public static void unauthenticated(String path, AuthenticationException failure) {
        String reason = reasonFor(failure);
        log(UNAUTHENTICATED, null, path, reason,
            () -> LOG.warn("{} path={} reason={}", UNAUTHENTICATED, path, reason));
    }

    /**
     * An authenticated caller refused, by the chain or by the domain, as {@code reason} says.
     */
    public static void denied(String path, String reason) {
        String subject = currentSubject();
        log(DENIED, subject, path, reason,
            () -> LOG.warn("{} subject={} path={} reason={}", DENIED, subject, path, reason));
    }

    static String reasonFor(AuthenticationException failure) {
        if (failure instanceof OAuth2AuthenticationException oauth2) {
            return oauth2.getError().getErrorCode();
        }
        if (failure instanceof InsufficientAuthenticationException) {
            return "missing_token";
        }
        return "authentication_failed";
    }

    private static String currentSubject() {
        Authentication caller = SecurityContextHolder.getContext().getAuthentication();
        return TRUST.isAuthenticated(caller) ? caller.getName() : null;
    }

    private static void log(String event, String subject, String path, String reason, Runnable line) {
        try {
            MDC.put(EVENT, event);
            if (subject != null) {
                MDC.put(SUBJECT, subject);
            }
            MDC.put(PATH, path);
            MDC.put(REASON, reason);
            line.run();
        } finally {
            MDC.remove(EVENT);
            MDC.remove(SUBJECT);
            MDC.remove(PATH);
            MDC.remove(REASON);
        }
    }
}
