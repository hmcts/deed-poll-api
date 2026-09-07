/**
 * Whether this database can actually serve deed-poll traffic.
 *
 * A connection check — `select 1` — proves only that Postgres answers. It says nothing about whether
 * the migrations have run, and an unmigrated pod passes it while every request 500s. That is the worst
 * shape of readiness failure: the probe is green, so Kubernetes keeps sending traffic and nothing
 * escalates. Hence checking for the tables themselves.
 *
 * One query against `information_schema`, not one per table, and it returns the **missing** names
 * rather than a boolean, so whatever reports the failure can say which tables — a health check reduces
 * to UP/DOWN, and "readiness DOWN" with no further detail is most of the diagnosis wasted.
 *
 * In the domain rather than in each app because both apps ask the same question of the same schema, and
 * it needs nothing from Express to answer it.
 */
import { pgPool, prisma } from "@hmcts/postgres-prisma";

/**
 * Every table the deed-poll domain reads or writes, as named by the `@@map`s in
 * `libs/postgres-prisma/prisma/schema/deed-poll.prisma`.
 *
 * Listed explicitly rather than derived, because Prisma exposes no runtime schema introspection — so
 * **this has to be updated by hand when a model is added.** A missing entry is a silent gap: readiness
 * would pass on a database lacking the new table. `health.test.ts` guards against that by
 * checking this list against the schema file itself.
 */
export const DEED_POLL_TABLES = [
  "deedpoll_deedpoll",
  "deedpoll_notes",
  "deedpoll_witness",
  "deedpoll_supportingdocument",
  "deedpoll_supportingdocumenttype",
  "deedpoll_referencecounter"
] as const;

/**
 * The deed-poll tables that are not present, in the schema this connection actually resolves to.
 *
 * `current_schema()` rather than a literal `'public'`, so it follows the connection's `search_path`
 * and answers for the schema Prisma is querying — this database also carries a `ccd` schema, and a
 * check that looked in the wrong one would be worse than no check.
 *
 * Throws if the database is unreachable. That is deliberate: "cannot connect" and "connected but
 * unmigrated" are different faults, and flattening them here would lose the distinction the caller
 * wants to log.
 */
export async function missingDeedPollTables(): Promise<string[]> {
  const expected = [...DEED_POLL_TABLES];

  const found = await prisma.$queryRaw<{ table_name: string }[]>`
    select table_name from information_schema.tables
    where table_schema = current_schema() and table_name = any(${expected})
  `;

  const present = new Set(found.map((row) => row.table_name));
  return expected.filter((table) => !present.has(table));
}

/**
 * The checks a deed-poll service is not ready without, shared by both apps.
 *
 * Shared rather than a wrapper per app, for the reason `plain-http.ts` gives: two copies of the same
 * decision are two things free to drift, and "what does this service need from its database" has one
 * answer. **Whether these are readiness or liveness is the app's call, not this file's** — that depends
 * on the deployment, and both apps get it wrong in the same direction if this decides for them. See
 * each `app.ts`.
 *
 * Plain `() => Promise<"UP" | "DOWN">` functions, which is what `@hmcts-cft/cloud-native-platform`'s
 * `HealthCheck` is — so the domain needs no dependency on the app framework to supply them.
 */
export function deedPollDatabaseChecks(): Record<string, () => Promise<"UP" | "DOWN">> {
  return {
    postgres: guarded(async () => {
      await pgPool.query("select 1");
    }),
    deedPollSchema: guarded(async () => {
      const missing = await missingDeedPollTables();
      if (missing.length === 0) return;

      // `HealthCheck` collapses to UP/DOWN, so these names are logged here or lost — and being paged
      // for "readiness DOWN" with no further detail is most of the diagnosis wasted.
      throw new Error(`the deed-poll schema is incomplete, missing: ${missing.join(", ")}. Has the migration job run?`);
    })
  };
}

/** Any throw is DOWN, with the reason logged rather than swallowed as `hc.raw` would. */
function guarded(check: () => Promise<void>): () => Promise<"UP" | "DOWN"> {
  return async () => {
    try {
      await check();
      return "UP";
    } catch (error) {
      console.error("Health check failed:", error instanceof Error ? error.message : error);
      return "DOWN";
    }
  };
}
