import { DeedPollState } from "@hmcts/postgres-prisma";
import { describe, expect, it } from "vitest";
import { filterFor, filterTabs, LIST_FILTERS } from "./list-filters.js";

describe("deed-poll/list-filters", () => {
  it("puts 'all' first, since it is both the default and the fallback", () => {
    expect(LIST_FILTERS[0].key).toBe("all");
  });

  it("gives 'all' no where clause, so it does not filter", () => {
    expect(LIST_FILTERS[0].where).toBeUndefined();
  });

  it("defines 'open' by exclusion, so a state added later shows up as open", () => {
    // The safe direction: a new state appearing in a caseworker's open list is noticeable, whereas
    // one silently missing from it is not.
    const open = LIST_FILTERS.find((f) => f.key === "open");

    expect(open?.where).toEqual({ state: { notIn: [DeedPollState.Enroled, DeedPollState.Returned] } });
  });

  it("covers every terminal state with its own filter", () => {
    const keys = LIST_FILTERS.map((f) => f.key);

    expect(keys).toEqual(["all", "open", "received", "enroled", "returned"]);
  });

  describe("filterFor", () => {
    it("resolves a known key", () => {
      expect(filterFor("enroled").key).toBe("enroled");
    });

    it("falls back to 'all' for an unrecognised key rather than throwing", () => {
      // The key comes from a URL a caseworker may have edited or bookmarked against an older
      // version. Showing everything is a safe answer; a 400 on the service's front page is not.
      expect(filterFor("nonsense").key).toBe("all");
      expect(filterFor(undefined).key).toBe("all");
      expect(filterFor(null).key).toBe("all");
    });

    it("falls back for a non-string, which a repeated query parameter produces", () => {
      expect(filterFor(["open", "enroled"]).key).toBe("all");
    });
  });

  describe("filterTabs", () => {
    it("drops the where clause, which is not the template's business", () => {
      for (const tab of filterTabs()) {
        expect(Object.keys(tab)).toEqual(["key", "label"]);
      }
    });

    it("preserves order, because it is the order the tabs render in", () => {
      expect(filterTabs().map((t) => t.key)).toEqual(LIST_FILTERS.map((f) => f.key));
    });
  });
});
