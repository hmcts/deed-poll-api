package uk.gov.hmcts.reform.deedpoll.notify;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import uk.gov.hmcts.reform.deedpoll.entity.DeedPoll;
import uk.gov.hmcts.reform.deedpoll.entity.Referral;
import uk.gov.hmcts.reform.deedpoll.entity.enums.DeedPollType;
import uk.gov.hmcts.reform.deedpoll.entity.enums.PostCounter;

import java.lang.reflect.RecordComponent;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Resolving the <em>Request confirmation to proceed</em> email: what comes from the case, the one value that
 * comes from the referral, and what Notify is handed.
 *
 * <p>Container-free, for the reason {@link ApplicationRejectedPersonalisationTest} is: CI has no Docker, so a
 * rule stated only in a container-backed class is a rule CI does not check.
 *
 * <p>Three rules here are this template's own rather than inherited from ③. The child wording must fall on the
 * right side of <em>four</em> {@code DeedPollType} members and not two. The decision date must reach the
 * applicant as a date and not as a timestamp. And <strong>the judge's direction must not appear in a resolved
 * value at all</strong>, even though the referral is now in scope -- it belongs in the editable slot, once.
 */
class RequestConfirmationToProceedPersonalisationTest {

    private static final LocalDateTime DECIDED = LocalDateTime.of(2026, 9, 18, 14, 32, 7);

    private static final String DIRECTION = "The request not to publish is refused: no evidence of risk.";

    private static DeedPoll caseWith(PostCounter postCounter, DeedPollType type) {
        DeedPoll deedPoll = new DeedPoll();
        deedPoll.setId("case-1");
        deedPoll.setDisplayReference("DP2026-1234");
        deedPoll.setReferencenumber(1234);
        deedPoll.setOnlineApplicationReference("OA-99887766");
        deedPoll.setOldForename("Alexandra");
        deedPoll.setOldSurname("Parrott");
        deedPoll.setApplicantEmail("applicant@example.com");
        deedPoll.setPostCounter(postCounter);
        deedPoll.setType(type);
        return deedPoll;
    }

