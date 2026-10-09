package uk.gov.hmcts.reform.deedpoll.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import uk.gov.hmcts.reform.deedpoll.api.ApiError;

import java.io.IOException;

/**
 * A 403 from the filter chain, in the body a 403 from the domain has.
 *
 * <p>The judicial services already refuse a non-judge with {@code {"error": "Requires one of the following roles:
 * ..."}}, and callers -- admin and this service's own tests -- read it. Spring Security's own 403 has no body, so a
 * chain rule in front of those services would change the contract for the same refusal. This writes the same
 * sentence {@code CurrentUser.requireAnyRole} would, from {@link StaffArea}, so the caller cannot tell which layer
 * refused. A refused path outside every staff area, such as an SDK route, gets a plain sentence.
 */
public final class StaffAccessDeniedHandler implements AccessDeniedHandler {

    private final ObjectMapper json;

    public StaffAccessDeniedHandler(ObjectMapper json) {
        this.json = json;
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException refusal)
        throws IOException {
        String message = StaffArea.of(request.getRequestURI())
            .map(StaffArea::refusal)
            .orElse("Access is denied");
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        json.writeValue(response.getOutputStream(), ApiError.of(message));
    }
}
