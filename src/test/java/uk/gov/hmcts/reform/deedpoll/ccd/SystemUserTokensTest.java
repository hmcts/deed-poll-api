package uk.gov.hmcts.reform.deedpoll.ccd;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uk.gov.hmcts.reform.idam.client.IdamClient;
import uk.gov.hmcts.reform.idam.client.models.TokenResponse;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The system user's credentials have no working default, its token is reused until shortly before it expires, and the
 * linking operation's pacing refuses a value that would make it unsafe.
 */
class SystemUserTokensTest {

    private static final Duration MAX_AGE = Duration.ofMinutes(30);
    private static final Instant START = Instant.parse("2026-11-04T09:00:00Z");

    private final IdamClient idam = mock(IdamClient.class);
    private final MutableClock clock = new MutableClock(START);

    @Test
    @DisplayName("refuses unset credentials before IDAM is asked")
    void refusesUnsetCredentials() {
        for (String[] credentials : new String[][] {{"", ""}, {"user", ""}, {"", "password"}, {" ", "password"}}) {
            assertThatThrownBy(() -> tokens(credentials[0], credentials[1], MAX_AGE).accessToken())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("The CCD system user's IDAM credentials are not configured");
        }
        verify(idam, never()).getAccessTokenResponse(anyString(), anyString());
    }

    @Test
    @DisplayName("refuses a negative or missing maximum age")
    void refusesUnsafeMaxAge() {
        assertThatThrownBy(() -> tokens("system", "password", Duration.ofSeconds(-1)))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> tokens("system", "password", null))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("signs the system user in by password grant, with the Bearer prefix")
    void signsIn() {
        grants(token("first", "28800"));

        assertThat(tokens("system", "password", MAX_AGE).accessToken()).isEqualTo("Bearer first");
        verify(idam).getAccessTokenResponse("system", "password");
    }

    @Test
    @DisplayName("reuses the token rather than asking IDAM again")
    void reusesTheToken() {
        grants(token("first", "28800"), token("second", "28800"));
        SystemUserTokens tokens = tokens("system", "password", MAX_AGE);

        String first = tokens.accessToken();
        clock.advance(Duration.ofMinutes(29));
        String again = tokens.accessToken();

        assertThat(again).isEqualTo(first).isEqualTo("Bearer first");
        verify(idam, times(1)).getAccessTokenResponse("system", "password");
    }

    @Test
    @DisplayName("asks again once the maximum age has passed, however long IDAM said the token lasts")
    void refreshesAtTheMaximumAge() {
        grants(token("first", "28800"), token("second", "28800"));
        SystemUserTokens tokens = tokens("system", "password", MAX_AGE);

        tokens.accessToken();
        clock.advance(MAX_AGE);

        assertThat(tokens.accessToken()).isEqualTo("Bearer second");
        verify(idam, times(2)).getAccessTokenResponse("system", "password");
    }

    @Test
    @DisplayName("asks again a minute before a short-lived token expires")
    void refreshesBeforeExpiry() {
        grants(token("first", "300"), token("second", "300"));
        SystemUserTokens tokens = tokens("system", "password", MAX_AGE);

        tokens.accessToken();
        clock.advance(Duration.ofSeconds(239));
        assertThat(tokens.accessToken()).isEqualTo("Bearer first");
        clock.advance(Duration.ofSeconds(1));

        assertThat(tokens.accessToken()).isEqualTo("Bearer second");
    }

    @Test
    @DisplayName("keeps nothing when the answer has no usable expiry, or the token lasts no longer than the margin")
    void keepsNothingWithoutAUsableExpiry() {
        for (String expiresIn : new String[] {null, "", "soon", "60", "0", "-5"}) {
            IdamClient fresh = mock(IdamClient.class);
            when(fresh.getAccessTokenResponse("system", "password"))
                .thenReturn(token("a", expiresIn), token("b", expiresIn));
            SystemUserTokens tokens = new SystemUserTokens(fresh, "system", "password", MAX_AGE, clock);

            assertThat(tokens.accessToken()).isEqualTo("Bearer a");
            assertThat(tokens.accessToken()).as("expires_in %s", expiresIn).isEqualTo("Bearer b");
        }
    }

