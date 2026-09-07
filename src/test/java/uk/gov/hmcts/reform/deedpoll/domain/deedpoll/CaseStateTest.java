package uk.gov.hmcts.reform.deedpoll.domain.deedpoll;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import uk.gov.hmcts.reform.deedpoll.domain.FieldError;
import uk.gov.hmcts.reform.deedpoll.entity.enums.DeedPollState;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ports every test block in {@code reference/ts/deed-poll/case-state.test.ts}. The describe blocks are
 * kept as nested classes and their names as written, so the two files can be read side by side.
 *
 * <p>Two of the reference's blocks assert that an <em>invalid</em> {@code Date} --
 * {@code new Date("nonsense")} -- does not fire a cascade branch. A {@link LocalDateTime} cannot hold a
 * non-date, so the only "not set" left in Java is null; both blocks are ported as the null case they
 * collapse to, and each says so where it appears.
 */
class CaseStateTest {

    private static final LocalDateTime SOME = LocalDateTime.parse("2026-01-01T00:00:00");
    private static final LocalDateTime EARLY = LocalDateTime.parse("2026-08-03T00:00:00");
    private static final LocalDateTime LATE = LocalDateTime.parse("2026-08-15T00:00:00");

    private static Stream<Arguments> oneDateAtATime() {
        return Stream.of(
            Arguments.of("dateReceived", CaseStateInput.builder().dateReceived(SOME).build(), DeedPollState.Received),
            Arguments.of("awaitingFurtherDocsInfo",
                CaseStateInput.builder().awaitingFurtherDocsInfo(SOME).build(),
                DeedPollState.AwaitingFurtherDocs),
            Arguments.of("dateReadyForMaster",
                CaseStateInput.builder().dateReadyForMaster(SOME).build(),
                DeedPollState.ReadyForMaster),
            Arguments.of("signatureRequestedDate",
                CaseStateInput.builder().signatureRequestedDate(SOME).build(),
                DeedPollState.AwaitingSignature),
            Arguments.of("signatureReceivedDate",
                CaseStateInput.builder().signatureReceivedDate(SOME).build(),
                DeedPollState.AwaitingEnrolment),
            Arguments.of("dateEnroled", CaseStateInput.builder().dateEnroled(SOME).build(), DeedPollState.Enroled),
            Arguments.of("dateReturned", CaseStateInput.builder().dateReturned(SOME).build(), DeedPollState.Returned));
    }

    @Nested
    @DisplayName("deriveState -- one date at a time")
    class DeriveStateOneDateAtATime {

        @ParameterizedTest(name = "{0} set gives {2}")
        @MethodSource("uk.gov.hmcts.reform.deedpoll.domain.deedpoll.CaseStateTest#oneDateAtATime")
        void oneDateGivesItsState(String field, CaseStateInput input, DeedPollState expected) {
            assertThat(CaseState.deriveState(input)).isEqualTo(expected);
        }
    }

    @Test
    @DisplayName("empty input defaults to Received")
    void emptyInputDefaultsToReceived() {
        assertThat(CaseState.deriveState(CaseStateInput.empty())).isEqualTo(DeedPollState.Received);
    }

    @Nested
    @DisplayName("deriveState -- precedence cascade (first match wins)")
    class PrecedenceCascade {

        @Test
        @DisplayName("Returned beats every earlier date")
        void returnedBeatsEveryEarlierDate() {
            assertThat(CaseState.deriveState(CaseStateInput.builder()
                .dateReturned(SOME)
                .dateEnroled(SOME)
                .signatureReceivedDate(SOME)
                .dateReadyForMaster(SOME)
                .dateReceived(SOME)
                .build())).isEqualTo(DeedPollState.Returned);
        }

        @Test
        @DisplayName("Enroled beats signature / master / received")
        void enroledBeatsSignatureMasterReceived() {
            assertThat(CaseState.deriveState(CaseStateInput.builder()
                .dateEnroled(SOME)
                .signatureReceivedDate(SOME)
                .signatureRequestedDate(SOME)
                .dateReadyForMaster(SOME)
                .build())).isEqualTo(DeedPollState.Enroled);
        }

