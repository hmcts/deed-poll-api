import type { DeedPoll, DeedPollNote, DeedPollSupportingDocument, DeedPollSupportingDocumentType, DeedPollWitness } from "@hmcts/postgres-prisma";

/**
 * Derived (non-stored) columns, ported from the source's calculated fields in
 * Formulas/deedpoll_deedpoll-FormulaDefinitions.yaml:
 *
 *   deedpoll_datetoday             = Now()
 *   deedpoll_daysreceivedtoenrolled = DateDiff(datereceived, dateenrolled, Days)
 *   deedpoll_daysreceivedtoreturned = DateDiff(datereceived, datereturned, Days)
 *
 * The migration plan calls for these to be derived in the app layer rather than
 * stored, so there is no column to drift out of date. They are attached on the
 * read path (DeedPollService.findOne/findAll), which is why both the REST API and
 * the SSR pages see them without either having to compute anything.
 *
 * `dateToday` is deliberately NOT materialised here: it is just now(), a template
 * concern, and persisting it per-record is what made the source formula useless.
 * `testofdatediffyears` is dropped too — it is a leftover probe in the source
 * ("testof…"), referenced by no form, view or workflow.
 */

/** Whole days from `from` to `to`, or null when either end is missing/invalid. */
export function daysBetween(from: Date | string | null | undefined, to: Date | string | null | undefined): number | null {
  if (from == null || to == null) return null;
  const a = from instanceof Date ? from : new Date(from);
  const b = to instanceof Date ? to : new Date(to);
  if (Number.isNaN(a.getTime()) || Number.isNaN(b.getTime())) return null;
  // Compare calendar days in UTC so a clock time on either date cannot round a
  // same-day pair to a non-zero difference (all these columns are date-only).
  const dayA = Date.UTC(a.getUTCFullYear(), a.getUTCMonth(), a.getUTCDate());
  const dayB = Date.UTC(b.getUTCFullYear(), b.getUTCMonth(), b.getUTCDate());
  return Math.round((dayB - dayA) / 86_400_000);
}

/** The derived fields added to every record on read. */
export interface ComputedFields {
  daysReceivedToEnroled: number | null;
  daysReceivedToReturned: number | null;
}

/** A record plus its derived columns. */
export type WithComputed<T> = T & ComputedFields;

/** Compute the derived columns for one record (never mutates the input). */
export function computedFor(record: { dateReceived?: Date | null; dateEnroled?: Date | null; dateReturned?: Date | null }): ComputedFields {
  return {
    daysReceivedToEnroled: daysBetween(record.dateReceived, record.dateEnroled),
    daysReceivedToReturned: daysBetween(record.dateReceived, record.dateReturned)
  };
}

/** Attach the derived columns to a record. */
export function withComputed<T extends Parameters<typeof computedFor>[0]>(record: T): WithComputed<T> {
  return { ...record, ...computedFor(record) };
}

/** Attach the derived columns to every record in a list. */
export function withComputedAll<T extends Parameters<typeof computedFor>[0]>(records: T[]): WithComputed<T>[] {
  return records.map((r) => withComputed(r));
}

/** Convenience alias for the common case: a full DeedPoll plus derived columns. */
export type DeedPollWithComputed = WithComputed<DeedPoll>;

/**
 * One case as `findOne()` actually returns it: the record, its derived columns AND the
 * three child collections that method eager-loads.
 *
 * Split out from `DeedPollWithComputed` (which `findAll()` returns, children absent)
 * because the two shapes are genuinely different and a single type covering both would
 * have to lie about one of them. It lied in favour of `findAll` until 2026-08-10, so any
 * server-side use of `case.notes` was a type error even though the data was there —
 * Nunjucks is untyped, which is why the templates never noticed.
 */
export type DeedPollWithChildren = WithComputed<
  DeedPoll & {
    notes: DeedPollNote[];
    witnesses: DeedPollWitness[];
    documents: (DeedPollSupportingDocument & {
      documentType: DeedPollSupportingDocumentType | null;
    })[];
  }
>;
