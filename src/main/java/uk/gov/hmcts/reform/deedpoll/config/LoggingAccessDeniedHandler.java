package uk.gov.hmcts.reform.deedpoll.config;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import uk.gov.hmcts.reform.deedpoll.auth.SecurityEvents;

import java.io.IOException;

/**
 * Logs {@code authz.denied} through {@link SecurityEvents}, then answers exactly as the delegate does.
 *
 * <p>The delegate is whatever the chain answered with before: {@link StaffAccessDeniedHandler} on the main chain,
 * for its body, and Spring's {@code BearerTokenAccessDeniedHandler} on {@code /ccd-persistence/**}, for its
 * {@code WWW-Authenticate: Bearer}. {@code AccessDeniedHandlerImpl} would have dropped that header, so it is not
 * used there.
 */
public final class LoggingAccessDeniedHandler implements AccessDeniedHandler {

    private final AccessDeniedHandler delegate;

    public LoggingAccessDeniedHandler(AccessDeniedHandler delegate) {
        this.delegate = delegate;
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException refusal)
        throws IOException, ServletException {
        SecurityEvents.denied(request.getRequestURI(), SecurityEvents.ACCESS_RULE);
        delegate.handle(request, response, refusal);
    }
}
