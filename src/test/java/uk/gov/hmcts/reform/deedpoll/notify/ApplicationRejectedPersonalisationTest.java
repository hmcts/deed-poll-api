package uk.gov.hmcts.reform.deedpoll.notify;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import uk.gov.hmcts.reform.deedpoll.entity.DeedPoll;
import uk.gov.hmcts.reform.deedpoll.entity.enums.PostCounter;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Resolving the <em>Application Rejected</em> email: what comes from the case, what comes from the caseworker,
 * and what Notify is handed.
 *
 * <p>Container-free. The rule this exists to pin is that the resolved values come only from the case -- so a
 * component added to the record without a key in {@link ApplicationRejectedPersonalisation#toMap()}, or a key
 * without a component, fails here rather than silently never reaching an applicant.
 */
class ApplicationRejectedPersonalisationTest {

    private static DeedPoll caseWith(PostCounter postCounter) {
        DeedPoll deedPoll = new DeedPoll();
        deedPoll.setId("case-1");
        deedPoll.setDisplayReference("DP2026-1234");
        deedPoll.setReferencenumber(1234);
        deedPoll.setOnlineApplicationReference("OA-99887766");
        deedPoll.setOldForename("Alexandra");
        deedPoll.setOldSurname("Parrott");
        deedPoll.setApplicantEmail("applicant@example.com");
        deedPoll.setPostCounter(postCounter);
        return deedPoll;
    }

    private static Map<EmailSlot, String> slots(String judgesReason, String additionalNotes) {
        Map<EmailSlot, String> filled = new EnumMap<>(EmailSlot.class);
        if (judgesReason != null) {
            filled.put(EmailSlot.JUDGES_REASON, judgesReason);
        }
        if (additionalNotes != null) {
            filled.put(EmailSlot.ADDITIONAL_NOTES, additionalNotes);
        }
        return filled;
    }

    @Nested
    @DisplayName("resolved from the case")
    class ResolvedFromTheCase {

        @Test
        @DisplayName("every resolved value comes off the case, and none off the request")
        void everyResolvedValueComesOffTheCase() {
            ApplicationRejectedPersonalisation resolved = ApplicationRejectedPersonalisation
                .resolve(caseWith(PostCounter.Online), slots("Refused.", null));

            assertThat(resolved.referenceNumber()).isEqualTo("DP2026-1234");
            assertThat(resolved.onlineApplicationReference()).isEqualTo("OA-99887766");
            assertThat(resolved.oldForename()).isEqualTo("Alexandra");
            assertThat(resolved.oldSurname()).isEqualTo("Parrott");
        }

        @Test
        @DisplayName("the reference is the display reference, never the internal serial")
        void theReferenceIsTheDisplayReference() {
            // referencenumber is a component of the rendered reference and is not what the applicant was ever
            // shown, so an applicant quoting "1234" back at the office would mean nothing to anyone.
            DeedPoll deedPoll = caseWith(PostCounter.Online);
            deedPoll.setReferencenumber(4321);

            assertThat(ApplicationRejectedPersonalisation.resolve(deedPoll, slots("Refused.", null))
                           .referenceNumber()).isEqualTo("DP2026-1234");
        }

        @Test
        @DisplayName("a null column becomes an empty value, not a missing key")
        void nullColumnsBecomeEmptyValues() {
            // Notify refuses a send whose personalisation is missing a placeholder the template uses, so an
            // absent key would turn a blank column into a permanent failure on the drain.
            DeedPoll blank = new DeedPoll();
            blank.setId("case-2");

            ApplicationRejectedPersonalisation resolved =
                ApplicationRejectedPersonalisation.resolve(blank, slots("Refused.", null));

            assertThat(resolved.referenceNumber()).isEmpty();
            assertThat(resolved.onlineApplicationReference()).isEmpty();
            assertThat(resolved.oldForename()).isEmpty();
            assertThat(resolved.oldSurname()).isEmpty();
            assertThat(resolved.toMap()).containsOnlyKeys(
                EmailTemplate.APPLICATION_REJECTED.personalisationKeys().toArray(String[]::new));
        }
    }

    @Nested
    @DisplayName("the refund branch")
    class RefundBranch {

        @ParameterizedTest
        @EnumSource(PostCounter.class)
        @DisplayName("only Online is an online application, so only Online gets the automatic-refund text")
        void onlyOnlineIsOnline(PostCounter postCounter) {
            boolean expected = postCounter == PostCounter.Online;

            assertThat(ApplicationRejectedPersonalisation.appliedOnline(postCounter))
                .as("%s", postCounter).isEqualTo(expected);
            assertThat(ApplicationRejectedPersonalisation
                           .resolve(caseWith(postCounter), slots("Refused.", null)).appliedOnline())
                .as("%s", postCounter).isEqualTo(expected);
        }

        @Test
        @DisplayName("Counter, Post and Unknown all get the paper text, which is the recoverable branch")
        void everythingElseGetsThePaperText() {
            // Asymmetric on purpose: the paper paragraph asks the applicant how they paid, which recovers from
            // a wrong guess. Promising a refund to a card nobody paid with does not.
            assertThat(ApplicationRejectedPersonalisation.appliedOnline(PostCounter.Counter)).isFalse();
            assertThat(ApplicationRejectedPersonalisation.appliedOnline(PostCounter.Post)).isFalse();
            assertThat(ApplicationRejectedPersonalisation.appliedOnline(PostCounter.Unknown)).isFalse();
        }

        @Test
        @DisplayName("an unset channel is treated as paper rather than guessed at")
        void anUnsetChannelIsPaper() {
            assertThat(ApplicationRejectedPersonalisation.appliedOnline(null)).isFalse();
            assertThat(ApplicationRejectedPersonalisation.resolve(caseWith(null), slots("Refused.", null))
                           .appliedOnline()).isFalse();
        }
    }

    @Nested
    @DisplayName("the caseworker's slots")
    class Slots {

        @Test
        @DisplayName("the judge's reason and the notes reach the email exactly as validated")
        void theSlotsReachTheEmail() {
            String direction = "The declarant does not meet the requirements of section 4.\nSee the note.";

            ApplicationRejectedPersonalisation resolved = ApplicationRejectedPersonalisation
                .resolve(caseWith(PostCounter.Post), slots(direction, "Call us on the number below."));

            assertThat(resolved.judgesReason()).isEqualTo(direction);
            assertThat(resolved.additionalNotes()).isEqualTo("Call us on the number below.");
        }

        @Test
        @DisplayName("an absent notes slot renders as nothing rather than as a missing key")
        void absentNotesRenderAsNothing() {
            assertThat(ApplicationRejectedPersonalisation
                           .resolve(caseWith(PostCounter.Online), slots("Refused.", null)).additionalNotes())
                .isEmpty();
        }
    }

    @Nested
    @DisplayName("what Notify is handed")
    class TheMap {

        @Test
        @DisplayName("the keys are exactly the template's, in the template's order")
        void theKeysAreExactlyTheTemplates() {
            Map<String, Object> personalisation = ApplicationRejectedPersonalisation
                .resolve(caseWith(PostCounter.Online), slots("Refused.", "Note.")).toMap();

            assertThat(personalisation.keySet())
                .containsExactlyElementsOf(EmailTemplate.APPLICATION_REJECTED.personalisationKeys());
        }

        @Test
        @DisplayName("one key per record component, so neither list can grow without the other")
        void oneKeyPerRecordComponent() {
            // The assertion that makes the record enumerable: add a component and this fails, add a key and it
            // fails the other way, so nothing reaches an applicant -- or fails to -- unnoticed.
            Map<String, Object> personalisation = ApplicationRejectedPersonalisation
                .resolve(caseWith(PostCounter.Online), slots("Refused.", "Note.")).toMap();

            var components = Arrays.stream(
                    ApplicationRejectedPersonalisation.class.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();

            assertThat(components)
                .isNotEmpty()
                .allSatisfy(component ->
                                assertThat(snakeCase(component)).isIn(personalisation.keySet()));
        }

        @Test
        @DisplayName("the values are the resolved text and a real boolean for the branch")
        void theValuesAreTextAndABoolean() {
            Map<String, Object> personalisation = ApplicationRejectedPersonalisation
                .resolve(caseWith(PostCounter.Online), slots("Refused.", "Note.")).toMap();

            assertThat(personalisation).containsEntry("reference_number", "DP2026-1234")
                .containsEntry("online_application_reference", "OA-99887766")
                .containsEntry("old_forename", "Alexandra")
                .containsEntry("old_surname", "Parrott")
                .containsEntry("judges_reason", "Refused.")
                .containsEntry("additional_notes", "Note.");
            // A JSON boolean, not "yes": Notify's conditional reads the boolean directly, and a string would
            // make the refund branch depend on which words somebody chose.
            assertThat(personalisation.get("applied_online")).isInstanceOf(Boolean.class).isEqualTo(true);
        }

        @Test
        @DisplayName("the map is the row's own, so storing it cannot be changed by a later resolve")
        void theMapIsFresh() {
            ApplicationRejectedPersonalisation resolved = ApplicationRejectedPersonalisation
                .resolve(caseWith(PostCounter.Online), slots("Refused.", "Note."));

            Map<String, Object> first = resolved.toMap();
            first.put("old_forename", "tampered");

            assertThat(resolved.toMap()).containsEntry("old_forename", "Alexandra");
        }

        /**
         * A record component's name as its Notify placeholder: {@code oldForename} to {@code old_forename}.
         */
        private static String snakeCase(String component) {
            return component.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(Locale.ROOT);
        }
    }
}
