/**
 * View model for the case-list table.
 *
 * Built here rather than formatted in the template, which is how the prototype did it via two
 * custom Nunjucks filters (`govdate` and `label`) registered in `nunjucks.setup.ts`. Those filters
 * were the only reason that file existed beyond view-engine setup, and `configureGovuk` — which
 * `apps/web` already uses — does not register them. Reproducing them would mean either patching a
 * template-provided starter or carrying a bespoke Nunjucks environment; formatting in the view model
 * needs neither, and unlike a filter it can be tested directly.
 *
 * It reuses `formatValue` and `FIELD_BY_NAME`, so a column shows exactly what the same field shows
 * on the detail page. A second formatter here would be a second opinion about what a Type is called.
 */
import type { DeedPoll } from "@hmcts/postgres-prisma";
import { formatValue, NOT_PROVIDED } from "./detail-view.js";
import { FIELD_BY_NAME } from "./form-fields.js";

/** A row of the list table, with every cell already a string the template prints as-is. */
export interface CaseListRow {
  id: string;
  reference: string;
  /** Rendered as a red tag when private, so the state is not carried by colour alone. */
  privacy: string;
  isPrivate: boolean;
  dateReceived: string;
  type: string;
  state: string;
  newForename: string;
}

/** The table's column headings, beside the row builder so the two cannot fall out of step. */
export const CASE_LIST_COLUMNS = ["Reference", "Privacy", "Received", "Type", "State", "New forename"];

export function buildCaseList(
  cases: Pick<DeedPoll, "id" | "displayReference" | "privacy" | "dateReceived" | "type" | "state" | "newForename">[]
): CaseListRow[] {
  return cases.map((record) => ({
    id: record.id,
    // Not run through formatValue: the reference is already a display string, so formatting it
    // would only risk changing it. It is nullable in the schema even though `createDeedPoll` always
    // allocates one — a seeded or imported row need not have — so the placeholder is possible here,
    // and a reference-less case showing as such is the honest rendering. The row still links.
    reference: record.displayReference ?? NOT_PROVIDED,
    privacy: format("privacy", record.privacy),
    isPrivate: record.privacy === "Private",
    dateReceived: format("dateReceived", record.dateReceived),
    type: format("type", record.type),
    state: format("state", record.state),
    newForename: format("newForename", record.newForename)
  }));
}

/**
 * Format one cell by looking its field up in the shared metadata.
 *
 * A name absent from `FIELD_BY_NAME` would silently stringify, so it falls back to `String()` only
 * for a genuinely unset value and otherwise trusts the metadata — every name used above is on a
 * form tab, and a test pins that.
 */
function format(name: string, raw: unknown): string {
  const field = FIELD_BY_NAME[name];
  if (!field) return raw === null || raw === undefined || raw === "" ? NOT_PROVIDED : String(raw);
  return formatValue(field, raw);
}
