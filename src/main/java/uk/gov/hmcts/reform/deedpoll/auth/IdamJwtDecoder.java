package uk.gov.hmcts.reform.deedpoll.auth;

import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import uk.gov.hmcts.reform.deedpoll.auth.IdamDiscovery.IdamMetadata;

/**
 * Validates a forwarded IDAM access token: signature, issuer, audience, expiry and kind.
 *
 * <p>Ported from {@code verify()} in {@code apps/api/src/auth/idam.ts}, with Nimbus doing what {@code jose}
 * did there. The part worth not hand-rolling is the key set: the source Nimbus JWKS source caches keys,
 * refetches on an unknown {@code kid}, and rate-limits that refetch so a stream of bogus {@code kid}s
 * cannot be turned into a denial of service against IDAM.
 *
 * <p><strong>Built lazily, not at start-up.</strong> Discovery is a network call, and a service that cannot
 * start because IDAM is briefly unreachable is a service that cannot start during an IDAM deployment. The
 * first request pays for it and {@link IdamDiscovery} remembers it; nothing is remembered on failure, so a
 * blip does not persist.
 *
 * <p><strong>RS256 is pinned rather than left to the token</strong>, because {@code alg} is a field the
 * sender controls -- IDAM signs with RS256 ({@code rse-idam-simulator}'s {@code JwTokenGeneratorService}
 * builds an {@code RS256} header over an {@code RSAKey}). Nimbus would already refuse {@code none} and
 * would fail an HMAC token against an RSA key, so this is braces as well as belt, but the failure it guards
 * against is total and one line is a cheap brace.
 */
public class IdamJwtDecoder implements JwtDecoder {

    private final IdamDiscovery discovery;
    private final IdamProperties properties;

    private volatile JwtDecoder delegate;

    public IdamJwtDecoder(IdamDiscovery discovery, IdamProperties properties) {
        this.discovery = discovery;
        this.properties = properties;
    }

    @Override
    public Jwt decode(String token) throws JwtException {
        return delegate().decode(token);
    }

    /**
     * The decoder, built once from the discovered metadata.
     *
     * <p>Discovery happens <strong>before</strong> this monitor is taken, not inside it. Holding a lock across
     * the network call would recreate at this level the convoy {@link IdamDiscovery#metadata()} guards against:
     * every waiting request would be serialised behind one attempt, each paying a full timeout in turn.
     * {@code metadata()} already limits itself to one concurrent attempt and fails fast, so there is nothing
     * left for this monitor to protect except {@link #build}, which is local construction --
     * {@code withJwkSetUri} does not fetch, the key set is fetched lazily on the first {@code decode}.
     *
     * <p>Two threads racing here may therefore each build a decoder, and one is discarded. That is cheaper than
     * what it buys: an unused decoder has fetched nothing, whereas serialising the build would put IDAM's
     * latency in front of every request.
     */
    private JwtDecoder delegate() {
        JwtDecoder existing = delegate;
        if (existing != null) {
            return existing;
        }

        IdamMetadata metadata = discovery.metadata();

        synchronized (this) {
            if (delegate == null) {
                delegate = build(metadata);
            }
            return delegate;
        }
    }

    private JwtDecoder build(IdamMetadata metadata) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder
            .withJwkSetUri(metadata.jwksUri())
            .jwsAlgorithm(SignatureAlgorithm.RS256)
            // Nimbus would otherwise fetch the key set through a bare RestTemplate, which has no read timeout
            // at all -- and this fetch happens on an unknown `kid`, which is to say during a key rotation and
            // on the request path. See IdamHttp for what an unbounded wait here costs.
            .restOperations(IdamHttp.restTemplate())
            .build();

        // Every claim check in one chain, so the decoder cannot be used without them: the reference's note
        // that doing the checks separately is how one gets forgotten applies just as well here.
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
            new JwtTimestampValidator(),
            new JwtIssuerValidator(metadata.issuer()),
            new IdamAudienceValidator(properties.audiences()),
            new IdamAccessTokenValidator()
        ));

        return decoder;
    }
}
