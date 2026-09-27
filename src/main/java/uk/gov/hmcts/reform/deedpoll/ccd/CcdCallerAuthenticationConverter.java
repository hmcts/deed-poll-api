package uk.gov.hmcts.reform.deedpoll.ccd;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import uk.gov.hmcts.reform.deedpoll.auth.IdamAuthentication;
import uk.gov.hmcts.reform.deedpoll.auth.IdamUserInfoClient;
import uk.gov.hmcts.reform.deedpoll.domain.UnauthorizedError;
import uk.gov.hmcts.reform.deedpoll.domain.auth.IdamClaims;

import java.util.Map;

/**
 * Turns the user token data-store forwards to {@code /ccd-persistence/**} into a caller who holds the definition's
 * access profiles.
 *
 * <p>The token has already been validated by the same {@code IdamJwtDecoder} every other namespace uses. What
 * differs is the reduction. {@link uk.gov.hmcts.reform.deedpoll.auth.IdamAuthenticationConverter} keeps only the
 * roles this service recognises, and the system role is not one of them: it is configuration (plan 02,
 * decision 5). So this converter reads the raw roles, maps them through {@link DeedPollRoleMapping}, and publishes
 * the resulting profiles as authorities for the chain to require.
 *
 * <p>The failure split is the other converter's: a token IDAM will not resolve is a 401, and IDAM being
 * unreachable is left to become a 500.
 */
public class CcdCallerAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    private final IdamUserInfoClient userInfoClient;
    private final DeedPollRoleMapping roleMapping;

    public CcdCallerAuthenticationConverter(IdamUserInfoClient userInfoClient, DeedPollRoleMapping roleMapping) {
        this.userInfoClient = userInfoClient;
        this.roleMapping = roleMapping;
    }

    @Override
    public AbstractAuthenticationToken convert(Jwt token) {
        Map<String, Object> claims;
        try {
            claims = userInfoClient.claims(token.getTokenValue(), token.getSubject());
        } catch (UnauthorizedError e) {
            throw new InvalidBearerTokenException(e.getMessage(), e);
        }

        return new IdamAuthentication(
            token,
            IdamClaims.userFromClaims(claims),
            roleMapping.profilesFor(IdamClaims.roleNames(claims)).stream()
                .map(DeedPollAccessProfile::getRole)
                .toList());
    }
}
