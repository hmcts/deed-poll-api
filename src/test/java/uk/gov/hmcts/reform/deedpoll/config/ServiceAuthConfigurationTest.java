package uk.gov.hmcts.reform.deedpoll.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import uk.gov.hmcts.reform.authorisation.filters.ServiceAuthFilter;
import uk.gov.hmcts.reform.authorisation.generators.AuthTokenGenerator;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Outbound S2S is wired and mints nothing.
 *
 * <p>Nothing in plan 01 injects the generator, so without this the wiring would be unexercised until plan 02
 * needed it -- and "the Feign client the library expects is actually built" is exactly the kind of thing that
 * fails on the day somebody is trying to call CCD.
 */
@SpringBootTest
class ServiceAuthConfigurationTest extends AbstractPostgresContainerTest {

    @Autowired
    private ApplicationContext context;

    @Value("${idam.s2s-auth.totp-secret}")
    private String secret;

    @Test
    @DisplayName("builds a token generator, so plan 02 finds one rather than inventing one")
    void buildsATokenGenerator() {
        assertThat(context.getBean(AuthTokenGenerator.class)).isNotNull();
    }

    @Test
    @DisplayName("has no working default secret, so an unconfigured environment cannot mint a token")
    void hasNoWorkingDefaultSecret() {
        // A committed placeholder that happened to work somewhere is the failure worth designing out.
        assertThat(secret).isEmpty();
    }

    @Test
    @DisplayName("registers no inbound S2S filter, because nothing calls this service service-to-service yet")
    void registersNoInboundFilter() {
        // The library adds one only when `idam.s2s-authorised.services` is set. Leaving it unset is the
        // decision, not an omission: a filter with an empty allowlist would refuse every caller, and one with
        // a guessed allowlist would admit services nobody has agreed to.
        assertThat(context.getBeanNamesForType(ServiceAuthFilter.class)).isEmpty();
    }
}
