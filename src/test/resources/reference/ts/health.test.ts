import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { prisma } from "@hmcts/postgres-prisma";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { DEED_POLL_TABLES, missingDeedPollTables } from "./health.js";

vi.mock("@hmcts/postgres-prisma", () => ({ prisma: { $queryRaw: vi.fn() } }));

const SCHEMA = fileURLToPath(new URL("../../postgres-prisma/prisma/schema/deed-poll.prisma", import.meta.url));

describe("DEED_POLL_TABLES", () => {
  it("lists exactly the tables the Prisma schema maps", () => {
    // The list cannot be derived — Prisma has no runtime schema introspection — so this is what stops
    // it silently falling behind. A model added without updating it would leave readiness passing on a
    // database that lacks the new table, which is the failure this whole check exists to prevent.
    const mapped = [...readFileSync(SCHEMA, "utf8").matchAll(/@@map\("([^"]+)"\)/g)].map((match) => match[1]);

    expect([...DEED_POLL_TABLES].sort()).toEqual(mapped.sort());
  });
});

describe("missingDeedPollTables", () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it("reports nothing missing when every table is present", async () => {
    vi.mocked(prisma.$queryRaw).mockResolvedValue(DEED_POLL_TABLES.map((table) => ({ table_name: table })) as never);

    expect(await missingDeedPollTables()).toEqual([]);
  });

  it("names the tables that are absent", async () => {
    // An unmigrated database, which `select 1` would pass. The names are the whole point: "readiness
    // DOWN" with no detail is a bad thing to be woken up for.
    vi.mocked(prisma.$queryRaw).mockResolvedValue([{ table_name: "deedpoll_deedpoll" }] as never);

    const missing = await missingDeedPollTables();

    expect(missing).toContain("deedpoll_notes");
    expect(missing).not.toContain("deedpoll_deedpoll");
    expect(missing).toHaveLength(DEED_POLL_TABLES.length - 1);
  });

  it("reports all of them missing against an empty database", async () => {
    vi.mocked(prisma.$queryRaw).mockResolvedValue([] as never);

    expect(await missingDeedPollTables()).toEqual([...DEED_POLL_TABLES]);
  });

  it("ignores tables the query returns that are not ours", async () => {
    // Defensive: the `any()` bind should make this impossible, but a stray row must not be read as
    // satisfying one of ours.
    vi.mocked(prisma.$queryRaw).mockResolvedValue([...DEED_POLL_TABLES.map((table) => ({ table_name: table })), { table_name: "ccd_case_data" }] as never);

    expect(await missingDeedPollTables()).toEqual([]);
  });

  it("propagates a connection failure rather than reporting an unmigrated database", async () => {
    // Two different faults. "Cannot connect" and "connected but unmigrated" want different responses,
    // and flattening them here would lose the distinction the caller logs.
    vi.mocked(prisma.$queryRaw).mockRejectedValue(new Error("ECONNREFUSED"));

    await expect(missingDeedPollTables()).rejects.toThrow("ECONNREFUSED");
  });

  it("looks in the schema the connection resolves to, not a hardcoded public", async () => {
    // This database also carries a `ccd` schema. A check against the wrong one would be worse than none.
    vi.mocked(prisma.$queryRaw).mockResolvedValue([] as never);

    await missingDeedPollTables();

    expect(vi.mocked(prisma.$queryRaw).mock.calls[0][0].join("?")).toContain("current_schema()");
  });
});
