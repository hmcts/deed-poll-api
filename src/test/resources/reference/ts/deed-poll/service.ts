import { type DeedPoll, type DeedPollType, type Prisma, type Privacy, prisma } from "@hmcts/postgres-prisma";
import { SERVICE_ACCOUNT_NAME } from "../auth/current-user.js";
import { InvalidInputError, NotFoundError } from "../errors.js";
import type { CaseStateInput } from "./case-state.js";
// Namespace imports: the prototype reached these through constructor-injected services
// (this.caseState, this.validation, …). Keeping the same names means every call site below
// reads unchanged, and it stays obvious which module each rule comes from.
import * as caseState from "./case-state.js";
import { type DeedPollWithChildren, type DeedPollWithComputed, withComputed, withComputedAll } from "./computed.js";
import type { ValidationInput } from "./deed-poll-validation.js";
import * as validation from "./deed-poll-validation.js";
import type { PresentationContext } from "./form-presentation.js";
import * as presentation from "./form-presentation.js";
import * as referenceNumbers from "./reference-number.js";
import type { CreateDeedPollInput, UpdateDeedPollInput } from "./schemas.js";

/**
 * Viewer context for a write. Defaults to a non-admin creating a new record,
 * attributed to the service account when no caller name is supplied.
 */
export type WriteContext = Pick<PresentationContext, "isAdmin"> & {
  /** Display name recorded in createdBy/modifiedBy. */
  userName?: string;
};
const DEFAULT_WRITE_CONTEXT: WriteContext = { isAdmin: false };

/** State-driving date fields, pulled from a record/patch to feed CaseStateService. */
const STATE_DATE_FIELDS: (keyof CaseStateInput)[] = [
  "dateReturned",
  "dateEnroled",
  "signatureReceivedDate",
  "signatureRequestedDate",
  "dateReferred",
  "dateReadyForMaster",
  "awaitingFurtherDocsInfo",
  "dateReceived"
];

/** Fields that arrive as ISO date strings and must be coerced to Date for Prisma. */
const DATE_FIELDS = [
  "minorDateOfBirth",
  "dateOfDeclaration",
  "dateOfApplication",
  "dateReceived",
  "awaitingFurtherDocsInfo",
  "signatureRequestedDate",
  "signatureReceivedDate",
  "dateReadyForMaster",
  "dateReferred",
  "dateEnroled",
  "dateReturned",
  "dateNoticeSentToLondonGazette",
  "masterDateOfDecision",
  "dateDecisionDirectionsReceived",
  "dateOfRefund"
] as const;

/**
 * CRUD for DeedPoll, orchestrating the derived-logic services on every write:
 * reference-number allocation (Phase 3) and case-state derivation (Phase 4).
 * Request-time validation (Phase 5) is layered in next.
 */

/** Extract the state-driving dates from a record or merged patch. */
function stateInputFrom(source: Record<string, unknown>): CaseStateInput {
  const input: CaseStateInput = {};
  for (const f of STATE_DATE_FIELDS) {
    input[f] = (source[f] as Date | null | undefined) ?? null;
  }
  return input;
}

/** Coerce ISO date strings to Date objects; leave everything else untouched. */
function coerceDates(dto: object): Record<string, unknown> {
  const out: Record<string, unknown> = { ...dto };
  for (const field of DATE_FIELDS) {
    const v = out[field];
    if (typeof v === "string" && v.length > 0) {
      out[field] = new Date(v);
    }
  }
  return out;
}

/**
 * Drop `privacy` from a write when the caller is not entitled to set it.
 *
 * Authorisation, so it belongs on the write path and not in the template. `edit.njk`
 * renders the control `disabled` for a non-admin, but a disabled attribute is a
 * courtesy to a cooperating browser: until 2026-08-10 a hand-rolled POST, or an API
 * PATCH, set privacy freely. On create the lock was accidental rather than absent —
 * `defaultPrivacy()` overwrites the caller's value for types 1-5, but Unknown and a
 * typeless create fall through its `default:` branch to `current ?? Normal`, so a
 * non-admin could create a Private case simply by omitting the type.
 *
 * The entitlement rule is NOT restated here: it is `privacyEditable()`, the same
 * function `edit.njk` consults, so the lock and the input's disabled state cannot
 * drift apart. Note that rule permits one non-admin case — a NEW relinquishment.
 *
 * Deletes the key rather than throwing. The source disables the control instead of
 * erroring, and a form that legitimately omits a disabled input must still be able to
 * save the other fields on its tab; a 403 would block a save the caseworker is
 * entitled to make. Every other field in the patch is untouched.
 */
