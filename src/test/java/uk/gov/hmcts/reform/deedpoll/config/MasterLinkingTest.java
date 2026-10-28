package uk.gov.hmcts.reform.deedpoll.config;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import uk.gov.hmcts.reform.deedpoll.auth.IdamAuthentication;
import uk.gov.hmcts.reform.deedpoll.domain.auth.CurrentUser;
import uk.gov.hmcts.reform.deedpoll.domain.auth.Role;
import uk.gov.hmcts.reform.deedpoll.domain.masters.MasterService;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Linking is best effort: whatever {@link MasterService#linkIfListed} throws, the judge's request goes on.
 */
class MasterLinkingTest {

    private static final String ADDRESS = "master.sullivan@judiciary.example";
    private static final String SUBJECT = "judge-subject-0001";
    private static final CurrentUser JUDGE =
        CurrentUser.currentUser(List.of(Role.JUDICIAL), "Master Sullivan", SUBJECT, ADDRESS);

    private final MasterService masters = mock(MasterService.class);
    private final MasterLinking linking = new MasterLinking(masters);
    private final Logger logger = (Logger) LoggerFactory.getLogger(MasterLinking.class);
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();

    @BeforeEach
    void capture() {
        logs.start();
        logger.addAppender(logs);
    }

    @AfterEach
    void release() {
        logger.detachAppender(logs);
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("a failure to link is one WARN naming the exception's class, and the request proceeds")
    void failureProceeds() {
        SecurityContextHolder.getContext().setAuthentication(new IdamAuthentication(
            Jwt.withTokenValue("token").header("alg", "none").subject(SUBJECT).build(), JUDGE));
        willThrow(new DataIntegrityViolationException("Key (idam_subject)=(" + SUBJECT + ") for " + ADDRESS))
            .given(masters).linkIfListed(JUDGE);

        assertThat(linking.preHandle(new MockHttpServletRequest(), new MockHttpServletResponse(), new Object()))
            .isTrue();

        verify(masters).linkIfListed(JUDGE);
        assertThat(logs.list).extracting(ILoggingEvent::getFormattedMessage).containsExactly(
            "Linking a judicial caller to the Masters list failed: "
                + DataIntegrityViolationException.class.getName());
        assertThat(logs.list).allSatisfy(event -> assertThat(event.getThrowableProxy()).isNull());
    }

    @Test
    @DisplayName("a request with no IDAM caller links nothing and proceeds")
    void noCaller() {
        assertThat(linking.preHandle(new MockHttpServletRequest(), new MockHttpServletResponse(), new Object()))
            .isTrue();

        verifyNoInteractions(masters);
        assertThat(logs.list).isEmpty();
    }
}
