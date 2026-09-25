package uk.gov.hmcts.reform.deedpoll.notify;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import uk.gov.hmcts.reform.deedpoll.entity.DeedPoll;
import uk.gov.hmcts.reform.deedpoll.entity.enums.DeedPollState;
import uk.gov.hmcts.reform.deedpoll.entity.enums.DeedPollType;
import uk.gov.hmcts.reform.deedpoll.entity.enums.PostCounter;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Resolving the <em>Chase missing documents</em> email: four values off the case and one the caseworker wrote.
 *
 * <p>Container-free, and the rule it pins is the same one ③'s test pins: the resolved values come only from the
 * case, and the record and its map cannot grow independently -- so a component added without a key, or a key
 * added without a component, fails here rather than silently never reaching an applicant.
 *
 * <p>The other rule, and the one this template is the first to need: <strong>nothing here reads a referral,
 * a decision, a payment channel or a deed type.</strong> Those are all reachable off the case entity, so the way
 * this email would acquire a fifth resolved value is by somebody adding one that Notify has nowhere to put --
 * which is a send Notify refuses rather than a paragraph nobody reads.
 */
class MissingDocumentsPersonalisationTest {

    /** What a caseworker types: applicant-facing prose, multi-line, not tidied by this service. */
    private static final String REQUIRED = "We still need:\n"
        + "* your birth certificate\n"
        + "* proof of your current address.";

    private static DeedPoll awaitingDocuments() {
        DeedPoll deedPoll = new DeedPoll();
        deedPoll.setId("case-1");
        deedPoll.setState(DeedPollState.AwaitingFurtherDocs);
        deedPoll.setDisplayReference("DP2026-1234");
        deedPoll.setReferencenumber(1234);
        deedPoll.setOnlineApplicationReference("OA-99887766");
        deedPoll.setOldForename("Alexandra");
        deedPoll.setOldSurname("Parrott");
        deedPoll.setApplicantEmail("applicant@example.com");
        // Both set, and neither resolved: this email starts no refund and addresses nobody's child.
        deedPoll.setPostCounter(PostCounter.Online);
        deedPoll.setType(DeedPollType.Minor);
        return deedPoll;
    }

    private static Map<EmailSlot, String> slots(String documentsRequired) {
        Map<EmailSlot, String> filled = new EnumMap<>(EmailSlot.class);
        if (documentsRequired != null) {
            filled.put(EmailSlot.DOCUMENTS_REQUIRED, documentsRequired);
        }
        return filled;
    }

    @Nested
    @DisplayName("resolved from the case")
    class ResolvedFromTheCase {

        @Test
        @DisplayName("the four common values come off the case, and the fifth off the caseworker")
        void theFourCommonValuesComeOffTheCase() {
            MissingDocumentsPersonalisation resolved =
                MissingDocumentsPersonalisation.resolve(awaitingDocuments(), slots(REQUIRED));

            assertThat(resolved.referenceNumber()).isEqualTo("DP2026-1234");
            assertThat(resolved.onlineApplicationReference()).isEqualTo("OA-99887766");
            assertThat(resolved.oldForename()).isEqualTo("Alexandra");
            assertThat(resolved.oldSurname()).isEqualTo("Parrott");
            // Unaltered: the caseworker's newlines and list markers are what Notify renders, and this service is
            // not a second place that decides what an applicant is asked for.
            assertThat(resolved.documentsRequired()).isEqualTo(REQUIRED);
        }

        @Test
        @DisplayName("the reference is the display reference, never the internal serial")
        void theReferenceIsTheDisplayReference() {
            // referencenumber is a component of the rendered reference and is not what the applicant was ever
            // shown, so an applicant quoting "1234" back at the office would mean nothing to anybody.
            DeedPoll deedPoll = awaitingDocuments();
            deedPoll.setReferencenumber(4321);

            assertThat(MissingDocumentsPersonalisation.resolve(deedPoll, slots(REQUIRED)).referenceNumber())
                .isEqualTo("DP2026-1234");
        }