    @Test
    @DisplayName("a zero maximum age turns the cache off")
    void zeroMaxAgeCachesNothing() {
        grants(token("first", "28800"), token("second", "28800"));
        SystemUserTokens tokens = tokens("system", "password", Duration.ZERO);

        tokens.accessToken();

        assertThat(tokens.accessToken()).isEqualTo("Bearer second");
    }

    @Test
    @DisplayName("a failed grant caches nothing, so the next caller asks IDAM again")
    void failedGrantIsNotCached() {
        when(idam.getAccessTokenResponse("system", "password"))
            .thenThrow(new IllegalStateException("IDAM is down"))
            .thenReturn(token("first", "28800"));
        SystemUserTokens tokens = tokens("system", "password", MAX_AGE);

        assertThatThrownBy(tokens::accessToken).hasMessage("IDAM is down");

        assertThat(tokens.accessToken()).isEqualTo("Bearer first");
    }

    @Test
    @DisplayName("concurrent callers share one grant rather than each asking IDAM")
    void concurrentCallersShareOneGrant() throws Exception {
        CountDownLatch inGrant = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(idam.getAccessTokenResponse("system", "password")).thenAnswer(call -> {
            inGrant.countDown();
            release.await(5, TimeUnit.SECONDS);
            return token("shared", "28800");
        });
        SystemUserTokens tokens = tokens("system", "password", MAX_AGE);
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            Future<String> first = pool.submit(tokens::accessToken);
            assertThat(inGrant.await(5, TimeUnit.SECONDS)).isTrue();
            List<Future<String>> waiting = List.of(
                pool.submit(tokens::accessToken), pool.submit(tokens::accessToken), pool.submit(tokens::accessToken));
            release.countDown();

            assertThat(first.get(5, TimeUnit.SECONDS)).isEqualTo("Bearer shared");
            for (Future<String> other : waiting) {
                assertThat(other.get(5, TimeUnit.SECONDS)).isEqualTo("Bearer shared");
            }
        } finally {
            pool.shutdownNow();
        }
        verify(idam, times(1)).getAccessTokenResponse("system", "password");
    }

    @Test
    @DisplayName("the linking properties refuse a blank worker, a non-positive or missing duration and no attempts")
    void linkingPropertiesRefuseUnsafeValues() {
        Duration minute = Duration.ofMinutes(1);
        assertThatThrownBy(() -> new CcdLinkingProperties(" ", minute, minute, 1, minute))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CcdLinkingProperties("w", Duration.ZERO, minute, 1, minute))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CcdLinkingProperties("w", minute, minute.negated(), 1, minute))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CcdLinkingProperties("w", minute, null, 1, minute))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CcdLinkingProperties("w", minute, minute, 0, minute))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CcdLinkingProperties("w", minute, minute, 1, Duration.ZERO))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CcdLinkingProperties("w", minute, minute, 1, null))
            .isInstanceOf(IllegalArgumentException.class);
        // A claimant is the name, a separator and a UUID, and has to fit V22's 200-character "claimedBy".
        assertThatThrownBy(() -> new CcdLinkingProperties("w".repeat(164), minute, minute, 1, minute))
            .isInstanceOf(IllegalArgumentException.class);
        assertThat(CcdLinkingService.claimant(
            new CcdLinkingProperties("w".repeat(163), minute, minute, 1, minute).worker())).hasSize(200);
        assertThat(new CcdLinkingProperties(" pod-1 ", minute, minute, 1, minute).worker()).isEqualTo("pod-1");
    }

    private SystemUserTokens tokens(String username, String password, Duration maxAge) {
        return new SystemUserTokens(idam, username, password, maxAge, clock);
    }

    private void grants(TokenResponse first, TokenResponse... rest) {
        when(idam.getAccessTokenResponse("system", "password")).thenReturn(first, rest);
    }

    private static TokenResponse token(String accessToken, String expiresIn) {
        return new TokenResponse(accessToken, expiresIn, null, null, "openid", "Bearer");
    }

    /** A clock the test moves by hand. */
    private static final class MutableClock extends Clock {

        private Instant now;

        MutableClock(Instant start) {
            this.now = start;
        }

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
