import type { DeedPoll } from "@hmcts/postgres-prisma";
import { LABELS } from "./enums/dynamics-values.js";

/**
 * The case header shown above the tabs on the detail page, porting the Dynamics
 * form header (power_app/screenshots/aplication-header.png). The source shows five
 * things next to the record title: State, Modified By, Modified On and Created By,
 * with "Deed Poll" as the entity subtitle.
 *
 * Two deliberate departures from the screenshot:
 *   - Type is added as a row. The Dynamics header omits it, but it is the field that
 *     drives every per-type rule in the form, so a caseworker should not have to open
 *     a tab to see it.
 *   - The "- Saved" suffix and the header-collapse chevron are not ported. Both are
 *     Power Apps platform furniture: a server-rendered page has no dirty-form state
 *     and no collapsible header, so neither has anything to convey here.
 *
 * Rendered as a govukSummaryList, so no custom CSS is involved and the pairs stack
 * correctly on a narrow viewport.
 */

/** The entity name, shown as the caption above the record title. */
export const ENTITY_LABEL = "Deed Poll";

/**
 * Placeholder for an absent header value. Distinct from detail-view's "Not provided":
 * these fields are never asked of anybody, so an empty one means the system did not
 * record it (a row predating the audit columns), not that someone left it blank.
 */
export const NOT_RECORDED = "Not recorded";

export interface CaseHeaderRow {
  key: string;
  value: string;
  /** Render the value as a govukTag rather than plain text (State only). */
  tag?: boolean;
}

export interface CaseHeader {
  /** Caption above the <h1>. */
  entityLabel: string;
  rows: CaseHeaderRow[];
}

/** Look a stored enum member up in the shared label registry. */
function label(set: string, value: unknown): string {
  if (value === null || value === undefined || value === "") return NOT_RECORDED;
  const map = (LABELS as Record<string, Record<string, string>>)[set];
  return map?.[String(value)] ?? String(value);
}

/**
 * A timestamp in GOV.UK style: "13 May 2026 at 9:44am". The source renders
 * `13/05/2026 09:44`, but every other date in this app is GOV.UK-formatted and a
 * single numeric outlier in the header would be the odd one out.
 *
 * Formatted in Europe/London, not UTC: `modifiedOn` is a UTC instant, and a save made
 * at 09:44 British Summer Time must read back as 9:44am rather than 8:44am. Naming the
 * zone explicitly also keeps the output independent of the server's local TZ.
 */
export function formatTimestamp(raw: unknown): string {
  if (raw === null || raw === undefined || raw === "") return NOT_RECORDED;
  const d = raw instanceof Date ? raw : new Date(String(raw));
  if (Number.isNaN(d.getTime())) return NOT_RECORDED;

  const date = d.toLocaleDateString("en-GB", {
    day: "numeric",
    month: "long",
    year: "numeric",
    timeZone: "Europe/London"
  });
  // en-GB gives "09:44" for a 2-digit hour cycle; GOV.UK style is "9:44am".
  const time = d
    .toLocaleTimeString("en-GB", {
      hour: "numeric",
      minute: "2-digit",
      hour12: true,
      timeZone: "Europe/London"
    })
    .replace(/\s?(am|pm)$/i, (_m, meridiem: string) => meridiem.toLowerCase());

  return `${date} at ${time}`;
}

/** A stored author name, or the placeholder for a record predating the columns. */
function author(raw: unknown): string {
  const name = typeof raw === "string" ? raw.trim() : "";
  return name.length > 0 ? name : NOT_RECORDED;
}

/**
 * Build the header view model for one case. Every row is always present — unlike the
 * tab sections, which drop empty fields. The header is a fixed set of five facts about
 * the record, and a header whose rows came and went by case would be harder to read,
 * not cleaner.
 */
export function buildCaseHeader(record: Partial<DeedPoll>): CaseHeader {
  return {
    entityLabel: ENTITY_LABEL,
    rows: [
      { key: "Type", value: label("type", record.type) },
      { key: "State", value: label("state", record.state), tag: true },
      { key: "Modified By", value: author(record.modifiedBy) },
      { key: "Modified On", value: formatTimestamp(record.modifiedOn) },
      { key: "Created By", value: author(record.createdBy) }
    ]
  };
}
