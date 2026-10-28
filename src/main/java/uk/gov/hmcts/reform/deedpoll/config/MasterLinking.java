package uk.gov.hmcts.reform.deedpoll.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import uk.gov.hmcts.reform.deedpoll.auth.IdamAuthentication;
import uk.gov.hmcts.reform.deedpoll.domain.masters.MasterService;

/**
 * Links a judge to their Masters-list entry on each judicial request, ahead of the handler (plan D1).
 *
 * <p>An interceptor on {@code /judicial/**} rather than a call from each judicial service method, because the lists
 * and the document routes do not share one entry point, and a preview refuses an active transaction: linking commits
 * its own before the handler starts. It runs only for a request the chain already admitted, so only a judge.
 *
 * <p>Best effort. A failure to link is a WARN naming the exception's class and nothing else, and the request goes
 * on: the judge can still do everything they could before, and the next request tries again.
 */
@Configuration
public class MasterLinking implements WebMvcConfigurer, HandlerInterceptor {

    private static final Logger LOG = LoggerFactory.getLogger(MasterLinking.class);

    private final MasterService masters;

    public MasterLinking(MasterService masters) {
        this.masters = masters;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(this).addPathPatterns(StaffArea.JUDICIAL.pattern());
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof IdamAuthentication idam) {
            try {
                masters.linkIfListed(idam.currentUser());
            } catch (RuntimeException failure) {
                LOG.warn("Linking a judicial caller to the Masters list failed: {}", failure.getClass().getName());
            }
        }
        return true;
    }
}
