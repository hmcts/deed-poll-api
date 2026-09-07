package uk.gov.hmcts.reform.deedpoll.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * What this service needs to know about IDAM to accept a forwarded access token.
 *
 * <p>Ported from {@code IdamApiConfig} in {@code apps/api/src/auth/idam.ts}. Endpoints are discovered
 * from the issuer rather than configured -- see {@link IdamDiscovery} -- so there are only two values.
 *
 * <p>IDAM issues one estate-wide set of signing keys, so a valid signature alone only proves "some IDAM
 * token". The {@code aud} claim names the client the token was minted for, and checking it is what stops
 * a token issued to an unrelated CFT service being replayed here. {@link #audiences} is therefore
 * required, and <strong>empty means every request is refused rather than every token accepted</strong> --
 * the same polarity {@link PlainHttp} keeps, and for the same reason: an unset configuration value must
 * not be the permissive case.
 *
 * @param issuerUrl the IDAM issuer, e.g. {@code https://idam-api.aat.platform.hmcts.net/o}
 * @param audiences the {@code aud} values this service accepts, comma-separated in configuration
 */
@ConfigurationProperties(prefix = "idam.api")
public record IdamProperties(String issuerUrl, List<String> audiences) {

    public IdamProperties(String issuerUrl, List<String> audiences) {
        this.issuerUrl = issuerUrl == null ? "" : issuerUrl.trim();
        this.audiences = audiences == null
            ? List.of()
            : audiences.stream().map(String::trim).filter(audience -> !audience.isEmpty()).toList();
    }
}
