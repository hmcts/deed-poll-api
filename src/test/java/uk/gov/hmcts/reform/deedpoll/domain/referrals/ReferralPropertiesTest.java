package uk.gov.hmcts.reform.deedpoll.domain.referrals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The claim-expiry window: that it defaults to two hours, that configuration can move it, and that a
 * value which would break the sweep stops the application instead.
 *
 * <p>Container-free and boots no application context beyond the one bean, because what is under test is
 * binding rather than behaviour. The sweep that reads the window is tested against the window it is given.
 *
 * <p>The default matters more than it looks. Nothing in {@code application.yaml} is mounted in a test, and
 * a deployed environment can perfectly well be given no {@code DEEDPOLL_REFERRAL_CLAIM_EXPIRY}: if an
 * absent value bound to {@code null}, the sweep would throw on the first pool load, and if it bound to
 * zero it would release every claim the instant it was made.
 */
class ReferralPropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withUserConfiguration(ReferralConfiguration.class);

    @Test
    @DisplayName("an unset window is two hours, not null")
    void unsetWindowIsTwoHours() {
        assertThat(new ReferralProperties(null).claimExpiry()).isEqualTo(Duration.ofHours(2));
    }

    @Test
    @DisplayName("binds nothing to the two-hour default, so an unconfigured environment still sweeps")
    void bindsNothingToTheDefault() {
        contextRunner.run(context -> assertThat(context.getBean(ReferralProperties.class).claimExpiry())
            .isEqualTo(Duration.ofHours(2)));
    }

    @Test
    @DisplayName("binds deedpoll.referrals.claim-expiry, so the window moves without a release")
    void bindsAnOverride() {
        // An ISO-8601 duration, which is how it arrives from a Helm environment map -- Boot's relaxed
        // binding accepts `30m` too, but the key is documented as ISO-8601 and this is the documented form.
        contextRunner
            .withPropertyValues("deedpoll.referrals.claim-expiry=PT30M")
            .run(context -> assertThat(context.getBean(ReferralProperties.class).claimExpiry())
                .isEqualTo(Duration.ofMinutes(30)));
    }

    @Test
    @DisplayName("refuses to start on a zero or negative window rather than reclaiming every new claim")
    void refusesANonPositiveWindow() {
        for (String nonsense : new String[] {"PT0S", "PT-1H"}) {
            contextRunner
                .withPropertyValues("deedpoll.referrals.claim-expiry=" + nonsense)
                .run(context -> assertThat(context)
                    .as("%s would make every claim stale the moment it was made", nonsense)
                    .hasFailed());
        }

        // The message names the key, because the thing a reader of the startup log has to change is the
        // Helm value and not this class.
        assertThatThrownBy(() -> new ReferralProperties(Duration.ZERO))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("deedpoll.referrals.claim-expiry");
    }
}
