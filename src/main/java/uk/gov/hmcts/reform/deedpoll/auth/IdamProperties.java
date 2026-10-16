package uk.gov.hmcts.reform.deedpoll.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;

/**
 * What this service needs to know about IDAM to accept a forwarded access token.
 *
 * <p>Ported from {@code IdamApiConfig} in {@code apps/api/src/auth/idam.ts}. Endpoints are discovered
 * from the issuer rather than configured -- see {@link IdamDiscovery} -- so there are only two values
 * the port needed, plus the additional issuers it did not.
 *
 * <p>IDAM issues one estate-wide set of signing keys, so a valid signature alone only proves "some IDAM
 * token". The {@code aud} claim names the client the token was minted for, and checking it is what stops
 * a token issued to an unrelated CFT service being replayed here. {@link #audiences} is therefore
 * required, and <strong>empty means every request is refused rather than every token accepted</strong> --
 * the same polarity {@link PlainHttp} keeps, and for the same reason: an unset configuration value must
 * not be the permissive case.
 *
 * <p>{@link #additionalIssuers} exists because AAT's password-grant tokens carry ForgeRock's internal name
 * for the issuer rather than the discovered one, while the discovered key set signs them. Each is a name
 * accepted on {@code iss}, never a place keys are fetched from, so it widens nothing but that one claim.
 * Each must be absolute {@code https}, the rule {@link IdamDiscovery} applies to the main issuer, and a
 * bad entry fails start-up rather than reading as an issuer no token carries.
 *
 * @param issuerUrl the IDAM issuer, e.g. {@code https://idam-api.aat.platform.hmcts.net/o}
 * @param audiences the {@code aud} values this service accepts, comma-separated in configuration
 * @param additionalIssuers further {@code iss} values accepted on tokens the main issuer's keys sign,
 *     comma-separated in configuration and empty by default
 */
@ConfigurationProperties(prefix = "idam.api")
public record IdamProperties(String issuerUrl, List<String> audiences, List<String> additionalIssuers) {

    static final String ADDITIONAL_ISSUERS_VARIABLE = "IDAM_API_ADDITIONAL_ISSUERS";

    public IdamProperties(String issuerUrl, List<String> audiences, List<String> additionalIssuers) {
        this.issuerUrl = issuerUrl == null ? "" : issuerUrl.trim();
        this.audiences = audiences == null
            ? List.of()
            : audiences.stream().map(String::trim).filter(audience -> !audience.isEmpty()).toList();
        this.additionalIssuers = normaliseIssuers(additionalIssuers);
    }

    private static List<String> normaliseIssuers(List<String> configured) {
        if (configured == null) {
            return List.of();
        }
        List<String> issuers = new ArrayList<>();
        int position = 0;
        for (String entry : configured) {
            position++;
            String issuer = entry == null ? "" : entry.trim();
            if (issuer.isEmpty()) {
                continue;
            }
            requireHttps(issuer, position);
            String withoutSlash = issuer.endsWith("/") ? issuer.substring(0, issuer.length() - 1) : issuer;
            String normalised = asSpringReadsIt(withoutSlash, position);
            if (!issuers.contains(normalised)) {
                issuers.add(normalised);
            }
        }
        return List.copyOf(issuers);
    }

    /**
     * The entry as Spring will present a token's {@code iss}, so the two sides compare alike.
     *
     * <p>Spring's claim-set converter reads {@code iss} through {@code URI.toURL().toExternalForm()} before any
     * validator sees it, which lower-cases the scheme and changes nothing else. Unconverted, an entry written
     * {@code HTTPS://...} would never equal the claim it names, and every token would fail with nothing to say why.
     */
    private static String asSpringReadsIt(String issuer, int position) {
        try {
            return URI.create(issuer).toURL().toExternalForm();
        } catch (IllegalArgumentException | MalformedURLException e) {
            throw invalid(position);
        }
    }

    /**
     * Refuses an entry that is not an absolute {@code https} URL with a host.
     *
     * <p>The message names the variable and the entry's position but never the value: a pasted URL may carry a
     * query string, and a start-up failure is logged wherever the pod's logs go.
     */
    private static void requireHttps(String issuer, int position) {
        URI uri;
        try {
            uri = new URI(issuer);
        } catch (URISyntaxException e) {
            throw invalid(position);
        }
        if (!uri.isAbsolute() || !"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
            throw invalid(position);
        }
    }

    private static IllegalArgumentException invalid(int position) {
        return new IllegalArgumentException(ADDITIONAL_ISSUERS_VARIABLE + " entry " + position
                                                + " is not an absolute https URL");
    }
}
