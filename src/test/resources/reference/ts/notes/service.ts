import { type DeedPollNote, prisma } from "@hmcts/postgres-prisma";
import { SERVICE_ACCOUNT_NAME } from "../auth/current-user.js";
import { NotFoundError } from "../errors.js";
import type { CreateNoteInput, UpdateNoteInput } from "./schemas.js";

/**
 * Who is writing a note. Optional throughout: an omitted name is stamped as the
 * service account, exactly as the case's own audit columns behave.
 */
export interface NoteAuthor {
  userName?: string;
}

/**
 * Nested CRUD for case notes. All operations are scoped to a parent DeedPoll so
 * a note can never be read or mutated outside its case. The parent's existence
 * is checked on create (FK would fail anyway, but a 404 is clearer than a 500).
 *
 * The scoping is applied twice on the write paths, deliberately. `findNote` runs first
 * because it produces the `NotFoundError` the route layer maps to a clean 404, and the
 * `update`/`delete` that follow *also* carry `deedPollId` in their own `where` — Prisma
 * permits a non-unique filter alongside the unique `id`, so this costs nothing.
 *
 * Belt and braces on purpose: with only the guard, cross-case safety would be a
 * convention — one line that a later `updateX` could omit, leaving an unscoped write
 * that no existing test would catch. With it in the `where` too, an unscoped write is
 * not expressible. See "Phase 3 slice 2" in `.business_context/PROGRESS.md`.
 */

async function assertCaseExists(deedPollId: string): Promise<void> {
  const exists = await prisma.deedPoll.findUnique({
    where: { id: deedPollId },
    select: { id: true }
  });
  if (!exists) throw new NotFoundError(`DeedPoll ${deedPollId} not found`);
}

export async function createNote(deedPollId: string, dto: CreateNoteInput, author: NoteAuthor = {}): Promise<DeedPollNote> {
  await assertCaseExists(deedPollId);
  // Both stamped on create: the source shows a Modified By on a note that has only
  // ever been created, so "modified" means "last written", not "edited since".
  const name = author.userName ?? SERVICE_ACCOUNT_NAME;
  return prisma.deedPollNote.create({
    data: { ...dto, deedPollId, createdBy: name, modifiedBy: name }
  });
}

/**
 * Notes for a case, newest first.
 *
 * The parent is checked, so a missing case is a 404 rather than an empty list. Filtering by
 * `deedPollId` alone — which is what this did, following the prototype — answers `[]` with 200
 * for a case id that does not exist, and `[]` with 200 for a real case that has no notes. Those
 * are different facts and only one of them is true, so a caller who mistypes a case id is told
 * the case has no notes. `assertCaseExists` is the same check every create in this file already
 * makes; doing it here too costs one indexed primary-key lookup.
 */
export async function findNotes(deedPollId: string): Promise<DeedPollNote[]> {
  await assertCaseExists(deedPollId);
  return prisma.deedPollNote.findMany({
    where: { deedPollId },
    orderBy: { createdOn: "desc" }
  });
}

export async function findNote(deedPollId: string, id: string): Promise<DeedPollNote> {
  const note = await prisma.deedPollNote.findFirst({
    where: { id, deedPollId }
  });
  if (!note) throw new NotFoundError(`Note ${id} not found`);
  return note;
}

export async function updateNote(deedPollId: string, id: string, dto: UpdateNoteInput, author: NoteAuthor = {}): Promise<DeedPollNote> {
  await findNote(deedPollId, id); // 404 if missing or wrong parent
  // `createdBy` is never in the patch — UpdateNoteInput cannot carry it — so an edit
  // can only ever move `modifiedBy`, matching the case's updateNote() rule that no
  // update may rewrite who created a record.
  return prisma.deedPollNote.update({
    where: { id, deedPollId },
    data: { ...dto, modifiedBy: author.userName ?? SERVICE_ACCOUNT_NAME }
  });
}

export async function removeNote(deedPollId: string, id: string): Promise<void> {
  await findNote(deedPollId, id);
  await prisma.deedPollNote.delete({ where: { id, deedPollId } });
}
