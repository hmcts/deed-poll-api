import { DeedPollState, DeedPollType, Privacy, ReferToMaster } from "@hmcts/postgres-prisma";
import { describe, expect, it } from "vitest";
import { getFieldRules } from "./deed-poll-validation.js";
import { buildDetailTabs, type DetailSection, formatValue, NOT_PROVIDED } from "./detail-view.js";
import { type FieldDef, FORM_TABS } from "./form-fields.js";
import { build } from "./form-presentation.js";

const rulesFor = (type: DeedPollType | null) => getFieldRules(type);
const presentationFor = (input: {
  type?: DeedPollType | null;
  privacy?: Privacy | null;
  referToMasterChoice?: ReferToMaster | null;
  state?: DeedPollState | null;
}) => build(input, { isAdmin: true, isNew: false });

/** Flatten a built tab set to "Tab › Section › Key" = value for easy assertions. */
const flatten = (tabs: ReturnType<typeof buildDetailTabs>) => {
  const out: Record<string, string> = {};
  for (const tab of tabs) {
    for (const section of tab.sections) {
      for (const row of section.rows) {
        out[`${tab.label} › ${section.label} › ${row.key}`] = row.value;
      }
    }
  }
  return out;
};

describe("formatValue", () => {
  const field = (over: Partial<FieldDef>): FieldDef => ({ name: "f", label: "F", kind: "text", ...over }) as FieldDef;

  it.each([null, undefined, ""])("renders %p as the placeholder", (raw) => {
    expect(formatValue(field({}), raw)).toBe(NOT_PROVIDED);
  });

  it("formats a date GOV.UK style in UTC", () => {
    expect(formatValue(field({ kind: "date" }), new Date("2026-07-31T00:00:00Z"))).toBe("31 July 2026");
  });

  it("does not shift a date across a day boundary via local time", () => {
    // A midnight-UTC date must not render as the 30th in a negative-offset zone.
    expect(formatValue(field({ kind: "date" }), new Date("2026-01-01T00:00:00Z"))).toBe("1 January 2026");
  });

  it("renders booleans as Yes/No, including false", () => {
    expect(formatValue(field({ kind: "boolean" }), true)).toBe("Yes");
    // false is meaningful here, not absent — it must not become "Not provided".
    expect(formatValue(field({ kind: "boolean" }), false)).toBe("No");
  });

  it("maps an enum to its Dynamics label", () => {
    expect(formatValue(field({ kind: "enum", enumSet: "type" }), DeedPollType.DeedOfRelinquishment)).toBe("Deed of Relinquishment");
  });

  it("falls back to the raw value for an unmapped enum member", () => {
    expect(formatValue(field({ kind: "enum", enumSet: "type" }), "Wat")).toBe("Wat");
  });

  it("joins a multiselect through its integer label map", () => {
    expect(formatValue(field({ kind: "multiselect", enumSet: "iShall" }), [1, 4])).toBe("and my wife, and my children");
  });

  it("renders an empty multiselect as the placeholder", () => {
    expect(formatValue(field({ kind: "multiselect", enumSet: "iShall" }), [])).toBe(NOT_PROVIDED);
  });

  it("keeps a zero number rather than treating it as absent", () => {
    expect(formatValue(field({ kind: "number" }), 0)).toBe("0");
  });
});