        @Test
        @DisplayName("Awaiting Enrolment (signature received) is evaluated BEFORE referral")
        void awaitingEnrolmentIsEvaluatedBeforeReferral() {
            // Source order: signature-received wins over a referral date.
            assertThat(CaseState.deriveState(CaseStateInput.builder()
                .signatureReceivedDate(SOME)
                .dateReferred(SOME)
                .dateReadyForMaster(SOME)
                .build())).isEqualTo(DeedPollState.AwaitingEnrolment);
        }

        @Test
        @DisplayName("Awaiting Signature beats referral / ready-for-master")
        void awaitingSignatureBeatsReferralAndReadyForMaster() {
            assertThat(CaseState.deriveState(CaseStateInput.builder()
                .signatureRequestedDate(SOME)
                .dateReferred(SOME)
                .dateReadyForMaster(SOME)
                .build())).isEqualTo(DeedPollState.AwaitingSignature);
        }

        @Test
        @DisplayName("Referred to Master requires BOTH dateReferred and dateReadyForMaster")
        void referredToMasterRequiresBothDates() {
            assertThat(CaseState.deriveState(CaseStateInput.builder()
                .dateReferred(SOME)
                .dateReadyForMaster(SOME)
                .build())).isEqualTo(DeedPollState.ReferredToMaster);
        }

        @Test
        @DisplayName("dateReferred alone (no ready-for-master) does NOT reach Referred; falls through to Received")
        void dateReferredAloneFallsThroughToReceived() {
            assertThat(CaseState.deriveState(CaseStateInput.builder().dateReferred(SOME).build()))
                .isEqualTo(DeedPollState.Received);
        }

        @Test
        @DisplayName("dateReadyForMaster alone gives Ready for Master")
        void dateReadyForMasterAloneGivesReadyForMaster() {
            assertThat(CaseState.deriveState(CaseStateInput.builder().dateReadyForMaster(SOME).build()))
                .isEqualTo(DeedPollState.ReadyForMaster);
        }

        @Test
        @DisplayName("Ready for Master beats awaiting-docs and received")
        void readyForMasterBeatsAwaitingDocsAndReceived() {
            assertThat(CaseState.deriveState(CaseStateInput.builder()
                .dateReadyForMaster(SOME)
                .awaitingFurtherDocsInfo(SOME)
                .dateReceived(SOME)
                .build())).isEqualTo(DeedPollState.ReadyForMaster);
        }
    }

    @Nested
    @DisplayName("invalid dates are treated as unset")
    class InvalidDatesAreTreatedAsUnset {

        @Test
        @DisplayName("an unset date does not trigger its branch")
        void anUnsetDateDoesNotTriggerItsBranch() {
            // The reference passes `new Date("nonsense")` for dateReturned and asserts the cascade falls
            // through to Received. A LocalDateTime cannot be invalid, so null is the whole of "not set"
            // here and this is what that block collapses to.
            assertThat(CaseState.deriveState(CaseStateInput.builder()
                .dateReturned(null)
                .dateReceived(SOME)
                .build())).isEqualTo(DeedPollState.Received);
        }
    }

    @Nested
    @DisplayName("hasContradiction")
    class HasContradiction {

        @Test
        @DisplayName("flags both enrolled and returned set")
        void flagsBothEnrolledAndReturnedSet() {
            assertThat(CaseState.hasContradiction(CaseStateInput.builder()
                .dateEnroled(SOME)
                .dateReturned(SOME)
                .build())).isTrue();
        }

        @Test
        @DisplayName("is false when only one (or neither) terminal date is set")
        void isFalseWhenOnlyOneOrNeitherTerminalDateIsSet() {
            assertThat(CaseState.hasContradiction(CaseStateInput.builder().dateEnroled(SOME).build())).isFalse();
            assertThat(CaseState.hasContradiction(CaseStateInput.builder().dateReturned(SOME).build())).isFalse();
            assertThat(CaseState.hasContradiction(CaseStateInput.empty())).isFalse();
        }
    }

