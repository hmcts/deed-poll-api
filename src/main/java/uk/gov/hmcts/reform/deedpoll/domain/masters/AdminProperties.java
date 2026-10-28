package uk.gov.hmcts.reform.deedpoll.domain.masters;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Where the admin app is, so an email can link a Master to a referral in it (plan D3).
 *
 * @param baseUrl the admin app's base URL ({@code ADMIN_BASE_URL}), trimmed. Empty by default; required, and
 *     checked, by {@link MasterEmailLinks} once a Master email template is provisioned
 */
@ConfigurationProperties(prefix = "deedpoll.admin")
public record AdminProperties(String baseUrl) {

    public AdminProperties {
        baseUrl = baseUrl == null ? "" : baseUrl.trim();
    }
}