describe("buildDetailTabs — parity with the edit form", () => {
  it("offers every non-conditional FORM_TABS tab", () => {
    // The regression this module exists to prevent: the detail page listed six
    // tabs by hand while the form had nine, so whole field groups were
    // write-only. Both sides now derive from FORM_TABS.
    const tabs = buildDetailTabs({ type: DeedPollType.Adult }, rulesFor(DeedPollType.Adult), presentationFor({ type: DeedPollType.Adult }));
    const ids = tabs.map((t) => t.id);
    const expected = FORM_TABS.map((t) => t.id);
    expect(ids).toEqual(expected);
  });

  it("surfaces address, minor and declaration fields that the old page dropped", () => {
    const record = {
      type: DeedPollType.Minor,
      applicantAddress1: "1 High Street",
      applicantTownCity: "Cardiff",
      applicantPostcode: "CF10 1AA",
      representativeName: "Leah Okafor",
      representativeCompany: "Okafor & Co",
      ageOfMinor: 9,
      ageOfMinorMonths: 4,
      parents: "A Okafor, B Okafor",
      iShall: [5],
      dateOfDeclaration: new Date("2026-07-20T00:00:00Z")
    };
    const flat = flatten(buildDetailTabs(record, rulesFor(DeedPollType.Minor), presentationFor({ type: DeedPollType.Minor })));

    expect(flat["Application › Applicant address and contact details › Address line 1"]).toBe("1 High Street");
    expect(flat["Application › Applicant address and contact details › Postcode"]).toBe("CF10 1AA");
    expect(flat["Application › Representative address and contact details › Company"]).toBe("Okafor & Co");
    expect(flat["Application › General › Age of minor years"]).toBe("9");
    expect(flat["Application › General › Parents"]).toBe("A Okafor, B Okafor");
    expect(flat["Application › Declarations › I shall"]).toBe("and I alone");
    expect(flat["Application › Declarations › Date of declaration"]).toBe("20 July 2026");
  });

  it("shows the computed day-count once it has a value", () => {
    const flat = flatten(
      buildDetailTabs(
        {
          type: DeedPollType.Adult,
          dateEnroled: new Date("2026-07-15T00:00:00Z"),
          daysReceivedToEnroled: 14
        },
        rulesFor(DeedPollType.Adult),
        presentationFor({ type: DeedPollType.Adult, state: DeedPollState.Enroled })
      )
    );
    expect(flat["Enrolment › Enrolment › Days received to enroled"]).toBe("14");
  });
});

describe("buildDetailTabs — per-type field rules", () => {
  it("hides minor fields on an adult case", () => {
    // ageOfMinor is populated but the adult rule set hides it; the read-only page
    // must respect the same rules as the form rather than leaking the value.
    const flat = flatten(
      buildDetailTabs({ type: DeedPollType.Adult, ageOfMinor: 9 }, rulesFor(DeedPollType.Adult), presentationFor({ type: DeedPollType.Adult }))
    );
    expect(flat["Application › General › Age of minor years"]).toBeUndefined();
  });

  it("hides old-name and nationality fields on a relinquishment", () => {
    const flat = flatten(
      buildDetailTabs(
        {
          type: DeedPollType.DeedOfRelinquishment,
          oldForename: "Sam",
          nationality: "BritishCitizen",
          newForename: "Alex"
        },
        rulesFor(DeedPollType.DeedOfRelinquishment),
        presentationFor({ type: DeedPollType.DeedOfRelinquishment })
      )
    );
    expect(flat["Application › Names › Old forename"]).toBeUndefined();
    expect(flat["Application › Nationality › Nationality"]).toBeUndefined();
    // The fields it does use still show.
    expect(flat["Application › Names › New forename"]).toBe("Alex");
  });
});

describe("buildDetailTabs — conditional tabs", () => {
  it("hides Referral to Master when the choice is No", () => {
    const tabs = buildDetailTabs(
      { type: DeedPollType.Adult, referToMasterChoice: ReferToMaster.No },
      rulesFor(DeedPollType.Adult),
      presentationFor({
        type: DeedPollType.Adult,
        referToMasterChoice: ReferToMaster.No
      })
    );
    expect(tabs.map((t) => t.id)).not.toContain("referral");
  });

  it("hides Return when the case is Enroled", () => {
    const tabs = buildDetailTabs(
      { type: DeedPollType.Adult },
      rulesFor(DeedPollType.Adult),
      presentationFor({ type: DeedPollType.Adult, state: DeedPollState.Enroled })
    );
    const ids = tabs.map((t) => t.id);
    expect(ids).toContain("enrolment");
    expect(ids).not.toContain("return");
  });

  it("hides Enrolment when the case is Returned", () => {
    const tabs = buildDetailTabs(
      { type: DeedPollType.Adult },
      rulesFor(DeedPollType.Adult),
      presentationFor({ type: DeedPollType.Adult, state: DeedPollState.Returned })
    );
    const ids = tabs.map((t) => t.id);
    expect(ids).toContain("return");
    expect(ids).not.toContain("enrolment");
  });
});

