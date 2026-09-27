package uk.gov.hmcts.reform.deedpoll.ccd;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import uk.gov.hmcts.reform.idam.client.IdamClient;

/**
 * The IDAM access token the linking operation acts with: the system user's, by password grant.
 *
 * <p>{@code pcs-api}'s {@code IdamTokenProvider} shape -- one provider, one identity, credentials from the
 * environment -- over {@code idam-java-client}'s {@link IdamClient}, which the SDK already wires and whose
 * {@code OAuth2Configuration} reads the {@code idam.client.*} keys. Nothing is cached: the operation runs once per
 * record, and a cached token is one more secret held in memory for no saving worth having.
 *
 * <p><strong>No working default.</strong> Username and password default to empty, and an empty one is refused here
 * before IDAM is asked, so an environment nobody provisioned fails its first claim -- before anything is submitted,
 * which the linking operation treats as nothing created -- rather than sending a guess.
 */
@Component
public class SystemUserTokens {

    private final IdamClient idam;
    private final String username;
    private final String password;

    public SystemUserTokens(IdamClient idam,
                            @Value("${idam.system-user.username}") String username,
                            @Value("${idam.system-user.password}") String password) {
        this.idam = idam;
        this.username = username;
        this.password = password;
    }

    /**
     * A fresh access token for the system user, with its {@code Bearer } prefix.
     *
     * @throws IllegalStateException if the credentials are not configured
     */
    public String accessToken() {
        if (username == null || username.isBlank() || password == null || password.isBlank()) {
            throw new IllegalStateException("The CCD system user's IDAM credentials are not configured");
        }
        return idam.getAccessToken(username, password);
    }
}
