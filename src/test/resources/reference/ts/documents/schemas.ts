import { DocumentDirection } from "@hmcts/postgres-prisma";
import { z } from "zod";
import { optionalText, optionSetValues } from "../schema-primitives.js";

/**
 * Create payload for a supporting-document metadata row (deedpoll_supportingdocument).
 * `direction`, `name` and the `documentTypeId` FK are required; the file itself is attached
 * separately, which is why the storage fields are optional here.
 */
export const createDocumentSchema = z.object({
  direction: z.enum(DocumentDirection),
  name: z.string().max(100),
  documentTypeId: z.uuid(),

  storageKey: optionalText,
  originalName: optionalText,
  mimeType: optionalText
});

/** Update payload — PATCH semantics. */
export const updateDocumentSchema = createDocumentSchema.partial();

/**
 * The non-file fields of the upload form, which arrive alongside the file in a multipart body.
 *
 * Separate from `createDocumentSchema` because an upload supplies less: `direction` and `name` are
 * optional (the domain defaults direction to Incoming and falls back to the file's own name), and
 * the storage fields are set from the file rather than by the caller.
 *
 * The prototype validated none of this — `@Body('documentTypeId')` and friends are raw primitives,
 * which its global `ValidationPipe` skipped, so an unrecognised `direction` reached Prisma and a
 * missing `documentTypeId` produced a foreign-key failure. Both are 400s here.
 *
 * Every value arrives as a string: multipart carries no types. That only shows in `direction`,
 * whose enum members are strings anyway.
 */
export const uploadFormSchema = z.object({
  documentTypeId: z.uuid(),
  name: z.string().max(100).optional(),
  direction: z.enum(DocumentDirection).optional()
});

/**
 * Create payload for a supporting-document type (deedpoll_supportingdocumenttype) — reference
 * data, not case data. The two applicability lists are multiselect option-set values stored as
 * int[], preserving the Dynamics integers so a future Dataverse re-import still lines up.
 */
export const createDocumentTypeSchema = z.object({
  name: z.string().max(100),
  applicableMaritalStatuses: optionSetValues,
  applicableTypes: optionSetValues
});

/** Update payload — PATCH semantics. */
export const updateDocumentTypeSchema = createDocumentTypeSchema.partial();

export type CreateDocumentInput = z.infer<typeof createDocumentSchema>;
export type UpdateDocumentInput = z.infer<typeof updateDocumentSchema>;
export type UploadFormInput = z.infer<typeof uploadFormSchema>;
export type CreateDocumentTypeInput = z.infer<typeof createDocumentTypeSchema>;
export type UpdateDocumentTypeInput = z.infer<typeof updateDocumentTypeSchema>;
