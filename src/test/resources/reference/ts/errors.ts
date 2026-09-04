/**
 * Domain errors.
 *
 * The prototype threw Nest's HTTP exceptions (`NotFoundException`,
 * `BadRequestException`) straight out of its services. That put an HTTP concern in the
 * domain layer, which this repo's conventions forbid — and it would have made
 * `@nestjs/common` a dependency of this library. These are the framework-free
 * equivalents; the route and page handlers map them to status codes.
 */

import type { ZodError } from "zod";

/** A record that was asked for does not exist, or is not visible in the given scope. */
export class NotFoundError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "NotFoundError";
  }
}

/**
 * The caller is known but does not hold a role the operation requires.
 * Replaces the `ForbiddenException` the prototype's `RolesGuard` threw.
 *
 * Authorisation is a domain rule here, not a transport one — which role gates which
 * operation is part of the deed-poll model (see `auth/roles.ts`), so the error belongs
 * with the other domain errors even though only the apps currently throw it.
 */
export class ForbiddenError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "ForbiddenError";
  }
}

/**
 * The caller is not identified at all — no credential, or one that does not hold up.
 *
 * The distinction from `ForbiddenError` is the one HTTP draws between 401 and 403, and it is worth
 * keeping: a 401 tells the caller to present a credential, a 403 tells them theirs will never be
 * enough for this operation. Collapsing the two sends a caseworker whose token has simply expired to
 * ask for a role they already have.
 *
 * Introduced with bearer-token authentication in `apps/api`. Nothing in the domain throws it — the
 * same as `ForbiddenError` when it was added — but it belongs with the others so both apps map one
 * vocabulary of failure rather than inventing their own.
 */
export class UnauthorizedError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "UnauthorizedError";
  }
}

/**
 * The request is well-formed and permitted, but the current state of the data forbids it.
 *
 * Distinct from `InvalidInputError` because nothing about the input is wrong: deleting a
 * document type is a perfectly good request that fails only because documents still reference
 * it, and it would succeed unchanged once they no longer do. That is a 409, not a 400 — the
 * caller should retry later or remove the references, not correct a field.
 *
 * Introduced for `removeDocumentType`, whose restrict-on-delete FK previously surfaced as a
 * `DriverAdapterError` that `errorHandler` could not recognise, making an ordinary caseworker
 * action answer 500.
 */
export class ConflictError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "ConflictError";
  }
}

/** A failure addressed to a specific field, so a form can render it in place. */
export interface FieldError {
  field: string;
  message: string;
}

/**
 * The caller's input is not acceptable — the domain rejected it, not the transport.
 * Replaces the prototype's `BadRequestException`.
 *
 * Carries the per-field failures as well as a summary message. That is not decoration: the
 * prototype threw `BadRequestException({ message, errors })` and the SSR forms render those
 * `errors` against the individual inputs. A message-only error would have quietly downgraded
 * every field-level validation failure to an unattributed one.
 *
 * Deliberately not called `ValidationError`: `deed-poll-validation.ts` already exports that
 * name for a per-field result from `validate()`, which is a collected-and-returned failure
 * rather than a thrown one.
 */
export class InvalidInputError extends Error {
  readonly errors: FieldError[];

  constructor(message: string, errors: FieldError[] = []) {
    super(message);
    this.name = "InvalidInputError";
    this.errors = errors;
  }
}

/**
 * Flatten a `ZodError` into the same `FieldError[]` an `InvalidInputError` carries, so a
 * schema rejection and a domain rejection are indistinguishable to whatever renders them.
 * Without this the two arrive in different shapes and the caller needs two code paths.
 *
 * Nested and indexed paths are dotted (`["witnesses", 0, "name"]` → `witnesses.0.name`).
 * An issue with an empty path — a `.refine()` on the object as a whole — yields an empty
 * `field`, which callers should treat as addressed to the form rather than to an input.
 */
export function zodErrorToFieldErrors(error: ZodError): FieldError[] {
  return error.issues.map((issue) => ({
    field: issue.path.map(String).join("."),
    message: issue.message
  }));
}