describe("buildDetailTabs — empty states", () => {
  it("keeps an unpopulated field as a placeholder row, so positions are stable", () => {
    // Superseded the original skip-if-empty rule when the page moved to two-column
    // cards. The cards replicate the Dynamics form so a caseworker finds a given
    // field in the same place on every case; a row that vanishes when blank shifts
    // everything below it and defeats that.
    const tabs = buildDetailTabs(
      { type: DeedPollType.Adult, newForename: "Alex" },
      rulesFor(DeedPollType.Adult),
      presentationFor({ type: DeedPollType.Adult })
    );
    const general = tabs.find((t) => t.id === "application")?.sections.find((s) => s.label === "General");

    expect(general?.rows).toContainEqual({
      key: "Type",
      value: "Adult",
      column: 1
    });
    expect(general?.rows).toContainEqual({
      key: "Fee reference",
      value: NOT_PROVIDED,
      column: 1
    });
  });

  it("still drops a section whose every field is hidden", () => {
    // The one remaining reason to drop a section. No real type hides a whole section
    // today (every section keeps at least one visible field for both Adult and Minor),
    // so the rules are supplied explicitly rather than via getFieldRules — otherwise
    // this branch would go untested until a future rule change reached it.
    const nationalityFields = FORM_TABS.find((t) => t.id === "application")?.sections.find((s) => s.label === "Nationality")?.fields;
    const rules = Object.fromEntries((nationalityFields ?? []).map((f) => [f.name, { visible: false, required: false }]));

    const tabs = buildDetailTabs(
      { type: DeedPollType.Adult, newForename: "Alex" },
      { ...rulesFor(DeedPollType.Adult), ...rules },
      presentationFor({ type: DeedPollType.Adult })
    );
    const application = tabs.find((t) => t.id === "application");
    expect(application?.sections.map((s) => s.label)).not.toContain("Nationality");
  });

  it("hides individual fields the type does not use, keeping the rest", () => {
    // All four minor fields sit at the foot of General's left column and all four
    // are hidden for an Adult, so General survives on its remaining fields. This is
    // the per-field hiding path, distinct from the whole-section drop above.
    const general = buildDetailTabs({ type: DeedPollType.Adult, ageOfMinor: 9 }, rulesFor(DeedPollType.Adult), presentationFor({ type: DeedPollType.Adult }))
      .find((t) => t.id === "application")
      ?.sections.find((s) => s.label === "General");
    const keys = general?.rows.map((r) => r.key) ?? [];
    expect(keys).not.toContain("Age of minor years");
    expect(keys).not.toContain("Minor's date of birth");
    expect(keys).toContain("Marital status");
  });

  it("ends the General left column at the minor fields for a minor case", () => {
    // power_app/screenshots/application_general_minor.png: the four minor fields
    // close the left column, below Marital status. On an Adult case they vanish and
    // the column simply ends earlier — no stranded card, no empty rows.
    const general = buildDetailTabs({ type: DeedPollType.Minor }, rulesFor(DeedPollType.Minor), presentationFor({ type: DeedPollType.Minor }))
      .find((t) => t.id === "application")
      ?.sections.find((s) => s.label === "General");
    const left = (general?.blocks ?? []).flatMap((b) => (b.kind === "columns" ? b.left : []));
    expect(left.map((r) => r.key).slice(-5)).toEqual(["Marital status", "Minor's date of birth", "Age of minor years", "Age of minor months", "Parents"]);
  });

  it("no longer marks a tab empty just because nothing is filled in", () => {
    // Refund has no hidden-by-type fields, so its rows now always render.
    const tabs = buildDetailTabs({ type: DeedPollType.Adult }, rulesFor(DeedPollType.Adult), presentationFor({ type: DeedPollType.Adult }));
    const refund = tabs.find((t) => t.id === "refund");
    expect(refund?.empty).toBe(false);
    expect(refund?.sections[0].rows.every((r) => r.value === NOT_PROVIDED)).toBe(true);
  });

  it("marks subgrid tabs empty or not from their child arrays", () => {
    const tabs = buildDetailTabs(
      {
        type: DeedPollType.Adult,
        notes: [{ note: "Chased applicant" }],
        witnesses: []
      },
      rulesFor(DeedPollType.Adult),
      presentationFor({ type: DeedPollType.Adult })
    );
    expect(tabs.find((t) => t.id === "notes")?.empty).toBe(false);
    expect(tabs.find((t) => t.id === "witnesses")?.empty).toBe(true);
    // Absent entirely (not just empty) must also read as empty, not crash.
    expect(tabs.find((t) => t.id === "documents")?.empty).toBe(true);
  });

  it("carries the subgrid marker so the template can render child records", () => {
    const tabs = buildDetailTabs({ type: DeedPollType.Adult }, rulesFor(DeedPollType.Adult), presentationFor({ type: DeedPollType.Adult }));
    expect(tabs.find((t) => t.id === "documents")?.subgrid).toBe("documents");
    expect(tabs.find((t) => t.id === "application")?.subgrid).toBeUndefined();
  });
});

