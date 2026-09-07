/**
 * View model for one editable tab of a case, and the error summary that goes with it.
 *
 * The template gets fields already decorated with their value, their rule, their enum options and
 * their date parts, so it needs no per-field logic — which is the only way a 25-field form stays
 * legible in Nunjucks. Ported from the prototype's private `editViewModel`; it lives in the domain
 * because every decision it makes (is this field required, may this caller edit privacy, which
 * options does this enum have) is a business rule, and none of it is HTTP.
 */
import type { DeedPollType } from "@hmcts/postgres-prisma";
import type { DateParts } from "./date-parts.js";
import { splitDateParts, submittedDateParts } from "./date-parts.js";
import { getFieldRules } from "./deed-poll-validation.js";
import { enumOptions, type FieldDef, FORM_TABS, type TabDef } from "./form-fields.js";
import { privacyEditable } from "./form-presentation.js";
import type { FormError } from "./form-validation.js";

/** A field, plus everything the template needs in order to render it without deciding anything. */
export interface EditField extends FieldDef {
  value: unknown;
  dateParts: DateParts | null;
  rule: { visible: boolean; required: boolean };
  options: { value: string; text: string }[];
  /** Editable in principle but not by this caller — currently only `privacy`. */
  locked: boolean;
}

export interface EditSection {
  label: string;
  fields: EditField[];
}

export interface EditView {
  case: Record<string, unknown>;
  tab: { id: string; label: string; subgrid: string | null };
  sections: EditSection[];
  tabs: { id: string; label: string }[];
}

/** Who is editing, as far as field-level editability is concerned. */
export interface EditContext {
  isAdmin: boolean;
}

/** Resolve a tab id from a URL segment, falling back to the first tab. */
export function tabFor(id: unknown): TabDef {
  return FORM_TABS.find((tab) => tab.id === id) ?? FORM_TABS[0];
}

/** The non-read-only field names on a tab — what an error summary may legitimately link to. */
export function editableFieldNames(tab: TabDef): Set<string> {
  return new Set(tab.sections.flatMap((section) => section.fields.filter((field) => !field.readOnly).map((field) => field.name)));
}

/**
 * Build the error summary for a rejected save.
 *
 * An entry links only when its field is actually on the submitted tab. The cross-field date rules
 * can produce an error addressed to a field on a different tab — the Enroled+Returned rule is
 * addressed to `state`, which lives on the Application tab, so saving the Return tab yields a real
 * error about a field that is not on screen. A `#state` link there would be a dead anchor, which is
 * worse than no link at all: the message reads perfectly well on its own.
 */
export function buildErrorSummary(errors: FormError[], tab: TabDef): { text: string; href?: string }[] {
  const onTab = editableFieldNames(tab);

  return errors.map((error) => ({
    text: error.message,
    href: onTab.has(error.field) ? `#${error.field}` : undefined
  }));
}

/**
 * Decorate a tab's fields with everything needed to render them.
 *
 * `submitted`, when present, is a rejected POST body replayed over the stored record, so a
 * re-rendered form shows what the caseworker typed rather than what is saved. Read-only fields are
 * excluded from that replay — they were never editable, so the body has nothing to say about them
 * and the stored value is the truth.
 */
export function buildEditView(record: Record<string, unknown>, tab: TabDef, ctx: EditContext, submitted?: Record<string, unknown>): EditView {
  const type = (record.type ?? null) as DeedPollType | null;
  const rules = getFieldRules(type, record.migratedRecord === true, record.publishToLondonGazette !== false);
  const canEditPrivacy = privacyEditable(type, { isAdmin: ctx.isAdmin, isNew: false });

  const sections = tab.sections.map((section) => ({
    label: section.label,
    fields: section.fields.map((field) => decorate(field, record, rules, canEditPrivacy, submitted))
  }));

  return {
    case: record,
    tab: { id: tab.id, label: tab.label, subgrid: tab.subgrid ?? null },
    sections,
    tabs: FORM_TABS.map((t) => ({ id: t.id, label: t.label }))
  };
}

function decorate(
  field: FieldDef,
  record: Record<string, unknown>,
  rules: ReturnType<typeof getFieldRules>,
  canEditPrivacy: boolean,
  submitted?: Record<string, unknown>
): EditField {
  const replaying = submitted !== undefined && !field.readOnly;
  const value = replaying ? (submitted[field.name] ?? null) : (record[field.name] ?? null);

  return {
    ...field,
    value,
    dateParts: field.kind !== "date" ? null : replaying ? submittedDateParts(field.name, submitted) : splitDateParts(record[field.name] ?? null),
    rule: rules[field.name] ?? { visible: true, required: false },
    options: field.enumSet ? enumOptions(field.enumSet) : [],
    // Privacy editability is governed by the caller's role, separately from the per-type rules.
    locked: field.name === "privacy" ? !canEditPrivacy : false
  };
}
