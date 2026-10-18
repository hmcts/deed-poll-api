package uk.gov.hmcts.reform.deedpoll.config;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import uk.gov.hmcts.reform.deedpoll.domain.auth.Role;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The membership decision itself, with no chain, no context and no HTTP. */
class StaffGateTest {

    private static final RequestAuthorizationContext CASES =
        new RequestAuthorizationContext(new MockHttpServletRequest("GET", "/caseworker/cases"));

    private ListAppender<ILoggingEvent> logs;
    private Logger logger;

    @BeforeEach
    void captureLogs() {
        logs = new ListAppender<>();
        logs.start();
        logger = (Logger) LoggerFactory.getLogger(StaffGate.class);
        logger.addAppender(logs);
    }

    @AfterEach
    void releaseLogs() {
        logger.detachAppender(logs);
    }

    @Test
    @DisplayName("refuses an authenticated caller holding no deed-poll role, when enforcing")
    void refusesARolelessCallerWhenEnforcing() {
        assertThat(granted(StaffGate.Mode.ENFORCE, caller("idam-1"))).isFalse();
        assertThat(logs.list).isEmpty();
    }

    @Test
    @DisplayName("lets a role-less caller through in report mode, and logs exactly one line naming them")
    void reportsARolelessCaller() {
        assertThat(granted(StaffGate.Mode.REPORT, caller("idam-1"))).isTrue();

        assertThat(logs.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getFormattedMessage())
                .isEqualTo("staff-gate report subject=idam-1 method=GET path=/caseworker/cases");
        });
    }

    @ParameterizedTest
    @EnumSource(StaffGate.Mode.class)
    @DisplayName("admits every caseworker staff role alone without warning in either mode")
    void admitsEachStaffRole(StaffGate.Mode mode) {
        for (Role role : List.of(Role.CASEWORKER, Role.TEAM_LEADER, Role.DELIVERY_MANAGER, Role.DEEDPOLL_ADMIN)) {
            assertThat(granted(mode, caller("idam-2", role.claimValue()))).isTrue();
        }
        assertThat(logs.list).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"caseworker-deedpoll-judicial", "caseworker", "CASEWORKER-DEEDPOLL",
        "caseworker-deedpoll-judiciary", "caseworker-divorce"})
    @DisplayName("refuses the judicial role and every near miss, because membership matches exactly")
    void refusesNearMisses(String authority) {
        assertThat(granted(StaffGate.Mode.ENFORCE, caller("idam-4", authority))).isFalse();
    }

    @ParameterizedTest
    @EnumSource(StaffGate.Mode.class)
    @DisplayName("refuses an anonymous caller in either mode, so report mode never admits the unauthenticated")
    void refusesAnonymousCallers(StaffGate.Mode mode) {
        Authentication anonymous = new AnonymousAuthenticationToken("key", "anonymousUser",
            AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS"));

        assertThat(granted(mode, anonymous)).isFalse();
        assertThat(logs.list).isEmpty();
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "enforce", "reprot", "off", "true"})
    @DisplayName("treats every setting but `report` as enforcement, so a typo cannot open the gate")
    void enforcesUnlessToldToReport(String setting) {
        assertThat(StaffGate.Mode.of(setting)).isEqualTo(StaffGate.Mode.ENFORCE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"report", "REPORT", " report "})
    @DisplayName("reads `report` whatever its case or surrounding spaces")
    void readsReport(String setting) {
        assertThat(StaffGate.Mode.of(setting)).isEqualTo(StaffGate.Mode.REPORT);
    }

    private static boolean granted(StaffGate.Mode mode, Authentication caller) {
        return new StaffGate(StaffArea.CASEWORKER, mode).manager().authorize(() -> caller, CASES).isGranted();
    }

    private static Authentication caller(String subject, String... authorities) {
        TestingAuthenticationToken token = new TestingAuthenticationToken(subject, "credentials",
            List.of(authorities).stream().map(SimpleGrantedAuthority::new).toList());
        token.setAuthenticated(true);
        return token;
    }
}