describe("buildDetailTabs — card columns", () => {
  /**
   * The single two-column band of a section that has no full-width fields. Most
   * sections are one band, so this keeps those assertions about positions rather
   * than about the band sequence — see the Return/Referral tests below for the
   * sections that genuinely have more than one.
   */
  const cols = (s?: DetailSection) => {
    const bands = (s?.blocks ?? []).filter((b) => b.kind === "columns");
    expect(bands).toHaveLength(1);
    const band = bands[0];
    if (band.kind !== "columns") throw new Error("unreachable");
    return band;
  };
  const keys = (rows: { key: string }[]) => rows.map((r) => r.key);

  /** Every tab of a case built to show as many of them as possible. */
  const allTabs = () =>
    buildDetailTabs(
      {
        type: DeedPollType.Minor,
        referToMasterChoice: ReferToMaster.Yes
      },
      rulesFor(DeedPollType.Minor),
      presentationFor({
        type: DeedPollType.Minor,
        referToMasterChoice: ReferToMaster.Yes
      })
    );

  const general = () =>
    buildDetailTabs({ type: DeedPollType.Adult, privacy: Privacy.Normal }, rulesFor(DeedPollType.Adult), presentationFor({ type: DeedPollType.Adult }))
      .find((t) => t.id === "application")
      ?.sections.find((s) => s.label === "General");

  it("splits General into the Dynamics form column positions", () => {
    // Read down power_app/screenshots/application_general.png: Reference Number top
    // left, Privacy top right. Two of ours trail their related field (see form-fields).
    // This is the ADULT case, so the four minor fields that would close the left
    // column are hidden — see the minor-case test for those.
    const c = cols(general());
    expect(keys(c.left)).toEqual([
      "Reference number",
      "Display reference",
      "Date of application",
      "In person",
      "Post / Counter",
      "Fee reference",
      "Date received",
      "Type",
      "Marital status"
    ]);
    expect(keys(c.right)).toEqual([
      "Privacy",
      "State",
      "Urgency",
      "Urgency reason",
      "Online application reference",
      "Retain permanently",
      "Awaiting further docs/info"
    ]);
  });

  const section = (label: string) =>
    buildDetailTabs({ type: DeedPollType.Adult }, rulesFor(DeedPollType.Adult), presentationFor({ type: DeedPollType.Adult }))
      .find((t) => t.id === "application")
      ?.sections.find((s) => s.label === label);

  it("splits Names into new down the left, old down the right", () => {
    // power_app/screenshots/application_names.png. The source form's cells are
    // row-major (New Forename / Old Forename, then the middle names, then the
    // surnames); read column-wise that is new-name left, old-name right.
    const names = section("Names");
    expect(keys(cols(names).left)).toEqual(["New forename", "New middle name", "New surname"]);
    expect(keys(cols(names).right)).toEqual(["Old forename", "Old middle name", "Old surname"]);
  });

  it("splits the applicant address into address left, contact details right", () => {
    // power_app/screenshots/application_address.png. Postcode belongs at the FOOT OF
    // THE LEFT column, which is what makes this section worth asserting: the
    // halve-in-order fallback would have put it at the head of the right one, next
    // to Email, breaking the postal address in two.
    const address = section("Applicant address and contact details");
    expect(keys(cols(address).left)).toEqual(["Building number/name", "Address line 1", "Address line 2", "Town/city", "Postcode"]);
    expect(keys(cols(address).right)).toEqual(["Email", "Telephone"]);
  });

  it("mirrors the representative card: contact left, address right", () => {
    // power_app/screenshots/application_repesentative.png. Deliberately the OPPOSITE
    // way round from the applicant card asserted above. Both are asserted so that
    // "make the two consistent" cannot pass silently — the source form is
    // inconsistent here and we match it.
    const rep = section("Representative address and contact details");
    expect(keys(cols(rep).left)).toEqual(["Name", "Company", "Email", "Telephone"]);
    expect(keys(cols(rep).right)).toEqual(["Building number/name", "Address line 1", "Address line 2", "Town/city", "Postcode"]);
  });

  it("stacks Nationality in one column, keeping Section (other) under Section", () => {
    // sectionNationality puts all three in the left cell. The halve-in-order fallback
    // used to strand Section (other) in the right column, away from the Section it
    // qualifies — one of the four sections that had no explicit positions.
    const nationality = section("Nationality");
    expect(keys(cols(nationality).left)).toEqual(["Nationality", "Section", "Section (other)"]);
    expect(cols(nationality).right).toEqual([]);
  });

  it("splits Declarations the way the source cells read column-wise", () => {
    // sectionDeclarations is row-major: I shall / I have subscribed, then Date of
    // declaration / And also my old. The halving fallback had produced
    // left [I shall, I have subscribed], right [And also my old, Date of declaration].
    const declarations = section("Declarations");
    expect(keys(cols(declarations).left)).toEqual(["I shall", "Date of declaration"]);
    expect(keys(cols(declarations).right)).toEqual(["I have subscribed", "And also my old"]);
  });

  it("accounts for every row across the bands", () => {
    // The bands are a view OF rows, not a second source of truth: a field that
    // reached `rows` but no band would be invisible on the page. Asserted over every
    // section of every tab, because a band sequence has more ways to drop a row than
    // a single left/right split did.
    for (const tab of allTabs()) {
      for (const s of tab.sections) {
        const banded = s.blocks.flatMap((b) => (b.kind === "full" ? b.rows : [...b.left, ...b.right]));
        expect(keys(banded).sort()).toEqual(keys(s.rows).sort());
      }
    }
  });
});

