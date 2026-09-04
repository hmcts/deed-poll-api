// Deed-poll domain logic, ported from the power_app_nest prototype.
//
// Framework-agnostic by design: no Express, no HTTP concerns, no Nest DI. The apps
// (apps/web pages, apps/api routes) import named functions from here.
//
// The prototype expressed these as Nest @Injectable() classes. They held no state, so
// per this repo's conventions they are plain exported functions here.

export * from "./auth/current-user.js";
export * from "./auth/idam-claims.js";
export * from "./auth/plain-http.js";
export * from "./auth/roles.js";
export * from "./deed-poll/card-layout.js";
export * from "./deed-poll/case-header.js";
export * from "./deed-poll/case-state.js";
export * from "./deed-poll/computed.js";
export * from "./deed-poll/date-parts.js";
export * from "./deed-poll/deed-poll-validation.js";
export * from "./deed-poll/detail-view.js";
export * from "./deed-poll/edit-view.js";
export * from "./deed-poll/enums/dynamics-values.js";
export * from "./deed-poll/form-fields.js";
export * from "./deed-poll/form-payload.js";
export * from "./deed-poll/form-presentation.js";
export * from "./deed-poll/form-validation.js";
export * from "./deed-poll/list-filters.js";
export * from "./deed-poll/list-view.js";
export * from "./deed-poll/reference-number.js";
export * from "./deed-poll/schemas.js";
export * from "./deed-poll/service.js";
export * from "./documents/document-types.js";
export * from "./documents/documents-view.js";
export * from "./documents/file-storage.js";
export * from "./documents/schemas.js";
export * from "./documents/service.js";
export * from "./documents/upload-policy.js";
export * from "./errors.js";
export * from "./health.js";
export * from "./notes/note-form.js";
export * from "./notes/notes-view.js";
export * from "./notes/schemas.js";
export * from "./notes/service.js";
export * from "./schema-primitives.js";
export * from "./witnesses/schemas.js";
export * from "./witnesses/service.js";
