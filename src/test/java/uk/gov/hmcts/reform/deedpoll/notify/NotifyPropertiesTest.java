package uk.gov.hmcts.reform.deedpoll.notify;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the Notify configuration does when nobody has configured it -- which is every environment, today.
 *
 * <p>The values arrive from a Helm environment map and, once notify-0 lands, from a vault file, so all three
 * arrive as text and all three arrive absent or blank here and now. What the compact constructor does with an
 * absent value is therefore behaviour rather than tidy-up.
 */
class NotifyPropertiesTest {

    @Test
    @DisplayName("unset values become empty rather than null, so nothing has to null-check configuration")
    void unsetValuesBecomeEmpty() {
        NotifyProperties properties = new NotifyProperties(null, null, null);

        assertThat(properties.apiUrl()).isEmpty();
        assertThat(properties.apiKey()).isEmpty();
        assertThat(properties.templates()).isEmpty();
    }

    @Test
    @DisplayName("trims the key and the URL, because a trailing newline in a vault file is invisible")
    void trimsTheKeyAndUrl() {
        NotifyProperties properties =
            new NotifyProperties(" https://api.notifications.service.gov.uk \n", "  a-key\n", Map.of());

        // A key with a newline on the end is a key Notify refuses for no visible reason -- the JWT it signs is
        // valid and the service ID it names does not exist.
        assertThat(properties.apiKey()).isEqualTo("a-key");
        assertThat(properties.apiUrl()).isEqualTo("https://api.notifications.service.gov.uk");
    }

    @Test
    @DisplayName("an unconfigured template answers the placeholder, not null")
    void unconfiguredTemplateAnswersThePlaceholder() {
        // So a caller never has to ask whether a template is configured: the unconfigured case fails once, at
        // Notify, with a status the taxonomy already classifies, instead of as a NullPointerException further in.
        assertThat(new NotifyProperties("", "", Map.of()).templateId("application-rejected"))
            .isEqualTo(NotifyProperties.UNPROVISIONED_TEMPLATE_ID);
    }

    @Test
    @DisplayName("the placeholder is the same literal the CCD case type uses, so one grep finds both")
    void placeholderIsTheSameLiteralAsCcd() {
        // deedpoll.ccd.case-type-id: DEED_POLL_PLACEHOLDER. The convention is the point: an external identifier
        // this service is still waiting on should be findable without knowing which feature is waiting for it.
        assertThat(NotifyProperties.UNPROVISIONED_TEMPLATE_ID).isEqualTo("DEED_POLL_PLACEHOLDER");
    }

    @Test
    @DisplayName("a configured template is provisioned and a placeholder one is not")
    void reportsWhetherATemplateIsProvisioned() {
        NotifyProperties configured = new NotifyProperties("", "",
            Map.of("application-rejected", "c1d2e3f4-a5b6-4c7d-8e9f-0a1b2c3d4e55"));

        assertThat(configured.isProvisioned("application-rejected")).isTrue();
        assertThat(configured.isProvisioned("request-confirmation")).isFalse();
        assertThat(new NotifyProperties("", "", Map.of()).isProvisioned("application-rejected")).isFalse();
    }

    @Test
    @DisplayName("the template map is copied, so nothing can repoint a template after startup")
    void templateMapIsCopied() {
        Map<String, String> mutable = new HashMap<>(Map.of("application-rejected", "the-real-one"));
        NotifyProperties properties = new NotifyProperties("", "", mutable);

        mutable.put("application-rejected", "somebody-elses-template");

        assertThat(properties.templateId("application-rejected")).isEqualTo("the-real-one");
    }
}
