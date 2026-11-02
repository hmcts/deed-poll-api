package uk.gov.hmcts.reform.deedpoll.domain.referrals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import uk.gov.hmcts.reform.deedpoll.domain.FieldError;
import uk.gov.hmcts.reform.deedpoll.domain.InvalidInputError;
import uk.gov.hmcts.reform.deedpoll.entity.enums.ConfirmationOutcome;
import uk.gov.hmcts.reform.deedpoll.entity.enums.MasterDecision;
import uk.gov.hmcts.reform.deedpoll.entity.enums.Urgency;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Validating a submitted referral, a submitted direction on one, and the applicant's answer to the confirmation
 * email as a caseworker records it.
 *
 * <p>No reference test to port -- referrals are new behaviour rather than something the source store had.
 *
 * <p>Plain unit tests with no container, deliberately: the Jenkins agent has no Docker and excludes every
 * container-backed class, so the rules asserted here are the ones CI actually runs. They are also the rules
 * most worth pinning -- two of them are conditional on another field of the same payload, which is the shape
 * a later refactor is most likely to get subtly wrong.
 */
class ReferralSchemasTest {

    private static Map<String, Object> payload(Object... keysAndValues) {
        Map<String, Object> raw = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            raw.put(String.valueOf(keysAndValues[i]), keysAndValues[i + 1]);
        }
        return raw;
    }

    /** The failures a rejection carries, each attributed to its field. */
    private static List<FieldError> errorsFrom(Runnable parse) {
        try {
            parse.run();
        } catch (InvalidInputError rejected) {
            return rejected.errors();
        }
        throw new AssertionError("expected the payload to be rejected");
    }

    private static CreateReferralInput parse(Map<String, Object> raw) {
        return ReferralSchemas.parseCreateReferral(raw);
    }

    private static String repeated(int length) {
        return "x".repeat(length);
    }

    @Nested
    @DisplayName("reasons")
    class Reasons {

        @Test
        @DisplayName("accepts a whole referral, as the enum members rather than the wire strings")
        void acceptsAWholeReferral() {
            CreateReferralInput created = parse(payload(
                "reasons", List.of("NonPublication", "DomesticAbuse"),
                "notes", "  The applicant is nine.  ",
                "urgency", "Urgent",
                "urgencyReason", "Hearing on Friday"));

            // The enum, not the strings: unlike a saved query's conditions, only the names are stored and
            // every later reader matches against the enum.
            assertThat(created.reasons())
                .containsExactly(ReferralReason.NonPublication, ReferralReason.DomesticAbuse);
            // Trimmed, and the trimmed value is what gets stored -- the rule every payload here applies.
            assertThat(created.notes()).contains("The applicant is nine.");
            assertThat(created.urgency()).isEqualTo(Urgency.Urgent);
            assertThat(created.urgencyReason()).contains("Hearing on Friday");
        }

        @Test
        @DisplayName("requires at least one reason, and says so for absent, null and empty alike")
        void requiresAtLeastOneReason() {
            String expected = "reasons must name at least one reason for the referral";

            assertThat(errorsFrom(() -> parse(payload())))
                .containsExactly(new FieldError("reasons", expected));
            assertThat(errorsFrom(() -> parse(payload("reasons", null))))
                .containsExactly(new FieldError("reasons", expected));
            // An empty list is the case a multi-select with nothing ticked actually posts, and it is the one
            // a check for `containsKey` alone would let through.
            assertThat(errorsFrom(() -> parse(payload("reasons", List.of()))))
                .containsExactly(new FieldError("reasons", expected));
        }

        @Test
        @DisplayName("refuses an unknown reason, naming the field and quoting what was sent")
        void refusesAnUnknownReason() {
            List<FieldError> errors = errorsFrom(() -> parse(payload("reasons", List.of("Whatever"))));

            assertThat(errors).hasSize(1);
            assertThat(errors.getFirst().field()).isEqualTo("reasons");
            assertThat(errors.getFirst().message())
                .contains("names no referral reason", "'Whatever'")
                // The acceptable values are listed, so a client does not have to hold the enum to recover.
                .contains("Minor", "Other");
        }

        @Test
        @DisplayName("refuses a reason whose case is wrong, because the name is the stored form")
        void refusesAReasonWhoseCaseIsWrong() {
            // Not a convenience worth having: the names are the wire form and the stored form at once, so
            // accepting "other" here would write a value nothing later matches against the enum.
            assertThat(errorsFrom(() -> parse(payload("reasons", List.of("other")))))
                .singleElement()
                .extracting(FieldError::field)
                .isEqualTo("reasons");
        }

        @Test
        @DisplayName("accepts every member of the enum, so none is unreachable")
        void acceptsEveryMember() {
            // Two passes, because one referral may not carry both halves of a contradictory pair: the first
            // half of each pair in one, the second in the other, and every other member in both.
            List<ReferralReason> secondHalves = List.of(
                ReferralReason.MinorNoParentalConsent,
                ReferralReason.MinorNoStepparentConsent,
                ReferralReason.MinorAged16Or17NotConsented);
            List<ReferralReason> firstHalves = List.of(
                ReferralReason.MinorParentalConsent,
                ReferralReason.MinorStepparentConsent,
                ReferralReason.MinorAged16Or17Consented);

            for (List<ReferralReason> left : List.of(secondHalves, firstHalves)) {
                List<ReferralReason> sent = Arrays.stream(ReferralReason.values())
                    .filter(reason -> !left.contains(reason))
                    .toList();

                CreateReferralInput created = parse(payload(
                    "reasons", sent.stream().map(Enum::name).toList(),
                    "notes", "Other is in there"));

                assertThat(created.reasons()).containsExactlyElementsOf(sent);
            }
        }

        @Test
        @DisplayName("de-duplicates rather than refusing, keeping the order the caller sent")
        void deDuplicates() {
            CreateReferralInput created =
                parse(payload("reasons", List.of("DomesticAbuse", "NonPublication", "DomesticAbuse")));

            // A multi-select posting the same box twice has one obvious correct reading, and the stored array
            // is a set in everything but type.
            assertThat(created.reasons())
                .containsExactly(ReferralReason.DomesticAbuse, ReferralReason.NonPublication);
        }

        @Test
        @DisplayName("refuses a bare string rather than reading it as a single reason")
        void refusesABareString() {
            // One shape, whether there is one reason or six: a client sending a string would otherwise store
            // something every reader has to handle separately.
            assertThat(errorsFrom(() -> parse(payload("reasons", "Minor"))))
                .containsExactly(new FieldError("reasons", "reasons must be a list of text"));
        }

        @Test
        @DisplayName("refuses a list holding something that is not text")
        void refusesANonTextElement() {
            assertThat(errorsFrom(() -> parse(payload("reasons", List.of("Minor", 7)))))
                .containsExactly(new FieldError("reasons", "reasons must be a list of text"));
        }
    }

    @Nested
    @DisplayName("a minor's details, which refine Minor")
    class MinorDetails {

        @Test
        @DisplayName("accepts the details beside Minor, in the order they were ticked")
        void acceptsDetailsBesideMinor() {
            CreateReferralInput created = parse(payload("reasons", List.of(
                "Minor", "MinorTransgender", "MinorParentalConsent", "MinorAged16Or17Consented")));

            assertThat(created.reasons()).containsExactly(
                ReferralReason.Minor,
                ReferralReason.MinorTransgender,
                ReferralReason.MinorParentalConsent,
                ReferralReason.MinorAged16Or17Consented);
        }

        @Test
        @DisplayName("refuses a detail without Minor, because it describes a minor's application")
        void refusesADetailWithoutMinor() {
            assertThat(errorsFrom(() -> parse(payload("reasons", List.of("DomesticAbuse", "MinorCourtOrder")))))
                .containsExactly(new FieldError("reasons",
                    "reasons names a minor's detail (MinorCourtOrder) without Minor"));
        }

        @ParameterizedTest(name = "{0} and {1}")
        @CsvSource({
            "MinorParentalConsent, MinorNoParentalConsent",
            "MinorStepparentConsent, MinorNoStepparentConsent",
            "MinorAged16Or17Consented, MinorAged16Or17NotConsented"
        })
        @DisplayName("refuses both halves of a contradictory pair")
        void refusesAContradictoryPair(String first, String second) {
            assertThat(errorsFrom(() -> parse(payload("reasons", List.of("Minor", second, first)))))
                .containsExactly(new FieldError("reasons", "reasons cannot name both " + first + " and " + second));
        }

        @Test
        @DisplayName("refuses Minor alone, because the Masters asked always to be told what kind of application it is")
        void refusesMinorAlone() {
            assertThat(errorsFrom(() -> parse(payload("reasons", List.of("Minor")))))
                .singleElement()
                .satisfies(error -> {
                    assertThat(error.field()).isEqualTo("reasons");
                    assertThat(error.message()).contains("names Minor without saying what applies",
                        "MinorTransgender", "MinorAged16Or17NotConsented");
                });
        }

        @Test
        @DisplayName("marks exactly the eight Minor details, so a new reason is not one by accident")
        void marksTheEightDetails() {
            assertThat(Arrays.stream(ReferralReason.values()).filter(ReferralReason::isMinorDetail).map(Enum::name))
                .containsExactly(
                    "MinorTransgender",
                    "MinorParentalConsent",
                    "MinorNoParentalConsent",
                    "MinorStepparentConsent",
                    "MinorNoStepparentConsent",
                    "MinorCourtOrder",
                    "MinorAged16Or17Consented",
                    "MinorAged16Or17NotConsented");
        }
    }

    @Nested
    @DisplayName("notes, which Other requires")
    class Notes {

        @Test
        @DisplayName("requires notes when Other is among the reasons")
        void requiresNotesWhenOther() {
            List<FieldError> errors = errorsFrom(() -> parse(payload("reasons", List.of("Other"))));

            assertThat(errors).hasSize(1);
            assertThat(errors.getFirst().field()).isEqualTo("notes");
            assertThat(errors.getFirst().message()).contains("required when Other");
        }

        @Test
        @DisplayName("requires notes when Other arrives alongside a reason that does not need them")
        void requiresNotesWhenOtherIsOneOfSeveral() {
            // The rule is about the set containing Other, not about Other being the only selection -- which
            // is the reading an implementation checking `reasons.getFirst()` would get wrong.
            assertThat(errorsFrom(() -> parse(payload("reasons", List.of("DomesticAbuse", "Other")))))
                .singleElement()
                .extracting(FieldError::field)
                .isEqualTo("notes");
        }

        @Test
        @DisplayName("does not accept whitespace as the explanation Other needs")
        void doesNotAcceptWhitespaceAsAnExplanation() {
            // Blank becomes absent, which is what makes the conditional rule real: a stored explanation of
            // nothing reads as an explanation until somebody opens it.
            assertThat(errorsFrom(() -> parse(payload("reasons", List.of("Other"), "notes", "   "))))
                .singleElement()
                .extracting(FieldError::field)
                .isEqualTo("notes");
        }

        @Test
        @DisplayName("leaves notes optional when Other is not among the reasons")
        void leavesNotesOptionalOtherwise() {
            CreateReferralInput created = parse(payload("reasons", List.of("NonPublication")));

            assertThat(created.notes()).isEmpty();
        }

        @Test
        @DisplayName("accepts notes on a referral that does not need them")
        void acceptsNotesWithoutOther() {
            assertThat(parse(payload("reasons", List.of("DomesticAbuse"), "notes", "Born 2019")).notes())
                .contains("Born 2019");
        }

        @Test
        @DisplayName("caps the notes, because the case note the referral writes is bounded")
        void capsTheNotes() {
            assertThat(errorsFrom(() ->
                parse(payload("reasons", List.of("DomesticAbuse"),
                    "notes", repeated(ReferralSchemas.MAX_NOTES_LENGTH + 1)))))
                .containsExactly(new FieldError(
                    "notes", "notes must be " + ReferralSchemas.MAX_NOTES_LENGTH + " characters or fewer"));

            assertThat(parse(payload("reasons", List.of("DomesticAbuse"),
                "notes", repeated(ReferralSchemas.MAX_NOTES_LENGTH))).notes())
                .isPresent();
        }

        @Test
        @DisplayName("refuses notes that are not text")
        void refusesNotesThatAreNotText() {
            assertThat(errorsFrom(() -> parse(payload("reasons", List.of("DomesticAbuse"), "notes", 7))))
                .containsExactly(new FieldError("notes", "notes must be text"));
        }
    }

    @Nested
    @DisplayName("urgency, and the reason Urgent requires")
    class UrgencyAndItsReason {

        @Test
        @DisplayName("defaults to Normal when the caller says nothing")
        void defaultsToNormal() {
            // Not demanded: most referrals are not urgent, and a required field whose usual answer is the
            // default is a field every caller fills in without reading.
            assertThat(parse(payload("reasons", List.of("DomesticAbuse"))).urgency()).isEqualTo(Urgency.Normal);
            assertThat(parse(payload("reasons", List.of("DomesticAbuse"), "urgency", "  ")).urgency())
                .isEqualTo(Urgency.Normal);
        }

        @Test
        @DisplayName("requires a reason when the referral is Urgent")
        void requiresAReasonWhenUrgent() {
            List<FieldError> errors =
                errorsFrom(() -> parse(payload("reasons", List.of("DomesticAbuse"), "urgency", "Urgent")));

            assertThat(errors).hasSize(1);
            assertThat(errors.getFirst().field()).isEqualTo("urgencyReason");
            assertThat(errors.getFirst().message()).contains("required when urgency is Urgent");
        }

        @Test
        @DisplayName("does not require one when the referral is Normal")
        void doesNotRequireOneWhenNormal() {
            assertThat(parse(payload("reasons", List.of("DomesticAbuse"), "urgency", "Normal")).urgencyReason())
                .isEmpty();
        }

        @Test
        @DisplayName("refuses an urgency it does not know rather than reading it as Normal")
        void refusesAnUnknownUrgency() {
            // Coercing would drop the one signal that gets a referral looked at today, so an unknown value is
            // refused the way a saved query's `shared` flag is.
            List<FieldError> errors =
                errorsFrom(() -> parse(payload("reasons", List.of("DomesticAbuse"), "urgency", "Standard")));

            assertThat(errors).hasSize(1);
            assertThat(errors.getFirst().field()).isEqualTo("urgency");
            assertThat(errors.getFirst().message())
                .contains("names no urgency", "'Standard'", "Normal", "Urgent");
        }

        @Test
        @DisplayName("caps the urgency reason")
        void capsTheUrgencyReason() {
            assertThat(errorsFrom(() -> parse(payload(
                "reasons", List.of("DomesticAbuse"),
                "urgency", "Urgent",
                "urgencyReason", repeated(ReferralSchemas.MAX_URGENCY_REASON_LENGTH + 1)))))
                .contains(new FieldError("urgencyReason",
                    "urgencyReason must be " + ReferralSchemas.MAX_URGENCY_REASON_LENGTH
                        + " characters or fewer"));
        }
    }

    @Nested
    @DisplayName("masterId (plans D2 and F1)")
    class MasterId {

        private static final String SULLIVAN = "aaaaaaaa-0000-0000-0000-000000000001";

        @Test
        @DisplayName("is stripped from the create, as any unknown key is: valid, malformed or not a string (plan F1)")
        void strippedFromTheCreate() {
            CreateReferralInput plain = parse(payload("reasons", List.of("DomesticAbuse")));
            for (Object masterId : new Object[] {SULLIVAN, "nope", 7}) {
                assertThat(parse(payload("reasons", List.of("DomesticAbuse"), "masterId", masterId)))
                    .isEqualTo(plain);
            }
            assertThat(errorsFrom(() -> parse(payload("masterId", "nope"))))
                .extracting(FieldError::field).containsExactly("reasons");
        }

        @Test
        @DisplayName("is required on a reassignment")
        void requiredOnAssignment() {
            assertThat(ReferralSchemas.parseAssignment(payload("masterId", SULLIVAN)))
                .isEqualTo(UUID.fromString(SULLIVAN));
            for (Map<String, Object> raw : List.of(payload(), payload("masterId", null), payload("masterId", ""))) {
                assertThat(errorsFrom(() -> ReferralSchemas.parseAssignment(raw)))
                    .containsExactly(new FieldError("masterId", "Select a Master who is available"));
            }
        }
    }

    @Nested
    @DisplayName("the batch send (plan F1)")
    class Send {

        private static final String ONE = "bbbbbbbb-0000-0000-0000-000000000001";
        private static final String TWO = "bbbbbbbb-0000-0000-0000-000000000002";
        private static final String SULLIVAN = "aaaaaaaa-0000-0000-0000-000000000001";

        @Test
        @DisplayName("reads each entry in order, ignoring unknown keys")
        void reads() {
            assertThat(ReferralSchemas.parseSend(payload("allocations", List.of(
                payload("referralId", ONE, "masterId", SULLIVAN, "note", "ignored"),
                payload("referralId", " " + TWO + " ", "masterId", SULLIVAN)), "other", 1), 100))
                .containsExactly(new ReferralAllocation(UUID.fromString(ONE), UUID.fromString(SULLIVAN)),
                                 new ReferralAllocation(UUID.fromString(TWO), UUID.fromString(SULLIVAN)));
        }

        @Test
        @DisplayName("refuses no allocations, an empty array or not an array, on allocations")
        void empty() {
            for (Map<String, Object> raw : List.of(payload(), payload("allocations", null),
                                                   payload("allocations", List.of()),
                                                   payload("allocations", "nope"))) {
                assertThat(errorsFrom(() -> ReferralSchemas.parseSend(raw, 100)))
                    .containsExactly(new FieldError("allocations", ReferralSchemas.CHOOSE_REFERRALS));
            }
        }

        @Test
        @DisplayName("accepts exactly the maximum")
        void atTheMaximum() {
            List<Object> two = List.of(payload("referralId", ONE, "masterId", SULLIVAN),
                                       payload("referralId", TWO, "masterId", SULLIVAN));
            assertThat(ReferralSchemas.parseSend(payload("allocations", two), 2)).hasSize(2);
        }

        @Test
        @DisplayName("refuses more entries than the maximum")
        void tooMany() {
            List<Object> three = List.of(payload("referralId", ONE, "masterId", SULLIVAN),
                                         payload("referralId", TWO, "masterId", SULLIVAN),
                                         payload("referralId", SULLIVAN, "masterId", SULLIVAN));
            assertThat(errorsFrom(() -> ReferralSchemas.parseSend(payload("allocations", three), 2)))
                .containsExactly(new FieldError("allocations", "Send at most 2 referrals at once"));
        }

        @Test
        @DisplayName("names each bad entry's field, and a referral named twice on its later copy")
        void entries() {
            List<Object> entries = Arrays.asList(
                payload("referralId", ONE, "masterId", SULLIVAN),
                payload("referralId", "nope", "masterId", 7),
                "not an object",
                payload("masterId", SULLIVAN),
                payload("referralId", ONE, "masterId", SULLIVAN));

            assertThat(errorsFrom(() -> ReferralSchemas.parseSend(payload("allocations", entries), 100)))
                .containsExactly(
                    new FieldError("allocations[1].referralId", ReferralSchemas.SELECT_A_REFERRAL),
                    new FieldError("allocations[1].masterId", ReferralSchemas.SELECT_A_MASTER),
                    new FieldError("allocations[2]", ReferralSchemas.ALLOCATION_SHAPE),
                    new FieldError("allocations[3].referralId", ReferralSchemas.SELECT_A_REFERRAL),
                    new FieldError("allocations[4].referralId", ReferralSchemas.SENT_TWICE));
        }
    }

    @Nested
    @DisplayName("the payload as a whole")
    class ThePayloadAsAWhole {

        @Test
        @DisplayName("reports every failure at once rather than one per round trip")
        void reportsEveryFailureAtOnce() {
            List<FieldError> errors = errorsFrom(() -> parse(payload("urgency", "Urgent")));

            assertThat(errors).extracting(FieldError::field)
                .containsExactlyInAnyOrder("reasons", "urgencyReason");
        }

        @Test
        @DisplayName("does not demand an explanation for a reason it could not read")
        void doesNotDemandAnExplanationForAReasonItCouldNotRead() {
            // A misspelled "Other" is one mistake, not two: naming `notes` as well would send the caseworker
            // to fix a field that is only required because of the field they have yet to correct.
            assertThat(errorsFrom(() -> parse(payload("reasons", List.of("Otherr")))))
                .extracting(FieldError::field)
                .containsExactly("reasons");
        }

        @Test
        @DisplayName("carries the summary message a 400 renders as its banner")
        void carriesTheSummaryMessage() {
            assertThatThrownBy(() -> parse(payload()))
                .isInstanceOf(InvalidInputError.class)
                .hasMessage("Referral is not valid");
        }

        @Test
        @DisplayName("strips what the caller may not set, including the state and the migrated flag")
        void stripsWhatTheCallerMayNotSet() {
            // Structural rather than a check: CreateReferralInput has nowhere to put any of these, so a
            // caller cannot post a referral that is already Closed, one attributed to the backfill, or one
            // addressed to a chosen judge.
            CreateReferralInput created = parse(payload(
                "reasons", List.of("DomesticAbuse"),
                "state", "Closed",
                "migrated", true,
                "masterName", "Master Someone",
                "createdBy", "Somebody Else"));

            assertThat(created).isEqualTo(new CreateReferralInput(
                List.of(ReferralReason.DomesticAbuse), Optional.empty(), Urgency.Normal, Optional.empty()));
        }
    }

    @Nested
    @DisplayName("a judge's direction")
    class Direction {

        private static DirectionInput direction(Map<String, Object> raw) {
            return ReferralSchemas.parseDirection(raw);
        }

        @Test
        @DisplayName("accepts a whole direction, as the enum member rather than the wire string")
        void acceptsAWholeDirection() {
            DirectionInput recorded = direction(payload(
                "decision", "Approved",
                "directionsText", "  Approved. No publication necessary.  ",
                "changesToStandardPublication", true));

            assertThat(recorded.decision()).isEqualTo(MasterDecision.Approved);
            // Trimmed, and the trimmed value is what gets stored -- the rule every payload here applies.
            assertThat(recorded.directionsText()).contains("Approved. No publication necessary.");
            assertThat(recorded.changesToStandardPublication()).isTrue();
        }

        @Test
        @DisplayName("accepts a rejection with directions and publication")
        void acceptsARejection() {
            DirectionInput recorded = direction(payload(
                "decision", "Rejected",
                "directionsText", "Refused: the minor's second parent has not consented.",
                "changesToStandardPublication", false));

            assertThat(recorded.decision()).isEqualTo(MasterDecision.Rejected);
            assertThat(recorded.changesToStandardPublication()).isFalse();
        }

        @Test
        @DisplayName("requires a decision, naming the two acceptable values")
        void requiresADecision() {
            List<FieldError> errors = errorsFrom(() -> direction(payload("changesToStandardPublication", false)));

            assertThat(errors).extracting(FieldError::field).contains("decision");
            assertThat(errors.getFirst().message()).contains("required", "Approved", "Rejected");
        }

        @Test
        @DisplayName("refuses a decision it does not know rather than guessing between two opposites")
        void refusesAnUnknownDecision() {
            List<FieldError> errors = errorsFrom(() -> direction(payload(
                "decision", "Adjourned", "changesToStandardPublication", false)));

            assertThat(errors).hasSize(1);
            assertThat(errors.getFirst().field()).isEqualTo("decision");
            assertThat(errors.getFirst().message())
                .contains("names no decision", "'Adjourned'", "Approved", "Rejected");
        }

        @Test
        @DisplayName("matches the decision exactly, because the name is also the stored enum label")
        void matchesTheDecisionExactly() {
            // Accepting "approved" would send Postgres a label the "MasterDecision" type does not have, which
            // fails at the insert rather than here -- the same reason a referral reason is matched exactly.
            assertThat(errorsFrom(() -> direction(payload(
                "decision", "approved", "changesToStandardPublication", false))))
                .extracting(FieldError::field)
                .containsExactly("decision");
        }

        @Test
        @DisplayName("requires changesToStandardPublication, because it has no safe default in either direction")
        void requiresTheGazetteAnswer() {
            // Defaulting it would make the judge's choice for them: false withholds a notice the public record
            // may depend on, and true publishes a change that was to stay private.
            List<FieldError> errors = errorsFrom(() -> direction(payload("decision", "Approved")));

            assertThat(errors).hasSize(1);
            assertThat(errors.getFirst().field()).isEqualTo("changesToStandardPublication");
            assertThat(errors.getFirst().message()).contains("required", "no default");
        }

        @Test
        @DisplayName("refuses an explicit null changesToStandardPublication as well as an absent one")
        void refusesANullGazetteAnswer() {
            Map<String, Object> raw = payload("decision", "Approved");
            raw.put("changesToStandardPublication", null);

            assertThat(errorsFrom(() -> direction(raw)))
                .extracting(FieldError::field)
                .containsExactly("changesToStandardPublication");
        }

        @Test
        @DisplayName("refuses a truthy string rather than coercing it, because publication cannot be undone")
        void refusesATruthyString() {
            List<FieldError> errors = errorsFrom(() -> direction(payload(
                "decision", "Approved", "changesToStandardPublication", "false")));

            assertThat(errors).hasSize(1);
            assertThat(errors.getFirst()).isEqualTo(
                new FieldError("changesToStandardPublication", "changesToStandardPublication must be true or false"));
        }

        @Test
        @DisplayName("does not require directions text on a standard publication, because an approval may need no "
            + "comment")
        void doesNotRequireDirectionsText() {
            assertThat(direction(payload(
                "decision", "Approved", "changesToStandardPublication", false)).directionsText())
                .isEmpty();
        }

        @Test
        @DisplayName("reads whitespace-only directions as none, rather than storing an empty explanation")
        void readsBlankDirectionsAsNone() {
            assertThat(direction(payload(
                "decision", "Approved", "directionsText", "   ",
                "changesToStandardPublication", false)).directionsText())
                .isEmpty();
        }

        @Test
        @DisplayName("caps the directions text")
        void capsTheDirectionsText() {
            assertThat(errorsFrom(() -> direction(payload(
                "decision", "Approved",
                "directionsText", repeated(ReferralSchemas.MAX_DIRECTIONS_LENGTH + 1),
                "changesToStandardPublication", false))))
                .contains(new FieldError("directionsText",
                    "directionsText must be " + ReferralSchemas.MAX_DIRECTIONS_LENGTH + " characters or fewer"));
        }

        @Test
        @DisplayName("accepts the longest directions text the cap allows")
        void acceptsTheLongestDirectionsText() {
            // The other half of the cap: an off-by-one here would refuse a direction of exactly the length the
            // form told the judge they had.
            assertThat(direction(payload(
                "decision", "Approved",
                "directionsText", repeated(ReferralSchemas.MAX_DIRECTIONS_LENGTH),
                "changesToStandardPublication", false)).directionsText())
                .isPresent();
        }

        @Test
        @DisplayName("reports every failure at once rather than one per round trip")
        void reportsEveryFailureAtOnce() {
            assertThat(errorsFrom(() -> direction(payload("directionsText", 7))))
                .extracting(FieldError::field)
                .containsExactlyInAnyOrder("decision", "directionsText", "changesToStandardPublication");
        }

        @Test
        @DisplayName("carries its own summary message, distinct from the create's")
        void carriesItsOwnSummaryMessage() {
            // Two payloads with two banners: "Referral is not valid" on a direction would send a judge looking
            // at the caseworker's referral for the mistake they made in their own form.
            assertThatThrownBy(() -> direction(payload()))
                .isInstanceOf(InvalidInputError.class)
                .hasMessage("Direction is not valid");
        }

        @Test
        @DisplayName("requires directions text when there are changes to the standard publication")
        void requiresDirectionsForChanges() {
            // "Yes" alone does not say whether to publish nothing or only the last name; the text is what does.
            List<FieldError> absent = errorsFrom(() -> direction(payload(
                "decision", "Approved", "changesToStandardPublication", true)));
            List<FieldError> blank = errorsFrom(() -> direction(payload(
                "decision", "Approved", "directionsText", "   ", "changesToStandardPublication", true)));

            assertThat(absent).singleElement().satisfies(error -> {
                assertThat(error.field()).isEqualTo("directionsText");
                assertThat(error.message()).contains("required", "changes to the standard publication");
            });
            assertThat(blank).extracting(FieldError::field).containsExactly("directionsText");
        }

        @Test
        @DisplayName("accepts changes to the standard publication that say what they are")
        void acceptsChangesWithDirections() {
            DirectionInput recorded = direction(payload(
                "decision", "Approved", "directionsText", "Publish the last name only",
                "changesToStandardPublication", true));

            assertThat(recorded.changesToStandardPublication()).isTrue();
            assertThat(recorded.directionsText()).contains("Publish the last name only");
        }

        @Test
        @DisplayName("reports a too-long directions text once, not also as missing")
        void reportsALongDirectionsTextOnce() {
            assertThat(errorsFrom(() -> direction(payload(
                "decision", "Approved",
                "directionsText", repeated(ReferralSchemas.MAX_DIRECTIONS_LENGTH + 1),
                "changesToStandardPublication", true))))
                .extracting(FieldError::field)
                .containsExactly("directionsText");
        }

        @Test
        @DisplayName("no longer reads the pre-2026-10-27 publishToGazette, so a body sending only it is refused")
        void noLongerReadsTheOldGazetteName() {
            // Stripped as any unknown key is: the answer it carried is not there, and has no default.
            assertThat(errorsFrom(() -> direction(payload("decision", "Rejected", "publishToGazette", false))))
                .extracting(FieldError::field)
                .containsExactly("changesToStandardPublication");
        }

        @Test
        @DisplayName("strips what the judge may not set: the state, the author and the date")
        void stripsWhatTheJudgeMayNotSet() {
            // Structural rather than a check: DirectionInput has nowhere to put any of these, so a direction
            // cannot close its own referral, sign itself as another judge, or backdate itself.
            DirectionInput recorded = direction(payload(
                "decision", "Approved",
                "changesToStandardPublication", false,
                "state", "Closed",
                "decidedBy", "Some Other Judge",
                "decisionDate", "2020-01-01T00:00:00",
                "claimedBy", "Some Other Judge"));

            assertThat(recorded).isEqualTo(
                new DirectionInput(MasterDecision.Approved, Optional.empty(), false));
        }
    }

    @Nested
    @DisplayName("the applicant's confirmation")
    class Confirmation {

        private static ConfirmationInput confirmation(Map<String, Object> raw) {
            return ReferralSchemas.parseConfirmation(raw);
        }

        @Test
        @DisplayName("accepts an outcome and a note, as the enum member rather than the wire string")
        void acceptsAnOutcomeAndANote() {
            assertThat(confirmation(payload("outcome", "Confirmed", "note", " Phoned to confirm. ")))
                .isEqualTo(new ConfirmationInput(
                    ConfirmationOutcome.Confirmed, Optional.of("Phoned to confirm.")));
        }

        @Test
        @DisplayName("accepts both outcomes, so neither answer is unreachable")
        void acceptsBothOutcomes() {
            for (ConfirmationOutcome outcome : ConfirmationOutcome.values()) {
                assertThat(confirmation(payload("outcome", outcome.name())).outcome()).isEqualTo(outcome);
            }
        }

        @Test
        @DisplayName("requires an outcome, and says the same for absent and explicitly null")
        void requiresAnOutcome() {
            // No default in either direction: Confirmed and Withdrawn mean opposite things -- enrol it, or refund
            // it -- so a default would record an answer on the applicant's behalf.
            for (Map<String, Object> raw : List.of(payload(), payload("outcome", null))) {
                assertThat(errorsFrom(() -> confirmation(raw)))
                    .extracting(FieldError::field)
                    .containsExactly("outcome");
            }
        }

        @Test
        @DisplayName("names both acceptable values in the refusal, so a client need not hold the enum")
        void namesBothAcceptableValues() {
            assertThat(errorsFrom(() -> confirmation(payload())).getFirst().message())
                .contains(ConfirmationOutcome.Confirmed.name())
                .contains(ConfirmationOutcome.Withdrawn.name());
        }

        @Test
        @DisplayName("refuses an outcome it does not know, quoting what was sent")
        void refusesAnUnknownOutcome() {
            assertThat(errorsFrom(() -> confirmation(payload("outcome", "Maybe"))).getFirst().message())
                .contains("'Maybe'");
        }

        @Test
        @DisplayName("matches the outcome exactly, because the name is also the stored enum label")
        void matchesTheOutcomeExactly() {
            // The column is the "ConfirmationOutcome" Postgres type those names spell, so accepting "confirmed"
            // here would send Postgres a label it does not have.
            assertThat(errorsFrom(() -> confirmation(payload("outcome", "confirmed"))))
                .extracting(FieldError::field)
                .containsExactly("outcome");
        }

        @Test
        @DisplayName("refuses an outcome that is not text rather than reading it as one")
        void refusesAnOutcomeThatIsNotText() {
            assertThat(errorsFrom(() -> confirmation(payload("outcome", 1))))
                .contains(new FieldError("outcome", "outcome must be text"));
        }

        @Test
        @DisplayName("does not require a note, and reads a blank one as none")
        void doesNotRequireANote() {
            assertThat(confirmation(payload("outcome", "Withdrawn")).note()).isEmpty();
            assertThat(confirmation(payload("outcome", "Withdrawn", "note", "   ")).note()).isEmpty();
        }

        @Test
        @DisplayName("caps the note, because the case note it is quoted in is bounded")
        void capsTheNote() {
            assertThat(errorsFrom(() -> confirmation(payload(
                "outcome", "Confirmed",
                "note", repeated(ReferralSchemas.MAX_CONFIRMATION_NOTE_LENGTH + 1)))))
                .contains(new FieldError("note", "note must be "
                    + ReferralSchemas.MAX_CONFIRMATION_NOTE_LENGTH + " characters or fewer"));
        }

        @Test
        @DisplayName("accepts the longest note the cap allows")
        void acceptsTheLongestNote() {
            assertThat(confirmation(payload(
                "outcome", "Confirmed",
                "note", repeated(ReferralSchemas.MAX_CONFIRMATION_NOTE_LENGTH))).note())
                .isPresent();
        }

        @Test
        @DisplayName("reports every failure at once rather than one per round trip")
        void reportsEveryFailureAtOnce() {
            assertThat(errorsFrom(() -> confirmation(payload("note", 7))))
                .extracting(FieldError::field)
                .containsExactlyInAnyOrder("outcome", "note");
        }

        @Test
        @DisplayName("carries its own summary message, distinct from the create's and the direction's")
        void carriesItsOwnSummaryMessage() {
            assertThatThrownBy(() -> confirmation(payload()))
                .isInstanceOf(InvalidInputError.class)
                .hasMessage("Confirmation is not valid");
        }

        @Test
        @DisplayName("strips what the caller may not set: the author, the date and the case's own dates")
        void stripsWhatTheCallerMayNotSet() {
            // Structural rather than a check: ConfirmationInput has nowhere to put any of these, so a body cannot
            // backdate a reply, sign it as somebody else, or reach into the date cascade.
            ConfirmationInput recorded = confirmation(payload(
                "outcome", "Confirmed",
                "confirmationRecordedBy", "Somebody Else",
                "confirmationReceivedAt", "2020-01-01T00:00:00",
                "confirmationRequestedAt", "2020-01-01T00:00:00",
                "dateEnroled", "2020-01-01T00:00:00",
                "state", "Closed"));

            assertThat(recorded).isEqualTo(
                new ConfirmationInput(ConfirmationOutcome.Confirmed, Optional.empty()));
        }
    }
}
