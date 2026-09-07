import { type DeedPollSupportingDocumentType, prisma } from "@hmcts/postgres-prisma";
import { ConflictError, NotFoundError } from "../errors.js";
import type { CreateDocumentTypeInput, UpdateDocumentTypeInput } from "./schemas.js";

/**
 * CRUD for supporting-document *types* — global reference data (not nested under
 * a case). Documents reference a type by FK, and a type still in use cannot be deleted.
 */

export function createDocumentType(dto: CreateDocumentTypeInput): Promise<DeedPollSupportingDocumentType> {
  return prisma.deedPollSupportingDocumentType.create({ data: dto });
}

export function findDocumentTypes(): Promise<DeedPollSupportingDocumentType[]> {
  return prisma.deedPollSupportingDocumentType.findMany({
    orderBy: { name: "asc" }
  });
}

export async function findDocumentType(id: string): Promise<DeedPollSupportingDocumentType> {
  const type = await prisma.deedPollSupportingDocumentType.findUnique({
    where: { id }
  });
  if (!type) throw new NotFoundError(`Document type ${id} not found`);
  return type;
}

export async function updateDocumentType(id: string, dto: UpdateDocumentTypeInput): Promise<DeedPollSupportingDocumentType> {
  await findDocumentType(id);
  return prisma.deedPollSupportingDocumentType.update({
    where: { id },
    data: dto
  });
}

/**
 * Delete a type, refusing if any document still references it.
 *
 * The reference check is explicit rather than left to the foreign key. The `documentType`
 * relation is required and declares no `onDelete`, so Postgres already refuses — but it does so
 * as a `RESTRICT` violation surfaced through the `PrismaPg` adapter as a `DriverAdapterError`
 * carrying SQLSTATE 23001 on `err.cause.code`. That is not one of the domain errors, so it
 * reached `apps/api`'s error middleware unrecognised and became a generic 500: an ordinary
 * caseworker deleting a type that happens to be in use was told the server had broken.
 *
 * Checking here means the answer is a 409 naming the number of documents, which is both true
 * and actionable, and it does not depend on the driver's internal error shape — that would be
 * fragile across a Prisma upgrade, and would fail by silently reverting to 500.
 *
 * A document created between this count and the delete would still hit the FK and produce that
 * 500. The window is tiny, the database refuses either way so integrity holds, and closing it
 * would mean parsing the driver internals this check exists to avoid.
 */
export async function removeDocumentType(id: string): Promise<void> {
  await findDocumentType(id);

  const inUse = await prisma.deedPollSupportingDocument.count({ where: { documentTypeId: id } });
  if (inUse > 0) {
    throw new ConflictError(`Cannot delete this document type: ${inUse} document(s) still reference it`);
  }

  await prisma.deedPollSupportingDocumentType.delete({ where: { id } });
}
