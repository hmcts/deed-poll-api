package uk.gov.hmcts.reform.deedpoll.notify;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What an email is: its configuration key, its slots, and the keys a client may not set.
 *
 * <p>Container-free, deliberately. CI has no Docker and excludes every container-backed class, so a rule
 * stated only in one of those is a rule CI does not check -- and the rule here that most wants checking is the
 * split between resolved keys and slots, because it is what keeps a caseworker from altering the reference
 * number an applicant is told.
 */
class EmailTemplateTest {

    @Nested
    @DisplayName("naming")
    class Naming {

        @Test
        @DisplayName("the member name is the value an outbox row stores, so a rename is a data migration")
        void memberNameIsTheStoredValue() {
            // Notification.template holds this string. Renaming the member would orphan every stored row, so
            // this assertion exists to make that visible in a diff rather than in production.
            assertThat(EmailTemplate.APPLICATION_REJECTED.name()).isEqualTo("APPLICATION_REJECTED");
        }

        @Test
        @DisplayName("the key is the configuration key under notify.templates and the URL token")
        void keyIsTheConfigurationKey() {
            // application.yaml spells it notify.templates.application-rejected; the send path spells it
            // /caseworker/cases/{caseId}/emails/application-rejected. One string, on purpose.
            assertThat(EmailTemplate.APPLICATION_REJECTED.key()).isEqualTo("application-rejected");
        }

        @ParameterizedTest
        @EnumSource(EmailTemplate.class)
        @DisplayName("every template's key round trips, and no two share one")
        void everyKeyRoundTrips(EmailTemplate template) {
            assertThat(EmailTemplate.byKey(template.key())).contains(template);
        }

        @Test
        @DisplayName("a key is matched exactly, but surrounding whitespace is forgiven")
        void keyIsMatchedExactly() {
            assertThat(EmailTemplate.byKey("  application-rejected  "))
                .contains(EmailTemplate.APPLICATION_REJECTED);
            // Not case-insensitive and not near-missed: the key is what a client read out of the GET that
            // lists what may be sent, so a variant is a client bug worth reporting.
            assertThat(EmailTemplate.byKey("APPLICATION-REJECTED")).isEmpty();
            assertThat(EmailTemplate.byKey("application_rejected")).isEmpty();
            assertThat(EmailTemplate.byKey("")).isEmpty();
            assertThat(EmailTemplate.byKey(null)).isEmpty();
        }

        @Test
        @DisplayName("keys() lists what a refusal would have accepted")
        void keysListsEveryTemplate() {
            assertThat(EmailTemplate.keys()).contains(EmailTemplate.APPLICATION_REJECTED.key());
        }
    }

    @Nested
    @DisplayName("what a client may and may not set")
    class ClientInput {

        @Test
        @DisplayName("the slots are the two the caseworker fills, in the order a form shows them")
        void slotsAreTheCaseworkersTwo() {
            assertThat(EmailTemplate.APPLICATION_REJECTED.slots())
                .containsExactly(EmailSlot.JUDGES_REASON, EmailSlot.ADDITIONAL_NOTES);
        }

        @Test
        @DisplayName("the resolved keys are the case's, and a client may set none of them")
        void resolvedKeysComeFromTheCase() {
            assertThat(EmailTemplate.APPLICATION_REJECTED.resolvedKeys()).containsExactly(
                "reference_number", "online_application_reference", "old_forename", "old_surname",
                "applied_online");
            assertThat(EmailTemplate.APPLICATION_REJECTED.resolves("reference_number")).isTrue();
            assertThat(EmailTemplate.APPLICATION_REJECTED.resolves("applied_online")).isTrue();
        }

        @ParameterizedTest
        @EnumSource(EmailTemplate.class)
        @DisplayName("no slot is also a resolved key, so no key is both client input and not")
        void noKeyIsBothInputAndResolved(EmailTemplate template) {
            // The whole rule of decision 12 rests on the two sets being disjoint: a key in both would be
            // refused as unsettable and then silently overwritten anyway.
            assertThat(template.slots().stream().map(EmailSlot::placeholder))
                .noneMatch(template::resolves);
        }