        @Test
        @DisplayName("a null column becomes an empty value, not a missing key")
        void nullColumnsBecomeEmptyValues() {
            // Notify refuses a send whose personalisation omits a placeholder the template uses, so an absent key
            // would turn a blank column into a permanent failure on the drain -- where nobody is waiting to read
            // it. An empty string renders as nothing, which is what a blank column means.
            DeedPoll blank = new DeedPoll();
            blank.setId("case-2");

            MissingDocumentsPersonalisation resolved =
                MissingDocumentsPersonalisation.resolve(blank, slots(REQUIRED));

            assertThat(resolved.referenceNumber()).isEmpty();
            assertThat(resolved.onlineApplicationReference()).isEmpty();
            assertThat(resolved.oldForename()).isEmpty();
            assertThat(resolved.oldSurname()).isEmpty();
            assertThat(resolved.toMap()).containsOnlyKeys(
                EmailTemplate.CHASE_MISSING_DOCUMENTS.personalisationKeys().toArray(String[]::new));
        }

        @Test
        @DisplayName("an absent slot resolves to empty rather than null, though parse cannot produce one")
        void anAbsentSlotIsEmptyRatherThanNull() {
            // Unreachable through the service -- the slot is required and EmailSlots.parse refuses a blank one --
            // but a null in the map would reach Notify as a missing value and fail the send permanently, so the
            // resolver does not depend on the validator having run.
            assertThat(MissingDocumentsPersonalisation.resolve(awaitingDocuments(), slots(null)))
                .satisfies(resolved -> {
                    assertThat(resolved.documentsRequired()).isEmpty();
                    assertThat(resolved.toMap()).containsEntry("documents_required", "");
                });
        }
    }

    @Nested
    @DisplayName("what Notify is handed")
    class TheMap {

        @Test
        @DisplayName("the keys are exactly the template's five, in the template's order")
        void theKeysAreExactlyTheTemplates() {
            Map<String, Object> personalisation =
                MissingDocumentsPersonalisation.resolve(awaitingDocuments(), slots(REQUIRED)).toMap();

            assertThat(personalisation.keySet()).containsExactlyElementsOf(
                EmailTemplate.CHASE_MISSING_DOCUMENTS.personalisationKeys());
            // The contract read back out of the provisioned template on 2026-09-24, character for character.
            assertThat(personalisation.keySet()).containsExactly(
                "reference_number", "online_application_reference", "old_forename", "old_surname",
                "documents_required");
        }

        @Test
        @DisplayName("one key per record component and no key without one, so neither list can grow alone")
        void oneKeyPerRecordComponent() {
            // ③ has exactly one key with no component -- the derived `applied_on_paper` negation -- and this
            // template deliberately has none: there is no conditional in its wording, so every key is a value
            // somebody resolved. Asserted as an equal size rather than "+ 1" so that adding a derived key here
            // has to be argued for in a diff.
            Map<String, Object> personalisation =
                MissingDocumentsPersonalisation.resolve(awaitingDocuments(), slots(REQUIRED)).toMap();

            var components = Arrays.stream(MissingDocumentsPersonalisation.class.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();

            assertThat(components)
                .isNotEmpty()
                .allSatisfy(component ->
                                assertThat(snakeCase(component)).isIn(personalisation.keySet()));
            assertThat(personalisation).hasSameSizeAs(components);
        }

        @Test
        @DisplayName("no refund flag, no decision date and no minor flag, because the wording has none of them")
        void carriesNoneOfTheOtherTemplatesKeys() {
            // The case fixture has a payment channel and is a minor's application, so each of these is a value
            // that was available and deliberately not sent. A key Notify's template does not use is not harmless:
            // it is a claim about the wording that the next person reading the stored row would believe.
            Map<String, Object> personalisation =
                MissingDocumentsPersonalisation.resolve(awaitingDocuments(), slots(REQUIRED)).toMap();

            assertThat(personalisation).doesNotContainKeys(
                "applied_online", "applied_on_paper", "is_minor", "decision_date", "judges_reason",
                "additional_notes");
        }

        @Test
        @DisplayName("every value is text, so nothing here depends on a Notify conditional")
        void everyValueIsText() {
            assertThat(MissingDocumentsPersonalisation.resolve(awaitingDocuments(), slots(REQUIRED)).toMap())
                .allSatisfy((key, value) -> assertThat(value).as("%s", key).isInstanceOf(String.class));
        }

        @Test
        @DisplayName("the map is the row's own, so storing it cannot be changed by a later resolve")
        void theMapIsFresh() {
            MissingDocumentsPersonalisation resolved =
                MissingDocumentsPersonalisation.resolve(awaitingDocuments(), slots(REQUIRED));

            resolved.toMap().put("old_forename", "tampered");

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
