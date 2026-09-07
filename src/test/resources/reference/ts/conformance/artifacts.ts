/**
 * Conformance-harness fixture loader.
 *
 * Reads the source-of-truth Dataverse artifacts in
 * `.business_context/dataverse_tables/` and exposes them as typed data the
 * conformance specs assert against. This is the ONLY module that touches the
 * filesystem, so re-pointing at the artifacts (or swapping their format) is a
 * one-file change.
 *
 * No HTML-parsing dependency is installed, so the `columns.html` /
 * `relationships.html` extracts are parsed with a small regex over the flat,
 * regular `<td>` grid the export produces. If those files ever gain nested
 * markup this parser must be revisited — `parseGridRows` throws when a row does
 * not have the expected column count, so drift fails loudly rather than silently
 * dropping rows.
 *
 * KNOWN LIMITATION — the `deedpoll/columns.html` extract is alphabetically
 * TRUNCATED: it declares "Total columns: 70" but lists only A–R (ends at
 * "Refund Reference"), so Type/State/Urgency/Section/Retain Permanently and
 * other post-R columns are absent. The conformance specs therefore assert the
 * FORWARD direction only — every column present in the artifact must map to a
 * Prisma field — and never treat the artifact as a complete column set. See
 * `assertNoReverseCompletenessClaim` note in schema.conformance.spec.ts.
 */
import { existsSync, readFileSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

/** A parsed row of a Dataverse `columns.html` grid. */
export interface ColumnRow {
  displayName: string;
  logicalName: string;
  dataType: string;
  required: string;
}

/** A parsed row of the `relationships.html` grid. */
export interface RelationshipRow {
  displayName: string;
  relationshipName: string;
  relatedTable: string;
  relationshipType: string;
}

/** The five exported tables, by their artifact directory name. */
export type TableDir = "deedpoll" | "deeppoll_note" | "deeppoll_witness" | "deeppoll_supporting_document" | "deeppoll_supporting_document_type";

/**
 * Resolve `.business_context/dataverse_tables/` by walking up from this file until the
 * directory is found.
 *
 * The prototype probed for `nest-cli.json` and then looked one level up, which only worked
 * from inside that project. Probing for the artifacts directory itself is both simpler and
 * location-independent: it works from `libs/`, from a compiled `dist/`, and from anywhere else
 * the harness might be run.
 */
function artifactsRoot(): string {
  let dir = dirname(fileURLToPath(import.meta.url));
  for (let i = 0; i < 10; i++) {
    const candidate = resolve(dir, ".business_context", "dataverse_tables");
    if (existsSync(candidate)) return candidate;
    const parent = dirname(dir);
    if (parent === dir) break;
    dir = parent;
  }
  throw new Error(
    "Could not locate .business_context/dataverse_tables. These are the source-of-truth " +
      "Dataverse exports the conformance harness asserts against; without them the " +
      "schema-coverage assertions cannot run."
  );
}

const ROOT = artifactsRoot();

function read(table: TableDir, file: string): string {
  return readFileSync(join(ROOT, table, file), "utf8");
}

/**
 * Extract the `<td>` cells of every `<tr>` in the first `<tbody>`, grouped per
 * row. Throws if any row does not have exactly `expectedCols` cells, so a
 * format change cannot silently drop or misalign columns.
 */
function parseGridRows(html: string, expectedCols: number): string[][] {
  const body = html.match(/<tbody>([\s\S]*?)<\/tbody>/i)?.[1] ?? "";
  const rows: string[][] = [];
  for (const m of body.matchAll(/<tr>([\s\S]*?)<\/tr>/gi)) {
    const cells = [...m[1].matchAll(/<td>([\s\S]*?)<\/td>/gi)].map((c) => c[1].trim());
    if (cells.length !== expectedCols) {
      throw new Error(`Expected ${expectedCols} cells per row but found ${cells.length}: ${m[1]}`);
    }
    rows.push(cells);
  }
  return rows;
}

/** Columns grid has 8 cells: display, name, type, managed, customized, customizable, required, searchable. */
export function loadColumns(table: TableDir): ColumnRow[] {
  const rows = parseGridRows(read(table, "columns.html"), 8);
  return rows.map(([displayName, logicalName, dataType, , , , required]) => ({
    displayName,
    logicalName,
    dataType,
    required
  }));
}

/** Relationships grid has 7 cells: display, name, relatedTable, type, managed, customized, customizable. */
export function loadRelationships(table: TableDir): RelationshipRow[] {
  const rows = parseGridRows(read(table, "relationships.html"), 7);
  return rows.map(([displayName, relationshipName, relatedTable, relationshipType]) => ({
    displayName,
    relationshipName,
    relatedTable,
    relationshipType
  }));
}

/** The declared "Total columns: N" figure, for the truncation sanity-check. */
export function declaredColumnTotal(table: TableDir): number | null {
  const m = read(table, "columns.html").match(/Total columns:<\/strong>\s*(\d+)/i);
  return m ? Number(m[1]) : null;
}

/**
 * True when a table's `business_rules.md` records no rules. The four child
 * tables all carry the sentinel "There [are] no business rules for this table"
 * plus an empty rules table (a single `| - | - |` row). Asserting this pins the
 * fact that nothing was to be ported for them — if a future artifact adds a
 * rule, this flips false and forces someone to port it.
 */
export function hasNoBusinessRules(table: TableDir): boolean {
  const md = read(table, "business_rules.md");
  const sentinel = /no business rules for this table/i.test(md);
  // The rules table body is only the placeholder row (dashes), i.e. no real rule.
  const onlyPlaceholderRows = !/^\|\s*[A-Za-z][^|]*\|/m.test(md.replace(/\|\s*business rule name\s*\|.*$/im, ""));
  return sentinel && onlyPlaceholderRows;
}

export { ROOT as ARTIFACTS_ROOT };
