package uk.gov.hmcts.reform.deedpoll.auth;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import uk.gov.hmcts.reform.deedpoll.domain.UnauthorizedError;
import uk.gov.hmcts.reform.deedpoll.domain.auth.CurrentUser;
import uk.gov.hmcts.reform.deedpoll.domain.auth.IdamClaims;
import uk.gov.hmcts.reform.deedpoll.domain.auth.Role;

import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

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
 * <p><strong>Roles have two sources; identity has one.</strong> The roles {@link AmRoleSource} reads from AM are
 * unioned with IDAM's, so a caseworker whose job title lives in Caseworker Reference Data is the same {@link Role}
 * as one IDAM names directly, and every capability check downstream is unchanged. The subject and display name are
 * IDAM's alone, and so is the email address. AM can only add a role, and on any failure it adds none.
 *
 * <p>An {@link UnauthorizedError} becomes an {@link InvalidBearerTokenException} so it answers 401. Anything
 * else -- IDAM unreachable, no audiences configured -- is deliberately left to propagate as a 500: the
 * reference's split, because answering 401 when the fault is ours sends the caller off to re-authenticate
 * against a service that will refuse them just as firmly the second time.
 */
public class IdamAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    private final IdamUserInfoClient userInfoClient;
    private final AmRoleSource amRoles;

    public IdamAuthenticationConverter(IdamUserInfoClient userInfoClient, AmRoleSource amRoles) {
        this.userInfoClient = userInfoClient;
        this.amRoles = amRoles;
    }

    @Override
    public AbstractAuthenticationToken convert(Jwt token) {
        Map<String, Object> claims;
        try {
            claims = userInfoClient.claims(token.getTokenValue(), token.getSubject());
        } catch (UnauthorizedError e) {
            throw new InvalidBearerTokenException(e.getMessage(), e);
        }

        CurrentUser idamUser = IdamClaims.userFromClaims(claims);
        Set<Role> fromAm = amRoles.rolesFor(token.getTokenValue(), token.getSubject(), IdamClaims.roleNames(claims));
        if (fromAm.isEmpty()) {
            return new IdamAuthentication(token, idamUser);
        }

        // In declaration order, so the union reads the same whichever source named a role first.
        Set<Role> held = Set.copyOf(Stream.concat(idamUser.roles().stream(), fromAm.stream()).toList());
        return new IdamAuthentication(token, CurrentUser.currentUser(
            Stream.of(Role.values()).filter(held::contains).toList(), idamUser.name(), idamUser.userId(),
            idamUser.email()));
    }
}
