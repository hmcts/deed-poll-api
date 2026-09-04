import { afterEach, describe, expect, it } from "vitest";
import { PLAIN_HTTP_ENVIRONMENTS, plainHttpPermitted } from "./plain-http.js";

const original = process.env.NODE_ENV;

afterEach(() => {
  process.env.NODE_ENV = original;
});

describe("plainHttpPermitted", () => {
  it("permits cleartext in development and test, and nowhere else", () => {
    expect([...PLAIN_HTTP_ENVIRONMENTS].sort()).toEqual(["development", "test"]);
  });

  it.each(["development", "test"])("permits it under NODE_ENV=%s", (env) => {
    process.env.NODE_ENV = env;

    expect(plainHttpPermitted()).toBe(true);
  });

  // The whole point of the allowlist: these are the values a negation of "production" would have
  // wrongly permitted. If someone "simplifies" this to `NODE_ENV !== "production"`, these fail.
  it.each(["production", "aat", "preview", "staging", "demo", "ithc", "perftest", ""])("refuses it under NODE_ENV=%s", (env) => {
    process.env.NODE_ENV = env;

    expect(plainHttpPermitted()).toBe(false);
  });

  it("refuses it when NODE_ENV is not set at all", () => {
    delete process.env.NODE_ENV;

    expect(plainHttpPermitted()).toBe(false);
  });
});
