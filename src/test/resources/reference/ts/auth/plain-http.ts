/**
 * Whether this process may talk to an IDAM issuer over plain HTTP.
 *
 * Both apps need the answer and must not be able to disagree about it. `apps/web` uses it to grant
 * `openid-client` permission to use a cleartext issuer, which it refuses by default; `apps/api` uses
 * it to reject a cleartext issuer URL outright, since `fetch` will happily use one. Different
 * mechanisms, one decision — and the decision is what matters, so it lives here rather than twice.
 *
 * Cleartext matters because of what crosses the connection: the code exchange carries the client
 * secret, the token response carries the tokens themselves, and `/o/userinfo` carries the roles
 * authorisation is derived from. Anything on the path can read all three, and rewrite the third.
 *
 * **This is an allowlist, and its polarity is deliberately the opposite of the rest of the
 * codebase**, which asks `NODE_ENV === "production"` and treats everything else as development. That
 * default-open reading is fine for choosing a log level and wrong for a security relaxation: it would
 * permit cleartext for any value that merely is not the literal string "production" — an unset
 * variable, a Helm env override, `node dist/server.js` run outside the image, or a deployed
 * environment named `aat`. An allowlist requires the relaxation to be asked for. **Do not "correct"
 * this to a negation.**
 *
 * This reasoning was originally written for `header-user.ts`, the `x-user-roles` bypass both apps read
 * identity from before IDAM existed. That file is gone; the reasoning outlived it, and other comments
 * still point here for it.
 *
 * "test" is included because vitest sets `NODE_ENV=test`, and a test issuer is an `http://` one.
 */

/** The only environments in which a cleartext issuer is tolerated. */
export const PLAIN_HTTP_ENVIRONMENTS = new Set(["development", "test"]);

export function plainHttpPermitted(): boolean {
  return PLAIN_HTTP_ENVIRONMENTS.has(process.env.NODE_ENV ?? "");
}
