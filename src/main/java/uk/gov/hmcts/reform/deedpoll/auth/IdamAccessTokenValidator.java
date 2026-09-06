package uk.gov.hmcts.reform.deedpoll.auth;

import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * The credential must be an access token, and it must name a subject.
 *
 * <p>Ported from the remaining two checks in {@code verify()} in {@code apps/api/src/auth/idam.ts}.
 *
 * <p><strong>An ID token is not a credential for calling an API.</strong> It is issued to the client,
 * describes the user to that client, and carries the same {@code aud} as the access token -- so
 * {@link IdamAudienceValidator} cannot separate the two. Forgerock labels its tokens with
 * {@code tokenName}, so that is what separates them. Checked only when present, since nothing in OIDC
 * requires the claim and an issuer without it should still work.
 *
 * <p>A token with no {@code sub} identifies nobody, and everything downstream -- the userinfo lookup and
 * the audit columns -- is keyed on it.
 */
public class IdamAccessTokenValidator implements OAuth2TokenValidator<Jwt> {

    private static final String TOKEN_NAME_CLAIM = "tokenName";
    private static final String ACCESS_TOKEN_NAME = "access_token";

    @Override
    public OAuth2TokenValidatorResult validate(Jwt token) {
        Object tokenName = token.getClaim(TOKEN_NAME_CLAIM);
        if (tokenName instanceof String name && !ACCESS_TOKEN_NAME.equals(name)) {
            return failure("Expected an access token, not a " + name);
        }

        String subject = token.getSubject();
        if (subject == null || subject.isBlank()) {
            return failure("Access token has no subject");
        }

        return OAuth2TokenValidatorResult.success();
    }

    private static OAuth2TokenValidatorResult failure(String description) {
        return OAuth2TokenValidatorResult.failure(
            new OAuth2Error(OAuth2ErrorCodes.INVALID_TOKEN, description, null));
    }
}
