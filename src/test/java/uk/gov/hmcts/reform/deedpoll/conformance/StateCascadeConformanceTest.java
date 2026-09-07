package uk.gov.hmcts.reform.deedpoll.conformance;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import uk.gov.hmcts.reform.deedpoll.domain.FieldError;
import uk.gov.hmcts.reform.deedpoll.domain.deedpoll.CaseState;
import uk.gov.hmcts.reform.deedpoll.domain.deedpoll.CaseStateInput;
import uk.gov.hmcts.reform.deedpoll.entity.enums.DeedPollState;
import uk.gov.hmcts.reform.deedpoll.reference.DataverseArtifacts;
import uk.gov.hmcts.reform.deedpoll.reference.DataverseArtifacts.TableDir;

import java.time.LocalDateTime;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Layer 2a -- state-cascade conformance.
 *
 * <p>Ported from {@code reference/ts/conformance/state-cascade.conformance.test.ts}. Encodes the
 * "Set States based on Dates completed" rule from the vendored
 * {@code dataverse_tables/deedpoll/business_rules.md} as a truth table over {@link CaseState}. The
 * rule is a top-down IF / ELSE-IF cascade; the FIRST satisfied branch wins, so the cases below
 * assert ORDER, not just membership -- including the documented quirk that Awaiting Enrolment
 * (signature received) outranks the referral check.
 *
 * <p>Read this alongside {@code CaseStateTest}, which is the port of the module's own unit tests.
 * This class is the narrower question: does the implementation still agree with the source rule as
 * the Dataverse export states it. Which is why the contradiction message here is read out of
 * {@code business_rules.md} rather than transcribed -- see
 * {@link #emitsTheSourceErrorMessageAgainstFieldState()}.
 */
class StateCascadeConformanceTest {

    /**
     * The reference's single fixed date, {@code new Date("2026-01-01")}.
     */
    private static final LocalDateTime D = LocalDateTime.of(2026, 1, 1, 0, 0);

    /**
     * The message the source rule shows, as {@code business_rules.md} words it.
     */
    private static final Pattern SOURCE_ERROR_MESSAGE =
        Pattern.compile("Show the error message \"([^\"]+)\" against field State");

    /** One row of the cascade truth table: the populated dates, and the state they must derive. */
    private record Case(String label, CaseStateInput input, DeedPollState expected) {
    }

    @TestFactory
    @DisplayName("the cascade derives the source rule's state")
    List<DynamicTest> theCascadeDerivesTheSourceRulesState() {
        // Mirrors the rule's branch order.
        List<Case> cases = List.of(
            new Case("Date Returned -> Returned",
                CaseStateInput.builder().dateReturned(D).build(), DeedPollState.Returned),
            new Case("Date Enroled -> Enroled",
                CaseStateInput.builder().dateEnroled(D).build(), DeedPollState.Enroled),
            new Case("Signature Received -> Awaiting Enrolment",
                CaseStateInput.builder().signatureReceivedDate(D).build(),
                DeedPollState.AwaitingEnrolment),
            new Case("Signature Requested -> Awaiting Signature",
                CaseStateInput.builder().signatureRequestedDate(D).build(),
                DeedPollState.AwaitingSignature),
            new Case("Referred AND Ready for Master -> Referred to Master",
                CaseStateInput.builder().dateReferred(D).dateReadyForMaster(D).build(),
                DeedPollState.ReferredToMaster),
            new Case("Ready for Master alone -> Ready for Master",
                CaseStateInput.builder().dateReadyForMaster(D).build(), DeedPollState.ReadyForMaster),
            new Case("Referred WITHOUT Ready for Master -> falls through to Received",
                CaseStateInput.builder().dateReferred(D).build(), DeedPollState.Received),
            new Case("Awaiting further docs -> Awaiting Further Docs",
                CaseStateInput.builder().awaitingFurtherDocsInfo(D).build(),
                DeedPollState.AwaitingFurtherDocs),
            new Case("No dates -> Received", CaseStateInput.empty(), DeedPollState.Received));

        return cases.stream()
            .map(one -> DynamicTest.dynamicTest(one.label(),
                () -> assertThat(CaseState.deriveState(one.input())).isEqualTo(one.expected())))
            .toList();
    }

    @Nested
    @DisplayName("branch ORDER (first satisfied wins)")
    class BranchOrder {

        @Test
        @DisplayName("Returned outranks Enroled when both dates present")
        void returnedOutranksEnroled() {
            CaseStateInput input = CaseStateInput.builder().dateReturned(D).dateEnroled(D).build();

            assertThat(CaseState.deriveState(input)).isEqualTo(DeedPollState.Returned);
        }

        @Test
        @DisplayName("Enroled outranks Signature Received")
        void enroledOutranksSignatureReceived() {
            CaseStateInput input =
                CaseStateInput.builder().dateEnroled(D).signatureReceivedDate(D).build();

            assertThat(CaseState.deriveState(input)).isEqualTo(DeedPollState.Enroled);
        }

        @Test
        @DisplayName("Signature Received outranks a present referral (documented quirk)")
        void signatureReceivedOutranksAPresentReferral() {
            CaseStateInput input = CaseStateInput.builder()
                .signatureReceivedDate(D)
                .dateReferred(D)
                .dateReadyForMaster(D)
                .build();

            assertThat(CaseState.deriveState(input)).isEqualTo(DeedPollState.AwaitingEnrolment);
        }
    }

    @Nested
    @DisplayName("contradiction warning")
    class ContradictionWarning {

        @Test
        @DisplayName("flags Returned AND Enroled both present")
        void flagsReturnedAndEnroledBothPresent() {
            CaseStateInput input = CaseStateInput.builder().dateReturned(D).dateEnroled(D).build();

            assertThat(CaseState.hasContradiction(input)).isTrue();
        }

        @Test
        @DisplayName("does not flag a single terminal date")
        void doesNotFlagASingleTerminalDate() {
            assertThat(CaseState.hasContradiction(CaseStateInput.builder().dateReturned(D).build()))
                .isFalse();
        }
    }

    /**
     * The FIRST branch of the source rule, which was detected but never surfaced until 2026-08-10:
     * {@code hasContradiction()} existed with no caller, so {@code deriveState()}'s else-if chain
     * silently returned Returned and the record read as decided.
     *
     * <p>The reference asserts the message against a transcription of {@code business_rules.md};
     * this reads it out of the vendored rules file instead, so the two cannot drift apart. That is
     * the one strengthening in this class -- a caseworker moving between the two apps should be told
     * the same thing in the same words, and a hand-typed copy of those words is exactly what stops
     * being the same.
     *
     * <p>The source says "against field State"; the port addresses the error to the field named
     * {@code state}, which is what the form renders it against.
     */
    @Test
    @DisplayName("emits the source error message, against field State")
    void emitsTheSourceErrorMessageAgainstFieldState() {
        CaseStateInput input = CaseStateInput.builder().dateReturned(D).dateEnroled(D).build();

        List<FieldError> errors = CaseState.collectDateRuleErrors(input);

        assertThat(errors).containsExactly(new FieldError("state", sourceErrorMessage()));
    }

    /**
     * NOT a port. The source has no ordering rule on the signature pair: it accepts a received date
     * earlier than the requested date and derives Awaiting Enrolment from it, so a mistyped year
     * advances the case. Recorded here as an intentional divergence so a future reader does not
     * "correct" it back to source behaviour.
     */
    @Nested
    @DisplayName("signature date order (addition, not a port)")
    class SignatureDateOrder {

        private static final LocalDateTime EARLY = LocalDateTime.of(2026, 8, 3, 0, 0);
        private static final LocalDateTime LATE = LocalDateTime.of(2026, 8, 15, 0, 0);

        @Test
        @DisplayName("rejects received-before-requested, which the source allows")
        void rejectsReceivedBeforeRequested() {
            List<FieldError> errors = CaseState.collectDateRuleErrors(CaseStateInput.builder()
                .signatureRequestedDate(LATE)
                .signatureReceivedDate(EARLY)
                .build());

            assertThat(errors).hasSize(1);
            assertThat(errors.getFirst().field()).isEqualTo("signatureReceivedDate");
        }

        @Test
        @DisplayName("still derives the source state for the in-order pair")
        void stillDerivesTheSourceStateForTheInOrderPair() {
            CaseStateInput input = CaseStateInput.builder()
                .signatureRequestedDate(EARLY)
                .signatureReceivedDate(LATE)
                .build();

            assertThat(CaseState.deriveState(input)).isEqualTo(DeedPollState.AwaitingEnrolment);
        }
    }

    /**
     * The reference's last block, "invalid dates are treated as unset", has no equivalent here and
     * is deliberately absent rather than approximated. It asserts that
     * {@code deriveState({dateReturned: new Date("nonsense")})} still yields Received, because a
     * JavaScript {@code Date} can hold an unparseable value that is nonetheless not null. A
     * {@link LocalDateTime} cannot: it either parses at the boundary or never exists, so "unset" has
     * one representation instead of two and there is no second case to test. The same note is on
     * {@code CaseStateInput}.
     *
     * <p>What can still be asserted is the half that carries the risk -- a date that IS set
     * satisfies its branch, and nothing else does -- and the truth table above does that.
     */
    @Test
    @DisplayName("an unset date does not satisfy its branch")
    void anUnsetDateDoesNotSatisfyItsBranch() {
        assertThat(CaseState.deriveState(CaseStateInput.builder().dateReturned(null).build()))
            .isEqualTo(DeedPollState.Received);
    }

    /**
     * The contradiction message, read out of the vendored {@code business_rules.md}.
     */
    private static String sourceErrorMessage() {
        String rules = DataverseArtifacts.businessRules(TableDir.DEEDPOLL);
        Matcher matcher = SOURCE_ERROR_MESSAGE.matcher(rules);
        assertThat(matcher.find())
            .as("business_rules.md no longer states an error message for the Returned / Enroled "
                + "contradiction -- the rule this test asserts has changed at the source")
            .isTrue();
        return matcher.group(1);
    }
}
