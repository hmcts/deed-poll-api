import type { ReadStream } from "node:fs";
import { type DeedPollSupportingDocument, DocumentDirection, prisma } from "@hmcts/postgres-prisma";
import { InvalidInputError, NotFoundError } from "../errors.js";
import { createFileReadStream, deleteFile, saveFile } from "./file-storage.js";
import type { CreateDocumentInput, UpdateDocumentInput } from "./schemas.js";

/**
 * Nested CRUD for supporting-document metadata, scoped to a parent DeedPoll.
 * The actual file blob is attached in Phase 10; here we manage the row and its
 * document-type reference. Both the parent case and the referenced type are
 * checked so a bad FK yields a 404 rather than a Prisma 500.
 *
 * The write paths carry `deedPollId` in their own `where` as well as behind the
 * `findDocument` guard — see the notes service for why.
 */

async function assertCaseExists(deedPollId: string): Promise<void> {
  const exists = await prisma.deedPoll.findUnique({
    where: { id: deedPollId },
    select: { id: true }
  });
  if (!exists) throw new NotFoundError(`DeedPoll ${deedPollId} not found`);
}

async function assertType(documentTypeId: string): Promise<void> {
  const exists = await prisma.deedPollSupportingDocumentType.findUnique({
    where: { id: documentTypeId },
    select: { id: true }
  });
  if (!exists) {
    throw new NotFoundError(`Document type ${documentTypeId} not found`);
  }
}

export async function createDocument(deedPollId: string, dto: CreateDocumentInput): Promise<DeedPollSupportingDocument> {
  await assertCaseExists(deedPollId);
  await assertType(dto.documentTypeId);
  return prisma.deedPollSupportingDocument.create({
    data: { ...dto, deedPollId }
  });
}

/** Documents for a case. The parent is checked so a missing case 404s rather than answering `[]`. */
export async function findDocuments(deedPollId: string): Promise<DeedPollSupportingDocument[]> {
  await assertCaseExists(deedPollId);
  return prisma.deedPollSupportingDocument.findMany({
    where: { deedPollId },
    orderBy: { createdOn: "asc" },
    include: { documentType: true }
  });
}

export async function findDocument(deedPollId: string, id: string): Promise<DeedPollSupportingDocument> {
  const doc = await prisma.deedPollSupportingDocument.findFirst({
    where: { id, deedPollId },
    include: { documentType: true }
  });
  if (!doc) throw new NotFoundError(`Document ${id} not found`);
  return doc;
}

export async function updateDocument(deedPollId: string, id: string, dto: UpdateDocumentInput): Promise<DeedPollSupportingDocument> {
  await findDocument(deedPollId, id);
  if (dto.documentTypeId) await assertType(dto.documentTypeId);
  return prisma.deedPollSupportingDocument.update({
    where: { id, deedPollId },
    data: dto
  });
}

export async function removeDocument(deedPollId: string, id: string): Promise<void> {
  const doc = await findDocument(deedPollId, id);
  await prisma.deedPollSupportingDocument.delete({ where: { id, deedPollId } });

  // Blob cleanup, and now genuinely best-effort — the comment here used to claim that while an
  // unawaited-failure propagated. The row is already gone at this point, so a failed unlink turned
  // a completed delete into a 500: the caller retries, gets a 404, and has no way to tell the
  // delete had in fact worked. An orphaned blob is recoverable and cheap; a status code that
  // contradicts what happened is neither. `deleteFile` already no-ops on a missing file, so this
  // only swallows real I/O failures, and it says so in the log rather than silently.
  try {
    await deleteFile(doc.storageKey);
  } catch (error) {
    console.error(`Deleted document ${id} but could not remove its stored file (key ${doc.storageKey}):`, error);
  }
}

/**
 * The parts of an uploaded file this domain needs.
 *
 * The prototype typed this as `Express.Multer.File`, which put a transport type in the
 * domain layer. These four fields are all it ever read, so the app maps whatever its
 * upload middleware produces onto this.
 */
export interface UploadedFile {
  buffer: Buffer;
  originalname: string;
  mimetype: string;
  size: number;
}

/**
 * Upload a file and create its document row in one step. The blob is written
 * to storage first, then the metadata row records the returned storageKey plus
 * the original filename, MIME type and size. Direction defaults to Incoming
 * (an applicant-supplied document) unless overridden.
 */
export async function uploadDocument(
  deedPollId: string,
  documentTypeId: string,
  file: UploadedFile | undefined,
  opts: { name?: string; direction?: DocumentDirection } = {}
): Promise<DeedPollSupportingDocument> {
  if (!file) throw new InvalidInputError("Select a file to upload");
  await assertCaseExists(deedPollId);
  await assertType(documentTypeId);

  const storageKey = await saveFile(file.buffer);
  return prisma.deedPollSupportingDocument.create({
    data: {
      deedPollId,
      documentTypeId,
      direction: opts.direction ?? DocumentDirection.Incoming,
      name: opts.name?.slice(0, 100) || file.originalname.slice(0, 100),
      storageKey,
      originalName: file.originalname,
      mimeType: file.mimetype,
      sizeBytes: file.size
    }
  });
}

/**
 * Resolve a document's stored blob for download: the metadata row (scoped to
 * its parent) plus a read stream. 404 if the row has no attached file.
 */
export async function downloadDocument(deedPollId: string, id: string): Promise<{ doc: DeedPollSupportingDocument; stream: ReadStream }> {
  const doc = await findDocument(deedPollId, id);
  if (!doc.storageKey) {
    throw new NotFoundError("This document has no attached file");
  }
  return { doc, stream: createFileReadStream(doc.storageKey) };
}
