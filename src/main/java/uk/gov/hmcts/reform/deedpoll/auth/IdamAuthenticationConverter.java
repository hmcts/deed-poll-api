package uk.gov.hmcts.reform.deedpoll.auth;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import uk.gov.hmcts.reform.deedpoll.domain.UnauthorizedError;

/**
 * Turns a validated token into the authenticated caller.
 *
 * <p>Ported from {@code userFromBearerToken()} in {@code apps/api/src/auth/idam.ts}: the token has already
 * been validated by {@link IdamJwtDecoder} at this point, so all that remains is to ask IDAM who it belongs
 * to and reduce that to a {@link uk.gov.hmcts.reform.deedpoll.domain.auth.CurrentUser}.
 *
 * <p>Identity is therefore resolved once, before routing, exactly as the reference's globally-mounted
 * {@code authenticate()} resolved it -- so a handler cannot accidentally do it differently or skip it.
 *
 * <p>An {@link UnauthorizedError} becomes an {@link InvalidBearerTokenException} so it answers 401. Anything
 * else -- IDAM unreachable, no audiences configured -- is deliberately left to propagate as a 500: the
 * reference's split, because answering 401 when the fault is ours sends the caller off to re-authenticate
 * against a service that will refuse them just as firmly the second time.
 */
public class IdamAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    private final IdamUserInfoClient userInfoClient;

    public IdamAuthenticationConverter(IdamUserInfoClient userInfoClient) {
        this.userInfoClient = userInfoClient;
    }

    @Override
    public AbstractAuthenticationToken convert(Jwt token) {
        try {
            return new IdamAuthentication(token, userInfoClient.currentUser(token.getTokenValue(), token.getSubject()));
        } catch (UnauthorizedError e) {
            throw new InvalidBearerTokenException(e.getMessage(), e);
        }
    }
}
