import { describe, expect, it } from "vitest";
import { buildEditView, buildErrorSummary, editableFieldNames, tabFor } from "./edit-view.js";
import { FORM_TABS } from "./form-fields.js";

const ADMIN = { isAdmin: true };
const CASEWORKER = { isAdmin: false };

/** The Application tab, which carries `privacy` and `type` — the two fields with special rules. */
const applicationTab = FORM_TABS[0];

function fieldsOf(view: ReturnType<typeof buildEditView>) {
  return view.sections.flatMap((section) => section.fields);
}

function field(view: ReturnType<typeof buildEditView>, name: string) {
  return fieldsOf(view).find((f) => f.name === name);
}

describe("deed-poll/edit-view", () => {
  describe("tabFor", () => {
    it("resolves a known tab id", () => {
      expect(tabFor(FORM_TABS[1].id).id).toBe(FORM_TABS[1].id);
    });

    it("falls back to the first tab for an unrecognised id, since the id comes from a URL", () => {
      expect(tabFor("nonsense").id).toBe(FORM_TABS[0].id);
      expect(tabFor(undefined).id).toBe(FORM_TABS[0].id);
    });
  });

  describe("buildEditView", () => {
    it("decorates every field with a value taken from the record", () => {
      const view = buildEditView({ type: "Adult", newSurname: "Lovelace" }, applicationTab, ADMIN);

      expect(field(view, "newSurname")?.value).toBe("Lovelace");
    });

    it("uses null rather than undefined for an absent value, so the template has one empty case", () => {
      const view = buildEditView({}, applicationTab, ADMIN);

      for (const f of fieldsOf(view)) {
        expect(f.value).not.toBeUndefined();
      }
    });

    it("attaches enum options only to enum fields", () => {
      const view = buildEditView({ type: "Adult" }, applicationTab, ADMIN);

      for (const f of fieldsOf(view)) {
        if (f.enumSet) expect(f.options.length).toBeGreaterThan(0);
        else expect(f.options).toEqual([]);
      }
    });

    it("attaches date parts only to date fields", () => {
      const view = buildEditView({ type: "Adult" }, applicationTab, ADMIN);

      for (const f of fieldsOf(view)) {
        if (f.kind === "date") expect(f.dateParts).not.toBeNull();
        else expect(f.dateParts).toBeNull();
      }
    });

    it("splits a stored date into parts for pre-filling", () => {
      const tab = FORM_TABS.find((t) => t.sections.some((s) => s.fields.some((f) => f.kind === "date")));
      const dateField = tab?.sections.flatMap((s) => s.fields).find((f) => f.kind === "date");
      if (!tab || !dateField) throw new Error("expected at least one date field in FORM_TABS");

      const view = buildEditView({ [dateField.name]: "2026-03-01T00:00:00.000Z" }, tab, ADMIN);

      expect(field(view, dateField.name)?.dateParts).toEqual({ day: "1", month: "3", year: "2026" });
    });

    it("locks privacy for a non-admin on an existing record", () => {
      const view = buildEditView({ type: "Adult" }, applicationTab, CASEWORKER);

      expect(field(view, "privacy")?.locked).toBe(true);
    });

    it("does not lock privacy for an admin", () => {
      const view = buildEditView({ type: "Adult" }, applicationTab, ADMIN);

      expect(field(view, "privacy")?.locked).toBe(false);
    });

    it("locks nothing except privacy, which is the only role-gated field", () => {
      const view = buildEditView({ type: "Adult" }, applicationTab, CASEWORKER);

      for (const f of fieldsOf(view)) {
        if (f.name !== "privacy") expect(f.locked).toBe(false);
      }
    });

    it("carries the full tab list so the form can render its own navigation", () => {
      const view = buildEditView({}, applicationTab, ADMIN);

      expect(view.tabs.map((t) => t.id)).toEqual(FORM_TABS.map((t) => t.id));
    });

    describe("replaying a rejected submission", () => {
      it("shows what was submitted rather than what is stored", () => {
        // Showing stored values would discard the caseworker's work and leave them looking at an
        // error against a field that now appears fine.
        const view = buildEditView({ newSurname: "Stored" }, applicationTab, ADMIN, { newSurname: "Typed" });

        expect(field(view, "newSurname")?.value).toBe("Typed");
      });

      it("keeps the offending date parts verbatim, not normalised", () => {
        const tab = FORM_TABS.find((t) => t.sections.some((s) => s.fields.some((f) => f.kind === "date" && !f.readOnly)));
        const dateField = tab?.sections.flatMap((s) => s.fields).find((f) => f.kind === "date" && !f.readOnly);
        if (!tab || !dateField) throw new Error("expected an editable date field in FORM_TABS");

        const view = buildEditView({ [dateField.name]: "2026-03-01T00:00:00.000Z" }, tab, ADMIN, {
          [`${dateField.name}-day`]: "99",
          [`${dateField.name}-month`]: "13",
          [`${dateField.name}-year`]: "abcd"
        });

        expect(field(view, dateField.name)?.dateParts).toEqual({ day: "99", month: "13", year: "abcd" });
      });

      it("still reads read-only fields from the record, since the body cannot speak for them", () => {
        const tab = FORM_TABS.find((t) => t.sections.some((s) => s.fields.some((f) => f.readOnly)));
        const readOnly = tab?.sections.flatMap((s) => s.fields).find((f) => f.readOnly);
        if (!tab || !readOnly) throw new Error("expected a read-only field in FORM_TABS");

        const view = buildEditView({ [readOnly.name]: "Stored" }, tab, ADMIN, { [readOnly.name]: "Injected" });

        expect(field(view, readOnly.name)?.value).toBe("Stored");
      });

      it("treats an empty body as a replay, not as absent", () => {
        // An empty submitted object is a real rejected POST with everything blanked, so the form
        // must come back blank rather than silently repopulated from storage.
        const view = buildEditView({ newSurname: "Stored" }, applicationTab, ADMIN, {});

        expect(field(view, "newSurname")?.value).toBeNull();
      });
    });
  });

  describe("buildErrorSummary", () => {
    it("links an entry whose field is on the submitted tab", () => {
      const onTab = [...editableFieldNames(applicationTab)][0];

      expect(buildErrorSummary([{ field: onTab, message: "Bad" }], applicationTab)).toEqual([{ text: "Bad", href: `#${onTab}` }]);
    });

    it("omits the link when the field is not on this tab, rather than emitting a dead anchor", () => {
      // The cross-field date rules can be addressed to a field on another tab -- the
      // Enroled+Returned rule targets `state`, on the Application tab. A dead #state link from the
      // Return tab is worse than no link: the message reads fine on its own.
      const summary = buildErrorSummary([{ field: "notOnThisTab", message: "Dates are inconsistent" }], applicationTab);

      expect(summary).toEqual([{ text: "Dates are inconsistent", href: undefined }]);
    });

    it("keeps the message text regardless of whether it links", () => {
      const summary = buildErrorSummary([{ field: "nope", message: "Still shown" }], applicationTab);

      expect(summary[0].text).toBe("Still shown");
    });

    it("returns an empty summary for no errors", () => {
      expect(buildErrorSummary([], applicationTab)).toEqual([]);
    });
  });

  describe("editableFieldNames", () => {
    it("excludes read-only fields, which an error summary must not link to", () => {
      for (const tab of FORM_TABS) {
        const names = editableFieldNames(tab);
        const readOnly = tab.sections.flatMap((s) => s.fields).filter((f) => f.readOnly);

        for (const f of readOnly) expect(names.has(f.name)).toBe(false);
      }
    });
  });
});