    private static Referral decidedOn(LocalDateTime decisionDate) {
        Referral referral = new Referral();
        referral.setDecisionDate(decisionDate);
        // Set on purpose, so every assertion below that the direction does not leak is asserting against a
        // referral that actually has one to leak.
        referral.setDirectionsText(DIRECTION);
        return referral;
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

    private static RequestConfirmationToProceedPersonalisation resolved() {
        return RequestConfirmationToProceedPersonalisation.resolve(
            caseWith(PostCounter.Online, DeedPollType.Adult), decidedOn(DECIDED), slots(DIRECTION, "Note."));
    }

    @Nested
    @DisplayName("resolved from the case")
    class ResolvedFromTheCase {

        @Test
        @DisplayName("every resolved value comes off the case, and none off the request")
        void everyResolvedValueComesOffTheCase() {
            RequestConfirmationToProceedPersonalisation personalisation = resolved();

            assertThat(personalisation.referenceNumber()).isEqualTo("DP2026-1234");
            assertThat(personalisation.onlineApplicationReference()).isEqualTo("OA-99887766");
            assertThat(personalisation.oldForename()).isEqualTo("Alexandra");
            assertThat(personalisation.oldSurname()).isEqualTo("Parrott");
        }

        @Test
        @DisplayName("the reference is the display reference, never the internal serial")
        void theReferenceIsTheDisplayReference() {
            DeedPoll deedPoll = caseWith(PostCounter.Online, DeedPollType.Adult);
            deedPoll.setReferencenumber(4321);

            assertThat(RequestConfirmationToProceedPersonalisation
                           .resolve(deedPoll, decidedOn(DECIDED), slots(DIRECTION, null)).referenceNumber())
                .isEqualTo("DP2026-1234");
        }

        @Test
        @DisplayName("a null column becomes an empty value, not a missing key")
        void nullColumnsBecomeEmptyValues() {
            DeedPoll blank = new DeedPoll();
            blank.setId("case-2");

            RequestConfirmationToProceedPersonalisation personalisation = RequestConfirmationToProceedPersonalisation
                .resolve(blank, new Referral(), slots(DIRECTION, null));

            assertThat(personalisation.referenceNumber()).isEmpty();
            assertThat(personalisation.onlineApplicationReference()).isEmpty();
            assertThat(personalisation.oldForename()).isEmpty();
            assertThat(personalisation.oldSurname()).isEmpty();
            assertThat(personalisation.toMap()).containsOnlyKeys(
                EmailTemplate.REQUEST_CONFIRMATION_TO_PROCEED.personalisationKeys().toArray(String[]::new));
        }
    }

    @Nested
    @DisplayName("the child wording")
    class TheChildWording {

        /** The two members that are a child's application; every other member is not. */
        private static final Set<DeedPollType> CHILDREN =
            EnumSet.of(DeedPollType.Minor, DeedPollType.MinorTransgender);

        @ParameterizedTest
        @EnumSource(DeedPollType.class)
        @DisplayName("is_minor is true for both minor types and false for every other, including the transgender "
            + "adult one")
        void isMinorCoversEveryType(DeedPollType type) {
            // The assertion that stops "type == Minor" coming back: MinorTransgender is a child's application
            // too, and AdultTransgender is not -- a check on the word "Minor" in the member name would get one
            // of those right and one wrong.
            boolean expected = CHILDREN.contains(type);

            assertThat(ResolvedValues.isMinor(type)).as("%s", type).isEqualTo(expected);
            assertThat(RequestConfirmationToProceedPersonalisation
                           .resolve(caseWith(PostCounter.Post, type), decidedOn(DECIDED), slots(DIRECTION, null))
                           .isMinor())
                .as("%s", type).isEqualTo(expected);
        }

        @Test
        @DisplayName("the two minor types are the true ones and are named rather than derived")
        void bothMinorTypesAreChildren() {
            assertThat(ResolvedValues.isMinor(DeedPollType.Minor)).isTrue();
            assertThat(ResolvedValues.isMinor(DeedPollType.MinorTransgender)).isTrue();
            assertThat(ResolvedValues.isMinor(DeedPollType.Adult)).isFalse();
            assertThat(ResolvedValues.isMinor(DeedPollType.AdultTransgender)).isFalse();
        }

        @Test
        @DisplayName("an unset or unknown type takes the adult wording rather than inventing a child")
        void anUnknownTypeIsNotAChild() {
            // Addressing an applicant about their own deed is impersonal if wrong; addressing them about their
            // child's refers them to a child who may not exist.
            assertThat(ResolvedValues.isMinor(null)).isFalse();
            assertThat(ResolvedValues.isMinor(DeedPollType.Unknown)).isFalse();
            assertThat(ResolvedValues.isMinor(DeedPollType.DeedOfRelinquishment)).isFalse();
            assertThat(RequestConfirmationToProceedPersonalisation
                           .resolve(caseWith(PostCounter.Post, null), decidedOn(DECIDED), slots(DIRECTION, null))
                           .isMinor()).isFalse();
        }
    }

    @Nested
    @DisplayName("the refund branch, shared with the rejection email")
    class RefundBranch {

        @ParameterizedTest
        @EnumSource(PostCounter.class)
        @DisplayName("only Online gets the automatic-refund text, on exactly the rule template ③ uses")
        void onlyOnlineIsOnline(PostCounter postCounter) {
            // Shared rather than copied, so the two emails cannot come to disagree about who gets a refund
            // without being asked for their bank details.
            boolean expected = postCounter == PostCounter.Online;

            assertThat(RequestConfirmationToProceedPersonalisation
                           .resolve(caseWith(postCounter, DeedPollType.Adult), decidedOn(DECIDED),
                                    slots(DIRECTION, null)).appliedOnline())
                .as("%s", postCounter).isEqualTo(expected);
            assertThat(ApplicationRejectedPersonalisation
                           .resolve(caseWith(postCounter, DeedPollType.Adult), slots(DIRECTION, null))
                           .appliedOnline())
                .as("%s", postCounter).isEqualTo(expected);
        }

        @Test
        @DisplayName("an unset channel is treated as paper rather than guessed at")
        void anUnsetChannelIsPaper() {
            assertThat(RequestConfirmationToProceedPersonalisation
                           .resolve(caseWith(null, DeedPollType.Adult), decidedOn(DECIDED), slots(DIRECTION, null))
                           .appliedOnline()).isFalse();
        }
    }

    @Nested
    @DisplayName("the decision date")
    class TheDecisionDate {

        @Test
        @DisplayName("is the day the direction came back, formatted as an applicant reads a date")
        void isFormattedForAnApplicant() {
            // The template says "On XX/YY/ZZZZ we received instructions from the judge". Notify has no date
            // formatting, so a raw LocalDateTime would reach the applicant as 2026-09-18T14:32:07.
            assertThat(resolved().decisionDate()).isEqualTo("18 September 2026");
        }

        @Test
        @DisplayName("names the month in English whatever the JVM's default locale is")
        void isFormattedInTheServiceLocale() {
            Locale original = Locale.getDefault();
            try {
                Locale.setDefault(Locale.forLanguageTag("fr-FR"));

                assertThat(ResolvedValues.date(DECIDED)).isEqualTo("18 September 2026");
            } finally {
                Locale.setDefault(original);
            }
        }

        @Test
        @DisplayName("renders empty rather than absent when the referral records no decision date")
        void nullDateRendersEmpty() {
            RequestConfirmationToProceedPersonalisation personalisation = RequestConfirmationToProceedPersonalisation
                .resolve(caseWith(PostCounter.Online, DeedPollType.Minor), decidedOn(null),
                         slots(DIRECTION, null));

            assertThat(personalisation.decisionDate()).isEmpty();
            // Present-but-empty, not missing: Notify refuses a send whose personalisation omits a placeholder
            // the template uses, so an absent key would be a permanent failure on the drain.
            assertThat(personalisation.toMap())
                .containsEntry(RequestConfirmationToProceedPersonalisation.DECISION_DATE, "");
        }

        @Test
        @DisplayName("a single-digit day is not zero padded, as GOV.UK renders a date")
        void singleDigitDayIsNotPadded() {
            assertThat(ResolvedValues.date(LocalDateTime.of(2026, 1, 3, 9, 5))).isEqualTo("3 January 2026");
        }
    }

    @Nested
    @DisplayName("the judge's direction")
    class TheJudgesDirection {

        @Test
        @DisplayName("never becomes a resolved value, even though the referral is in scope")
        void theDirectionIsNeverResolved() {
            // The rule ApplicationRejectedPersonalisation protected by refusing the referral outright. This
            // record needs the referral for its decision date, so the rule narrows: directionsText is not read.
            Map<String, Object> personalisation = resolved().toMap();

            assertThat(RequestConfirmationToProceedPersonalisation.RESOLVED_KEYS)
                .noneMatch(key -> key.contains("direction"));
            assertThat(personalisation.keySet()).doesNotContain("directions_text", "directionsText", "direction");
            // The one place the direction is allowed: the editable slot, whose value the caseworker approved.
            assertThat(personalisation.entrySet())
                .filteredOn(entry -> DIRECTION.equals(entry.getValue()))
                .extracting(Map.Entry::getKey)
                .containsExactly(EmailSlot.JUDGES_REASON.placeholder());
        }

        @Test
        @DisplayName("an edited slot is what reaches the email, and the stored direction does not follow it in")
        void anEditedSlotIsWhatReachesTheEmail() {
            // The strongest form of the rule: if the record read directionsText anywhere, the judge's original
            // words would travel alongside the caseworker's shortened quotation.
            String edited = "The judge refused the request.";

            Map<String, Object> personalisation = RequestConfirmationToProceedPersonalisation
                .resolve(caseWith(PostCounter.Online, DeedPollType.Adult), decidedOn(DECIDED), slots(edited, null))
                .toMap();

            assertThat(personalisation).containsEntry(EmailSlot.JUDGES_REASON.placeholder(), edited);
            assertThat(personalisation.values()).doesNotContain(DIRECTION);
        }
    }

    @Nested
    @DisplayName("what Notify is handed")
    class TheMap {

        @Test
        @DisplayName("the keys are exactly the template's, in the template's order")
        void theKeysAreExactlyTheTemplates() {
            assertThat(resolved().toMap().keySet()).containsExactlyElementsOf(
                EmailTemplate.REQUEST_CONFIRMATION_TO_PROCEED.personalisationKeys());
        }

        @Test
        @DisplayName("one key per record component, so neither list can grow without the other")
        void oneKeyPerRecordComponent() {
            Map<String, Object> personalisation = resolved().toMap();

            var components = Arrays.stream(
                    RequestConfirmationToProceedPersonalisation.class.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();

            assertThat(components)
                .isNotEmpty()
                .allSatisfy(component -> assertThat(snakeCase(component)).isIn(personalisation.keySet()));
            assertThat(personalisation).hasSameSizeAs(components);
        }

        @Test
        @DisplayName("the resolved keys are the case's and the referral's date, and a client may set none of them")
        void theResolvedKeysAreNotClientInput() {
            EmailTemplate template = EmailTemplate.REQUEST_CONFIRMATION_TO_PROCEED;

            assertThat(template.resolvedKeys()).containsExactlyElementsOf(
                RequestConfirmationToProceedPersonalisation.RESOLVED_KEYS);
            assertThat(template.resolves(RequestConfirmationToProceedPersonalisation.IS_MINOR)).isTrue();
            assertThat(template.resolves(RequestConfirmationToProceedPersonalisation.DECISION_DATE)).isTrue();
            assertThat(template.resolves(RequestConfirmationToProceedPersonalisation.APPLIED_ONLINE)).isTrue();
        }

        @Test
        @DisplayName("both conditionals are real booleans, so a Notify branch does not turn on a choice of words")
        void theConditionalsAreBooleans() {
            Map<String, Object> personalisation = RequestConfirmationToProceedPersonalisation
                .resolve(caseWith(PostCounter.Online, DeedPollType.MinorTransgender), decidedOn(DECIDED),
                         slots(DIRECTION, "Note."))
                .toMap();

            assertThat(personalisation.get(RequestConfirmationToProceedPersonalisation.APPLIED_ONLINE))
                .isInstanceOf(Boolean.class).isEqualTo(true);
            assertThat(personalisation.get(RequestConfirmationToProceedPersonalisation.IS_MINOR))
                .isInstanceOf(Boolean.class).isEqualTo(true);
        }

        @Test
        @DisplayName("the values are the resolved text, the formatted date and the caseworker's slots")
        void theValuesAreWhatWasResolved() {
            assertThat(resolved().toMap())
                .containsEntry("reference_number", "DP2026-1234")
                .containsEntry("online_application_reference", "OA-99887766")
                .containsEntry("old_forename", "Alexandra")
                .containsEntry("old_surname", "Parrott")
                .containsEntry("decision_date", "18 September 2026")
                .containsEntry("judges_reason", DIRECTION)
                .containsEntry("additional_notes", "Note.");
        }

        @Test
        @DisplayName("an absent notes slot renders as nothing rather than as a missing key")
        void absentNotesRenderAsNothing() {
            RequestConfirmationToProceedPersonalisation personalisation = RequestConfirmationToProceedPersonalisation
                .resolve(caseWith(PostCounter.Online, DeedPollType.Adult), decidedOn(DECIDED),
                         slots(DIRECTION, null));

            assertThat(personalisation.additionalNotes()).isEmpty();
            assertThat(personalisation.toMap())
                .containsEntry(EmailSlot.ADDITIONAL_NOTES.placeholder(), "");
        }

        @Test
        @DisplayName("the map is the row's own, so storing it cannot be changed by a later resolve")
        void theMapIsFresh() {
            RequestConfirmationToProceedPersonalisation personalisation = resolved();

            personalisation.toMap().put("old_forename", "tampered");

            assertThat(personalisation.toMap()).containsEntry("old_forename", "Alexandra");
        }

        /**
         * A record component's name as its Notify placeholder: {@code decisionDate} to {@code decision_date}.
         */
        private static String snakeCase(String component) {
            return component.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(Locale.ROOT);
        }
    }
}
