/**
 * Layer 2a — state-cascade conformance.
 *
 * Encodes the "Set States based on Dates completed" rule from
 * `.business_context/dataverse_tables/deedpoll/business_rules.md` as a truth
 * table over . The rule is a top-down IF/ELSE-IF cascade; the
 * FIRST satisfied branch wins, so the cases assert ORDER, not just membership —
 * including the documented quirk that Awaiting Enrolment (signature received)
 * outranks the referral check.
 */
import { DeedPollState } from "@hmcts/postgres-prisma";
import { describe, expect, it } from "vitest";
import { type CaseStateInput, collectDateRuleErrors, deriveState, hasContradiction } from "../deed-poll/case-state.js";

const D = new Date("2026-01-01");

describe("State cascade conformance (deedpoll business_rules.md)", () => {
  // [label, populated dates, expected state] — mirrors the rule's branch order.
  const cases: [string, CaseStateInput, DeedPollState][] = [
    ["Date Returned → Returned", { dateReturned: D }, DeedPollState.Returned],
    ["Date Enroled → Enroled", { dateEnroled: D }, DeedPollState.Enroled],
    ["Signature Received → Awaiting Enrolment", { signatureReceivedDate: D }, DeedPollState.AwaitingEnrolment],
    ["Signature Requested → Awaiting Signature", { signatureRequestedDate: D }, DeedPollState.AwaitingSignature],
    ["Referred AND Ready for Master → Referred to Master", { dateReferred: D, dateReadyForMaster: D }, DeedPollState.ReferredToMaster],
    ["Ready for Master alone → Ready for Master", { dateReadyForMaster: D }, DeedPollState.ReadyForMaster],
    ["Referred WITHOUT Ready for Master → falls through to Received", { dateReferred: D }, DeedPollState.Received],
    ["Awaiting further docs → Awaiting Further Docs", { awaitingFurtherDocsInfo: D }, DeedPollState.AwaitingFurtherDocs],
    ["No dates → Received", {}, DeedPollState.Received]
  ];

  for (const [label, input, expected] of cases) {
    it(label, () => {
      expect(deriveState(input)).toBe(expected);
    });
  }

  describe("branch ORDER (first satisfied wins)", () => {
    it("Returned outranks Enroled when both dates present", () => {
      expect(deriveState({ dateReturned: D, dateEnroled: D })).toBe(DeedPollState.Returned);
    });

    it("Enroled outranks Signature Received", () => {
      expect(deriveState({ dateEnroled: D, signatureReceivedDate: D })).toBe(DeedPollState.Enroled);
    });

    it("Signature Received outranks a present referral (documented quirk)", () => {
      expect(
        deriveState({
          signatureReceivedDate: D,
          dateReferred: D,
          dateReadyForMaster: D
        })
      ).toBe(DeedPollState.AwaitingEnrolment);
    });
  });

  describe("contradiction warning", () => {
    it("flags Returned AND Enroled both present", () => {
      expect(hasContradiction({ dateReturned: D, dateEnroled: D })).toBe(true);
    });

    it("does not flag a single terminal date", () => {
      expect(hasContradiction({ dateReturned: D })).toBe(false);
    });

    /**
     * The FIRST branch of the source rule, which was detected but never surfaced until
     * 2026-08-10: `hasContradiction()` existed with no caller, so `deriveState()`'s
     * else-if chain silently returned Returned and the record read as decided.
     *
     * The message is asserted verbatim against business_rules.md — a caseworker moving
     * between the two apps should be told the same thing in the same words.
     */
    it("emits the source error message, against field State", () => {
      const errors = collectDateRuleErrors({
        dateReturned: D,
        dateEnroled: D
      });
      expect(errors).toEqual([
        {
          field: "state",
          message: "Date Enroled and Date Returned both contain a date. Please remove one " + "depending on whether the Deed Poll is to be enroled or returned."
        }
      ]);
    });
  });

  /**
   * NOT a port. The source has no ordering rule on the signature pair: it accepts a
   * received date earlier than the requested date and derives Awaiting Enrolment from
   * it, so a mistyped year advances the case. Recorded here as an intentional
   * divergence so a future reader does not "correct" it back to source behaviour.
   */
  describe("signature date order (addition, not a port)", () => {
    const EARLY = new Date("2026-08-03T00:00:00.000Z");
    const LATE = new Date("2026-08-15T00:00:00.000Z");

    it("rejects received-before-requested, which the source allows", () => {
      const errors = collectDateRuleErrors({
        signatureRequestedDate: LATE,
        signatureReceivedDate: EARLY
      });
      expect(errors).toHaveLength(1);
      expect(errors[0].field).toBe("signatureReceivedDate");
    });

    it("still derives the source state for the in-order pair", () => {
      expect(
        deriveState({
          signatureRequestedDate: EARLY,
          signatureReceivedDate: LATE
        })
      ).toBe(DeedPollState.AwaitingEnrolment);
    });
  });

  describe("invalid dates are treated as unset", () => {
    it("an unparseable date does not satisfy its branch", () => {
      expect(deriveState({ dateReturned: new Date("nonsense") })).toBe(DeedPollState.Received);
    });
  });
});
