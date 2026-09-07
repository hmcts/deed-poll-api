package uk.gov.hmcts.reform.deedpoll.auth;

import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.List;

/**
 * The token must be addressed to this service.
 *
 * <p>Ported from the audience half of {@code verify()} in {@code apps/api/src/auth/idam.ts}. IDAM signs the
 * whole estate's tokens with one key set, so a valid signature only proves the token came from IDAM; the
 * {@code aud} claim is what says who it was minted for, and checking it is what stops a token issued to an
 * unrelated CFT service being replayed here.
 *
 * <p>Spring Security does not check {@code aud} by default -- there is nothing it could check it against --
 * so this is not belt and braces, it is the only thing doing it.
 *
 * <p><strong>No configured audiences means every token is refused</strong>, and by an
 * {@link IllegalStateException} rather than a validation failure, so it answers 500 rather than 401. That
 * split is the reference's: a 401 tells the caller to present a different token, and presenting one would
 * not help -- the fault is ours, and answering "client error" for it hides a misconfiguration behind a
 * status code that sends them away to fix something they do not control.
 */
public class IdamAudienceValidator implements OAuth2TokenValidator<Jwt> {

    private final List<String> audiences;

    public IdamAudienceValidator(List<String> audiences) {
        this.audiences = List.copyOf(audiences);
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt token) {
        if (audiences.isEmpty()) {
            throw new IllegalStateException(
                "No IDAM audiences are configured (IDAM_API_AUDIENCES), so no access token can be accepted");
        }

        List<String> claimed = token.getAudience();
        if (claimed != null && claimed.stream().anyMatch(audiences::contains)) {
            return OAuth2TokenValidatorResult.success();
        }

        return OAuth2TokenValidatorResult.failure(new OAuth2Error(
            OAuth2ErrorCodes.INVALID_TOKEN, "The access token is not addressed to this service", null));
    }
}
