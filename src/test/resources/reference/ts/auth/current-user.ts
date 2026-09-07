import type { Role } from "./roles.js";
import { DEEDPOLL_ADMIN } from "./roles.js";

/**
 * Fallback author for a write with no identified user. Matches the service account the
 * source system records for automated/integration writes, so an unattributed write is
 * labelled as one rather than left blank.
 */
export const SERVICE_ACCOUNT_NAME = "# svc_HMCTS_LCPT_Dev";

/**
 * The authenticated caller, as far as the domain is concerned.
 *
 * How this is populated is a transport concern and lives in the apps. The prototype read
 * it from `x-user-roles` / `x-user-name` request headers, which was explicitly a stand-in:
 * anyone could send `x-user-roles: deedpoll-admin` and become an admin. Replacing that with
 * a real IdP session is a separate piece of work, and nothing in this file assumes either
 * mechanism.
 *
 * `name` is only ever used as audit text (createdBy / modifiedBy), never for
 * authorisation — that is driven entirely by `roles` — so a wrong name cannot escalate
 * anything.
 */
export interface CurrentUser {
  roles: Role[];
  isAdmin: boolean;
  name: string;
}

/** Build a CurrentUser from a resolved set of roles and an optional display name. */
export function currentUser(roles: Role[], name?: string | null): CurrentUser {
  const trimmed = (name ?? "").trim();
  return {
    roles,
    isAdmin: roles.includes(DEEDPOLL_ADMIN),
    name: trimmed.length > 0 ? trimmed : SERVICE_ACCOUNT_NAME
  };
}
