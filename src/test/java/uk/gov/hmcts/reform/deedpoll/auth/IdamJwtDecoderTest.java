package uk.gov.hmcts.reform.deedpoll.auth;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidationException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

/**
 * The issuer check the decoder applies: the discovered issuer, or one of the configured additional ones, and
 * nothing that merely resembles either.
 *
 * <p>Every token is signed by the stub's published key, so the issuer is the only thing a refusal here can be
 * about.
 */
class IdamJwtDecoderTest {

    private static final String FORGEROCK =
        "https://forgerock-am.service.core-compute-idam-aat2.internal:8443/openam/oauth2/realms/root/realms/hmcts";

    private static final StubIdam IDAM = new StubIdam();

    @BeforeAll
    static void startIdam() {
        IDAM.start();
    }

    @AfterAll
    static void stopIdam() {
        IDAM.stop();
    }

    @BeforeEach
    void resetIdam() {
        IDAM.stubDefaults();
    }

    private static JwtDecoder decoder(String... additionalIssuers) {
        IdamProperties properties =
            new IdamProperties(IDAM.issuerUrl(), List.of(StubIdam.AUDIENCE), List.of(additionalIssuers));
        MockEnvironment environment = new MockEnvironment();
        // The stub serves cleartext, which discovery permits only under the test profiles.
        environment.setProperty("spring.profiles.active", "test");
        return new IdamJwtDecoder(new IdamDiscovery(properties, environment), properties);
    }

    private static String tokenFrom(String issuer) {
        return IDAM.accessToken(claims -> claims.issuer(issuer));
    }

    @Test
    @DisplayName("accepts the discovered issuer with no additional issuer configured")
    void acceptsTheMainIssuer() {
        assertThat(decoder().decode(IDAM.accessToken()).getSubject()).isEqualTo(StubIdam.SUBJECT);
    }

    @Test
    @DisplayName("accepts the discovered issuer alongside an additional one")
    void acceptsTheMainIssuerWithAnAdditionalOne() {
        assertThat(decoder(FORGEROCK).decode(IDAM.accessToken()).getSubject()).isEqualTo(StubIdam.SUBJECT);
    }

    @Test
    @DisplayName("accepts an additional issuer on a token the discovered key set signs")
    void acceptsAnAdditionalIssuer() {
        assertThat(decoder(FORGEROCK).decode(tokenFrom(FORGEROCK)).getIssuer())
            .hasToString(FORGEROCK);
    }

    @Test
    @DisplayName("refuses the additional issuer when it is not configured, as it always has")
    void refusesTheAdditionalIssuerUnconfigured() {
        assertRefusedForIssuer(decoder(), tokenFrom(FORGEROCK));
    }

    @Test
    @DisplayName("refuses an issuer nobody listed")
    void refusesAnUnlistedIssuer() {
        assertRefusedForIssuer(decoder(FORGEROCK), tokenFrom("https://idam.elsewhere.example/o"));
    }

    @Test
    @DisplayName("refuses a token with no issuer at all")
    void refusesAMissingIssuer() {
        assertRefusedForIssuer(decoder(FORGEROCK), tokenFrom(null));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        // a prefix
        "https://forgerock-am.service.core-compute-idam-aat2.internal:8443/openam/oauth2/realms/root",
        // the listed issuer with something after it
        FORGEROCK + "/realms/other",
        FORGEROCK + "x",
        // a different case
        "https://FORGEROCK-am.service.core-compute-idam-aat2.internal:8443/openam/oauth2/realms/root/realms/hmcts",
        // a trailing slash the list does not have
        FORGEROCK + "/"
    })
    @DisplayName("refuses anything that only resembles a listed issuer")
    void refusesNearMisses(String issuer) {
        assertRefusedForIssuer(decoder(FORGEROCK), tokenFrom(issuer));
    }

    @Test
    @DisplayName("matches a configured upper-case scheme to the token's issuer, which Spring reads lower-case")
    void matchesAnUpperCaseConfiguredScheme() {
        String configured = "HTTPS" + FORGEROCK.substring("https".length());
        assertThat(decoder(configured).decode(tokenFrom(FORGEROCK)).getIssuer()).hasToString(FORGEROCK);
    }

    @Test
    @DisplayName("reads a token's upper-case scheme as Spring always has, for any trusted issuer")
    void readsATokensSchemeAsSpringDoes() {
        String upperScheme = "HTTPS" + FORGEROCK.substring("https".length());
        assertThat(decoder(FORGEROCK).decode(tokenFrom(upperScheme)).getIssuer()).hasToString(FORGEROCK);
    }

    @Test
    @DisplayName("refuses a near miss of the discovered issuer too")
    void refusesANearMissOfTheMainIssuer() {
        assertRefusedForIssuer(decoder(FORGEROCK), tokenFrom(IDAM.issuerUrl() + "/"));
    }

    private static void assertRefusedForIssuer(JwtDecoder decoder, String token) {
        assertThatThrownBy(() -> decoder.decode(token))
            .isInstanceOfSatisfying(JwtValidationException.class, refusal ->
                assertThat(refusal.getErrors())
                    .extracting(OAuth2Error::getErrorCode, OAuth2Error::getDescription)
                    .containsExactly(tuple("invalid_token", "The iss claim is not valid")));
    }
}
