import { DeedPollState } from "@hmcts/postgres-prisma";
import { describe, expect, it } from "vitest";
import { type CaseStateInput, collectDateRuleErrors, deriveState, hasContradiction } from "./case-state.js";

const D = (iso: string) => new Date(iso);
const SOME = D("2026-01-01T00:00:00.000Z");

describe("case-state", () => {
  describe("deriveState — one date at a time", () => {
    const cases: Array<[keyof CaseStateInput, DeedPollState]> = [
      ["dateReceived", DeedPollState.Received],
      ["awaitingFurtherDocsInfo", DeedPollState.AwaitingFurtherDocs],
      ["dateReadyForMaster", DeedPollState.ReadyForMaster],
      ["signatureRequestedDate", DeedPollState.AwaitingSignature],
      ["signatureReceivedDate", DeedPollState.AwaitingEnrolment],
      ["dateEnroled", DeedPollState.Enroled],
      ["dateReturned", DeedPollState.Returned]
    ];

    it.each(cases)("%s set → %s", (field, expected) => {
      expect(deriveState({ [field]: SOME })).toBe(expected);
    });
  });

  it("empty input defaults to Received", () => {
    expect(deriveState({})).toBe(DeedPollState.Received);
  });

  describe("deriveState — precedence cascade (first match wins)", () => {
    it("Returned beats every earlier date", () => {
      expect(
        deriveState({
          dateReturned: SOME,
          dateEnroled: SOME,
          signatureReceivedDate: SOME,
          dateReadyForMaster: SOME,
          dateReceived: SOME
        })
      ).toBe(DeedPollState.Returned);
    });

    it("Enroled beats signature / master / received", () => {
      expect(
        deriveState({
          dateEnroled: SOME,
          signatureReceivedDate: SOME,
          signatureRequestedDate: SOME,
          dateReadyForMaster: SOME
        })
      ).toBe(DeedPollState.Enroled);
    });

    it("Awaiting Enrolment (signature received) is evaluated BEFORE referral", () => {
      // Source order: signature-received wins over a referral date.
      expect(
        deriveState({
          signatureReceivedDate: SOME,
          dateReferred: SOME,
          dateReadyForMaster: SOME
        })
      ).toBe(DeedPollState.AwaitingEnrolment);
    });

    it("Awaiting Signature beats referral / ready-for-master", () => {
      expect(
        deriveState({
          signatureRequestedDate: SOME,
          dateReferred: SOME,
          dateReadyForMaster: SOME
        })
      ).toBe(DeedPollState.AwaitingSignature);
    });

    it("Referred to Master requires BOTH dateReferred and dateReadyForMaster", () => {
      expect(deriveState({ dateReferred: SOME, dateReadyForMaster: SOME })).toBe(DeedPollState.ReferredToMaster);
    });

    it("dateReferred alone (no ready-for-master) does NOT reach Referred; falls through to Received", () => {
      expect(deriveState({ dateReferred: SOME })).toBe(DeedPollState.Received);
    });

    it("dateReadyForMaster alone → Ready for Master", () => {
      expect(deriveState({ dateReadyForMaster: SOME })).toBe(DeedPollState.ReadyForMaster);
    });

    it("Ready for Master beats awaiting-docs and received", () => {
      expect(
        deriveState({
          dateReadyForMaster: SOME,
          awaitingFurtherDocsInfo: SOME,
          dateReceived: SOME
        })
      ).toBe(DeedPollState.ReadyForMaster);
    });
  });

  describe("invalid dates are treated as unset", () => {
    it("an Invalid Date does not trigger its branch", () => {
      expect(
        deriveState({
          dateReturned: new Date("nonsense"),
          dateReceived: SOME
        })
      ).toBe(DeedPollState.Received);
    });
  });

  describe("hasContradiction", () => {
    it("flags both enrolled and returned set", () => {
      expect(hasContradiction({ dateEnroled: SOME, dateReturned: SOME })).toBe(true);
    });

    it("is false when only one (or neither) terminal date is set", () => {
      expect(hasContradiction({ dateEnroled: SOME })).toBe(false);
      expect(hasContradiction({ dateReturned: SOME })).toBe(false);
      expect(hasContradiction({})).toBe(false);
    });
  });

  describe("collectDateRuleErrors", () => {
    const EARLY = new Date("2026-08-03T00:00:00.000Z");
    const LATE = new Date("2026-08-15T00:00:00.000Z");

    it("is empty for a consistent record", () => {
      expect(
        collectDateRuleErrors({
          signatureRequestedDate: EARLY,
          signatureReceivedDate: LATE
        })
      ).toEqual([]);
    });

    it("ports the Enroled + Returned contradiction, against field state", () => {
      // business_rules.md, "Set States based on Dates completed", first branch. The
      // cascade below it silently prefers Returned, so without this the record gets a
      // state that reads as decided when it is actually contradictory.
      const errors = collectDateRuleErrors({
        dateEnroled: EARLY,
        dateReturned: LATE
      });
      expect(errors).toHaveLength(1);
      expect(errors[0].field).toBe("state");
      expect(errors[0].message).toBe(
        "Date Enroled and Date Returned both contain a date. Please remove one " + "depending on whether the Deed Poll is to be enroled or returned."
      );
    });

    it("rejects a signature received before it was requested", () => {
      const errors = collectDateRuleErrors({
        signatureRequestedDate: LATE,
        signatureReceivedDate: EARLY
      });
      expect(errors).toHaveLength(1);
      expect(errors[0].field).toBe("signatureReceivedDate");
      expect(errors[0].message).toContain("cannot be before");
    });

    it("allows the two signature dates on the same day", () => {
      // A pack issued and signed the same day is ordinary, so the comparison must be
      // strict: same-day is not out of order.
      expect(
        collectDateRuleErrors({
          signatureRequestedDate: EARLY,
          signatureReceivedDate: new Date(EARLY)
        })
      ).toEqual([]);
    });

    it("does not complain about a received date with no requested date", () => {
      // A different, permitted gap — the cascade derives Awaiting Enrolment from it
      // and the source never required the requested date to exist.
      expect(collectDateRuleErrors({ signatureReceivedDate: EARLY })).toEqual([]);
    });

    it("does not complain about a requested date with no received date", () => {
      expect(collectDateRuleErrors({ signatureRequestedDate: EARLY })).toEqual([]);
    });

    it("reports both rules at once", () => {
      const errors = collectDateRuleErrors({
        dateEnroled: EARLY,
        dateReturned: LATE,
        signatureRequestedDate: LATE,
        signatureReceivedDate: EARLY
      });
      expect(errors.map((e) => e.field)).toEqual(["state", "signatureReceivedDate"]);
    });

    it("ignores an Invalid Date rather than treating it as set", () => {
      // Consistent with deriveState, which does not fire a branch on an unparseable
      // date. A garbage value is caught by the form's date-part check, not here.
      expect(
        collectDateRuleErrors({
          signatureRequestedDate: LATE,
          signatureReceivedDate: new Date("nonsense")
        })
      ).toEqual([]);
    });
  });
});
