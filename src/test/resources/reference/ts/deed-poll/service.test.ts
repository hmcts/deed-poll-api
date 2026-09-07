import { prisma as prismaClient } from "@hmcts/postgres-prisma";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { InvalidInputError, NotFoundError } from "../errors.js";
import { createDeedPoll, findDeedPoll, removeDeedPoll, updateDeedPoll } from "./service.js";

// Only the database is mocked. The prototype registered ReferenceNumberService,
// CaseStateService, DeedPollValidationService and FormPresentationService as real DI
// providers; they are plain function modules now and are imported directly by service.ts,
// so the derived logic under test is the real thing, exactly as before.
vi.mock("@hmcts/postgres-prisma", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@hmcts/postgres-prisma")>();
  const client: Record<string, unknown> = {
    deedPoll: {
      create: vi.fn(),
      findMany: vi.fn(),
      findUnique: vi.fn(),
      update: vi.fn(),
      delete: vi.fn()
    },
    referenceCounter: { upsert: vi.fn() }
  };
  // Run the callback against the same mock (single-connection stand-in for a tx).
  client.$transaction = vi.fn((cb: (tx: unknown) => unknown) => cb(client));
  return { ...actual, prisma: client };
});

const prisma = vi.mocked(prismaClient, { deep: true });

