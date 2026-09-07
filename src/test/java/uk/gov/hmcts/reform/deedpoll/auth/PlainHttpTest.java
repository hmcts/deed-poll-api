package uk.gov.hmcts.reform.deedpoll.auth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ports every test block in {@code reference/ts/auth/plain-http.test.ts}.
 *
 * <p>{@code process.env.NODE_ENV} becomes the active Spring profiles, which is the same decision expressed in
 * the mechanism this service actually has -- see {@link PlainHttp}. The reference's "not set at all" case is
 * "no profile active", which is what a deployed container has unless {@code SPRING_PROFILES_ACTIVE} is given.
 */
class PlainHttpTest {

    private static boolean permittedUnder(String... profiles) {
        MockEnvironment environment = new MockEnvironment();
        if (profiles.length > 0) {
            environment.setProperty("spring.profiles.active", String.join(",", profiles));
        }
        return PlainHttp.plainHttpPermitted(environment);
    }

    @Test
    @DisplayName("permits cleartext in development and test, and nowhere else")
    void theAllowlistIsTheTwoEnvironments() {
        assertThat(PlainHttp.PLAIN_HTTP_PROFILES).containsExactlyInAnyOrder("development", "test");
    }

    @ParameterizedTest(name = "profile {0}")
    @ValueSource(strings = {"development", "test"})
    @DisplayName("permits it under a permitted profile")
    void permitsAPermittedProfile(String profile) {
        assertThat(permittedUnder(profile)).isTrue();
    }

    // The whole point of the allowlist: these are the values a negation of "production" would have wrongly
    // permitted. If someone "simplifies" this to `!isProduction()`, these fail.
    @ParameterizedTest(name = "profile {0}")
    @ValueSource(strings = {"production", "aat", "preview", "staging", "demo", "ithc", "perftest"})
    @DisplayName("refuses it under any other profile")
    void refusesAnyOtherProfile(String profile) {
        assertThat(permittedUnder(profile)).isFalse();
    }

    @Test
    @DisplayName("refuses it when no profile is active at all")
    void refusesWhenNoProfileIsActive() {
        assertThat(permittedUnder()).isFalse();
    }

    /**
     * Not in the reference, which has one {@code NODE_ENV} where a Spring application can have several
     * profiles at once. A deployment that adds {@code aat} beside {@code development} has still asked for the
     * relaxation, so the check is "any", and this is what pins that reading.
     */
    @Test
    @DisplayName("permits it when a permitted profile is one of several")
    void permitsAPermittedProfileAmongOthers() {
        assertThat(permittedUnder("aat", "development")).isTrue();
        assertThat(permittedUnder("aat", "preview")).isFalse();
    }
}
