package uk.gov.hmcts.reform.deedpoll.notify;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
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

    /** A configured document retention period, so a template that carries a document can read provisioned. */
    private static final int RETENTION_WEEKS = 17;

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
        @DisplayName("the chase's member name, key and display name are the three spellings it is published as")
        void chaseNamesAreTheStoredValues() {
            // notify.templates.chase-missing-documents, /caseworker/cases/{id}/emails/chase-missing-documents, and
            // CHASE_MISSING_DOCUMENTS in the outbox row. The key is the one notify-6's Task 0 read back out of
            // Notify, so a respelling here would leave a provisioned template unreachable.
            assertThat(EmailTemplate.CHASE_MISSING_DOCUMENTS.name()).isEqualTo("CHASE_MISSING_DOCUMENTS");
            assertThat(EmailTemplate.CHASE_MISSING_DOCUMENTS.key()).isEqualTo("chase-missing-documents");
            assertThat(EmailTemplate.CHASE_MISSING_DOCUMENTS.displayName()).isEqualTo("Chase missing documents");
        }

        @Test
        @DisplayName("each forms email's three spellings are the ones it is published as")
        void formsNamesAreTheStoredValues() {
            // notify.templates.forms-for-completion-adult, /caseworker/cases/{id}/emails/forms-for-completion-adult,
            // and FORMS_FOR_COMPLETION_ADULT in the outbox row. Pinned because the keys are also the environment
            // variable names the chart and the content draft already use -- NOTIFY_TEMPLATE_FORMS_FOR_COMPLETION_
            // ADULT -- so a plausible respelling would leave a provisioned template unreachable.
            assertThat(EmailTemplate.FORMS_FOR_COMPLETION_ADULT.name()).isEqualTo("FORMS_FOR_COMPLETION_ADULT");
            assertThat(EmailTemplate.FORMS_FOR_COMPLETION_ADULT.key()).isEqualTo("forms-for-completion-adult");
            assertThat(EmailTemplate.FORMS_FOR_COMPLETION_MINOR.name()).isEqualTo("FORMS_FOR_COMPLETION_MINOR");
            assertThat(EmailTemplate.FORMS_FOR_COMPLETION_MINOR.key()).isEqualTo("forms-for-completion-minor");
        }

        @Test
        @DisplayName("the two forms emails have different display names, because a note says which was sent")
        void theFormsEmailsAreNamedApart() {
            // EmailNotes composes from the display name alone, so one name on both would leave the case's only
            // timeline unable to say which deed the applicant was sent -- and notes are never revised.
            assertThat(EmailTemplate.FORMS_FOR_COMPLETION_ADULT.displayName())
                .isEqualTo("Forms for completion")
                .isNotEqualTo(EmailTemplate.FORMS_FOR_COMPLETION_MINOR.displayName());
            assertThat(EmailTemplate.FORMS_FOR_COMPLETION_MINOR.displayName())
                .isEqualTo("Forms for completion for a child");
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

        @Test
        @DisplayName("the chase is in a group of its own, so a refusal and a chase are not alternatives")
        void theChaseHasItsOwnGroup() {
            // Decision 5 as a fact about the enum: sharing REFERRAL_OUTCOME would make a chase impossible on any
            // case whose referral had already had an outcome email -- and those are unrelated processes.
            assertThat(EmailTemplate.CHASE_MISSING_DOCUMENTS.exclusionGroup())
                .isEqualTo(EmailExclusionGroup.DOCUMENT_CHASE)
                .isNotEqualTo(EmailTemplate.APPLICATION_REJECTED.exclusionGroup());
            // And the member name is the value a row stores, as REFERRAL_OUTCOME's is.
            assertThat(EmailExclusionGroup.DOCUMENT_CHASE.name()).isEqualTo("DOCUMENT_CHASE");
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

        @Test
        @DisplayName("the chase takes one slot and resolves the four common case values, and nothing more")
        void theChaseTakesOneSlot() {
            // The contract notify-6's Task 0 verified against the provisioned template: exactly five placeholders,
            // four resolved and one the caseworker writes. A sixth key would be personalisation Notify has nowhere
            // to put, and a fifth missing one is a send Notify refuses.
            assertThat(EmailTemplate.CHASE_MISSING_DOCUMENTS.slots())
                .containsExactly(EmailSlot.DOCUMENTS_REQUIRED);
            assertThat(EmailTemplate.CHASE_MISSING_DOCUMENTS.resolvedKeys()).containsExactly(
                "reference_number", "online_application_reference", "old_forename", "old_surname");
            assertThat(EmailTemplate.CHASE_MISSING_DOCUMENTS.personalisationKeys()).containsExactly(
                "reference_number", "online_application_reference", "old_forename", "old_surname",
                "documents_required");
            // No refund branch: this email starts no refund, so the flag would be a value the template could not
            // use and a reader of the stored row could not account for.
            assertThat(EmailTemplate.CHASE_MISSING_DOCUMENTS.resolves("applied_online")).isFalse();
            assertThat(EmailTemplate.CHASE_MISSING_DOCUMENTS.resolves("decision_date")).isFalse();
            // And nothing attached: this email asks for incoming material and sends none.
            assertThat(EmailTemplate.CHASE_MISSING_DOCUMENTS.documentKeys()).isEmpty();
            // Its slot is not a slot of the referral templates, so a client sending it to one is told so.
            assertThat(EmailTemplate.APPLICATION_REJECTED.slot("documents_required")).isEmpty();
        }

        @Test
        @DisplayName("the forms emails take no slots and carry one document each")
        void theFormsEmailsTakeNoSlots() {
            // Decision 7, measured rather than assumed: neither source document carries a caseworker marker of any
            // style, unlike ③ and ④. EmailSlots.parse therefore refuses anything a client sends, and a free-text
            // box would be a template change in notify-0 before it could be a slot here -- a slot the Notify
            // template has no placeholder for makes the send a 400.
            for (EmailTemplate template : new EmailTemplate[]{
                EmailTemplate.FORMS_FOR_COMPLETION_ADULT, EmailTemplate.FORMS_FOR_COMPLETION_MINOR}) {
                assertThat(template.slots()).isEmpty();
                assertThat(template.slot("judges_reason")).isEmpty();
                // The first templates that carry a file, and each carries exactly one: the deed. The five blank
                // forms are attached to the Notify template itself, so this service never holds them.
                assertThat(template.documentKeys()).containsExactly("deed_document");
                // The document key is a resolved key too, which is what refuses a client that tries to nominate
                // which document an applicant is sent.
                assertThat(template.resolves("deed_document")).isTrue();
            }
            // And no other template carries one, so nothing else reaches the upload seam.
            assertThat(EmailTemplate.CHASE_MISSING_DOCUMENTS.resolves("deed_document")).isFalse();
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
            return new NotifyProperties("https://notify.example", "", templates, REPLY_TO, RETENTION_WEEKS);
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
            NotifyProperties noReplyTo = new NotifyProperties("https://notify.example", "", both, "", RETENTION_WEEKS);

            assertThat(noReplyTo.isProvisioned(EmailTemplate.REQUEST_CONFIRMATION_TO_PROCEED)).isFalse();
            assertThat(noReplyTo.isProvisioned(EmailTemplate.APPLICATION_REJECTED)).isTrue();
            assertThat(new NotifyProperties("https://notify.example", "", both, REPLY_TO, RETENTION_WEEKS)
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
        @DisplayName("the chase does, because a reply is how the applicant sends what is missing")
        void theChaseInvitesAReply() {
            // "Reply to this email and we will help" -- read back off the provisioned template on 2026-09-24. Sent
            // from no-reply, the documents this email exists to collect would arrive nowhere.
            assertThat(EmailTemplate.CHASE_MISSING_DOCUMENTS.invitesReply()).isTrue();
            String uuid = "2b0e6b6e-0000-4000-8000-0000000000aa";
            Map<String, String> chase = Map.of(EmailTemplate.CHASE_MISSING_DOCUMENTS.key(), uuid);
            assertThat(new NotifyProperties("https://notify.example", "", chase, "", RETENTION_WEEKS)
                           .isProvisioned(EmailTemplate.CHASE_MISSING_DOCUMENTS)).isFalse();
            assertThat(new NotifyProperties("https://notify.example", "", chase, REPLY_TO, RETENTION_WEEKS)
                           .isProvisioned(EmailTemplate.CHASE_MISSING_DOCUMENTS)).isTrue();
        }

        @Test
        @DisplayName("both forms emails do, and each also needs a retention period to be sendable")
        void theFormsEmailsInviteAReplyAndCarryADocument() {
            // "If you have any questions relating to your application you can reply to this email" -- read off both
            // source documents on 2026-09-25. An earlier draft of the plan had treated reply-to as ④ and ⑤'s
            // concern alone; it is these two as well, and Notify silently drops a blank one.
            assertThat(EmailTemplate.FORMS_FOR_COMPLETION_ADULT.invitesReply()).isTrue();
            assertThat(EmailTemplate.FORMS_FOR_COMPLETION_MINOR.invitesReply()).isTrue();

            // Three things rather than two, because these are the first templates that carry a file: the UUID, the
            // reply-to, and the retention period the drain needs to call prepareUpload. Any one missing reads
            // unprovisioned, which is what the Email tab says instead of offering a send that fails on the drain.
            String uuid = "2b0e6b6e-0000-4000-8000-0000000000aa";
            Map<String, String> forms = Map.of(EmailTemplate.FORMS_FOR_COMPLETION_ADULT.key(), uuid);
            assertThat(new NotifyProperties("https://notify.example", "", forms, REPLY_TO, RETENTION_WEEKS)
                           .isProvisioned(EmailTemplate.FORMS_FOR_COMPLETION_ADULT)).isTrue();
            assertThat(new NotifyProperties("https://notify.example", "", forms, "", RETENTION_WEEKS)
                           .isProvisioned(EmailTemplate.FORMS_FOR_COMPLETION_ADULT)).isFalse();
            assertThat(new NotifyProperties("https://notify.example", "", forms, REPLY_TO, null)
                           .isProvisioned(EmailTemplate.FORMS_FOR_COMPLETION_ADULT)).isFalse();
            // Zero is unset rather than "keep it for no time at all", which is one rule and not two readings: a
            // chart that set it would otherwise send an applicant a link to nothing.
            assertThat(new NotifyProperties("https://notify.example", "", forms, REPLY_TO, 0)
                           .isProvisioned(EmailTemplate.FORMS_FOR_COMPLETION_ADULT)).isFalse();
            // And a template that carries no document is unaffected by the retention period in either direction,
            // so configuring one for ① cannot quietly change what ⑤ needs.
            Map<String, String> chase = Map.of(EmailTemplate.CHASE_MISSING_DOCUMENTS.key(), uuid);
            assertThat(new NotifyProperties("https://notify.example", "", chase, REPLY_TO, null)
                           .isProvisioned(EmailTemplate.CHASE_MISSING_DOCUMENTS)).isTrue();
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

        @Test
        @DisplayName("documents_required is required and capped by this package's own constant, not by a referral's")
        void documentsRequiredIsBoundedByEmailContent() {
            // The same 4,000 a direction has, and deliberately not read from ReferralSchemas: the equality is two
            // independent decisions agreeing, and a dependency would make a change to what a judge may record
            // silently change what a caseworker may write to an applicant.
            assertThat(EmailSlot.DOCUMENTS_REQUIRED.maxLength())
                .isEqualTo(EmailSlot.MAX_EMAIL_PROSE_LENGTH)
                .isEqualTo(4000);
            assertThat(EmailSlot.DOCUMENTS_REQUIRED.required()).isTrue();
        }

        @ParameterizedTest
        @EnumSource(EmailSlot.class)
        @DisplayName("a required slot's sentence is one a caseworker can read, and an optional one has none")
        void requirednessIsTheSentence(EmailSlot slot) {
            // Asserted against the sentence and not against required(), which IS "the sentence is not empty" --
            // comparing the two would be one expression compared with itself, green for every possible value
            // including a whitespace-only reason that produces the refusal "documents_required is required:    ".
            // So: an optional slot carries nothing, and a required one carries prose.
            if (slot.required()) {
                assertThat(slot.requiredBecause())
                    .as("%s is refused for being absent, so it owes the caseworker a reason", slot)
                    .isNotBlank()
                    .hasSizeGreaterThan(20);
            } else {
                assertThat(slot.requiredBecause())
                    .as("%s is never refused for being absent, so a reason would never be shown", slot)
                    .isEmpty();
            }
        }
    }

    @Nested
    @DisplayName("the configuration a template needs to exist at all")
    class Configuration {

        /**
         * {@code application.yaml}, read as text.
         *
         * <p>From {@code src/main/resources} rather than the classpath, for the reason
         * {@code EmailExclusionGroupTest} reads V20 that way: a stale {@code build/} copy must not be able to
         * make this pass against a file that is no longer what is committed.
         */
        private static final Path CONFIGURATION = Path.of("src/main/resources/application.yaml");

        @ParameterizedTest
        @EnumSource(EmailTemplate.class)
        @DisplayName("every template's key is declared under notify.templates, which nothing else would catch")
        void everyKeyIsDeclaredInTheConfiguration(EmailTemplate template) {
            // One of the two declaration sites CLAUDE.md names as failing silently. A member added to this enum
            // without a `notify.templates.<key>` line reads the placeholder for ever: its NOTIFY_TEMPLATE_* env
            // var is one nobody binds, isProvisioned stays false whatever the chart sets, and the email never
            // sends even once it has been authored in Notify. Nothing else in the build says so.
            assertThat(configuration())
                .as("notify.templates.%s must be declared, or NOTIFY_TEMPLATE_* for it is read by nobody",
                    template.key())
                .contains("    " + template.key() + ": ${NOTIFY_TEMPLATE_");
        }

        @Test
        @DisplayName("document-retention-weeks is declared, because a template carrying a document needs it")
        void retentionIsDeclaredForTheTemplatesThatCarryADocument() {
            assertThat(Arrays.stream(EmailTemplate.values()).anyMatch(t -> !t.documentKeys().isEmpty()))
                .as("if no template carries a document any more, this test is what has to be reconsidered")
                .isTrue();
            // The third silent-configuration failure, beside a missing template key and a missing env var: with
            // no retention isProvisioned answers false for both forms emails and the drain refuses the send.
            assertThat(configuration())
                .contains("document-retention-weeks: ${NOTIFY_DOCUMENT_RETENTION_WEEKS:");
        }

        private static String configuration() {
            try {
                return Files.readString(CONFIGURATION);
            } catch (IOException unreadable) {
                throw new UncheckedIOException("Could not read " + CONFIGURATION, unreadable);
            }
        }
    }
}
