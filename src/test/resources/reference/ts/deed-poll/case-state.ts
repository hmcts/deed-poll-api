import { DeedPollState } from "@hmcts/postgres-prisma";

/** The subset of date fields the state cascade reads. */
export interface CaseStateInput {
  dateReturned?: Date | null;
  dateEnroled?: Date | null;
  signatureReceivedDate?: Date | null;
  signatureRequestedDate?: Date | null;
  dateReferred?: Date | null;
  dateReadyForMaster?: Date | null;
  awaitingFurtherDocsInfo?: Date | null;
  dateReceived?: Date | null;
}

/** A cross-field date violation, addressed to the field it renders against. */
export interface DateRuleError {
  field: string;
  message: string;
}

const isSet = (d: Date | null | undefined): boolean => d instanceof Date && !Number.isNaN(d.getTime());

/**
 * CaseStateService — ports `SetStatesbasedonDatescompleted.xaml`.
 *
 * The Dynamics workflow derives `state` from which date fields are populated,
 * as a top-down priority cascade (an if/else-if chain). The FIRST satisfied
 * branch wins, so the *order* below is significant and mirrors the XAML exactly:
 *
 *   1. dateReturned            → Returned (5)
 *   2. dateEnroled             → Enroled (4)
 *   3. signatureReceivedDate   → Awaiting Enrolment (8)
 *   4. signatureRequestedDate  → Awaiting Signature (7)
 *   5. dateReferred AND dateReadyForMaster → Referred to Master (3)
 *   6. dateReadyForMaster      → Ready for Master (2)
 *   7. awaitingFurtherDocsInfo → Awaiting Docs/Info (6)
 *   8. (default)               → Received (1)
 *
 * Note: the source evaluates Awaiting Enrolment (signature received) *before*
 * the referral check, so a signed case reads Awaiting Enrolment even if a
 * referral date is also present. That is the source behaviour, preserved here.
 */
/** Derive the state from populated dates (pure; no I/O). */
export function deriveState(input: CaseStateInput): DeedPollState {
  if (isSet(input.dateReturned)) return DeedPollState.Returned;
  if (isSet(input.dateEnroled)) return DeedPollState.Enroled;
  if (isSet(input.signatureReceivedDate)) return DeedPollState.AwaitingEnrolment;
  if (isSet(input.signatureRequestedDate)) return DeedPollState.AwaitingSignature;
  if (isSet(input.dateReferred) && isSet(input.dateReadyForMaster)) return DeedPollState.ReferredToMaster;
  if (isSet(input.dateReadyForMaster)) return DeedPollState.ReadyForMaster;
  if (isSet(input.awaitingFurtherDocsInfo)) return DeedPollState.AwaitingFurtherDocs;
  return DeedPollState.Received;
}

/**
 * The source raises a warning (not a hard error) when a record has both an
 * enrolled and a returned date — mutually exclusive terminal outcomes. We
 * surface it so the write path / UI can show the same contradiction warning.
 */
export function hasContradiction(input: CaseStateInput): boolean {
  return isSet(input.dateReturned) && isSet(input.dateEnroled);
}

/**
 * Cross-field date rules, checked against a FULLY-MERGED record.
 *
 * Both rules are about a *pair* of dates, so neither can be checked while
 * looking at one field: a patch setting only `dateReturned` is fine or fatal
 * depending on a `dateEnroled` it never mentions. Callers must therefore pass
 * existing-overlaid-with-patch, the same input `deriveState` gets.
 *
 * Empty when the record is consistent. Errors are addressed to a field so the
 * form can render them in place.
 */
export function collectDateRuleErrors(input: CaseStateInput): DateRuleError[] {
  const errors: DateRuleError[] = [];

  // Ported verbatim from the first branch of SetStatesbasedonDatescompleted:
  // "Show the error message … against field State". The cascade's own else-if
  // chain silently prefers Returned over Enroled, which is why this branch
  // exists in the source at all — without it a contradictory record gets a
  // state that looks decided.
  if (hasContradiction(input)) {
    errors.push({
      field: "state",
      message: "Date Enroled and Date Returned both contain a date. Please remove one " + "depending on whether the Deed Poll is to be enroled or returned."
    });
  }

  // NOT a port — the source has no such rule, and accepts a signature received
  // before it was requested, deriving Awaiting Enrolment from it. A case cannot
  // be signed before the pack asking for the signature went out, so the pair is
  // evidence of a mistyped year in one of the two boxes. Only checked when both
  // are present: a received date with no requested date is a different (and
  // permitted) gap, and the cascade already handles it.
  if (
    isSet(input.signatureRequestedDate) &&
    isSet(input.signatureReceivedDate) &&
    (input.signatureReceivedDate as Date).getTime() < (input.signatureRequestedDate as Date).getTime()
  ) {
    errors.push({
      field: "signatureReceivedDate",
      message: "Signature received date cannot be before the signature requested date."
    });
  }

  return errors;
}
