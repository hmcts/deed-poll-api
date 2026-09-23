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

    /** A reply-to address id, invented: what matters about it here is only that it is configured. */
    private static final String REPLY_TO = "9b7c5d21-3f4e-4a6b-8c0d-1e2f3a4b5c66";

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

        @Test
        @DisplayName("the display name is explicit rather than derived from a stored enum member")
        void displayNameIsForCaseworkers() {
            assertThat(EmailTemplate.APPLICATION_REJECTED.displayName()).isEqualTo("Application rejected");
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
            assertThat(EmailTemplate.keys()).contains(EmailTemplate.REQUEST_CONFIRMATION_TO_PROCEED.key());
        }

        @Test
        @DisplayName("the confirmation email's member name is the value an outbox row stores")
        void confirmationMemberNameIsTheStoredValue() {
            assertThat(EmailTemplate.REQUEST_CONFIRMATION_TO_PROCEED.name())
                .isEqualTo("REQUEST_CONFIRMATION_TO_PROCEED");
        }

        @Test
        @DisplayName("the confirmation email's key is its configuration key and its URL token")
        void confirmationKeyIsTheConfigurationKey() {
            // notify.templates.request-confirmation-to-proceed, and
            // /caseworker/cases/{caseId}/emails/request-confirmation-to-proceed. One string.
            assertThat(EmailTemplate.REQUEST_CONFIRMATION_TO_PROCEED.key())
                .isEqualTo("request-confirmation-to-proceed");
            assertThat(EmailTemplate.REQUEST_CONFIRMATION_TO_PROCEED.displayName())
                .isEqualTo("Request confirmation to proceed");
        }

        @Test
        @DisplayName("the rejection email is listed first, which is the order the Email tab shows them in")
        void theRejectionEmailIsListedFirst() {
            assertThat(EmailTemplate.values())
                .startsWith(EmailTemplate.APPLICATION_REJECTED, EmailTemplate.REQUEST_CONFIRMATION_TO_PROCEED);
        }

        @ParameterizedTest
        @EnumSource(EmailTemplate.class)
        @DisplayName("every template's member name round trips, which is how the drain reads an outbox row")
        void everyMemberNameRoundTrips(EmailTemplate template) {
            assertThat(EmailTemplate.byName(template.name())).contains(template);
        }
    }

    @Nested
    @DisplayName("mutual exclusion")
    class ExclusionGroups {

        @Test
        @DisplayName("the two referral-driven emails share a group, which is what makes them exclusive")
        void bothReferralEmailsShareAGroup() {
            // Decision 3, as a fact about the enum: the group is the key V15's unique index is built on, so two
            // templates sharing one means a referral gets one of them and Postgres decides which.
            assertThat(EmailTemplate.APPLICATION_REJECTED.exclusionGroup())
                .isEqualTo(EmailExclusionGroup.REFERRAL_OUTCOME)
                .isEqualTo(EmailTemplate.REQUEST_CONFIRMATION_TO_PROCEED.exclusionGroup());
        }

        @Test
        @DisplayName("the group's member name is the value an outbox row stores and the column's default")
        void groupMemberNameIsTheStoredValue() {
            // V15 adds "exclusionGroup" text NOT NULL DEFAULT 'REFERRAL_OUTCOME' and enqueue binds this name, so
            // renaming the member is a data migration -- visible in a diff here rather than in production.
            assertThat(EmailExclusionGroup.REFERRAL_OUTCOME.name()).isEqualTo("REFERRAL_OUTCOME");
        }

        @ParameterizedTest
        @EnumSource(EmailTemplate.class)
        @DisplayName("every template declares a group, because the column is NOT NULL and the argument required")
        void everyTemplateDeclaresAGroup(EmailTemplate template) {
            // A group is a required constructor argument rather than a table of pairs for exactly this reason: a
            // template cannot be added without saying which emails it is an alternative to.
            assertThat(template.exclusionGroup()).isNotNull();
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
        @DisplayName("the rejection email carries no documents")
        void rejectedEmailCarriesNoDocuments() {
            assertThat(EmailTemplate.APPLICATION_REJECTED.documentKeys()).isEmpty();
        }

        @Test
        @DisplayName("the resolved keys are the case's, and a client may set none of them")
        void resolvedKeysComeFromTheCase() {
            assertThat(EmailTemplate.APPLICATION_REJECTED.resolvedKeys()).containsExactly(
                "reference_number", "online_application_reference", "old_forename", "old_surname",
                "applied_online", "applied_on_paper");
            assertThat(EmailTemplate.APPLICATION_REJECTED.resolves("reference_number")).isTrue();
            assertThat(EmailTemplate.APPLICATION_REJECTED.resolves("applied_online")).isTrue();
            // Resolved, so a client is refused it like any other case-derived key. It exists because Notify's
            // conditional has no `else` and the template needs two flags for two mutually exclusive paragraphs.
            assertThat(EmailTemplate.APPLICATION_REJECTED.resolves("applied_on_paper")).isTrue();
        }

        @Test
        @DisplayName("the confirmation email resolves two more keys, and neither is a slot of the other template")
        void theConfirmationEmailResolvesTwoMore() {
            assertThat(EmailTemplate.REQUEST_CONFIRMATION_TO_PROCEED.resolvedKeys()).containsExactly(
                "reference_number", "online_application_reference", "old_forename", "old_surname",
                "applied_online", "applied_on_paper", "is_minor", "decision_date");
            // The rejection email does not have them, so the two are not silently the same personalisation:
            // a client sending is_minor to application-rejected is told it is not a field at all.
            assertThat(EmailTemplate.APPLICATION_REJECTED.resolves("is_minor")).isFalse();
            assertThat(EmailTemplate.APPLICATION_REJECTED.resolves("decision_date")).isFalse();
        }

        @Test
        @DisplayName("both templates take the same two slots, which is decision 8 rather than a coincidence")
        void bothTemplatesTakeTheSameSlots() {
            // EmailSlot's requiredness holds for the whole proposed template set, so a second template needing
            // the judge's reason and optional notes is the design being confirmed, not a new slot.
            assertThat(EmailTemplate.REQUEST_CONFIRMATION_TO_PROCEED.slots())
                .containsExactly(EmailSlot.JUDGES_REASON, EmailSlot.ADDITIONAL_NOTES);
            assertThat(EmailTemplate.REQUEST_CONFIRMATION_TO_PROCEED.documentKeys()).isEmpty();
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
            return new NotifyProperties("https://notify.example", "", templates, REPLY_TO);
        }

        @Test
        @DisplayName("is the placeholder until notify-0 provisions one")
        void unprovisionedIsThePlaceholder() {
            NotifyProperties properties = configured(Map.of());

            assertThat(EmailTemplate.APPLICATION_REJECTED.templateId(properties))
                .isEqualTo(NotifyProperties.UNPROVISIONED_TEMPLATE_ID);
            assertThat(properties.isProvisioned(EmailTemplate.APPLICATION_REJECTED)).isFalse();
        }

        @Test
        @DisplayName("is read from configuration under the template's own key")
        void provisionedIsReadFromConfiguration() {
            String uuid = "2b0e6b6e-0000-4000-8000-0000000000aa";
            NotifyProperties properties = configured(Map.of("application-rejected", uuid));

            assertThat(EmailTemplate.APPLICATION_REJECTED.templateId(properties)).isEqualTo(uuid);
            assertThat(properties.isProvisioned(EmailTemplate.APPLICATION_REJECTED)).isTrue();
        }

        @ParameterizedTest
        @EnumSource(EmailTemplate.class)
        @DisplayName("every template has somewhere to read an id from, provisioned or not")
        void everyTemplateResolvesAnId(EmailTemplate template) {
            // Never null: an unconfigured template fails at Notify with a status the taxonomy classifies,
            // rather than with a NullPointerException somewhere further in.
            assertThat(Optional.ofNullable(template.templateId(configured(Map.of())))).isPresent();
        }

        @Test
        @DisplayName("a template that invites a reply needs the reply-to as well as the id")
        void anInvitedReplyIsPartOfBeingProvisioned() {
            // Decision 10 as a fact about this pair: provisioning is asked per template because the two templates
            // need different things of an environment. NotifyPropertiesTest states the rule itself.
            String uuid = "2b0e6b6e-0000-4000-8000-0000000000aa";
            Map<String, String> both = Map.of(
                EmailTemplate.APPLICATION_REJECTED.key(), uuid,
                EmailTemplate.REQUEST_CONFIRMATION_TO_PROCEED.key(), uuid);
            NotifyProperties noReplyTo = new NotifyProperties("https://notify.example", "", both, "");

            assertThat(noReplyTo.isProvisioned(EmailTemplate.REQUEST_CONFIRMATION_TO_PROCEED)).isFalse();
            assertThat(noReplyTo.isProvisioned(EmailTemplate.APPLICATION_REJECTED)).isTrue();
            assertThat(new NotifyProperties("https://notify.example", "", both, REPLY_TO)
                           .isProvisioned(EmailTemplate.REQUEST_CONFIRMATION_TO_PROCEED)).isTrue();
        }
    }

    @Nested
    @DisplayName("whether an email invites a reply")
    class InvitesReply {

        @Test
        @DisplayName("the confirmation email does, because asking for one is what it is for")
        void theConfirmationEmailInvitesAReply() {
            // "If you still wish to enrol the deed poll then let us know by replying to this email." Sent from a
            // no-reply address that sentence asks for something the applicant cannot give.
            assertThat(EmailTemplate.REQUEST_CONFIRMATION_TO_PROCEED.invitesReply()).isTrue();
        }

        @Test
        @DisplayName("the rejection email does not, and must not start needing a reply-to")
        void theRejectionEmailInvitesNone() {
            // It states a decision and starts a refund; it asks the applicant for nothing. Asserted rather than
            // left implicit because the tempting simplification is one flag for the service, which would make a
            // reply-to configured for ④ silently change what ③ sends.
            assertThat(EmailTemplate.APPLICATION_REJECTED.invitesReply()).isFalse();
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
