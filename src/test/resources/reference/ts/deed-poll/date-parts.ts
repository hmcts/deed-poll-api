/**
 * Conversions between a stored date and the three parts a GOV.UK date input renders.
 *
 * The forms submit `<name>-day`, `<name>-month` and `<name>-year` separately, so every page that
 * shows or accepts a date needs to move between that shape and a single value. Collected here
 * rather than left as private helpers on the page, which is where the prototype had them — three
 * of its twelve handlers needed them, and `form-payload.ts` had already grown a fourth copy of the
 * composing half.
 *
 * `composeDateParts` is that fourth copy, moved rather than duplicated: `form-payload.ts` now
 * imports it, so there is one definition of what a valid submitted date is.
 */

/** The three inputs a `govukDateInput` renders, as strings because that is what a form submits. */
export interface DateParts {
  day: string;
  month: string;
  year: string;
}

const EMPTY: DateParts = { day: "", month: "", year: "" };

/**
 * GOV.UK date parts → ISO string, or null when incomplete or not a real date.
 *
 * UTC deliberately. These are calendar dates — a date received, a date enroled — with no time
 * component, so constructing them in local time would shift them a day either side of midnight
 * depending on where the server happens to be.
 */
export function composeDateParts(day: string, month: string, year: string): string | null {
  if (!day || !month || !year) return null;
  const date = new Date(Date.UTC(Number(year), Number(month) - 1, Number(day)));
  return Number.isNaN(date.getTime()) ? null : date.toISOString();
}

/**
 * A stored value → parts, for pre-filling an edit form. An unset or unparseable value yields
 * empty strings rather than throwing, because a half-populated record is normal here: a case is
 * logged incrementally and most of its dates are null for most of its life.
 */
export function splitDateParts(value: unknown): DateParts {
  if (!value) return { ...EMPTY };

  const date = value instanceof Date ? value : new Date(String(value));
  if (Number.isNaN(date.getTime())) return { ...EMPTY };

  return {
    day: String(date.getUTCDate()),
    month: String(date.getUTCMonth() + 1),
    year: String(date.getUTCFullYear())
  };
}

/**
 * The parts exactly as submitted, for re-rendering a form that failed validation.
 *
 * Deliberately not routed through `composeDateParts` and back: the whole reason the form is being
 * re-rendered is that the parts did not compose, so the offending `99` has to stay on screen next
 * to the error explaining it. Reading the stored value instead would erase what the caseworker
 * typed and leave them looking at an error against a field that now appears fine.
 */
export function submittedDateParts(name: string, body: Record<string, unknown>): DateParts {
  const part = (suffix: string) => {
    const value = body[`${name}-${suffix}`];
    return typeof value === "string" ? value : "";
  };

  return { day: part("day"), month: part("month"), year: part("year") };
}
