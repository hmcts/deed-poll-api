/**
 * View model for the documents subgrid on the case detail page.
 *
 * Exists for the same reason `list-view.ts` does: the prototype's template formatted these cells
 * with the `label` and `govdate` Nunjucks filters from `nunjucks.setup.ts`, which `configureGovuk`
 * does not provide. Formatting in the view model needs no bespoke Nunjucks environment and can be
 * tested directly.
 *
 * `notes-view.ts` already does this for the notes subgrid, and the witnesses subgrid needs nothing —
 * its three fields are plain strings the template prints as-is.
 */
import type { DeedPollSupportingDocument, DeedPollSupportingDocumentType } from "@hmcts/postgres-prisma";
import { formatValue, NOT_PROVIDED } from "../deed-poll/detail-view.js";
import type { FieldDef } from "../deed-poll/form-fields.js";

/**
 * Field definitions for the two columns that need formatting.
 *
 * Declared here rather than looked up in `FIELD_BY_NAME`, which covers the case's own form fields
 * and contains neither of these — a document is a different entity. They still go through
 * `formatValue`, so the subgrid renders a direction and a date exactly as every other page does.
 */
const DIRECTION_FIELD: FieldDef = { name: "direction", label: "Direction", kind: "enum", enumSet: "documentDirection" };
const ADDED_FIELD: FieldDef = { name: "createdOn", label: "Added", kind: "date" };

/** A document as the subgrid renders it, with every cell already a display string. */
export interface DocumentRow {
  id: string;
  name: string;
  type: string;
  direction: string;
  added: string;
  /** Whether a file is actually attached — the row links only when there is something to fetch. */
  downloadable: boolean;
}

export const DOCUMENT_COLUMNS = ["Document", "Type", "Direction", "Added"];

/** A document with its type relation loaded, as `findDeedPoll` returns them. */
type DocumentWithType = DeedPollSupportingDocument & { documentType?: DeedPollSupportingDocumentType | null };

export function buildDocumentsView(documents: DocumentWithType[]): DocumentRow[] {
  return documents.map((document) => ({
    id: document.id,
    name: document.name,
    // The type is a relation, and the FK is required — but `findDeedPoll` is the only caller that
    // includes it, so an absent relation means "not loaded" rather than "no type". Saying
    // "Not provided" would be a claim about the data; this is a claim about the query.
    type: document.documentType?.name ?? NOT_PROVIDED,
    direction: formatValue(DIRECTION_FIELD, document.direction),
    added: formatValue(ADDED_FIELD, document.createdOn),
    // A metadata row can exist before its file arrives — `createDocument` makes one with no
    // storageKey — so a link would 404 for a document that is legitimately still pending.
    downloadable: Boolean(document.storageKey)
  }));
}
