/**
 * Mapping an IDAM identity onto the domain's `CurrentUser`.
 *
 * The workspace standard is explicit that these are separate concerns: *"OpenID Connect establishes
 * the user identity. Services are responsible for their own authorization decisions."*
 * (`docs/reference/standards/authentication-and-authorization.md`). So this file does the narrowest
 * possible job — take whatever IDAM asserts and reduce it to the roles and display name this service
 * recognises. Every authorization decision downstream is made against that, unchanged from the
 * header-based days.
 *
 * In the domain rather than in `apps/web` because it is the same reduction wherever it happens: the
 * SSR pages resolve it from a session and the API would resolve it from a bearer token, and both must
 * agree on which asserted roles count. It takes a plain claims object, so the domain needs no OIDC
 * library.
 */
import { type CurrentUser, currentUser } from "./current-user.js";
import { DEEDPOLL_ADMIN, type Role } from "./roles.js";

/** Every role this service acts on. Anything IDAM asserts that is not here is ignored. */
const KNOWN_ROLES: readonly string[] = [DEEDPOLL_ADMIN];

/**
 * The subset of an IDAM ID token this service reads.
 *
 * Deliberately loose: an ID token carries far more than this, the exact shape varies by IDAM
 * configuration, and asserting a tight type over a document controlled elsewhere would fail at
 * runtime the first time a claim moved. Everything is narrowed defensively below.
 */
export interface IdamClaims {
  roles?: unknown;
  name?: unknown;
  given_name?: unknown;
  family_name?: unknown;
  sub?: unknown;
  [claim: string]: unknown;
}

/**
 * Reduce IDAM's claims to a `CurrentUser`.
 *
 * **Unrecognised roles are dropped, not carried.** IDAM issues roles across the whole CFT estate, so
 * a caseworker's token can name roles from other services entirely. Keeping them would put strings
 * this service has no rules for into the object its authorization decisions are made from, and
 * `isAdmin` would then depend on a list nobody here maintains.
 */
export function userFromClaims(claims: IdamClaims): CurrentUser {
  return currentUser(rolesFrom(claims.roles), displayNameFrom(claims));
}

/**
 * IDAM may send roles as an array or, on some configurations, a single string. Both are accepted
 * because getting it wrong yields an empty role list, which fails closed but looks like a
 * permissions bug rather than a parsing one.
 */
function rolesFrom(raw: unknown): Role[] {
  const values = Array.isArray(raw) ? raw : typeof raw === "string" ? raw.split(",") : [];

  return values.map((value) => String(value).trim()).filter((value): value is Role => KNOWN_ROLES.includes(value));
}

/**
 * A display name for the audit columns, in descending order of preference.
 *
 * Falls through to `sub` before giving up, because `createdBy` reading as an opaque identifier is
 * still traceable, whereas the service-account fallback would attribute a real person's write to an
 * automated one. `currentUser` supplies that fallback if everything here is absent.
 */
function displayNameFrom(claims: IdamClaims): string | undefined {
  const name = str(claims.name);
  if (name) return name;

  const given = str(claims.given_name);
  const family = str(claims.family_name);
  if (given || family) return [given, family].filter(Boolean).join(" ");

  return str(claims.sub);
}

function str(value: unknown): string | undefined {
  if (typeof value !== "string") return undefined;
  const trimmed = value.trim();
  return trimmed.length > 0 ? trimmed : undefined;
}
