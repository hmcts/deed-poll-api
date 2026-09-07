/**
 * Layer 2b — field-rules conformance.
 *
 * Encodes the "Make fields required or non-required depending on Type and
 * Migrated Record" rule from deedpoll/business_rules.md, plus the "Minors" and
 * "Deed of Relinquishment" show/hide rules, as assertions over
 * .getFieldRules().
 *
 * The rule keys required-ness on BOTH Type and Migrated Record: when Migrated
 * Record = Yes, the whole per-type required set relaxes to optional. That is
 * gap #1 — getFieldRules() currently ignores migratedRecord — so the migrated
 * cases below fail until the service is extended to take it.
 */
import { DeedPollType } from "@hmcts/postgres-prisma";
import { describe, expect, it } from "vitest";
import { getFieldRules } from "../deed-poll/deed-poll-validation.js";

describe("Field-rules conformance (deedpoll business_rules.md)", () => {
  describe("new (non-migrated) records — per-type required set", () => {
    it("Adult requires inPerson, maritalStatus, urgency, nationality, section", () => {
      const r = getFieldRules(DeedPollType.Adult, false);
      for (const f of ["inPerson", "maritalStatus", "urgency", "nationality", "nationalitySection"]) {
        expect(r[f].required).toBe(true);
      }
    });

    it("Minor requires the Adult set plus age (years+months) and parents", () => {
      const r = getFieldRules(DeedPollType.Minor, false);
      for (const f of ["inPerson", "maritalStatus", "urgency", "nationality", "nationalitySection", "ageOfMinor", "ageOfMinorMonths", "parents"]) {
        expect(r[f].required).toBe(true);
      }
    });

    it("Deed of Relinquishment requires only inPerson + urgency", () => {
      const r = getFieldRules(DeedPollType.DeedOfRelinquishment, false);
      expect(r.inPerson.required).toBe(true);
      expect(r.urgency.required).toBe(true);
      expect(r.maritalStatus.required).toBe(false);
      expect(r.nationality.required).toBe(false);
    });
  });

  describe("migrated records — every per-type required field relaxes to optional (gap #1)", () => {
    const relaxable = ["inPerson", "maritalStatus", "ageOfMinor", "ageOfMinorMonths", "parents", "urgency", "nationality", "nationalitySection"];

    for (const type of [DeedPollType.Adult, DeedPollType.Minor, DeedPollType.DeedOfRelinquishment]) {
      it(`${type}: no field is required when Migrated Record = Yes`, () => {
        const r = getFieldRules(type, true);
        for (const f of relaxable) {
          expect(r[f].required).toBe(false);
        }
      });
    }
  });

  describe("show/hide rules are unaffected by Migrated Record", () => {
    it("minor fields stay hidden for Adult even when migrated", () => {
      const r = getFieldRules(DeedPollType.Adult, true);
      expect(r.ageOfMinor.visible).toBe(false);
      expect(r.parents.visible).toBe(false);
    });

    it("relinquishment hides old-name/marital/nationality/section/gazette even when migrated", () => {
      const r = getFieldRules(DeedPollType.DeedOfRelinquishment, true);
      for (const f of ["oldForename", "oldMiddleName", "oldSurname", "maritalStatus", "nationality", "nationalitySection", "dateNoticeSentToLondonGazette"]) {
        expect(r[f].visible).toBe(false);
      }
    });
  });
});
