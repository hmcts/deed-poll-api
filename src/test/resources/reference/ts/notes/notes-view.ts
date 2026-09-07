import type { DeedPollNote } from "@hmcts/postgres-prisma";
import { formatTimestamp, NOT_RECORDED } from "../deed-poll/case-header.js";

/**
 * View model for the Notes subgrid on the case detail page, porting the source's
 * notes grid (power_app/screenshots/note_overview.png).
 *
 * The source grid has seven columns: NoteID, Deed Poll, Note, Modified By, Modified On,
 * Created By, Created On. One is dropped, one is re-derived, and the four audit columns
 * are paired into two:
 *
 *   - **NoteID** keeps its label but shows a short code derived from `id`, not the
 *     source's autonumber (which is not ported — see `DeedPollNote` in schema.prisma).
 *     See `shortCode()` for why 8 characters is safe here.
 *   - **Deed Poll** repeats the `<h1>` of the page the grid is nested inside. The
 *     documents subgrid on the same page does not restate it either.
 *   - **Created** and **Modified** each render their timestamp with the author beneath
 *     it, rather than as four columns. Two columns instead of four keeps the Note itself
 *     readable width, and binding each time to its actor makes "who changed this, when"
 *     one glance. The timestamp leads because that is what a note history is scanned
 *     for; hence the model exposes all four values and lets the template pair them.
 *
 * Also not ported, all Power Apps grid furniture with no server-rendered equivalent:
 * the per-row checkboxes (no bulk actions exist), Refresh (the page reload IS the
 * refresh), Quick find (a filter box for a list that is nearly always one screen), the
 * column-sort chevrons and the horizontal scrollbar. "Rows: 1" IS ported — a count is
 * genuinely useful — but as a GOV.UK caption rather than a grid footer.
 */

/**
 * A note's `id` as a short human-readable code: the first 8 hex digits, uppercased and
 * hyphenated into two groups of four — `67EC-4D74`. It is the leading run of the same
 * uuid that appears in the note's URL, so the code and the id are visibly one thing.
 *
 * **Display only. Never a lookup key.** Eight hex digits is 32 bits, so codes are not
 * globally unique: across a whole notes table two rows will eventually share one (about a
 * 25% chance somewhere by 50,000 notes). That is harmless *here* because notes are only
 * ever listed scoped to a single case, and a clash is invisible unless both notes appear
 * in the same list — for a case carrying 20 notes that is roughly 1 in 22 million, and 1
 * in 870,000 even at 100 notes. Decision Steve's, 2026-08-10.
 *
 * Two conditions this reasoning depends on. Break either and the short code stops being
 * safe, so change it back to the full `id` if you do:
 *   1. Routing stays on the full uuid. If the code ever *resolved* a note, a table-wide
 *      collision would become a correctness bug rather than a cosmetic one.
 *   2. Notes are only ever displayed per-case. A cross-case notes list would put many
 *      codes in one view and the per-case arithmetic above would no longer apply.
 */
export function shortCode(id: string): string {
  const hex = id.replace(/-/g, "").toUpperCase();
  // A non-uuid id (nothing generates one, but the type does not forbid it) is returned
  // uppercased and ungrouped rather than padded into a fake code.
  if (hex.length < 8) return hex;
  return `${hex.slice(0, 4)}-${hex.slice(4, 8)}`;
}

/** One row of the notes grid, already formatted for display. */
export interface NoteRow {
  /** Note id, for the amend link. */
  id: string;
  /** `id` as the short display code shown under the NoteID label. */
  code: string;
  /** The note text itself. */
  note: string;
  createdBy: string;
  createdOn: string;
  modifiedBy: string;
  modifiedOn: string;
  /**
   * True when this note has never been edited since creation, so the Modified column
   * can say "Not edited" rather than repeating the creation pair. Compared on the stored
   * instants, not the formatted strings, so two writes inside the same displayed minute
   * still count as edited.
   */
  unmodified: boolean;
}

export interface NotesView {
  rows: NoteRow[];
  /** Row count, shown as a caption above the table. */
  count: number;
}

/** A stored author name, or the placeholder for a note predating the audit columns. */
function author(raw: unknown): string {
  const name = typeof raw === "string" ? raw.trim() : "";
  return name.length > 0 ? name : NOT_RECORDED;
}

/** Milliseconds for a stored timestamp, or null when absent/unparseable. */
function instant(raw: Date | string | null | undefined): number | null {
  if (raw === null || raw === undefined || raw === "") return null;
  const d = raw instanceof Date ? raw : new Date(String(raw));
  return Number.isNaN(d.getTime()) ? null : d.getTime();
}

/**
 * One note formatted for the amend page (power_app/screenshots/note_amend_note.png),
 * which shows the same four audit values the grid does — the source puts them in its
 * form header — plus the text to pre-fill and the code that titles the page.
 *
 * Deliberately the same shape as a grid row, built by the same function, so the two
 * pages cannot format one note differently. `unmodified` lets the page make the same
 * "Not edited" statement the grid does rather than repeating the creation pair.
 */
export function buildNoteDetail(note: DeedPollNote): NoteRow {
  return buildNotesView([note]).rows[0];
}

/**
 * Build the notes view model.
 *
 * Ordered newest first: the source grid is sorted by Modified On descending (the ↓ on
 * that column in the screenshot), and on a case that accumulates notes over months the
 * latest is what a caseworker wants first. `findAll()` already applies this order in
 * the query; sorting here too keeps the model correct for any caller that does not —
 * including the detail page, whose `include` orders notes ascending for the other tabs.
 *
 * A note with no usable `modifiedOn` sorts last rather than being dropped or treated as
 * the epoch, the same rule the prototype's case timeline follows.
 */
export function buildNotesView(notes: DeedPollNote[]): NotesView {
  const rows = [...notes]
    .sort((a, b) => {
      const at = instant(a.modifiedOn);
      const bt = instant(b.modifiedOn);
      if (at === null && bt === null) return 0;
      if (at === null) return 1;
      if (bt === null) return -1;
      return bt - at;
    })
    .map((n) => {
      const created = instant(n.createdOn);
      const modified = instant(n.modifiedOn);
      return {
        id: n.id,
        code: shortCode(n.id),
        note: n.note,
        createdBy: author(n.createdBy),
        createdOn: formatTimestamp(n.createdOn),
        modifiedBy: author(n.modifiedBy),
        modifiedOn: formatTimestamp(n.modifiedOn),
        unmodified: created !== null && modified !== null && created === modified
      };
    });

  return { rows, count: rows.length };
}
