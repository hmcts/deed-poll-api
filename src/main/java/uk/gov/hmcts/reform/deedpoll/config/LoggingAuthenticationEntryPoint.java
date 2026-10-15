package uk.gov.hmcts.reform.deedpoll.config;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import uk.gov.hmcts.reform.deedpoll.auth.SecurityEvents;

import java.io.IOException;

/**
 * Logs {@code auth.unauthenticated} through {@link SecurityEvents}, then answers exactly as the delegate does.
 *
 * <p>Both chains wrap Spring's {@code BearerTokenAuthenticationEntryPoint}, so the status and the
 * {@code WWW-Authenticate} challenge are unchanged; only the log line is new.
 */
public final class LoggingAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final AuthenticationEntryPoint delegate;

    public LoggingAuthenticationEntryPoint(AuthenticationEntryPoint delegate) {
        this.delegate = delegate;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException failure)
        throws IOException, ServletException {
        SecurityEvents.unauthenticated(request.getRequestURI(), failure);
        delegate.commence(request, response, failure);
    }
}