function applyPrivacyAuthorisation(data: { privacy?: unknown }, type: DeedPollType | null, ctx: WriteContext, isNew: boolean): void {
  if (data.privacy === undefined) return; // not part of this write
  if (presentation.privacyEditable(type, { isAdmin: ctx.isAdmin, isNew })) {
    return;
  }
  delete data.privacy;
}

export async function createDeedPoll(dto: CreateDeedPollInput, ctx: WriteContext = DEFAULT_WRITE_CONTEXT): Promise<DeedPoll> {
  const data = coerceDates(dto) as Prisma.DeedPollCreateInput;
  // Apply create-time defaults ported from the workflows (minor→Single marital
  // status; relinquishment→refer-to-master Yes + publish-to-Gazette false).
  // Non-blocking: a case may still be logged incrementally, so per-type required
  // fields are surfaced via getFieldRules()/validate() for the submit flow, not
  // enforced here.
  Object.assign(data, validation.applyCreateDefaults(data as ValidationInput));
  // "Show or hide Date Notice Sent to London Gazette": publish = No clears the
  // notice date. Applied after create-defaults so relinquishment (which forces
  // publish = No) also clears any supplied date.
  Object.assign(data, validation.applyGazetteGate(data as ValidationInput));
  // "Show hide and mandate fields for Minors": a non-minor type clears the four
  // minor fields, so they cannot be logged against an Adult case in the first
  // place. A create with no type supplied leaves them alone.
  Object.assign(data, validation.applyMinorGate(data as ValidationInput));
  // Discard a privacy value this caller is not entitled to set, BEFORE the
  // auto-default runs — so what defaultPrivacy() sees as "current" is a permitted
  // value and it falls back to Normal rather than honouring the request.
  applyPrivacyAuthorisation(data, data.type ?? null, ctx, true);
  // Privacy auto-default on create (ported from deedpoll_javascript OnChangeType):
  // depends on type + admin status. isNew is always true here.
  data.privacy = presentation.defaultPrivacy(data.type ?? null, (data.privacy as Privacy | null) ?? null, { isAdmin: ctx.isAdmin, isNew: true });
  // Same cross-field date rules as update(). Checked before the transaction opens,
  // so a rejected create never consumes a reference-number serial. There is no
  // existing record to merge against on create: the payload IS the whole record.
  const dateErrors = caseState.collectDateRuleErrors(stateInputFrom(data as Record<string, unknown>));
  if (dateErrors.length > 0) {
    throw new InvalidInputError("Case dates are inconsistent", dateErrors);
  }

  // Allocate the reference number and persist in one transaction so the
  // per-year serial is never skipped or duplicated under concurrency.
  return prisma.$transaction(async (tx) => {
    const ref = await referenceNumbers.allocateForCreate(tx, {
      type: data.type ?? null,
      dateReceived: (data.dateReceived as Date | null) ?? null
    });
    // Derive the case state from the populated dates (Phase 4).
    const state = caseState.deriveState(stateInputFrom(data as Record<string, unknown>));
    return tx.deedPoll.create({
      data: {
        ...data,
        referencenumber: ref.referencenumber,
        displayReference: ref.displayReference,
        state,
        // Both stamped on create: the source header shows a Modified By from the
        // moment a record exists, and a create IS the first modification.
        createdBy: ctx.userName ?? SERVICE_ACCOUNT_NAME,
        modifiedBy: ctx.userName ?? SERVICE_ACCOUNT_NAME
      }
    });
  });
}

export async function findDeedPolls(where?: Prisma.DeedPollWhereInput): Promise<DeedPollWithComputed[]> {
  const found = await prisma.deedPoll.findMany({
    where,
    orderBy: { createdOn: "desc" }
  });
  return withComputedAll(found);
}

/**
 * One case with its children and derived columns. Derived columns are attached
 * on the read path (see computed.ts) so the REST API and the SSR pages both
 * get them without either computing anything itself.
 */
export async function findDeedPoll(id: string): Promise<DeedPollWithChildren> {
  const found = await prisma.deedPoll.findUnique({
    where: { id },
    include: {
      notes: { orderBy: { createdOn: "asc" } },
      witnesses: { orderBy: { createdOn: "asc" } },
      documents: {
        orderBy: { createdOn: "asc" },
        include: { documentType: true }
      }
    }
  });
  if (!found) {
    throw new NotFoundError(`DeedPoll ${id} not found`);
  }
  return withComputed(found);
}

