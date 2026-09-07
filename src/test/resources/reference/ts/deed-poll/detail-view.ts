import { type CardBlock, type CardColumn, splitIntoBlocks } from "./card-layout.js";
import type { FieldRules } from "./deed-poll-validation.js";
import { LABELS } from "./enums/dynamics-values.js";
import { type FieldDef, FORM_TABS, type SectionDef, type TabDef } from "./form-fields.js";
import type { FormPresentation } from "./form-presentation.js";

/**
 * Read-only detail view model, built from the SAME FORM_TABS metadata that drives
 * the edit forms. This is the point of the module: the detail page used to hand-list
 * six tabs while the form had nine, so address, minor and declaration fields were
 * editable but never readable back. Deriving both from one source makes that class
 * of drift impossible — a field added to form-fields.ts appears on both pages.
 *
 * Two filters are applied, mirroring what the edit form does:
 *   - per-type field rules (getFieldRules) hide fields the Type does not use,
 *     e.g. minor fields on an adult case;
 *   - FormPresentation.tabs hides the three conditional tabs (Referral to Master,
 *     Enrolment, Return).
 * A section whose every field is hidden or empty is dropped rather than rendered
 * as a block of "Not provided" rows.
 */

/** Placeholder for an absent value, matching the prototype's house style. */
export const NOT_PROVIDED = "Not provided";

export interface DetailRow {
  key: string;
  value: string;
  /** Card column this row belongs in (see card-layout.ts). */
  column?: CardColumn;
}

export interface DetailSection {
  label: string;
  rows: DetailRow[];
  /**
   * The section's rows as an ordered sequence of card bands (two-column, or full
   * width). A view OF `rows`, not a second source of truth — every row must appear
   * in exactly one band or it would be invisible on the page.
   */
  blocks: CardBlock<DetailRow>[];
}

export interface DetailTab {
  id: string;
  label: string;
  /** Set for child-entity tabs, which render a list rather than rows. */
  subgrid?: "notes" | "witnesses" | "documents";
  sections: DetailSection[];
  /** True when the tab has nothing to show (drives the "None recorded" message). */
  empty: boolean;
}

/** Format one stored value for display, by field kind. */
export function formatValue(field: FieldDef, raw: unknown): string {
  if (raw === null || raw === undefined || raw === "") return NOT_PROVIDED;

  switch (field.kind) {
    case "date":
      return formatDate(raw);

    case "boolean":
      return raw ? "Yes" : "No";

    case "enum": {
      const map = field.enumSet ? (LABELS as Record<string, Record<string, string>>)[field.enumSet] : undefined;
      return map?.[String(raw)] ?? String(raw);
    }

    case "multiselect": {
      const values = Array.isArray(raw) ? raw : [raw];
      if (values.length === 0) return NOT_PROVIDED;
      const map = field.enumSet ? (LABELS as Record<string, Record<string, string>>)[field.enumSet] : undefined;
      return values.map((v) => map?.[String(v)] ?? String(v)).join(", ");
    }

    case "number":
      // A Prisma Decimal (refundAmount) stringifies correctly; 0 must survive.
      return String(raw);

    default:
      return String(raw);
  }
}

/** GOV.UK-style "1 January 2026", or the placeholder when unparseable. */
function formatDate(raw: unknown): string {
  const d = raw instanceof Date ? raw : new Date(String(raw));
  if (Number.isNaN(d.getTime())) return NOT_PROVIDED;
  return d.toLocaleDateString("en-GB", {
    day: "numeric",
    month: "long",
    year: "numeric",
    timeZone: "UTC"
  });
}

/** Should this field appear at all, per the type's field rules? */
function visible(field: FieldDef, rules: FieldRules): boolean {
  return rules[field.name]?.visible !== false;
}

/**
 * Build the display rows for one section, dropping only fields the type HIDES.
 *
 * Empty values are kept and render "Not provided". This replaced the original
 * skip-if-empty rule when the page moved to two-column cards: the cards exist to
 * replicate the Dynamics form so staff find a given field in the same place every
 * time, and a row that disappears when blank defeats that — it shifts every field
 * below it. Per-type hidden fields are still dropped, so an adult case shows no
 * minor fields; that is a statement about the case type, not about this record.
 */
function buildSection(section: SectionDef, record: Record<string, unknown>, rules: FieldRules): DetailSection | null {
  const rows: DetailRow[] = [];
  for (const field of section.fields) {
    if (!visible(field, rules)) continue;
    rows.push({
      key: field.label,
      value: formatValue(field, record[field.name]),
      column: field.column
    });
  }
  if (rows.length === 0) return null;
  return { label: section.label, rows, blocks: splitIntoBlocks(rows) };
}

/** Which conditional tabs are visible, keyed by tab id. */
function tabVisible(tab: TabDef, presentation: FormPresentation): boolean {
  switch (tab.id) {
    case "referral":
      return presentation.tabs.referralToMaster;
    case "enrolment":
      return presentation.tabs.enrolment;
    case "return":
      return presentation.tabs.return;
    default:
      return true;
  }
}

/** Count of child rows backing a subgrid tab, for its empty state. */
function subgridCount(subgrid: NonNullable<TabDef["subgrid"]>, record: Record<string, unknown>): number {
  const list = record[subgrid];
  return Array.isArray(list) ? list.length : 0;
}

/**
 * Build the full detail view model: every FORM_TABS tab the record's type and
 * state allow, each with its populated rows. Subgrid tabs carry no sections —
 * the template renders their child records directly off `case`.
 */
export function buildDetailTabs(record: Record<string, unknown>, rules: FieldRules, presentation: FormPresentation): DetailTab[] {
  const tabs: DetailTab[] = [];

  for (const tab of FORM_TABS) {
    if (!tabVisible(tab, presentation)) continue;

    if (tab.subgrid) {
      tabs.push({
        id: tab.id,
        label: tab.label,
        subgrid: tab.subgrid,
        sections: [],
        empty: subgridCount(tab.subgrid, record) === 0
      });
      continue;
    }

    const sections = tab.sections.map((s) => buildSection(s, record, rules)).filter((s): s is DetailSection => s !== null);

    tabs.push({
      id: tab.id,
      label: tab.label,
      sections,
      empty: sections.length === 0
    });
  }

  return tabs;
}
