/**
 * Role identifiers. The single privileged role ports the Dynamics security role
 * "Deed Poll Admin User" — the source's admin gate for privacy editability and
 * the create-time privacy auto-default (see FormPresentationService).
 *
 * The role name follows HMCTS IDAM convention: caseworker-{product}-{subrole}.
 */
export const DEEDPOLL_ADMIN = "caseworker-deedpoll-admin";

/** All roles a request may carry. Extend as further roles are modelled. */
export type Role = typeof DEEDPOLL_ADMIN;