describe("buildDetailTabs — full-width bands", () => {
  const keys = (rows: { key: string }[]) => rows.map((r) => r.key);
  const sectionOf = (tabId: string) =>
    buildDetailTabs(
      { type: DeedPollType.Adult, referToMasterChoice: ReferToMaster.Yes },
      rulesFor(DeedPollType.Adult),
      presentationFor({
        type: DeedPollType.Adult,
        referToMasterChoice: ReferToMaster.Yes
      })
    ).find((t) => t.id === tabId)?.sections[0];

  /** Band sequence as one comparable shape, so order is asserted, not just membership. */
  const shape = (s?: DetailSection) =>
    (s?.blocks ?? []).map((b) => (b.kind === "full" ? { full: keys(b.rows) } : { left: keys(b.left), right: keys(b.right) }));

  it("puts Return reason on its own full-width band below the dates", () => {
    // sectionReturn: both dates in the left cell, then deedpoll_returnreason at
    // colspan="2". Before this, the halve-in-order fallback put Return reason in the
    // RIGHT column beside Date returned — the defect this band model exists to fix.
    expect(shape(sectionOf("return"))).toEqual([{ left: ["Date returned", "Days received to returned"], right: [] }, { full: ["Return reason"] }]);
  });

  it("interleaves the two full-width Referral fields in source order", () => {
    // The section that proves a card must be a SEQUENCE of bands: Referral notes and
    // Master directions sit between two-column runs, so neither can be hoisted to an
    // end of the card without reordering the form.
    expect(shape(sectionOf("referral"))).toEqual([
      {
        left: ["Refer to Master", "Date ready for Master", "Date referred"],
        right: []
      },
      { full: ["Referral notes"] },
      { left: ["Master name"], right: [] },
      { full: ["Master directions"] },
      {
        left: ["Master decision", "Master date of decision", "Date decision/directions received", "Publish to London Gazette"],
        right: []
      }
    ]);
  });

  it.each([
    ["enrolment", ["Date enroled", "Days received to enroled", "Date notice sent to London Gazette"]],
    ["refund", ["Refund amount", "Date of refund", "Refund reference"]]
  ])("stacks the %s tab in one column, as the source does", (tabId, expected) => {
    // Both sections leave the right cell empty in the source. The fallback had split
    // them two-left/one-right, moving the third field up beside the first.
    expect(shape(sectionOf(tabId))).toEqual([{ left: expected, right: [] }]);
  });

  it("keeps the Signature dates side by side", () => {
    // The one later section that genuinely uses both cells. Positioned explicitly
    // now, so a third field on the tab cannot silently re-split it.
    expect(shape(sectionOf("signature"))).toEqual([{ left: ["Signature requested date"], right: ["Signature received date"] }]);
  });
});
