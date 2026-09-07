import { DeedPollType, type Prisma } from "@hmcts/postgres-prisma";
import { InvalidInputError } from "../errors.js";

/**
 * ReferenceNumberService — ports the `AddDeedPollReferenceNumber` Power Automate
 * flow (Workflows/AddDeedPollReferenceNumber-F900D547-…json).
 *
 * The composite display reference is `{prefix}{year}-{serial}`, e.g. `KBDP2026-00001`:
 *   - prefix: `KBDR` for Deed of Relinquishment (type 5), else `KBDP`.
 *   - year:   4-digit year taken from Date Received.
 *   - serial: 5-digit, zero-padded, shared per-year counter across DP and DR.
 *
 * On create we allocate the next serial for the year; on update we keep the
 * existing year + serial and recompute only the prefix (so a DP↔DR type flip
 * re-prefixes without renumbering). We do NOT renumber on a date-received edit.
 *
 * Concurrency: the source flow read the current max reference number and added
 * one, which races under concurrent creates. We increment a per-year counter row
 * inside the caller's transaction (SELECT … FOR UPDATE via an atomic UPDATE …
 * RETURNING), so serial allocation is atomic — a deliberate correctness
 * improvement over the source, noted here per the plan.
 */
/** Prefix from Type: type 5 (Deed of Relinquishment) → KBDR, else KBDP. */
export function prefixForType(type: DeedPollType | null | undefined): string {
  return type === DeedPollType.DeedOfRelinquishment ? "KBDR" : "KBDP";
}

/** Build the composite display reference from its three parts. */
export function compose(prefix: string, year: number, serial: number): string {
  return `${prefix}${year}-${formatSerial(serial)}`;
}

/** 5-digit zero-padded serial, matching the source `formatNumber(…, '00000')`. */
export function formatSerial(serial: number): string {
  return String(serial).padStart(5, "0");
}

/**
 * Parse a composite reference into its parts. Returns null if it does not
 * match the `KBDP2026-00001` shape (e.g. a legacy/hand-entered value).
 * Used on update to recover the year + serial while re-deriving the prefix.
 */
export function parse(reference: string | null | undefined): { prefix: string; year: number; serial: number } | null {
  if (!reference) return null;
  const m = /^(KBD[PR])(\d{4})-(\d{5})$/.exec(reference);
  if (!m) return null;
  return { prefix: m[1], year: Number(m[2]), serial: Number(m[3]) };
}

/**
 * Allocate the next serial for a year, atomically, inside the given
 * transaction client. Uses an upsert-then-increment so the first record of a
 * year seeds the counter at 1 and concurrent creates never collide.
 */
export async function allocateSerial(tx: Prisma.TransactionClient, year: number): Promise<number> {
  // Atomic read-modify-write: `increment` compiles to `lastSerial = lastSerial + 1`
  // in a single UPDATE, so concurrent transactions serialise on the row lock.
  const counter = await tx.referenceCounter.upsert({
    where: { year },
    create: { year, lastSerial: 1 },
    update: { lastSerial: { increment: 1 } }
  });
  return counter.lastSerial;
}

/**
 * Year to use for a new record, taken from Date Received.
 *
 * There is no fallback. The source read the year from `datereceived`, and inventing one from
 * the clock would put a case in the wrong year's serial sequence — a reference number is
 * external-facing and permanent, so a plausible-looking wrong one is worse than a refusal.
 *
 * `InvalidInputError` rather than a bare `Error`, because an absent Date Received is a caller
 * omission and not a fault: it is the whole of what a create needs and does not have. Thrown as
 * a bare Error it reached `apps/api`'s error middleware unrecognised and became a generic 500,
 * so `POST /deed-polls` with no Date Received — the most obvious request anyone makes of this
 * API — answered "Internal server error". Attributed to the field so the Phase 4 forms can
 * render it against the Date Received input rather than as an unattributed banner.
 */
export function yearFromDateReceived(dateReceived: Date | null | undefined): number {
  const d = dateReceived ?? undefined;
  if (d instanceof Date && !Number.isNaN(d.getTime())) {
    return d.getUTCFullYear();
  }
  throw new InvalidInputError("Cannot allocate a reference number without a Date Received to derive the year", [
    { field: "dateReceived", message: "Enter the date the application was received" }
  ]);
}

/**
 * On create: derive year from Date Received, allocate the serial, set prefix
 * from Type. Returns the serial (stored as `referencenumber`) and the composite
 * `displayReference`. Must be called inside a transaction.
 */
export async function allocateForCreate(
  tx: Prisma.TransactionClient,
  input: { type?: DeedPollType | null; dateReceived?: Date | null }
): Promise<{ referencenumber: number; displayReference: string }> {
  const year = yearFromDateReceived(input.dateReceived);
  const serial = await allocateSerial(tx, year);
  const prefix = prefixForType(input.type);
  return {
    referencenumber: serial,
    displayReference: compose(prefix, year, serial)
  };
}

/**
 * On update: keep the existing year + serial, recompute only the prefix from
 * the (possibly changed) Type. Returns the new displayReference, or null if
 * the existing reference cannot be parsed (leave it untouched in that case).
 */
export function reprefixForUpdate(existingReference: string | null | undefined, type: DeedPollType | null | undefined): string | null {
  const parsed = parse(existingReference);
  if (!parsed) return null;
  const prefix = prefixForType(type);
  return compose(prefix, parsed.year, parsed.serial);
}