        @ParameterizedTest
        @EnumSource(EmailTemplate.class)
        @DisplayName("personalisationKeys is the resolved keys then the slots, with no duplicates")
        void personalisationKeysAreResolvedThenSlots(EmailTemplate template) {
            assertThat(template.personalisationKeys())
                .startsWith(template.resolvedKeys().toArray(String[]::new))
                .endsWith(template.slots().stream().map(EmailSlot::placeholder).toArray(String[]::new))
                .doesNotHaveDuplicates()
                .hasSize(template.resolvedKeys().size() + template.slots().size());
        }

        @Test
        @DisplayName("slot() answers by placeholder name and refuses anything else")
        void slotAnswersByPlaceholder() {
            assertThat(EmailTemplate.APPLICATION_REJECTED.slot("judges_reason"))
                .contains(EmailSlot.JUDGES_REASON);
            assertThat(EmailTemplate.APPLICATION_REJECTED.slot("JUDGES_REASON")).isEmpty();
            assertThat(EmailTemplate.APPLICATION_REJECTED.slot("reference_number")).isEmpty();
        }
    }

    @Nested
    @DisplayName("the Notify template id")
    class TemplateId {

        private static NotifyProperties configured(Map<String, String> templates) {
            return new NotifyProperties("https://notify.example", "", templates);
        }

        @Test
        @DisplayName("is the placeholder until notify-0 provisions one")
        void unprovisionedIsThePlaceholder() {
            NotifyProperties properties = configured(Map.of());

            assertThat(EmailTemplate.APPLICATION_REJECTED.templateId(properties))
                .isEqualTo(NotifyProperties.UNPROVISIONED_TEMPLATE_ID);
            assertThat(properties.isProvisioned(EmailTemplate.APPLICATION_REJECTED.key())).isFalse();
        }

        @Test
        @DisplayName("is read from configuration under the template's own key")
        void provisionedIsReadFromConfiguration() {
            String uuid = "2b0e6b6e-0000-4000-8000-0000000000aa";
            NotifyProperties properties = configured(Map.of("application-rejected", uuid));

            assertThat(EmailTemplate.APPLICATION_REJECTED.templateId(properties)).isEqualTo(uuid);
            assertThat(properties.isProvisioned(EmailTemplate.APPLICATION_REJECTED.key())).isTrue();
        }

        @ParameterizedTest
        @EnumSource(EmailTemplate.class)
        @DisplayName("every template has somewhere to read an id from, provisioned or not")
        void everyTemplateResolvesAnId(EmailTemplate template) {
            // Never null: an unconfigured template fails at Notify with a status the taxonomy classifies,
            // rather than with a NullPointerException somewhere further in.
            assertThat(Optional.ofNullable(template.templateId(configured(Map.of())))).isPresent();
        }
    }

    @Nested
    @DisplayName("the slots themselves")
    class Slots {

        @ParameterizedTest
        @EnumSource(EmailSlot.class)
        @DisplayName("every slot is bounded and named in snake_case, as a Notify placeholder is")
        void everySlotIsBoundedAndSnakeCase(EmailSlot slot) {
            assertThat(slot.maxLength()).isPositive();
            assertThat(slot.placeholder()).matches("[a-z][a-z0-9_]*");
        }

        @Test
        @DisplayName("the judge's reason is capped at the cap a direction was accepted under")
        void judgesReasonCarriesAnyDirection() {
            // Load-bearing: a narrower cap here would mean a judge could record a direction a caseworker
            // could not pass on. ReferralSchemas.MAX_DIRECTIONS_LENGTH is the same number by construction.
            assertThat(EmailSlot.JUDGES_REASON.maxLength()).isEqualTo(4000);
            assertThat(EmailSlot.JUDGES_REASON.required()).isTrue();
        }

        @Test
        @DisplayName("notes are narrower and optional -- an email can go without them")
        void notesAreOptional() {
            assertThat(EmailSlot.ADDITIONAL_NOTES.maxLength()).isEqualTo(1000);
            assertThat(EmailSlot.ADDITIONAL_NOTES.required()).isFalse();
        }
    }
}
