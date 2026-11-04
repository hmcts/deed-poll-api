package uk.gov.hmcts.reform.deedpoll.ccd;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import uk.gov.hmcts.reform.idam.client.IdamClient;
import uk.gov.hmcts.reform.idam.client.models.TokenResponse;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * The IDAM access token the system user acts with -- CCD linking, CDAM uploads, deletes and service reads -- by
 * password grant, reused until shortly before it expires.
 *
 * <p>{@code pcs-api}'s {@code IdamTokenProvider} shape -- one provider, one identity, credentials from the
 * environment -- over {@code idam-java-client}'s {@link IdamClient}, which the SDK already wires and whose
 * {@code OAuth2Configuration} reads the {@code idam.client.*} keys. Like pcs-api's, it caches: a password grant is
 * expensive for IDAM, every HMCTS service shares it, and without a cache every document upload, delete and CCD link
 * cost at least one. IDAM's team asked for this on hmcts/idam-access-config#1056 before prod staff sign-in.
 *
 * <p>A token is reused until {@link #REFRESH_MARGIN} before its {@code expires_in}, and never for longer than
 * {@code idam.system-user.token-max-age} (default 30 minutes), which bounds how long a revoked token can keep being
 * sent. A zero maximum turns the cache off. An answer without a usable {@code expires_in} is used once and not kept.
 * One caller refreshes at a time and the others wait for its answer, so an expired token costs one grant per pod, not
 * one per concurrent request. A failed grant caches nothing, so the next caller asks again.
 *
 * <p><strong>No working default.</strong> Username and password default to empty, and an empty one is refused here
 * before IDAM is asked, so an environment nobody provisioned fails its first claim -- before anything is submitted,
 * which the linking operation treats as nothing created -- rather than sending a guess.
 */
@Component
public class SystemUserTokens {

    /** How long before its stated expiry a token stops being reused, so one is never sent as it lapses. */
    static final Duration REFRESH_MARGIN = Duration.ofSeconds(60);

    private final IdamClient idam;
    private final String username;
    private final String password;
    private final Duration maxAge;
    private final Clock clock;

    private Cached cached;

    @Autowired
    public SystemUserTokens(IdamClient idam,
                            @Value("${idam.system-user.username}") String username,
                            @Value("${idam.system-user.password}") String password,
                            @Value("${idam.system-user.token-max-age:PT30M}") Duration maxAge) {
        this(idam, username, password, maxAge, Clock.systemUTC());
    }

    SystemUserTokens(IdamClient idam, String username, String password, Duration maxAge, Clock clock) {
        if (maxAge == null || maxAge.isNegative()) {
            throw new IllegalArgumentException("idam.system-user.token-max-age must be zero or positive");
        }
        this.idam = idam;
        this.username = username;
        this.password = password;
        this.maxAge = maxAge;
        this.clock = clock;
    }

    /**
     * An access token for the system user, with its {@code Bearer } prefix: the cached one while it has time left,
     * otherwise a fresh one by password grant.
     *
     * @throws IllegalStateException if the credentials are not configured
     */
    public synchronized String accessToken() {
        if (username == null || username.isBlank() || password == null || password.isBlank()) {
            throw new IllegalStateException("The CCD system user's IDAM credentials are not configured");
        }
        Instant now = clock.instant();
        if (cached != null && now.isBefore(cached.refreshAt())) {
            return cached.bearer();
        }
        cached = null;
        TokenResponse response = idam.getAccessTokenResponse(username, password);
        String bearer = "Bearer " + response.accessToken;
        reuseFor(response.expiresIn).ifPresent(reuse -> cached = new Cached(bearer, now.plus(reuse)));
        return bearer;
    }

    /** How long a token may be reused: its lifetime less the margin, capped at the maximum; empty for not at all. */
    private Optional<Duration> reuseFor(String expiresIn) {
        Duration lifetime;
        try {
            lifetime = Duration.ofSeconds(Long.parseLong(expiresIn == null ? "" : expiresIn.trim()));
        } catch (NumberFormatException unreadable) {
            return Optional.empty();
        }
        Duration reuse = lifetime.minus(REFRESH_MARGIN);
        if (reuse.compareTo(maxAge) > 0) {
            reuse = maxAge;
        }
        return reuse.isPositive() ? Optional.of(reuse) : Optional.empty();
    }

    private record Cached(String bearer, Instant refreshAt) {
    }
}
