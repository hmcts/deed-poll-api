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
 * @param emailReplyToId the Notify reply-to address id the monitored inbox is registered under, for the emails
 *     that ask the applicant to reply. Empty until it is provisioned, and <strong>not a secret</strong> -- it is
 *     an id for an address a recipient reads on the email anyway, so it is an environment variable rather than a
 *     vault entry. One id rather than one per template: there is one office inbox, and a second would be a
 *     second thing for somebody to monitor
 */
@ConfigurationProperties(prefix = "notify")
public record NotifyProperties(String apiUrl,
                              String apiKey,
                              Map<String, String> templates,
                              String emailReplyToId) {

    /**
     * The value a template ID has until notify-0 provisions a real one. Deliberately the same literal as
     * {@code deedpoll.ccd.case-type-id}, so {@code grep DEED_POLL_PLACEHOLDER} lists everything unprovisioned.
     */
    public static final String UNPROVISIONED_TEMPLATE_ID = "DEED_POLL_PLACEHOLDER";

    public NotifyProperties(String apiUrl,
                           String apiKey,
                           Map<String, String> templates,
                           String emailReplyToId) {

        // Trimmed because all three arrive from a Helm environment map or a vault file, where a trailing newline
        // is invisible -- and a key with one on the end is a key Notify refuses for no visible reason. The
        // reply-to id is trimmed for a worse version of the same reason: the client drops a blank one silently
        // and sends from no-reply, so " " configured and nothing configured must mean the same thing here.
        this.apiUrl = apiUrl == null ? "" : apiUrl.trim();
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.templates = templates == null ? Map.of() : Map.copyOf(templates);
        this.emailReplyToId = emailReplyToId == null ? "" : emailReplyToId.trim();
    }

    /**
     * Everything but the API key, which is reported only as set or not.
     *
     * <p><strong>Overridden because a record's generated {@code toString()} prints every component, and one of
     * these is a credential.</strong> Nothing logs this object today -- checked 2026-09-23 -- but that is a
     * property of the current call sites rather than of the type, and it takes one
     * {@code log.info("{}", properties)}, one {@code String.valueOf} in an exception message or one actuator
     * endpoint to put a live Notify key into a log aggregator. Masking here makes it safe by construction
     * instead of by everybody remembering.
     *
     * <p><strong>Set-or-not rather than omitted entirely</strong>, because "is the key configured" is the first
     * question anyone debugging an unsent email asks, and answering it is the whole diagnostic value the field
     * has. The length is deliberately not reported either: a Notify key embeds the service id and its length is
     * structural, so a length narrows a guess where "set" does not.
     */
    @Override
    public String toString() {
        return "NotifyProperties[apiUrl=%s, apiKey=%s, templates=%s, emailReplyToId=%s]"
            .formatted(apiUrl, apiKey.isEmpty() ? "<unset>" : "<set>", templates, emailReplyToId);
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
     * Whether this environment has everything {@code template} needs to be sent.
     *
     * <p>For the Email tab: a template this answers false for is one a caseworker must be told about rather than
     * one whose preview mysteriously fails.
     *
     * <p><strong>Per template, and asking two questions rather than one.</strong> A real template UUID is
     * necessary for any of them; a template that {@linkplain EmailTemplate#invitesReply() invites a reply} also
     * needs {@link #emailReplyToId()}, because Notify would otherwise send an email asking for an answer from an
     * address nobody reads -- and it would do it silently, since the client drops a blank reply-to rather than
     * refusing the send. Asked of the template rather than answered for the service so that a missing reply-to
     * says "this email cannot be sent yet" about the one email that needs it, and leaves the other alone.
     */
    public boolean isProvisioned(EmailTemplate template) {
        return !UNPROVISIONED_TEMPLATE_ID.equals(templateId(template.key()))
            && (!template.invitesReply() || !emailReplyToId.isBlank());
    }
}
