import { prisma as prismaClient } from "@hmcts/postgres-prisma";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { NotFoundError } from "../errors.js";
import { createWitness, findWitness, findWitnesses, removeWitness, updateWitness } from "./service.js";

// Partial mock: only the client is replaced. The Prisma enums (DocumentDirection,
// DeedPollType, …) are real values the source modules import, so they must pass through.
vi.mock("@hmcts/postgres-prisma", async (importOriginal) => ({
  ...(await importOriginal<typeof import("@hmcts/postgres-prisma")>()),
  prisma: {
    deedPoll: { findUnique: vi.fn() },
    deedPollWitness: {
      create: vi.fn(),
      findMany: vi.fn(),
      findFirst: vi.fn(),
      update: vi.fn(),
      delete: vi.fn()
    }
  }
}));

// Aliased back to `prisma` so the assertions below read exactly as they did under Nest DI;
// `deep: true` keeps the mock methods typed so .mockResolvedValue is available on them.
const prisma = vi.mocked(prismaClient, { deep: true });

describe("witnesses/service", () => {
  const witness = {
    witnessName: "Sam Doe",
    witnessAddress: "1 High St",
    witnessOccupation: "Teacher",
    declarant: false
  };

  beforeEach(() => {
    vi.clearAllMocks();
  });

  it("creates a witness scoped to its parent", async () => {
    prisma.deedPoll.findUnique.mockResolvedValue({ id: "case1" });
    prisma.deedPollWitness.create.mockResolvedValue({ id: "w1" });
    await createWitness("case1", witness);
    expect(prisma.deedPollWitness.create).toHaveBeenCalledWith({
      data: { ...witness, deedPollId: "case1" }
    });
  });

  it("404s creating a witness on a missing case", async () => {
    prisma.deedPoll.findUnique.mockResolvedValue(null);
    await expect(createWitness("nope", witness)).rejects.toBeInstanceOf(NotFoundError);
    expect(prisma.deedPollWitness.create).not.toHaveBeenCalled();
  });

  it("lists witnesses for a case that exists", async () => {
    prisma.deedPoll.findUnique.mockResolvedValue({ id: "case1" });
    prisma.deedPollWitness.findMany.mockResolvedValue([{ id: "w1" }]);

    await expect(findWitnesses("case1")).resolves.toEqual([{ id: "w1" }]);

    expect(prisma.deedPollWitness.findMany).toHaveBeenCalledWith({
      where: { deedPollId: "case1" },
      orderBy: { createdOn: "asc" }
    });
  });

  it("404s listing witnesses for a case that does not exist, rather than answering an empty list", async () => {
    prisma.deedPoll.findUnique.mockResolvedValue(null);

    await expect(findWitnesses("nope")).rejects.toBeInstanceOf(NotFoundError);

    expect(prisma.deedPollWitness.findMany).not.toHaveBeenCalled();
  });

  it("returns an empty list for a real case with no witnesses", async () => {
    prisma.deedPoll.findUnique.mockResolvedValue({ id: "case1" });
    prisma.deedPollWitness.findMany.mockResolvedValue([]);

    await expect(findWitnesses("case1")).resolves.toEqual([]);
  });

  it("findOne 404s across parents", async () => {
    prisma.deedPollWitness.findFirst.mockResolvedValue(null);
    await expect(findWitness("case1", "w1")).rejects.toBeInstanceOf(NotFoundError);
  });

  it("removes after an existence check, scoped to the parent case", async () => {
    prisma.deedPollWitness.findFirst.mockResolvedValue({ id: "w1" });
    prisma.deedPollWitness.delete.mockResolvedValue({ id: "w1" });
    await removeWitness("case1", "w1");
    expect(prisma.deedPollWitness.delete).toHaveBeenCalledWith({
      where: { id: "w1", deedPollId: "case1" }
    });
  });

  it("404s updating a witness that belongs to another case, before writing", async () => {
    prisma.deedPollWitness.findFirst.mockResolvedValue(null);
    await expect(updateWitness("case1", "w1", { declarant: true })).rejects.toBeInstanceOf(NotFoundError);
    expect(prisma.deedPollWitness.update).not.toHaveBeenCalled();
  });

  it("scopes the update to the parent case, so the write does not rely on the guard above it", async () => {
    prisma.deedPollWitness.findFirst.mockResolvedValue({ id: "w1" });
    prisma.deedPollWitness.update.mockResolvedValue({ id: "w1" });
    await updateWitness("case1", "w1", { declarant: true });
    expect(prisma.deedPollWitness.update).toHaveBeenCalledWith({
      where: { id: "w1", deedPollId: "case1" },
      data: { declarant: true }
    });
  });
});
