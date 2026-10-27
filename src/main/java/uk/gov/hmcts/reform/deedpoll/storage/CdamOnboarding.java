package uk.gov.hmcts.reform.deedpoll.storage;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;
import uk.gov.hmcts.reform.deedpoll.ccd.CcdProperties;

import java.net.URI;
import java.util.List;

/** Refuses an enabled CDAM store with local defaults or unconfirmed identifiers outside the local test stacks. */
@Component
@ConditionalOnProperty(name = DocumentStoreProperties.STORE_PROPERTY, havingValue = DocumentStoreProperties.CDAM)
public class CdamOnboarding {

    public CdamOnboarding(@Value("${case_document_am.url:}") String url,
                          CcdProperties ccd,
                          Environment environment) {
        URI endpoint;
        try {
            endpoint = URI.create(url);
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("CASE_DOCUMENT_AM_URL must be a valid HTTP(S) endpoint");
        }
        if (!("http".equals(endpoint.getScheme()) || "https".equals(endpoint.getScheme())) || endpoint.getHost() == null
            || endpoint.getUserInfo() != null || endpoint.getQuery() != null || endpoint.getFragment() != null) {
            throw new IllegalArgumentException("CASE_DOCUMENT_AM_URL must be a valid HTTP(S) endpoint");
        }
        boolean local = List.of("localhost", "127.0.0.1", "[::1]", "0.0.0.0").contains(endpoint.getHost());
        // Only WireMock and cftlib, and only a loopback endpoint. No operator-facing bypass switch.
        if (local && environment.acceptsProfiles(Profiles.of("test", "cftlib"))) {
            return;
        }
        if (local) {
            throw new IllegalArgumentException("CASE_DOCUMENT_AM_URL must name the onboarded CDAM service");
        }
        if (List.of(ccd.caseTypeId(), ccd.jurisdictionId(), ccd.systemRole()).contains("DEED_POLL_PLACEHOLDER")) {
            throw new IllegalArgumentException("CDAM requires onboarded CCD case type, jurisdiction and system role");
        }
    }
}
