package uk.gov.hmcts.reform.deedpoll.domain.masters;

import uk.gov.hmcts.reform.deedpoll.notify.NotifyProperties;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.UUID;

/**
 * Which Master email templates this environment can send, and where their links point (plan D3).
 *
 * <p><strong>Start-up fails on a provisioned template with no admin base URL</strong>, because the alternative is
 * an email whose every link is relative, or names a host somebody guessed, sent to a judge. The messages are fixed
 * and never echo the configured value. A base URL that is set must be an absolute {@code http} or {@code https}
 * address with a host and no query or fragment, whether or not a template is provisioned yet, so a mistyped chart
 * value fails the deploy that introduced it rather than the one that provisions the template.
 */
public final class MasterEmailLinks {

    static final String MISSING_BASE_URL =
        "deedpoll.admin.base-url (ADMIN_BASE_URL) must be set when a Master email template is provisioned";

    static final String INVALID_BASE_URL =
        "deedpoll.admin.base-url (ADMIN_BASE_URL) must be an absolute http or https URL with no query or fragment";

    private final NotifyProperties notify;

    /** The base URL without a trailing slash, or empty. */
    private final String baseUrl;

    public MasterEmailLinks(AdminProperties admin, NotifyProperties notify) {
        this.notify = notify;
        this.baseUrl = checked(admin.baseUrl());
        boolean anyProvisioned = false;
        for (MasterEmailKind kind : MasterEmailKind.values()) {
            anyProvisioned |= isProvisioned(kind);
        }
        if (anyProvisioned && baseUrl.isEmpty()) {
            throw new IllegalStateException(MISSING_BASE_URL);
        }
    }

    /**
     * Whether {@code kind}'s template has an id. No reply-to and no document, so the id is the only question, and a
     * blank one answers no as the placeholder does.
     */
    public boolean isProvisioned(MasterEmailKind kind) {
        String id = templateId(kind);
        return !id.isBlank() && !NotifyProperties.UNPROVISIONED_TEMPLATE_ID.equals(id);
    }

    /**
     * {@code kind}'s Notify template id, trimmed, or the placeholder.
     */
    public String templateId(MasterEmailKind kind) {
        return notify.templateId(kind.templateKey()).trim();
    }

    /** The admin app's page for one referral, as a judge opens it. */
    public String referralLink(UUID referralId) {
        if (baseUrl.isEmpty()) {
            throw new IllegalStateException(MISSING_BASE_URL);
        }
        return baseUrl + "/judicial/referrals/" + referralId;
    }

    private static String checked(String configured) {
        if (configured.isEmpty()) {
            return "";
        }
        URI uri;
        try {
            uri = new URI(configured);
        } catch (URISyntaxException unparsable) {
            throw new IllegalStateException(INVALID_BASE_URL);
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("https") && !scheme.equals("http")
            || uri.getHost() == null || uri.getHost().isEmpty()
            || uri.getRawQuery() != null || uri.getRawFragment() != null) {
            throw new IllegalStateException(INVALID_BASE_URL);
        }
        return configured.endsWith("/") ? configured.substring(0, configured.length() - 1) : configured;
    }
}
