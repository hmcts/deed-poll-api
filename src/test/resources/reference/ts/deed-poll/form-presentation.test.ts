import { DeedPollState, DeedPollType, Privacy, ReferToMaster } from "@hmcts/postgres-prisma";
import { describe, expect, it } from "vitest";
import { build, defaultPrivacy, type PresentationContext, privacyEditable, tabVisibility } from "./form-presentation.js";

const ADMIN_NEW: PresentationContext = { isAdmin: true, isNew: true };
const ADMIN_EXISTING: PresentationContext = { isAdmin: true, isNew: false };
const USER_NEW: PresentationContext = { isAdmin: false, isNew: true };
const USER_EXISTING: PresentationContext = { isAdmin: false, isNew: false };

describe("form-presentation", () => {
  describe("defaultPrivacy — create matrix", () => {
    it("admin + Minor Transgender (new) → Private", () => {
      expect(defaultPrivacy(DeedPollType.MinorTransgender, null, ADMIN_NEW)).toBe(Privacy.Private);
    });

    it("admin + non-MinorTG (new) leaves the current value unchanged", () => {
      expect(defaultPrivacy(DeedPollType.Adult, Privacy.Private, ADMIN_NEW)).toBe(Privacy.Private);
      expect(defaultPrivacy(DeedPollType.Adult, null, ADMIN_NEW)).toBe(Privacy.Normal);
    });

    it("non-admin + Adult/AdultTG/Minor (new) → Normal", () => {
      for (const t of [DeedPollType.Adult, DeedPollType.AdultTransgender, DeedPollType.Minor]) {
        expect(defaultPrivacy(t, null, USER_NEW)).toBe(Privacy.Normal);
      }
    });

    it("non-admin + Minor Transgender (new) → Private", () => {
      expect(defaultPrivacy(DeedPollType.MinorTransgender, null, USER_NEW)).toBe(Privacy.Private);
    });

    it("non-admin + Deed of Relinquishment (new) → Normal", () => {
      expect(defaultPrivacy(DeedPollType.DeedOfRelinquishment, null, USER_NEW)).toBe(Privacy.Normal);
    });

    it("Unknown / null type (new) is left unchanged", () => {
      expect(defaultPrivacy(DeedPollType.Unknown, Privacy.Private, USER_NEW)).toBe(Privacy.Private);
      expect(defaultPrivacy(null, Privacy.Private, ADMIN_NEW)).toBe(Privacy.Private);
    });

    it("existing records are never auto-set (value preserved)", () => {
      expect(defaultPrivacy(DeedPollType.MinorTransgender, Privacy.Normal, USER_EXISTING)).toBe(Privacy.Normal);
      expect(defaultPrivacy(DeedPollType.MinorTransgender, Privacy.Normal, ADMIN_EXISTING)).toBe(Privacy.Normal);
    });
  });

  describe("privacyEditable", () => {
    it("admin can always edit", () => {
      expect(privacyEditable(DeedPollType.Adult, ADMIN_NEW)).toBe(true);
      expect(privacyEditable(DeedPollType.Adult, ADMIN_EXISTING)).toBe(true);
    });

    it("non-admin + new + Relinquishment can edit", () => {
      expect(privacyEditable(DeedPollType.DeedOfRelinquishment, USER_NEW)).toBe(true);
    });

    it("non-admin + new + other type is locked", () => {
      expect(privacyEditable(DeedPollType.Adult, USER_NEW)).toBe(false);
      expect(privacyEditable(DeedPollType.MinorTransgender, USER_NEW)).toBe(false);
    });

    it("non-admin + existing is locked", () => {
      expect(privacyEditable(DeedPollType.DeedOfRelinquishment, USER_EXISTING)).toBe(false);
    });
  });

  describe("tabVisibility", () => {
    it("hides Referral tab when refer-to-master is No", () => {
      expect(tabVisibility({ referToMasterChoice: ReferToMaster.No }).referralToMaster).toBe(false);
    });

    it("shows Referral tab for Yes or unset", () => {
      expect(tabVisibility({ referToMasterChoice: ReferToMaster.Yes }).referralToMaster).toBe(true);
      expect(tabVisibility({}).referralToMaster).toBe(true);
    });

    it("Enroled state shows Enrolment, hides Return", () => {
      const t = tabVisibility({ state: DeedPollState.Enroled });
      expect(t.enrolment).toBe(true);
      expect(t.return).toBe(false);
    });

    it("Returned state hides Enrolment, shows Return", () => {
      const t = tabVisibility({ state: DeedPollState.Returned });
      expect(t.enrolment).toBe(false);
      expect(t.return).toBe(true);
    });

    it("any other state shows both Enrolment and Return", () => {
      for (const s of [DeedPollState.Received, DeedPollState.AwaitingSignature, DeedPollState.ReferredToMaster]) {
        const t = tabVisibility({ state: s });
        expect(t.enrolment).toBe(true);
        expect(t.return).toBe(true);
      }
    });
  });

  describe("build — full model", () => {
    it("shows the PRIVATE banner when effective privacy is Private", () => {
      const model = build({ type: DeedPollType.MinorTransgender }, USER_NEW);
      expect(model.privacy.value).toBe(Privacy.Private);
      expect(model.privacy.showBanner).toBe(true);
      expect(model.privacy.bannerText).toBe("This record is PRIVATE!");
      expect(model.privacy.editable).toBe(false);
    });

    it("no banner for a Normal record and no banner text", () => {
      const model = build({ type: DeedPollType.Adult, privacy: Privacy.Normal }, USER_NEW);
      expect(model.privacy.showBanner).toBe(false);
      expect(model.privacy.bannerText).toBeUndefined();
    });
  });
});
