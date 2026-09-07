package uk.gov.hmcts.reform.deedpoll.auth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The audience check, which is the only thing that stops another CFT service's token being replayed here.
 *
 * <p>Spring Security checks no {@code aud} of its own -- there is nothing it could check one against -- so
 * this validator is not belt and braces. Its polarity is the part worth pinning: an unconfigured audience
 * list refuses every token, and it refuses by throwing so the answer is 500 rather than 401. A refactor that
 * turned either of those round would ship green otherwise, and the two failures are "every caseworker locked
 * out with a misconfiguration reported as their fault" and "every IDAM-signed token in the estate accepted".
 */
class IdamAudienceValidatorTest {

    private static final String OURS = "deed_poll_api";

    private static Jwt tokenFor(String... audiences) {
        Jwt.Builder token = Jwt.withTokenValue("token").header("alg", "RS256").subject("idam-user-123");
        if (audiences.length > 0) {
            token.audience(List.of(audiences));
        }
        return token.build();
    }

    @Test
    @DisplayName("accepts a token addressed to this service")
    void acceptsATokenAddressedToThisService() {
        assertThat(new IdamAudienceValidator(List.of(OURS)).validate(tokenFor(OURS)).hasErrors()).isFalse();
    }

    @Test
    @DisplayName("accepts a token whose audience list includes one of ours alongside others")
    void acceptsATokenWithOneOfOursAmongOthers() {
        // IDAM can address a token to more than one client; holding one of ours is what matters.
        assertThat(new IdamAudienceValidator(List.of(OURS)).validate(tokenFor("xui_webapp", OURS)).hasErrors())
            .isFalse();
    }

    @Test
    @DisplayName("refuses a token minted for another service, as an invalid token")
    void refusesATokenForAnotherService() {
        OAuth2TokenValidatorResult result =
            new IdamAudienceValidator(List.of(OURS)).validate(tokenFor("xui_webapp"));

        assertThat(result.hasErrors()).isTrue();
        assertThat(result.getErrors()).singleElement()
            .satisfies(error -> {
                assertThat(error.getErrorCode()).isEqualTo(OAuth2ErrorCodes.INVALID_TOKEN);
                assertThat(error.getDescription()).isEqualTo("The access token is not addressed to this service");
            });
    }

    @Test
    @DisplayName("refuses a token with no audience at all")
    void refusesATokenWithNoAudience() {
        assertThat(new IdamAudienceValidator(List.of(OURS)).validate(tokenFor()).hasErrors()).isTrue();
    }

    @Test
    @DisplayName("refuses every token when no audience is configured, and by throwing so the answer is 500")
    void refusesEveryTokenWhenUnconfigured() {
        // Not a validation failure: a 401 tells the caller to present a different token, and no token they
        // could present would help. The fault is ours and the status code has to say so.
        assertThatThrownBy(() -> new IdamAudienceValidator(List.of()).validate(tokenFor(OURS)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("IDAM_API_AUDIENCES");
    }
}
