import { composeDateParts } from "./date-parts.js";
import { EDITABLE_FIELDS, type FieldDef } from "./form-fields.js";

/**
 * Turn a flat SSR form POST body into an update patch, driven by the field
 * metadata so the accepted payload can't drift from the rendered form. Read-only
 * (computed/state/referencenumber) fields are never in the list, so are ignored.
 * Date fields arrive as GOV.UK date-input parts (`<name>-day/-month/-year`) and
 * are composed into ISO strings the service's coerceDates() then turns into Dates.
 *
 * Each field in `fields` is written (empty inputs → null), matching the Dynamics
 * form where saving persists the whole form, clearing blanked fields — so state
 * derivation stays correct (clearing dateEnroled un-enrols). Pass ONLY the fields
 * on the submitted tab so a per-tab save never nulls fields on other tabs.
 *
 * The one exception is a `notNullable` field: its column is NOT NULL, so "absent"
 * must mean "leave alone" rather than "clear". Nulling one is a 500, which is exactly
 * what a non-admin saving the Application tab used to get — `privacy` renders disabled
 * for them and a disabled control is not submitted.
 */
export function buildUpdatePatch(body: Record<string, unknown>, fields: FieldDef[] = EDITABLE_FIELDS): Record<string, unknown> {
  const patch: Record<string, unknown> = {};
  for (const field of fields) {
    if (field.readOnly) continue;
    const value = readField(field, body);
    if (value === null && field.notNullable) continue;
    patch[field.name] = value;
  }
  return patch;
}

function readField(field: FieldDef, body: Record<string, unknown>): unknown {
  switch (field.kind) {
    case "date":
      return composeDateParts(str(body[`${field.name}-day`]), str(body[`${field.name}-month`]), str(body[`${field.name}-year`]));
    case "boolean":
      // Unchecked checkboxes are absent from the body; a present truthy value wins.
      return body[field.name] === "true" || body[field.name] === "on";
    case "number": {
      const v = str(body[field.name]);
      if (v === "") return null;
      const n = Number(v);
      return Number.isNaN(n) ? null : n;
    }
    case "multiselect":
      return toIntArray(body[field.name]);
    // Everything else is read as a plain string: `enum`, `text` and `textarea`, plus any
    // kind added later. The prototype listed those three explicitly ahead of `default`;
    // they are named here instead, because the branch behaves identically for all of them
    // and an empty value always becomes null rather than "".
    default: {
      const v = str(body[field.name]);
      return v === "" ? null : v;
    }
  }
}

/** Coerce a possibly-array/undefined body value to a trimmed string. */
function str(v: unknown): string {
  if (Array.isArray(v)) v = v[0];
  return typeof v === "string" ? v.trim() : "";
}

/** Multi-checkbox → int[]. Body value is a string or array of strings. */
function toIntArray(v: unknown): number[] {
  const arr = Array.isArray(v) ? v : v == null ? [] : [v];
  return arr.map((x) => Number(String(x))).filter((n) => Number.isInteger(n));
}