describe("deed-poll/service", () => {
  /** createDeedPoll() cannot allocate a reference without a Date Received to derive the year. */
  const RECEIVED = "2026-07-01T00:00:00.000Z";

  beforeEach(() => {
    vi.clearAllMocks();
    // Default the reference counter, as the DI provider did at construction time.
    prisma.referenceCounter.upsert.mockResolvedValue({ year: 2026, lastSerial: 1 });
  });

  it("coerces ISO date strings to Date on create", async () => {
    prisma.deedPoll.create.mockResolvedValue({ id: "x" });
    await createDeedPoll({ dateReceived: "2026-07-31T00:00:00.000Z" });
    const arg = prisma.deedPoll.create.mock.calls[0][0].data;
    expect(arg.dateReceived).toBeInstanceOf(Date);
  });

  it("allocates a reference number on create", async () => {
    prisma.deedPoll.create.mockResolvedValue({ id: "x" });
    await createDeedPoll({
      type: "Adult",
      dateReceived: "2026-07-31T00:00:00.000Z"
    });
    const arg = prisma.deedPoll.create.mock.calls[0][0].data;
    expect(arg.referencenumber).toBe(1);
    expect(arg.displayReference).toBe("KBDP2026-00001");
  });

  it("applies create defaults (minor → marital status Single)", async () => {
    prisma.deedPoll.create.mockResolvedValue({ id: "x" });
    await createDeedPoll({
      type: "Minor",
      dateReceived: "2026-07-31T00:00:00.000Z"
    });
    const arg = prisma.deedPoll.create.mock.calls[0][0].data;
    expect(arg.maritalStatus).toBe("Single");
  });

  it("clears the Gazette notice date on create when publish = No", async () => {
    prisma.deedPoll.create.mockResolvedValue({ id: "x" });
    await createDeedPoll({
      type: "Adult",
      dateReceived: "2026-07-31T00:00:00.000Z",
      publishToLondonGazette: false,
      dateNoticeSentToLondonGazette: "2026-08-01T00:00:00.000Z"
    });
    const arg = prisma.deedPoll.create.mock.calls[0][0].data;
    expect(arg.dateNoticeSentToLondonGazette).toBeNull();
  });

  it("clears an existing Gazette notice date on update when publish flips to No", async () => {
    prisma.deedPoll.findUnique.mockResolvedValue({
      id: "x",
      displayReference: "KBDP2026-00001",
      dateNoticeSentToLondonGazette: new Date("2026-08-01T00:00:00.000Z")
    });
    prisma.deedPoll.update.mockResolvedValue({ id: "x" });
    await updateDeedPoll("x", { publishToLondonGazette: false });
    const arg = prisma.deedPoll.update.mock.calls[0][0].data;
    expect(arg.dateNoticeSentToLondonGazette).toBeNull();
  });

  it("clears minor fields on create for a non-minor type", async () => {
    prisma.deedPoll.create.mockResolvedValue({ id: "x" });
    await createDeedPoll({
      type: "Adult",
      dateReceived: "2026-07-31T00:00:00.000Z",
      minorDateOfBirth: "2017-03-10T00:00:00.000Z",
      ageOfMinor: 9,
      ageOfMinorMonths: 4,
      parents: "A Okafor"
    });
    const arg = prisma.deedPoll.create.mock.calls[0][0].data;
    expect(arg.minorDateOfBirth).toBeNull();
    expect(arg.ageOfMinor).toBeNull();
    expect(arg.ageOfMinorMonths).toBeNull();
    expect(arg.parents).toBeNull();
  });

  it("keeps minor fields on create for a minor type", async () => {
    prisma.deedPoll.create.mockResolvedValue({ id: "x" });
    await createDeedPoll({
      type: "Minor",
      dateReceived: "2026-07-31T00:00:00.000Z",
      ageOfMinor: 9,
      parents: "A Okafor"
    });
    const arg = prisma.deedPoll.create.mock.calls[0][0].data;
    expect(arg.ageOfMinor).toBe(9);
    expect(arg.parents).toBe("A Okafor");
  });

  it("clears stored minor fields when a case is converted Minor → Adult", async () => {
    // The reason this gate exists: without it the row keeps minor data that no
    // page will ever show again.
    prisma.deedPoll.findUnique.mockResolvedValue({
      id: "x",
      displayReference: "KBDP2026-00001",
      type: "Minor",
      minorDateOfBirth: new Date("2017-03-10T00:00:00.000Z"),
      ageOfMinor: 9,
      ageOfMinorMonths: 4,
      parents: "A Okafor"
    });
    prisma.deedPoll.update.mockResolvedValue({ id: "x" });
    await updateDeedPoll("x", { type: "Adult" });
    const arg = prisma.deedPoll.update.mock.calls[0][0].data;
    expect(arg.minorDateOfBirth).toBeNull();
    expect(arg.ageOfMinor).toBeNull();
    expect(arg.ageOfMinorMonths).toBeNull();
    expect(arg.parents).toBeNull();
  });

  it("does not clear minor fields on a patch that leaves the type alone", async () => {
    // The gate reads the MERGED type, so a patch touching only `parents` on a Minor
    // case must not wipe the value it just set.
    prisma.deedPoll.findUnique.mockResolvedValue({
      id: "x",
      displayReference: "KBDP2026-00001",
      type: "Minor",
      ageOfMinor: 9
    });
    prisma.deedPoll.update.mockResolvedValue({ id: "x" });
    await updateDeedPoll("x", { parents: "B Okafor" });
    const arg = prisma.deedPoll.update.mock.calls[0][0].data;
    expect(arg.parents).toBe("B Okafor");
    expect(arg.ageOfMinor).toBeUndefined();
  });

  it("auto-defaults privacy to Private on create for a Minor Transgender case", async () => {
    prisma.deedPoll.create.mockResolvedValue({ id: "x" });
    await createDeedPoll({
      type: "MinorTransgender",
      dateReceived: "2026-07-31T00:00:00.000Z"
    });
    const arg = prisma.deedPoll.create.mock.calls[0][0].data;
    expect(arg.privacy).toBe("Private");
  });

  it("auto-defaults privacy to Normal on create for a non-admin Adult case", async () => {
    prisma.deedPoll.create.mockResolvedValue({ id: "x" });
    await createDeedPoll({
      type: "Adult",
      dateReceived: "2026-07-31T00:00:00.000Z"
    });
    const arg = prisma.deedPoll.create.mock.calls[0][0].data;
    expect(arg.privacy).toBe("Normal");
  });

  it("derives case state on create (dateReceived → Received)", async () => {
    prisma.deedPoll.create.mockResolvedValue({ id: "x" });
    await createDeedPoll({
      type: "Adult",
      dateReceived: "2026-07-31T00:00:00.000Z"
    });
    const arg = prisma.deedPoll.create.mock.calls[0][0].data;
    expect(arg.state).toBe("Received");
  });

  it("recalculates state on update from merged dates (enrolment → Enroled)", async () => {
    prisma.deedPoll.findUnique.mockResolvedValue({
      id: "x",
      displayReference: "KBDP2026-00001",
      dateReceived: new Date("2026-01-01T00:00:00.000Z")
    });
    prisma.deedPoll.update.mockResolvedValue({ id: "x" });
    await updateDeedPoll("x", {
      dateEnroled: "2026-03-01T00:00:00.000Z"
    });
    const arg = prisma.deedPoll.update.mock.calls[0][0].data;
    expect(arg.state).toBe("Enroled");
  });

  it("throws NotFound when findOne misses", async () => {
    prisma.deedPoll.findUnique.mockResolvedValue(null);
    await expect(findDeedPoll("nope")).rejects.toBeInstanceOf(NotFoundError);
  });

  it("update checks existence first (404 propagates)", async () => {
    prisma.deedPoll.findUnique.mockResolvedValue(null);
    await expect(updateDeedPoll("nope", {})).rejects.toBeInstanceOf(NotFoundError);
    expect(prisma.deedPoll.update).not.toHaveBeenCalled();
  });

  it("re-prefixes the display reference on a DP→DR type flip", async () => {
    prisma.deedPoll.findUnique.mockResolvedValue({
      id: "x",
      displayReference: "KBDP2026-00007"
    });
    prisma.deedPoll.update.mockResolvedValue({ id: "x" });
    await updateDeedPoll("x", { type: "DeedOfRelinquishment" });
    const arg = prisma.deedPoll.update.mock.calls[0][0].data;
    expect(arg.displayReference).toBe("KBDR2026-00007");
  });

  /**
   * Privacy is admin-only on an existing record. Until 2026-08-10 the lock was UI-only:
   * `privacyEditable()` rendered the input `disabled`, but `update()` ignored
   * `ctx.isAdmin`, so a non-admin POSTing `privacy` directly — or PATCHing the API —
   * wrote it. Authorisation belongs on the write path, not the template.
   */
  describe("privacy authorisation on update", () => {
    beforeEach(() => {
      prisma.deedPoll.findUnique.mockResolvedValue({
        id: "x",
        displayReference: "KBDP2026-00001",
        type: "Adult",
        privacy: "Normal"
      });
      prisma.deedPoll.update.mockResolvedValue({ id: "x" });
    });

    it("drops privacy from a non-admin patch", async () => {
      await updateDeedPoll("x", { privacy: "Private" }, { isAdmin: false });
      const arg = prisma.deedPoll.update.mock.calls[0][0].data;
      expect(arg.privacy).toBeUndefined();
    });

    it("drops privacy when no context is given at all", async () => {
      // The default context is non-admin, so an internal caller that forgets to pass
      // one must not get the permissive branch.
      await updateDeedPoll("x", { privacy: "Private" });
      const arg = prisma.deedPoll.update.mock.calls[0][0].data;
      expect(arg.privacy).toBeUndefined();
    });

    it("lets an admin set privacy", async () => {
      await updateDeedPoll("x", { privacy: "Private" }, { isAdmin: true });
      const arg = prisma.deedPoll.update.mock.calls[0][0].data;
      expect(arg.privacy).toBe("Private");
    });

    it("still applies the rest of a non-admin patch", async () => {
      // Dropping the field, not rejecting the request: the source disables the control
      // rather than erroring, and a browser that legitimately omits a disabled input
      // must still be able to save the other fields on the tab.
      await updateDeedPoll("x", { privacy: "Private", newForename: "Ada" }, { isAdmin: false });
      const arg = prisma.deedPoll.update.mock.calls[0][0].data;
      expect(arg.privacy).toBeUndefined();
      expect(arg.newForename).toBe("Ada");
    });

    it("leaves a non-admin patch that does not mention privacy alone", async () => {
      await updateDeedPoll("x", { newForename: "Ada" }, { isAdmin: false });
      const arg = prisma.deedPoll.update.mock.calls[0][0].data;
      expect(arg.privacy).toBeUndefined();
      expect(arg.newForename).toBe("Ada");
    });

    it("does not let a non-admin clear privacy either", async () => {
      // The hole is write access to the field, not the Private value specifically:
      // downgrading a Private case to Normal is the more damaging direction.
      prisma.deedPoll.findUnique.mockResolvedValue({
        id: "x",
        displayReference: "KBDP2026-00001",
        type: "MinorTransgender",
        privacy: "Private"
      });
      await updateDeedPoll("x", { privacy: "Normal" }, { isAdmin: false });
      const arg = prisma.deedPoll.update.mock.calls[0][0].data;
      expect(arg.privacy).toBeUndefined();
    });
  });

  /**
   * On create the lock was mostly accidental: `defaultPrivacy()` overwrites whatever the
   * caller sent for types 1-5, so a requested Private was already discarded. Unknown and
   * a typeless create fall through its `default:` branch to `current ?? Normal`, which
   * honoured the request — so a non-admin could create a Private case by omitting a type.
   */
  describe("privacy authorisation on create", () => {
    it("ignores a non-admin request for Private on an Unknown-type case", async () => {
      prisma.deedPoll.create.mockResolvedValue({ id: "x" });
      await createDeedPoll({ type: "Unknown", privacy: "Private", dateReceived: RECEIVED }, { isAdmin: false });
      const arg = prisma.deedPoll.create.mock.calls[0][0].data;
      expect(arg.privacy).toBe("Normal");
    });

    it("ignores a non-admin request for Private when no type is given", async () => {
      prisma.deedPoll.create.mockResolvedValue({ id: "x" });
      await createDeedPoll({ privacy: "Private", dateReceived: RECEIVED }, { isAdmin: false });
      const arg = prisma.deedPoll.create.mock.calls[0][0].data;
      expect(arg.privacy).toBe("Normal");
    });

    it("still derives Private for a non-admin Minor Transgender case", async () => {
      // The auto-default is a rule about the case type, not a caller privilege — it
      // must survive the authorisation check.
      prisma.deedPoll.create.mockResolvedValue({ id: "x" });
      await createDeedPoll({ type: "MinorTransgender", dateReceived: RECEIVED }, { isAdmin: false });
      const arg = prisma.deedPoll.create.mock.calls[0][0].data;
      expect(arg.privacy).toBe("Private");
    });

    it("lets an admin create an Unknown-type case as Private", async () => {
      prisma.deedPoll.create.mockResolvedValue({ id: "x" });
      await createDeedPoll({ type: "Unknown", privacy: "Private", dateReceived: RECEIVED }, { isAdmin: true });
      const arg = prisma.deedPoll.create.mock.calls[0][0].data;
      expect(arg.privacy).toBe("Private");
    });

    it("lets a non-admin create a relinquishment with a chosen privacy", async () => {
      // privacyEditable() permits this one case: non-admin, new, relinquishment. The
      // check must not be blunter than the rule it enforces. defaultPrivacy sends
      // relinquishment to Normal, so Normal is what a non-admin can pick.
      prisma.deedPoll.create.mockResolvedValue({ id: "x" });
      await createDeedPoll({ type: "DeedOfRelinquishment", privacy: "Normal", dateReceived: RECEIVED }, { isAdmin: false });
      const arg = prisma.deedPoll.create.mock.calls[0][0].data;
      expect(arg.privacy).toBe("Normal");
    });
  });

  /**
   * Cross-field date rules on the write path. Enforced here, not only on the form,
   * for the reason the privacy lock is: an API PATCH bypasses every template. The
   * rules themselves are unit-tested in case-state.service.spec.ts — these assert
   * that update()/create() consult them and refuse the write.
   */
  describe("cross-field date rules", () => {
    it("rejects a patch that leaves the record both enroled and returned", async () => {
      prisma.deedPoll.findUnique.mockResolvedValue({
        id: "x",
        displayReference: "KBDP2026-00001",
        dateEnroled: new Date("2026-08-01T00:00:00.000Z")
      });
      await expect(updateDeedPoll("x", { dateReturned: "2026-08-05T00:00:00.000Z" })).rejects.toBeInstanceOf(InvalidInputError);
      expect(prisma.deedPoll.update).not.toHaveBeenCalled();
    });

    it("rejects a signature received before requested", async () => {
      prisma.deedPoll.findUnique.mockResolvedValue({
        id: "x",
        displayReference: "KBDP2026-00001",
        signatureRequestedDate: new Date("2026-08-15T00:00:00.000Z")
      });
      await expect(
        updateDeedPoll("x", {
          signatureReceivedDate: "2026-08-03T00:00:00.000Z"
        })
      ).rejects.toBeInstanceOf(InvalidInputError);
      expect(prisma.deedPoll.update).not.toHaveBeenCalled();
    });

    it("checks the MERGED record, so clearing one date of the pair is allowed", async () => {
      // The fix for a contradictory record must not itself be blocked: blanking
      // dateEnroled on an enroled+returned case is exactly how a caseworker resolves it.
      prisma.deedPoll.findUnique.mockResolvedValue({
        id: "x",
        displayReference: "KBDP2026-00001",
        dateEnroled: new Date("2026-08-01T00:00:00.000Z"),
        dateReturned: new Date("2026-08-05T00:00:00.000Z")
      });
      prisma.deedPoll.update.mockResolvedValue({ id: "x" });
      // Cast as the SSR controller does: buildUpdatePatch() clears a field with null,
      // but the DTO's date properties are typed `string | undefined`. That mismatch
      // predates this change (deed-poll-pages.controller.ts casts to get past it).
      await updateDeedPoll("x", { dateEnroled: null } as never);
      const arg = prisma.deedPoll.update.mock.calls[0][0].data;
      expect(arg.state).toBe("Returned");
    });

    it("rejects a create carrying both terminal dates, before a serial is spent", async () => {
      await expect(
        createDeedPoll({
          type: "Adult",
          dateReceived: RECEIVED,
          dateEnroled: "2026-08-01T00:00:00.000Z",
          dateReturned: "2026-08-05T00:00:00.000Z"
        })
      ).rejects.toBeInstanceOf(InvalidInputError);
      // A rejected create must not burn a reference number.
      expect(prisma.referenceCounter.upsert).not.toHaveBeenCalled();
      expect(prisma.deedPoll.create).not.toHaveBeenCalled();
    });

    it("leaves a consistent signature pair alone", async () => {
      prisma.deedPoll.findUnique.mockResolvedValue({
        id: "x",
        displayReference: "KBDP2026-00001",
        signatureRequestedDate: new Date("2026-08-03T00:00:00.000Z")
      });
      prisma.deedPoll.update.mockResolvedValue({ id: "x" });
      await updateDeedPoll("x", {
        signatureReceivedDate: "2026-08-15T00:00:00.000Z"
      });
      const arg = prisma.deedPoll.update.mock.calls[0][0].data;
      expect(arg.state).toBe("AwaitingEnrolment");
    });
  });

  it("remove deletes after existence check", async () => {
    prisma.deedPoll.findUnique.mockResolvedValue({ id: "x" });
    prisma.deedPoll.delete.mockResolvedValue({ id: "x" });
    await removeDeedPoll("x");
    expect(prisma.deedPoll.delete).toHaveBeenCalledWith({ where: { id: "x" } });
  });
});
