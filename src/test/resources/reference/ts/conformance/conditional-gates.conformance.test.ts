/**
 * Layer 2c — conditional-gate conformance.
 *
 * Encodes the three field-level conditional rules from deedpoll/business_rules.md:
 *   - "Show and mandate Urgency Reason" (urgencyreason required when Urgent)
 *   - "Show Online Application reference and mandate" (required when Online)
 *   - "Show or hide Date Notice Sent to London Gazette" (hide + CLEAR the date
 *      when Publish to London Gazette = No) — GENERAL case, gap #2. The source
 *      applied this to every type; the port only handled it inside the
 *      relinquishment branch. These cases fail until the general gate is added.
 */
import { DeedPollType, PostCounter, Urgency } from "@hmcts/postgres-prisma";
import { describe, expect, it } from "vitest";
import { applyGazetteGate, applyMinorGate, getFieldRules, type ValidationInput, validate } from "../deed-poll/deed-poll-validation.js";

describe("Conditional-gate conformance (deedpoll business_rules.md)", () => {
  const hasError = (errors: { field: string }[], field: string) => errors.some((e) => e.field === field);

  describe("Urgency Reason gate", () => {
    it("requires urgencyreason when urgency = Urgent and it is empty", () => {
      const errors = validate({
        type: DeedPollType.Adult,
        urgency: Urgency.Urgent,
        urgencyreason: ""
      } as ValidationInput);
      expect(hasError(errors, "urgencyreason")).toBe(true);
    });

    it("does not require urgencyreason when urgency = Normal", () => {
      const errors = validate({
        type: DeedPollType.Adult,
        urgency: Urgency.Normal
      } as ValidationInput);
      expect(hasError(errors, "urgencyreason")).toBe(false);
    });
  });

  describe("Online Application Reference gate", () => {
    it("requires onlineApplicationReference when postCounter = Online", () => {
      const errors = validate({
        type: DeedPollType.Adult,
        postCounter: PostCounter.Online,
        onlineApplicationReference: ""
      } as ValidationInput);
      expect(hasError(errors, "onlineApplicationReference")).toBe(true);
    });

    it("does not require it for Post/Counter", () => {
      const errors = validate({
        type: DeedPollType.Adult,
        postCounter: PostCounter.Post
      } as ValidationInput);
      expect(hasError(errors, "onlineApplicationReference")).toBe(false);
    });
  });

  describe("Gazette date gate — GENERAL, all types (gap #2)", () => {
    it("hides dateNoticeSentToLondonGazette when publish = No for a normal Adult case", () => {
      const rules = getFieldRules(DeedPollType.Adult, false, false);
      expect(rules.dateNoticeSentToLondonGazette.visible).toBe(false);
    });

    it("shows dateNoticeSentToLondonGazette when publish = Yes", () => {
      const rules = getFieldRules(DeedPollType.Adult, false, true);
      expect(rules.dateNoticeSentToLondonGazette.visible).toBe(true);
    });

    it("clears dateNoticeSentToLondonGazette on write when publish = No", () => {
      const patch = applyGazetteGate({
        publishToLondonGazette: false,
        dateNoticeSentToLondonGazette: new Date("2026-01-01")
      });
      expect(patch.dateNoticeSentToLondonGazette).toBeNull();
    });

    it("leaves the date untouched when publish = Yes", () => {
      const patch = applyGazetteGate({
        publishToLondonGazette: true,
        dateNoticeSentToLondonGazette: new Date("2026-01-01")
      });
      expect(patch.dateNoticeSentToLondonGazette).toBeUndefined();
    });
  });

  describe("Minor fields gate — hide AND clear", () => {
    // "Show hide and mandate fields for Minors": the source's else branch has four
    // Clear steps (Age of Minor Years, Age of Minor Months, Date of Birth, Parents)
    // alongside its four Hide steps. The port hid without clearing, so a Minor case
    // converted to Adult kept orphaned minor data in the row.
    const MINOR_FIELDS = ["minorDateOfBirth", "ageOfMinor", "ageOfMinorMonths", "parents"] as const;

    it.each([DeedPollType.Minor, DeedPollType.MinorTransgender])("hides nothing and clears nothing for %s", (type) => {
      const rules = getFieldRules(type);
      for (const f of MINOR_FIELDS) expect(rules[f].visible).toBe(true);
      expect(applyMinorGate({ type })).toEqual({});
    });

    it.each([DeedPollType.Adult, DeedPollType.AdultTransgender, DeedPollType.DeedOfRelinquishment])("hides and clears all four minor fields for %s", (type) => {
      const rules = getFieldRules(type);
      for (const f of MINOR_FIELDS) expect(rules[f].visible).toBe(false);

      const patch = applyMinorGate({ type });
      for (const f of MINOR_FIELDS) expect(patch[f]).toBeNull();
    });

    it("clears every one of the four, not just the three the port knew about", () => {
      // minorDateOfBirth was the omission: it stayed visible on an Adult case and
      // rendered as a stranded row. Asserted by name so a partial list fails here.
      expect(applyMinorGate({ type: DeedPollType.Adult })).toEqual({
        minorDateOfBirth: null,
        ageOfMinor: null,
        ageOfMinorMonths: null,
        parents: null
      });
    });

    it("clears nothing when no type is resolved", () => {
      // A patch that does not settle the type must not be read as "not a minor".
      // Unknown is the source's untouched type, so it clears nothing either.
      expect(applyMinorGate({})).toEqual({});
      expect(applyMinorGate({ type: null })).toEqual({});
      expect(applyMinorGate({ type: DeedPollType.Unknown })).toEqual({});
    });
  });
});
