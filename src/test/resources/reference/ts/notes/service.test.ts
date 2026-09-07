import { prisma as prismaClient } from "@hmcts/postgres-prisma";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { NotFoundError } from "../errors.js";
import { createNote, findNote, findNotes, removeNote, updateNote } from "./service.js";

// Partial mock: only the client is replaced. The Prisma enums (DocumentDirection,
// DeedPollType, …) are real values the source modules import, so they must pass through.
vi.mock("@hmcts/postgres-prisma", async (importOriginal) => ({
  ...(await importOriginal<typeof import("@hmcts/postgres-prisma")>()),
  prisma: {
    deedPoll: { findUnique: vi.fn() },
    deedPollNote: {
      create: vi.fn(),
      findMany: vi.fn(),
      findFirst: vi.fn(),
      update: vi.fn(),
      delete: vi.fn()
    }
  }
}));

const prisma = vi.mocked(prismaClient, { deep: true });

describe("notes/service", () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it("creates a note scoped to its parent", async () => {
    prisma.deedPoll.findUnique.mockResolvedValue({ id: "case1" });
    prisma.deedPollNote.create.mockResolvedValue({ id: "n1" });
    await createNote("case1", { note: "hello" }, { userName: "Steve Newman" });
    // Both audit columns are stamped on create (added 2026-08-10): the source shows a
    // Modified By on a note that has only ever been created, so "modified" means
    // "last written", not "edited since".
    expect(prisma.deedPollNote.create).toHaveBeenCalledWith({
      data: {
        note: "hello",
        deedPollId: "case1",
        createdBy: "Steve Newman",
        modifiedBy: "Steve Newman"
      }
    });
  });

  it("attributes a create with no caller name to the service account", async () => {
    prisma.deedPoll.findUnique.mockResolvedValue({ id: "case1" });
    prisma.deedPollNote.create.mockResolvedValue({ id: "n1" });
    await createNote("case1", { note: "hello" });
    expect(prisma.deedPollNote.create).toHaveBeenCalledWith({
      data: {
        note: "hello",
        deedPollId: "case1",
        createdBy: "# svc_HMCTS_LCPT_Dev",
        modifiedBy: "# svc_HMCTS_LCPT_Dev"
      }
    });
  });

  it("moves modifiedBy on update without touching createdBy", async () => {
    // An edit must never rewrite who created a note — the same rule the case's own
    // update() enforces by deleting createdBy from the patch. Here it is structural:
    // UpdateNoteInput cannot carry createdBy, so the patch has no way to express it.
    prisma.deedPollNote.findFirst.mockResolvedValue({ id: "n1" });
    prisma.deedPollNote.update.mockResolvedValue({ id: "n1" });

    await updateNote("case1", "n1", { note: "amended" }, { userName: "Jordan Blake" });

    // The where carries deedPollId as well as the unique id, so the write is scoped in its
    // own right rather than relying on the findFirst guard above it having been written.
    expect(prisma.deedPollNote.update).toHaveBeenCalledWith({
      where: { id: "n1", deedPollId: "case1" },
      data: { note: "amended", modifiedBy: "Jordan Blake" }
    });
    const { data } = prisma.deedPollNote.update.mock.calls[0][0] as {
      data: Record<string, unknown>;
    };
    expect(data).not.toHaveProperty("createdBy");
  });

  it("404s updating a note that belongs to another case, before writing", async () => {
    prisma.deedPollNote.findFirst.mockResolvedValue(null);
    await expect(updateNote("case1", "n1", { note: "x" })).rejects.toBeInstanceOf(NotFoundError);
    expect(prisma.deedPollNote.update).not.toHaveBeenCalled();
  });

  it("404s creating a note on a missing case", async () => {
    prisma.deedPoll.findUnique.mockResolvedValue(null);
    await expect(createNote("nope", { note: "x" })).rejects.toBeInstanceOf(NotFoundError);
    expect(prisma.deedPollNote.create).not.toHaveBeenCalled();
  });

  it("lists notes newest first for a case that exists", async () => {
    prisma.deedPoll.findUnique.mockResolvedValue({ id: "case1" });
    prisma.deedPollNote.findMany.mockResolvedValue([{ id: "n1" }]);

    await expect(findNotes("case1")).resolves.toEqual([{ id: "n1" }]);

    expect(prisma.deedPollNote.findMany).toHaveBeenCalledWith({
      where: { deedPollId: "case1" },
      orderBy: { createdOn: "desc" }
    });
  });

  it("404s listing notes for a case that does not exist, rather than answering an empty list", async () => {
    // An empty list would say "this case has no notes", which is a different fact from "there is
    // no such case" — and the wrong one for a mistyped id.
    prisma.deedPoll.findUnique.mockResolvedValue(null);

    await expect(findNotes("nope")).rejects.toBeInstanceOf(NotFoundError);

    expect(prisma.deedPollNote.findMany).not.toHaveBeenCalled();
  });

  it("returns an empty list for a real case with no notes", async () => {
    // The other half of the pair: the 404 above must not swallow the legitimately-empty case.
    prisma.deedPoll.findUnique.mockResolvedValue({ id: "case1" });
    prisma.deedPollNote.findMany.mockResolvedValue([]);

    await expect(findNotes("case1")).resolves.toEqual([]);
  });

  it("findOne 404s when the note belongs to another case", async () => {
    prisma.deedPollNote.findFirst.mockResolvedValue(null);
    await expect(findNote("case1", "n1")).rejects.toBeInstanceOf(NotFoundError);
    expect(prisma.deedPollNote.findFirst).toHaveBeenCalledWith({
      where: { id: "n1", deedPollId: "case1" }
    });
  });

  it("removes after an existence check, scoped to the parent case", async () => {
    prisma.deedPollNote.findFirst.mockResolvedValue({ id: "n1" });
    prisma.deedPollNote.delete.mockResolvedValue({ id: "n1" });
    await removeNote("case1", "n1");
    expect(prisma.deedPollNote.delete).toHaveBeenCalledWith({
      where: { id: "n1", deedPollId: "case1" }
    });
  });
});
