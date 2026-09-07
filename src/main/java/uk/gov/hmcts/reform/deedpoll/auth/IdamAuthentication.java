package uk.gov.hmcts.reform.deedpoll.auth;

import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import uk.gov.hmcts.reform.deedpoll.domain.auth.CurrentUser;
import uk.gov.hmcts.reform.deedpoll.domain.auth.Role;

/**
 * An authenticated caller, as Spring Security holds them.
 *
 * <p>The principal is the domain's {@link CurrentUser} rather than the {@link Jwt}, which is the whole point
 * of this type existing: every authorisation decision in the service is made against roles this service
 * recognises (see {@link uk.gov.hmcts.reform.deedpoll.domain.auth.IdamClaims}), and a handler that could
 * reach past it to the raw claims could make a decision on a role nobody here maintains.
 *
 * <p>The roles are also published as authorities, so Spring's own machinery -- {@code hasAuthority} in a
 * filter chain, {@code @PreAuthorize} -- can see them. Two representations of one fact, both derived from
 * the same reduction rather than from each other.
 */
public class IdamAuthentication extends AbstractAuthenticationToken {

    private static final long serialVersionUID = 1L;

    private final transient Jwt token;
    private final transient CurrentUser currentUser;

    public IdamAuthentication(Jwt token, CurrentUser currentUser) {
        super(currentUser.roles().stream().map(Role::claimValue).map(SimpleGrantedAuthority::new).toList());
        this.token = token;
        this.currentUser = currentUser;
        setAuthenticated(true);
    }

    /** The caller, reduced to what this service recognises. */
    public CurrentUser currentUser() {
        return currentUser;
    }

    @Override
    public Object getCredentials() {
        return token;
    }

    @Override
    public Object getPrincipal() {
        return currentUser;
    }

    @Override
    public String getName() {
        return token.getSubject();
    }
}