    @Nested
    @DisplayName("collectDateRuleErrors")
    class CollectDateRuleErrors {

        @Test
        @DisplayName("is empty for a consistent record")
        void isEmptyForAConsistentRecord() {
            assertThat(CaseState.collectDateRuleErrors(CaseStateInput.builder()
                .signatureRequestedDate(EARLY)
                .signatureReceivedDate(LATE)
                .build())).isEmpty();
        }

        @Test
        @DisplayName("ports the Enroled + Returned contradiction, against field state")
        void portsTheEnroledPlusReturnedContradiction() {
            // business_rules.md, "Set States based on Dates completed", first branch. The cascade below it
            // silently prefers Returned, so without this the record gets a state that reads as decided
            // when it is actually contradictory.
            List<FieldError> errors = CaseState.collectDateRuleErrors(CaseStateInput.builder()
                .dateEnroled(EARLY)
                .dateReturned(LATE)
                .build());
            assertThat(errors).hasSize(1);
            assertThat(errors.get(0).field()).isEqualTo("state");
            assertThat(errors.get(0).message()).isEqualTo(
                "Date Enroled and Date Returned both contain a date. Please remove one "
                    + "depending on whether the Deed Poll is to be enroled or returned.");
        }

        @Test
        @DisplayName("rejects a signature received before it was requested")
        void rejectsASignatureReceivedBeforeItWasRequested() {
            List<FieldError> errors = CaseState.collectDateRuleErrors(CaseStateInput.builder()
                .signatureRequestedDate(LATE)
                .signatureReceivedDate(EARLY)
                .build());
            assertThat(errors).hasSize(1);
            assertThat(errors.get(0).field()).isEqualTo("signatureReceivedDate");
            assertThat(errors.get(0).message()).contains("cannot be before");
        }

        @Test
        @DisplayName("allows the two signature dates on the same day")
        void allowsTheTwoSignatureDatesOnTheSameDay() {
            // A pack issued and signed the same day is ordinary, so the comparison must be strict:
            // same-day is not out of order.
            assertThat(CaseState.collectDateRuleErrors(CaseStateInput.builder()
                .signatureRequestedDate(EARLY)
                .signatureReceivedDate(LocalDateTime.from(EARLY))
                .build())).isEmpty();
        }

        @Test
        @DisplayName("does not complain about a received date with no requested date")
        void doesNotComplainAboutAReceivedDateWithNoRequestedDate() {
            // A different, permitted gap -- the cascade derives Awaiting Enrolment from it and the source
            // never required the requested date to exist.
            assertThat(CaseState.collectDateRuleErrors(CaseStateInput.builder()
                .signatureReceivedDate(EARLY)
                .build())).isEmpty();
        }

        @Test
        @DisplayName("does not complain about a requested date with no received date")
        void doesNotComplainAboutARequestedDateWithNoReceivedDate() {
            assertThat(CaseState.collectDateRuleErrors(CaseStateInput.builder()
                .signatureRequestedDate(EARLY)
                .build())).isEmpty();
        }

        @Test
        @DisplayName("reports both rules at once")
        void reportsBothRulesAtOnce() {
            List<FieldError> errors = CaseState.collectDateRuleErrors(CaseStateInput.builder()
                .dateEnroled(EARLY)
                .dateReturned(LATE)
                .signatureRequestedDate(LATE)
                .signatureReceivedDate(EARLY)
                .build());
            assertThat(errors).extracting(FieldError::field).containsExactly("state", "signatureReceivedDate");
        }

        @Test
        @DisplayName("ignores an unset received date rather than treating it as set")
        void ignoresAnUnsetReceivedDateRatherThanTreatingItAsSet() {
            // The reference passes `new Date("nonsense")` as the received date and asserts no error, for
            // consistency with deriveState -- a garbage value is caught by the form's date-part check, not
            // here. Java's equivalent is the null this port cannot distinguish it from.
            assertThat(CaseState.collectDateRuleErrors(CaseStateInput.builder()
                .signatureRequestedDate(LATE)
                .signatureReceivedDate(null)
                .build())).isEmpty();
        }
    }
}
