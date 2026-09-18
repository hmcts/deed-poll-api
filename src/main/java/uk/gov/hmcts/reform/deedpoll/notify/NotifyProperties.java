package uk.gov.hmcts.reform.deedpoll.notify;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Map;

/**
 * What this service needs to know about GOV.UK Notify: where it is, how to authenticate, and which template
 * is which.
 *
 * <p>Wired the way {@code IdamProperties} and {@code ReferralProperties} are -- a record with constructor
 * binding, registered by {@link NotifyConfiguration} rather than component-scanned.
 *
 * <p><strong>No Notify service exists for deed poll yet.</strong> There is no API key and there are no
 * templates, so {@link #apiKey()} has a non-working default and every template ID defaults to
 * {@link #UNPROVISIONED_TEMPLATE_ID} -- the same literal {@code deedpoll.ccd.case-type-id} uses, so one grep
 * finds every external identifier this service is still waiting on. Until notify-0 provisions them, a send
 * gets a 403 and a preview gets a 400 from Notify; both are the taxonomy's permanent branch (see
 * {@link NotificationFailure}), which is the correct answer to "you have no key" rather than a defect.
 *
 * @param apiUrl Notify's API base URL. Defaults to the real one, because that is where every provisioned
 *     environment points; a test points it at {@code StubNotify} instead. Harmless while the key is empty --
 *     nothing can be sent without one.
 * @param apiKey the Notify API key, which carries the service ID inside it. <strong>No working default</strong>,
 *     for the reason {@code idam.s2s-auth.totp-secret} has none: a committed placeholder that happened to work
 *     somewhere is the failure worth designing out. It is a secret and it is not in the {@code deed-poll} vault
 *     yet, which is why {@code charts/deed-poll-api/values.yaml} names no Notify secret -- a chart naming a
 *     secret the vault does not hold fails to deploy.
 * @param templates the Notify template UUID for each template this service sends, keyed by the configuration
 *     key an {@code EmailTemplate} member carries. Keyed rather than hard-coded because a Notify template UUID
 *     differs per environment, so it is configuration and never a constant in Java.
 */
@ConfigurationProperties(prefix = "notify")
public record NotifyProperties(String apiUrl, String apiKey, Map<String, String> templates) {

    /**
     * The value a template ID has until notify-0 provisions a real one. Deliberately the same literal as
     * {@code deedpoll.ccd.case-type-id}, so {@code grep DEED_POLL_PLACEHOLDER} lists everything unprovisioned.
     */
    public static final String UNPROVISIONED_TEMPLATE_ID = "DEED_POLL_PLACEHOLDER";

    public NotifyProperties(String apiUrl, String apiKey, Map<String, String> templates) {
        // Trimmed because both arrive from a Helm environment map or a vault file, where a trailing newline
        // is invisible -- and a key with one on the end is a key Notify refuses for no visible reason.
        this.apiUrl = apiUrl == null ? "" : apiUrl.trim();
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.templates = templates == null ? Map.of() : Map.copyOf(templates);
    }

    /**
     * The Notify template UUID configured under {@code key}, or the placeholder if the key is unset.
     *
     * <p>An absent key answers {@link #UNPROVISIONED_TEMPLATE_ID} rather than null, so a caller never has to
     * ask whether a template is configured before using it: the unconfigured case fails at Notify, in one
     * place, with a status the taxonomy already classifies -- instead of a {@code NullPointerException}
     * somewhere further in.
     */
    public String templateId(String key) {
        return templates.getOrDefault(key, UNPROVISIONED_TEMPLATE_ID);
    }

    /**
     * Whether {@code key} names a template Notify could actually render.
     *
     * <p>For the Email tab: a template whose ID is still the placeholder is one a caseworker must be told about
     * rather than one whose preview mysteriously fails.
     */
    public boolean isProvisioned(String key) {
        return !UNPROVISIONED_TEMPLATE_ID.equals(templateId(key));
    }
}