export async function updateDeedPoll(id: string, dto: UpdateDeedPollInput, ctx: WriteContext = DEFAULT_WRITE_CONTEXT): Promise<DeedPollWithComputed> {
  const existing = await findDeedPoll(id); // 404 if missing
  const data = coerceDates(dto) as Prisma.DeedPollUpdateInput;

  // If Type is part of this update, recompute the prefix from the new type,
  // preserving the existing year + serial (a DP↔DR flip re-prefixes; the
  // serial is never re-allocated). Date-received edits do NOT renumber.
  if (data.type !== undefined) {
    const newType = (data.type as DeedPollType | null) ?? null;
    const reprefixed = referenceNumbers.reprefixForUpdate(existing.displayReference, newType);
    if (reprefixed) {
      data.displayReference = reprefixed;
    }
  }

  // Recalculate state from the MERGED dates (existing record overlaid with the
  // patch), so a partial update touching one date still derives correctly.
  const merged = { ...existing, ...data };

  // "Show or hide Date Notice Sent to London Gazette": if the merged record has
  // publish = No, clear the notice date (whether the No or the date came from
  // this patch or already existed).
  Object.assign(data, validation.applyGazetteGate(merged as ValidationInput));

  // "Show hide and mandate fields for Minors": clear the four minor fields when
  // the MERGED type is not a minor type — so converting Minor → Adult drops the
  // now-meaningless minor data, while a patch that never mentions type keeps
  // whatever the existing type already permits.
  Object.assign(data, validation.applyMinorGate(merged as ValidationInput));

  // Cross-field date rules, checked against the MERGED dates for the same reason
  // the state is derived from them: a patch setting one date of a pair is valid or
  // not depending on the other, which it may never mention.
  //
  // This throws, where the privacy lock silently drops a field — the two are
  // different kinds of problem. Privacy is a field the caller was not entitled to
  // set, and the rest of their save is still legitimate. A contradictory pair of
  // dates makes the record itself incoherent, and the derived state would be a
  // guess: the source shows an error and refuses, so silently keeping one date
  // would invent a decision the caseworker did not make.
  const dateErrors = caseState.collectDateRuleErrors(stateInputFrom(merged as Record<string, unknown>));
  if (dateErrors.length > 0) {
    throw new InvalidInputError("Case dates are inconsistent", dateErrors);
  }

  data.state = caseState.deriveState(stateInputFrom(merged as Record<string, unknown>));

  // Privacy is admin-only on an existing record. Enforced here, on the write path,
  // because the template's `disabled` attribute is a courtesy to the browser and not
  // a control: until 2026-08-10 a non-admin POSTing `privacy` (or PATCHing the API)
  // wrote it. The MERGED type is passed for the same reason the gates above use it —
  // the entitlement depends on the type the record will have after this write.
  applyPrivacyAuthorisation(data, (merged as { type?: DeedPollType | null }).type ?? null, ctx, false);

  // Stamp the author of THIS write. `createdBy` is deliberately deleted rather
  // than preserved-from-existing: an update must never be able to rewrite who
  // created the record, whatever a caller puts in the payload.
  data.modifiedBy = ctx.userName ?? SERVICE_ACCOUNT_NAME;
  delete data.createdBy;

  const updated = await prisma.deedPoll.update({ where: { id }, data });
  return withComputed(updated);
}

export async function removeDeedPoll(id: string): Promise<void> {
  await findDeedPoll(id); // 404 if missing
  await prisma.deedPoll.delete({ where: { id } });
}

/**
 * Field show/hide/mandate rules for a Type — consumed by the SSR form (Phase 9)
 * to mirror the Dynamics form's per-type behaviour.
 */
export function getCaseFieldRules(type?: DeedPollType | null, migrated = false, publishToGazette = true) {
  return validation.getFieldRules(type ?? null, migrated, publishToGazette);
}

/**
 * Validate a case for submission (the point at which the source form's
 * required-levels must all be satisfied). Loads the record, checks the merged
 * state, and throws 400 with the collected violations if any remain. Kept
 * separate from update() so incremental saves stay unblocked.
 */
export async function validateCaseForSubmit(id: string): Promise<DeedPollWithChildren> {
  const record = await findDeedPoll(id);
  const errors = validation.validate(record as unknown as ValidationInput);
  if (errors.length > 0) {
    throw new InvalidInputError("Case is not valid for submission", errors);
  }
  return record;
}
