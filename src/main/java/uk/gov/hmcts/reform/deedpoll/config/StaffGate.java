package uk.gov.hmcts.reform.deedpoll.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.AuthenticationTrustResolver;
import org.springframework.security.authentication.AuthenticationTrustResolverImpl;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;

import java.util.Locale;
import java.util.function.Supplier;

/**
 * The {@code /caseworker/**} membership gate, which can run in report mode before it refuses anybody.
 *
 * <p><strong>Why report mode exists.</strong> The caseworker role is new to most accounts. Enforcing it on the
 * day it is registered would lock out every caseworker not yet assigned it, so the gate first logs who it
 * <em>would</em> refuse, and the logs say whether it is safe to enforce. Report mode grants; it never refuses.
 *
 * <ul>
 *   <li>{@code report}: a caller the area does not admit is logged once per request and let through.</li>
 *   <li>anything else, unset included: refused. Enforcement is the default because a typo in the setting
 *       must not quietly open the gate.</li>
 * </ul>
 *
 * <p>An unauthenticated caller is refused in either mode. The refusal becomes a 401 through the chain's entry
 * point, exactly as {@code anyRequest().authenticated()} answered before, so report mode never admits anyone
 * that authentication did not.
 *
 * <p>The log line carries the IDAM subject, the method and the path, and nothing from the token or the userinfo
 * body: the subject is an opaque identifier, and nothing else is needed to find the person who must be assigned
 * a role.
 *
 * <p>Not an {@link AuthorizationManager} itself, because that interface's one abstract method is deprecated and
 * {@code -Werror} refuses an override of it. {@link #manager()} adapts {@link #decide} instead.
 */
public final class StaffGate {

    private static final Logger LOG = LoggerFactory.getLogger(StaffGate.class);

    private static final AuthenticationTrustResolver TRUST = new AuthenticationTrustResolverImpl();

    private static final AuthorizationDecision GRANTED = new AuthorizationDecision(true);

    private static final AuthorizationDecision DENIED = new AuthorizationDecision(false);

    /** How a caller outside the area is treated. */
    public enum Mode {
        REPORT,
        ENFORCE;

        /**
         * {@code report}, in any case, is report mode; every other value, and none, is enforcement.
         */
        public static Mode of(String value) {
            return value != null && "report".equals(value.trim().toLowerCase(Locale.ROOT)) ? REPORT : ENFORCE;
        }
    }

    private final StaffArea area;
    private final Mode mode;

    public StaffGate(StaffArea area, Mode mode) {
        this.area = area;
        this.mode = mode;
    }

    public Mode mode() {
        return mode;
    }

    /** The decision for one request. */
    public AuthorizationDecision decide(Supplier<Authentication> authentication, RequestAuthorizationContext context) {
        Authentication caller = authentication.get();
        if (!TRUST.isAuthenticated(caller)) {
            return DENIED;
        }
        if (area.admits(caller.getAuthorities())) {
            return GRANTED;
        }
        if (mode == Mode.REPORT) {
            LOG.warn("staff-gate report subject={} method={} path={}", caller.getName(),
                     context.getRequest().getMethod(), context.getRequest().getRequestURI());
            return GRANTED;
        }
        return DENIED;
    }

    /** This gate as the chain takes it. */
    public AuthorizationManager<RequestAuthorizationContext> manager() {
        return this::decide;
    }
}
