import { prisma as prismaClient } from "@hmcts/postgres-prisma";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { ConflictError, NotFoundError } from "../errors.js";
import { createDocumentType, findDocumentType, findDocumentTypes, removeDocumentType, updateDocumentType } from "./document-types.js";

// This module had no unit tests before Phase 3 — only its Zod schemas were covered. Added with the
// route port, because the delete path gained a refusal that needs pinning.
vi.mock("@hmcts/postgres-prisma", async (importOriginal) => ({
  ...(await importOriginal<typeof import("@hmcts/postgres-prisma")>()),
  prisma: {
    deedPollSupportingDocumentType: {
      create: vi.fn(),
      findMany: vi.fn(),
      findUnique: vi.fn(),
      update: vi.fn(),
      delete: vi.fn()
    },
    deedPollSupportingDocument: { count: vi.fn() }
  }
}));

const prisma = vi.mocked(prismaClient, { deep: true });

describe("documents/document-types", () => {
  const dto = { name: "Birth certificate", applicableMaritalStatuses: [1], applicableTypes: [3] };

  beforeEach(() => {
    vi.clearAllMocks();
  });

  it("creates a type from the payload as given, with no parent to scope to", async () => {
    prisma.deedPollSupportingDocumentType.create.mockResolvedValue({ id: "t1" });

    await createDocumentType(dto);

    expect(prisma.deedPollSupportingDocumentType.create).toHaveBeenCalledWith({ data: dto });
  });

  it("lists types alphabetically, since they populate a form's options", async () => {
    prisma.deedPollSupportingDocumentType.findMany.mockResolvedValue([]);

    await findDocumentTypes();

    expect(prisma.deedPollSupportingDocumentType.findMany).toHaveBeenCalledWith({ orderBy: { name: "asc" } });
  });

  it("404s for a type that does not exist", async () => {
    prisma.deedPollSupportingDocumentType.findUnique.mockResolvedValue(null);

    await expect(findDocumentType("t1")).rejects.toBeInstanceOf(NotFoundError);
  });

  it("404s updating a type that does not exist, before writing", async () => {
    prisma.deedPollSupportingDocumentType.findUnique.mockResolvedValue(null);

    await expect(updateDocumentType("t1", { name: "Renamed" })).rejects.toBeInstanceOf(NotFoundError);

    expect(prisma.deedPollSupportingDocumentType.update).not.toHaveBeenCalled();
  });

  it("updates an existing type", async () => {
    prisma.deedPollSupportingDocumentType.findUnique.mockResolvedValue({ id: "t1" });
    prisma.deedPollSupportingDocumentType.update.mockResolvedValue({ id: "t1" });

    await updateDocumentType("t1", { name: "Renamed" });

    expect(prisma.deedPollSupportingDocumentType.update).toHaveBeenCalledWith({
      where: { id: "t1" },
      data: { name: "Renamed" }
    });
  });

  it("deletes a type nothing references", async () => {
    prisma.deedPollSupportingDocumentType.findUnique.mockResolvedValue({ id: "t1" });
    prisma.deedPollSupportingDocument.count.mockResolvedValue(0);

    await removeDocumentType("t1");

    expect(prisma.deedPollSupportingDocumentType.delete).toHaveBeenCalledWith({ where: { id: "t1" } });
  });

  it("refuses to delete a type documents still reference, with a ConflictError not a 500", async () => {
    // The FK already refuses, but via the PrismaPg adapter that arrives as a DriverAdapterError
    // carrying SQLSTATE 23001 — not a domain error, so apps/api answered a generic 500 for what is
    // an ordinary caseworker action.
    prisma.deedPollSupportingDocumentType.findUnique.mockResolvedValue({ id: "t1" });
    prisma.deedPollSupportingDocument.count.mockResolvedValue(2);

    await expect(removeDocumentType("t1")).rejects.toBeInstanceOf(ConflictError);

    expect(prisma.deedPollSupportingDocumentType.delete).not.toHaveBeenCalled();
  });

  it("names the number of referencing documents, so the message is actionable", async () => {
    prisma.deedPollSupportingDocumentType.findUnique.mockResolvedValue({ id: "t1" });
    prisma.deedPollSupportingDocument.count.mockResolvedValue(3);

    await expect(removeDocumentType("t1")).rejects.toThrow(/3 document\(s\)/);
  });

  it("counts only documents of the type being deleted", async () => {
    prisma.deedPollSupportingDocumentType.findUnique.mockResolvedValue({ id: "t1" });
    prisma.deedPollSupportingDocument.count.mockResolvedValue(0);

    await removeDocumentType("t1");

    expect(prisma.deedPollSupportingDocument.count).toHaveBeenCalledWith({ where: { documentTypeId: "t1" } });
  });

  it("404s deleting a type that does not exist, without counting references", async () => {
    prisma.deedPollSupportingDocumentType.findUnique.mockResolvedValue(null);

    await expect(removeDocumentType("t1")).rejects.toBeInstanceOf(NotFoundError);

    expect(prisma.deedPollSupportingDocument.count).not.toHaveBeenCalled();
    expect(prisma.deedPollSupportingDocumentType.delete).not.toHaveBeenCalled();
  });
});
