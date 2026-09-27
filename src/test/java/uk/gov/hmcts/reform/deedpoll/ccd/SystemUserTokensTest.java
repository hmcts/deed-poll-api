package uk.gov.hmcts.reform.deedpoll.ccd;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uk.gov.hmcts.reform.idam.client.IdamClient;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The system user's credentials have no working default, and the linking operation's pacing refuses a value that
 * would make it unsafe.
 */
class SystemUserTokensTest {

    @Test
    @DisplayName("refuses unset credentials before IDAM is asked")
    void refusesUnsetCredentials() {
        IdamClient idam = mock(IdamClient.class);

        for (String[] credentials : new String[][] {{"", ""}, {"user", ""}, {"", "password"}, {" ", "password"}}) {
            assertThatThrownBy(() -> new SystemUserTokens(idam, credentials[0], credentials[1]).accessToken())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("The CCD system user's IDAM credentials are not configured");
        }
        verify(idam, never()).getAccessToken(anyString(), anyString());
    }

    @Test
    @DisplayName("signs the system user in by password grant")
    void signsIn() {
        IdamClient idam = mock(IdamClient.class);
        when(idam.getAccessToken("system", "password")).thenReturn("Bearer token");

        assertThat(new SystemUserTokens(idam, "system", "password").accessToken()).isEqualTo("Bearer token");
    }

    @Test
    @DisplayName("the linking properties refuse a blank worker, a non-positive duration and no attempts")
    void linkingPropertiesRefuseUnsafeValues() {
        Duration minute = Duration.ofMinutes(1);
        assertThatThrownBy(() -> new CcdLinkingProperties(" ", minute, minute, 1))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CcdLinkingProperties("w", Duration.ZERO, minute, 1))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CcdLinkingProperties("w", minute, minute.negated(), 1))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CcdLinkingProperties("w", minute, null, 1))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CcdLinkingProperties("w", minute, minute, 0))
            .isInstanceOf(IllegalArgumentException.class);
        assertThat(new CcdLinkingProperties(" pod-1 ", minute, minute, 1).worker()).isEqualTo("pod-1");
    }
}
