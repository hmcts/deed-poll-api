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
        NotifyProperties properties = new NotifyProperties(null, null, null, null);

        assertThat(properties.apiUrl()).isEmpty();
        assertThat(properties.apiKey()).isEmpty();
        assertThat(properties.templates()).isEmpty();
        assertThat(properties.emailReplyToId()).isEmpty();
    }

    @Test
    @DisplayName("trims the key, the URL and the reply-to, because a trailing newline in a vault file is invisible")
    void trimsTheKeyAndUrl() {
        NotifyProperties properties = new NotifyProperties(
            " https://api.notifications.service.gov.uk \n", "  a-key\n", Map.of(), "  a-reply-to-id\n");

        // A key with a newline on the end is a key Notify refuses for no visible reason -- the JWT it signs is
        // valid and the service ID it names does not exist.
        assertThat(properties.apiKey()).isEqualTo("a-key");
        assertThat(properties.apiUrl()).isEqualTo("https://api.notifications.service.gov.uk");
        // And a reply-to id with one on the end is worse than refused: Notify's client sends it as a value the
        // API does not recognise, or -- if it is only whitespace -- drops it and sends from no-reply.
        assertThat(properties.emailReplyToId()).isEqualTo("a-reply-to-id");
        assertThat(new NotifyProperties("", "", Map.of(), "   ").emailReplyToId())
            .as("whitespace configured and nothing configured are the same thing")
            .isEmpty();
    }

    @Test
    @DisplayName("an unconfigured template answers the placeholder, not null")
    void unconfiguredTemplateAnswersThePlaceholder() {
        // So a caller never has to ask whether a template is configured: the unconfigured case fails once, at
        // Notify, with a status the taxonomy already classifies, instead of as a NullPointerException further in.
        assertThat(new NotifyProperties("", "", Map.of(), "").templateId("application-rejected"))
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
            Map.of("application-rejected", "c1d2e3f4-a5b6-4c7d-8e9f-0a1b2c3d4e55"), "");

        assertThat(configured.isProvisioned(EmailTemplate.APPLICATION_REJECTED)).isTrue();
        assertThat(configured.isProvisioned(EmailTemplate.REQUEST_CONFIRMATION_TO_PROCEED)).isFalse();
        assertThat(new NotifyProperties("", "", Map.of(), "")
                       .isProvisioned(EmailTemplate.APPLICATION_REJECTED)).isFalse();
    }

    @Test
    @DisplayName("a template that invites a reply is unprovisioned without a reply-to, and the other is not")
    void anInvitedReplyNeedsAnAddressToReplyTo() {
        // Decisions 9 and 10. The confirmation email's own words say "let us know by replying to this email", so a
        // send with no reply-to configured goes out from Notify's no-reply sender and the answer it asks for
        // bounces -- invisibly, because the client drops a blank reply-to rather than refusing. So the Email tab is
        // told it cannot be sent yet, in the same words and by the same flag as a missing template UUID.
        String uuid = "c1d2e3f4-a5b6-4c7d-8e9f-0a1b2c3d4e55";
        Map<String, String> both = Map.of(
            EmailTemplate.APPLICATION_REJECTED.key(), uuid,
            EmailTemplate.REQUEST_CONFIRMATION_TO_PROCEED.key(), uuid);

        NotifyProperties noReplyTo = new NotifyProperties("", "", both, "");
        assertThat(noReplyTo.isProvisioned(EmailTemplate.REQUEST_CONFIRMATION_TO_PROCEED)).isFalse();
        // And the other template is untouched by its absence, which is why this is asked per template: a missing
        // inbox must not disable the email that never invited a reply.
        assertThat(noReplyTo.isProvisioned(EmailTemplate.APPLICATION_REJECTED)).isTrue();

        NotifyProperties provisioned =
            new NotifyProperties("", "", both, "9b7c5d21-3f4e-4a6b-8c0d-1e2f3a4b5c66");
        assertThat(provisioned.isProvisioned(EmailTemplate.REQUEST_CONFIRMATION_TO_PROCEED)).isTrue();
        assertThat(provisioned.isProvisioned(EmailTemplate.APPLICATION_REJECTED)).isTrue();

        // A reply-to on its own provisions nothing: the template UUID is still necessary for either of them.
        assertThat(new NotifyProperties("", "", Map.of(), "9b7c5d21-3f4e-4a6b-8c0d-1e2f3a4b5c66")
                       .isProvisioned(EmailTemplate.REQUEST_CONFIRMATION_TO_PROCEED)).isFalse();
    }

    @Test
    @DisplayName("the template map is copied, so nothing can repoint a template after startup")
    void templateMapIsCopied() {
        Map<String, String> mutable = new HashMap<>(Map.of("application-rejected", "the-real-one"));
        NotifyProperties properties = new NotifyProperties("", "", mutable, "");

        mutable.put("application-rejected", "somebody-elses-template");

        assertThat(properties.templateId("application-rejected")).isEqualTo("the-real-one");
    }

    @Test
    @DisplayName("toString never prints the API key, however it is reached")
    void toStringNeverPrintsTheApiKey() {
        String key = "team_and_guest_key-11111111-2222-3333-4444-555555555555-66666666-7777-8888-9999-aaaaaaaaaaaa";
        NotifyProperties properties =
            new NotifyProperties("https://api.notifications.service.gov.uk", key, Map.of("application-rejected",
                                                                                        "a-template-uuid"), "");

        // Asserted on the whole rendering and on the key's parts separately: a record's generated toString would
        // have printed it, and the service id embedded in a Notify key is on its own worth not logging.
        assertThat(properties.toString()).doesNotContain(key).doesNotContain("11111111-2222-3333");
        // And through the routes that reach toString without naming it, which is how this leaks in practice.
        assertThat(String.valueOf(properties)).doesNotContain(key);
        assertThat("%s".formatted(properties)).doesNotContain(key);
    }

    @Test
    @DisplayName("toString still says whether a key is configured, which is the question worth answering")
    void toStringReportsWhetherTheKeyIsSet() {
        // The diagnostic value the field has at all: "is the key configured" is the first question anyone
        // debugging an unsent email asks. Masking it entirely would trade a real leak for a real blind spot.
        assertThat(new NotifyProperties("", "a-key", Map.of(), "").toString()).contains("apiKey=<set>");
        assertThat(new NotifyProperties("", "", Map.of(), "").toString()).contains("apiKey=<unset>");
        // Blank is unset, because the constructor trims -- " " configured and nothing configured are the same
        // state everywhere else in this class and must read the same here.
        assertThat(new NotifyProperties("", "   ", Map.of(), "").toString()).contains("apiKey=<unset>");
    }

    @Test
    @DisplayName("toString keeps the fields that are not secrets, so it stays useful")
    void toStringKeepsTheNonSecrets() {
        NotifyProperties properties = new NotifyProperties(
            "https://api.notifications.service.gov.uk", "a-key",
            Map.of("application-rejected", "a-template-uuid"), "a-reply-to-id");

        assertThat(properties.toString())
            .contains("https://api.notifications.service.gov.uk")
            .contains("a-template-uuid")
            .contains("a-reply-to-id");
    }
}
