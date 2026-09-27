package uk.gov.hmcts.reform.deedpoll.ccd;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The CCD identifiers this service is waiting on, and nothing else.
 *
 * <p>None of the three has been onboarded, so each defaults in {@code application.yaml} to the literal
 * {@code DEED_POLL_PLACEHOLDER}, and a grep for that literal still lists every external identifier the service
 * is waiting on (plan 02, decision 5). Swapping in a confirmed value is configuration, not a code change. A blank
 * one refuses to start rather than generating a definition with an empty id, which the definition store would
 * reject only at import, long after the build that produced it went green.
 *
 * @param caseTypeId the CCD case type id
 * @param jurisdictionId the CCD jurisdiction id
 * @param systemRole the IDAM role the linking operation's system user holds, which the definition maps onto the
 *     {@link DeedPollAccessProfile#DEED_POLL_SYSTEM} access profile
 */
@ConfigurationProperties(prefix = "deedpoll.ccd")
public record CcdProperties(String caseTypeId, String jurisdictionId, String systemRole) {

    public CcdProperties {
        caseTypeId = required("case-type-id", caseTypeId);
        jurisdictionId = required("jurisdiction-id", jurisdictionId);
        systemRole = required("system-role", systemRole);
    }

    private static String required(String key, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("deedpoll.ccd." + key + " must be set; its placeholder is "
                + "DEED_POLL_PLACEHOLDER until the identifier is onboarded");
        }
        return value.trim();
    }
}
