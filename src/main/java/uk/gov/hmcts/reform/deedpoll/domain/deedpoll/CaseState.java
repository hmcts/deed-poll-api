package uk.gov.hmcts.reform.deedpoll.domain.deedpoll;

import uk.gov.hmcts.reform.deedpoll.domain.FieldError;
import uk.gov.hmcts.reform.deedpoll.entity.enums.DeedPollState;

import java.util.ArrayList;
import java.util.List;

/**
 * The state cascade: which state a case is in, given which of its dates are populated.
 *
 * <p>Ported from {@code reference/ts/deed-poll/case-state.ts}, which itself ports
 * {@code SetStatesbasedonDatescompleted.xaml}.
 *
 * <p>The Dynamics workflow derives {@code state} from which date fields are populated, as a top-down
 * priority cascade (an if / else-if chain). The FIRST satisfied branch wins, so the <em>order</em>
 * below is significant and mirrors the XAML exactly:
 *
 * <ol>
 *   <li>{@code dateReturned} gives Returned (5)</li>
 *   <li>{@code dateEnroled} gives Enroled (4)</li>
 *   <li>{@code signatureReceivedDate} gives Awaiting Enrolment (8)</li>
 *   <li>{@code signatureRequestedDate} gives Awaiting Signature (7)</li>
 *   <li>{@code dateReferred} AND {@code dateReadyForMaster} give Referred to Master (3)</li>
 *   <li>{@code dateReadyForMaster} gives Ready for Master (2)</li>
 *   <li>{@code awaitingFurtherDocsInfo} gives Awaiting Docs/Info (6)</li>
 *   <li>(default) gives Received (1)</li>
 * </ol>
 *
 * <p>Note that the source evaluates Awaiting Enrolment (signature received) <em>before</em> the
 * referral check, so a signed case reads Awaiting Enrolment even if a referral date is also present.
 * That is the source behaviour, preserved here.
 *
 * <p>The reference declares its own {@code DateRuleError {field, message}} interface for the
 * cross-field violations. This port uses {@link FieldError}, which has the same two members: in
 * TypeScript the two types are structurally identical, which is why {@code service.ts} can hand a
 * {@code DateRuleError[]} straight to an {@code InvalidInputError} that declares {@code FieldError[]}.
 * Java has no structural typing, so declaring a second record would force a conversion the reference
 * does not have -- and a conversion is exactly where a field name gets lost.
 */
public final class CaseState {

    /**
     * The contradiction message, ported verbatim from the first branch of
     * {@code SetStatesbasedonDatescompleted}.
     */
    public static final String CONTRADICTION_MESSAGE =
        "Date Enroled and Date Returned both contain a date. Please remove one "
            + "depending on whether the Deed Poll is to be enroled or returned.";

    /**
     * The signature-order message. Not a port -- see {@link #collectDateRuleErrors}.
     */
    public static final String SIGNATURE_ORDER_MESSAGE =
        "Signature received date cannot be before the signature requested date.";

    private CaseState() {
    }

    /** Derives the state from the populated dates. Pure; no I/O. */
    public static DeedPollState deriveState(CaseStateInput input) {
        if (input.dateReturned() != null) {
            return DeedPollState.Returned;
        }
        if (input.dateEnroled() != null) {
            return DeedPollState.Enroled;
        }
        if (input.signatureReceivedDate() != null) {
            return DeedPollState.AwaitingEnrolment;
        }
        if (input.signatureRequestedDate() != null) {
            return DeedPollState.AwaitingSignature;
        }
        if (input.dateReferred() != null && input.dateReadyForMaster() != null) {
            return DeedPollState.ReferredToMaster;
        }
        if (input.dateReadyForMaster() != null) {
            return DeedPollState.ReadyForMaster;
        }
        if (input.awaitingFurtherDocsInfo() != null) {
            return DeedPollState.AwaitingFurtherDocs;
        }
        return DeedPollState.Received;
    }

    /**
     * Whether the record carries both an enrolled and a returned date.
     *
     * <p>The source raises a warning (not a hard error) for this pair -- mutually exclusive terminal
     * outcomes. It is surfaced so the write path and the UI can show the same contradiction warning.
     */
    public static boolean hasContradiction(CaseStateInput input) {
        return input.dateReturned() != null && input.dateEnroled() != null;
    }

    /**
     * Cross-field date rules, checked against a FULLY-MERGED record.
     *
     * <p>Both rules are about a <em>pair</em> of dates, so neither can be checked while looking at one
     * field: a patch setting only {@code dateReturned} is fine or fatal depending on a
     * {@code dateEnroled} it never mentions. Callers must therefore pass existing-overlaid-with-patch,
     * the same input {@link #deriveState} gets.
     *
     * <p>Empty when the record is consistent. Errors are addressed to a field so the form can render
     * them in place.
     */
    public static List<FieldError> collectDateRuleErrors(CaseStateInput input) {
        List<FieldError> errors = new ArrayList<>();

        // Ported verbatim from the first branch of SetStatesbasedonDatescompleted: "Show the error
        // message … against field State". The cascade's own else-if chain silently prefers Returned
        // over Enroled, which is why this branch exists in the source at all -- without it a
        // contradictory record gets a state that looks decided.
        if (hasContradiction(input)) {
            errors.add(new FieldError("state", CONTRADICTION_MESSAGE));
        }

        // NOT a port -- the source has no such rule, and accepts a signature received before it was
        // requested, deriving Awaiting Enrolment from it. A case cannot be signed before the pack
        // asking for the signature went out, so the pair is evidence of a mistyped year in one of the
        // two boxes. Only checked when both are present: a received date with no requested date is a
        // different (and permitted) gap, and the cascade already handles it.
        if (input.signatureRequestedDate() != null
            && input.signatureReceivedDate() != null
            && input.signatureReceivedDate().isBefore(input.signatureRequestedDate())) {
            errors.add(new FieldError("signatureReceivedDate", SIGNATURE_ORDER_MESSAGE));
        }

        return List.copyOf(errors);
    }
}
