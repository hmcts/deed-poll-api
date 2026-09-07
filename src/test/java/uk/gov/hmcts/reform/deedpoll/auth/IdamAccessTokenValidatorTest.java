package uk.gov.hmcts.reform.deedpoll.auth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The two checks that separate an access token from an ID token, and a token from one that names nobody.
 *
 * <p>Both carry a documented tolerance, and a tolerance nothing pins is a tolerance a later tightening
 * removes silently. {@code tokenName} is checked <em>only when present</em>, because nothing in OIDC requires
 * it and an issuer without it must still work -- requiring it would lock out any such issuer, and there is no
 * IDAM here to notice. A blank {@code sub} is refused as firmly as an absent one, because everything
 * downstream is keyed on it.
 */
class IdamAccessTokenValidatorTest {

    private final IdamAccessTokenValidator validator = new IdamAccessTokenValidator();

    private static Jwt token(String subject, Map<String, Object> extraClaims) {
        Jwt.Builder builder = Jwt.withTokenValue("token").header("alg", "RS256").subject(subject);
        extraClaims.forEach(builder::claim);
        return builder.build();
    }

    @Test
    @DisplayName("admits an access token")
    void admitsAnAccessToken() {
        assertThat(validator.validate(token("idam-user-123", Map.of("tokenName", "access_token"))).hasErrors())
            .isFalse();
    }

    @Test
    @DisplayName("admits a token with no tokenName, since nothing in OIDC requires the claim")
    void admitsATokenWithNoTokenName() {
        // The tolerance the javadoc states: an issuer that does not label its tokens should still work, so
        // the claim is checked when present rather than required.
        assertThat(validator.validate(token("idam-user-123", Map.of())).hasErrors()).isFalse();
    }

    @Test
    @DisplayName("refuses an ID token presented as an access token, naming what it got")
    void refusesAnIdToken() {
        // The audience validator cannot separate the two -- an ID token carries the same `aud` -- so this is
        // the only check that does.
        OAuth2TokenValidatorResult result =
            validator.validate(token("idam-user-123", Map.of("tokenName", "id_token")));

        assertThat(result.hasErrors()).isTrue();
        assertThat(result.getErrors()).singleElement()
            .satisfies(error -> {
                assertThat(error.getErrorCode()).isEqualTo(OAuth2ErrorCodes.INVALID_TOKEN);
                assertThat(error.getDescription()).isEqualTo("Expected an access token, not a id_token");
            });
    }

    @Test
    @DisplayName("refuses a token with no subject")
    void refusesATokenWithNoSubject() {
        assertThat(validator.validate(token(null, Map.of("tokenName", "access_token"))).hasErrors()).isTrue();
    }

    @Test
    @DisplayName("refuses a token whose subject is blank, not only one where it is absent")
    void refusesATokenWithABlankSubject() {
        OAuth2TokenValidatorResult result =
            validator.validate(token("   ", Map.of("tokenName", "access_token")));

        assertThat(result.hasErrors()).isTrue();
        assertThat(result.getErrors()).singleElement()
            .satisfies(error -> assertThat(error.getDescription()).isEqualTo("Access token has no subject"));
    }
}
