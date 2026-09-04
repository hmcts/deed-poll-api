import type { CardColumn } from "./card-layout.js";
import { LABELS } from "./enums/dynamics-values.js";

/**
 * Form-field metadata — the single source of truth for the editable case form.
 * Mirrors the Dynamics main form (customizations.xml, `<forms type="main">`):
 * tabs → sections → fields in document order. Both the Nunjucks form and the
 * POST coercion in the pages controller drive off this, so the rendered form
 * and the accepted payload can never drift apart.
 *
 * Faithfulness rules baked in (see the source form + ported workflows):
 *   - Nothing is form-required here; required-ness comes from getFieldRules(type).
 *   - `readOnly` fields (referencenumber, computed day-counts) render disabled.
 *   - `state` is derived and has no field — it recomputes on save.
 *   - Notes/Witnesses/Documents are subgrids, handled separately (nested CRUD).
 */

/** The control kind, deciding how a field renders and how its POST value coerces. */
export type FieldKind = "text" | "textarea" | "date" | "number" | "boolean" | "enum" | "multiselect";

export interface FieldDef {
  /** Prisma field name (also the form input name). */
  name: string;
  label: string;
  kind: FieldKind;
  /** For kind 'enum'/'multiselect': the LABELS set key (e.g. 'type'). */
  enumSet?: keyof typeof LABELS;
  /** Read-only on the form (disabled input); never written on save. */
  readOnly?: boolean;
  /**
   * The column is NOT NULL with a database default, so an absent input must be
   * omitted from a patch rather than nulled. Set this on any field whose Prisma
   * type has no `?` — `privacy` did not have it, and because privacy renders
   * `disabled` for a non-admin (and browsers do not submit disabled controls) a
   * non-admin saving the Application tab wrote null to a NOT NULL column and 500'd.
   */
  notNullable?: boolean;
  /**
   * Which column of the two-column card this field sits in (1 = left, 2 = right,
   * 'full' = spanning both), replicating the Dynamics form's field positions so staff
   * keep their muscle memory. Sections that leave it unset are split in order by
   * `splitIntoColumns` (see card-layout.ts).
   *
   * Every data-entry section is now positioned explicitly, read out of the `<cell>`
   * grid of the matching `<section>` in `power_app/.../customizations.xml` — a
   * `colspan="2"` cell is `'full'`, otherwise the cell index is the column. Read the
   * XML rather than eyeballing a screenshot: the halving fallback had put Return
   * Reason beside Date returned, and the four later tabs were laid out by it because
   * we had no screenshot of them. If you add a section, position it from the XML.
   */
  column?: CardColumn;
}

export interface SectionDef {
  label: string;
  fields: FieldDef[];
}

export interface TabDef {
  /** URL slug + form section id. */
  id: string;
  label: string;
  /** Data-entry sections. Empty when the tab is a subgrid (see `subgrid`). */
  sections: SectionDef[];
  /** Present when the tab is a child-entity subgrid rather than fields. */
  subgrid?: "notes" | "witnesses" | "documents";
}

/** Build govuk select items ({value,text}) for an enum LABELS set. */
export function enumOptions(set: keyof typeof LABELS) {
  return Object.entries(LABELS[set]).map(([value, text]) => ({ value, text }));
}

/** The Application tab: General, Names, Addresses, Nationality, Declarations. */
const APPLICATION_TAB: TabDef = {
  id: "application",
  label: "Application",
  sections: [
    {
      // Column positions replicate power_app/screenshots/application_general.png,
      // read down the left column then the right. Two departures, both deliberate:
      //   - `displayReference` and `urgencyreason` have no place on the source card.
      //     They are ours (the reference the user actually quotes, and the reason a
      //     case was flagged urgent), so they follow their related field.
      //   - The source card also shows "Refer to Master?", which we own on the
      //     Referral tab because it drives that tab's visibility. Duplicating one
      //     field across two editable forms would give it two save paths.
      label: "General",
      fields: [
        { name: "referencenumber", label: "Reference number", kind: "number", readOnly: true, column: 1 },
        { name: "displayReference", label: "Display reference", kind: "text", readOnly: true, column: 1 },
        { name: "dateOfApplication", label: "Date of application", kind: "date", column: 1 },
        { name: "inPerson", label: "In person", kind: "boolean", column: 1 },
        { name: "postCounter", label: "Post / Counter", kind: "enum", enumSet: "postCounter", column: 1 },
        { name: "feeReference", label: "Fee reference", kind: "text", column: 1 },
        { name: "dateReceived", label: "Date received", kind: "date", column: 1 },
        { name: "type", label: "Type", kind: "enum", enumSet: "type", column: 1 },
        { name: "maritalStatus", label: "Marital status", kind: "enum", enumSet: "maritalStatus", column: 1 },

        // The minor fields close the left column, exactly as in
        // power_app/screenshots/application_general_minor.png. They are NOT a
        // separate card: all four are hidden by getFieldRules for a non-minor type,
        // so on an Adult case the left column simply ends at Marital status.
        { name: "minorDateOfBirth", label: "Minor's date of birth", kind: "date", column: 1 },
        { name: "ageOfMinor", label: "Age of minor years", kind: "number", column: 1 },
        { name: "ageOfMinorMonths", label: "Age of minor months", kind: "number", column: 1 },
        { name: "parents", label: "Parents", kind: "text", column: 1 },

        { name: "privacy", label: "Privacy", kind: "enum", enumSet: "privacy", notNullable: true, column: 2 },
        { name: "state", label: "State", kind: "enum", enumSet: "state", readOnly: true, column: 2 },
        { name: "urgency", label: "Urgency", kind: "enum", enumSet: "urgency", notNullable: true, column: 2 },
        { name: "urgencyreason", label: "Urgency reason", kind: "text", column: 2 },
        { name: "onlineApplicationReference", label: "Online application reference", kind: "text", column: 2 },
        { name: "retainPermanently", label: "Retain permanently", kind: "boolean", column: 2 },
        { name: "awaitingFurtherDocsInfo", label: "Awaiting further docs/info", kind: "date", column: 2 }
      ]
    },
    {
      // Column positions replicate power_app/screenshots/application_names.png:
      // the new name down the left, the old name down the right. The source form's
      // cells are row-major (newforename, oldforename, newmiddlename, oldmiddlename,
      // …), which is the same layout read the other way round.
      //
      // The halve-in-order fallback already produced this split, since the fields
      // happen to be listed new-then-old. Positions are set anyway so the layout
      // survives someone reordering the list — an accidental match is not a spec.
      label: "Names",
      fields: [
        { name: "newForename", label: "New forename", kind: "text", column: 1 },
        { name: "newMiddleName", label: "New middle name", kind: "text", column: 1 },
        { name: "newSurname", label: "New surname", kind: "text", column: 1 },

        { name: "oldForename", label: "Old forename", kind: "text", column: 2 },
        { name: "oldMiddleName", label: "Old middle name", kind: "text", column: 2 },
        { name: "oldSurname", label: "Old surname", kind: "text", column: 2 }
      ]
    },
    {
      // Column positions replicate power_app/screenshots/application_address.png:
      // the postal address down the left, the two contact fields at the top right.
      // Note this is NOT the halve-in-order split, which put Postcode on the right.
      //
      // Labels drop the source form's "Applicant" prefix ("Applicant Address 1" →
      // "Address line 1") because the card title already says whose address it is,
      // and the full label wraps in a half-width column — the wrapping is visible
      // on the source screenshot's own "Applicant Building No/Name".
      label: "Applicant address and contact details",
      fields: [
        { name: "applicantBuildingNoName", label: "Building number/name", kind: "text", column: 1 },
        { name: "applicantAddress1", label: "Address line 1", kind: "text", column: 1 },
        { name: "applicantAddress2", label: "Address line 2", kind: "text", column: 1 },
        { name: "applicantTownCity", label: "Town/city", kind: "text", column: 1 },
        { name: "applicantPostcode", label: "Postcode", kind: "text", column: 1 },

        { name: "applicantEmail", label: "Email", kind: "text", column: 2 },
        { name: "applicantTelephone", label: "Telephone", kind: "text", column: 2 }
      ]
    },
    {
      // Column positions replicate power_app/screenshots/application_repesentative.png:
      // who they are and how to reach them on the left, their postal address on the
      // right. That is MIRRORED from the applicant card above, where the address is on
      // the left — the source form really is inconsistent between the two (its cell
      // order is name, building, company, address1, email, address2, …), and we copy
      // the inconsistency because muscle memory is the point of this exercise.
      label: "Representative address and contact details",
      fields: [
        { name: "representativeName", label: "Name", kind: "text", column: 1 },
        { name: "representativeCompany", label: "Company", kind: "text", column: 1 },
        { name: "representativeEmail", label: "Email", kind: "text", column: 1 },
        { name: "representativeTelephone", label: "Telephone", kind: "text", column: 1 },

        { name: "representativeBuildingNoName", label: "Building number/name", kind: "text", column: 2 },
        { name: "representativeAddress1", label: "Address line 1", kind: "text", column: 2 },
        { name: "representativeAddress2", label: "Address line 2", kind: "text", column: 2 },
        { name: "representativeTownCity", label: "Town/city", kind: "text", column: 2 },
        { name: "representativePostcode", label: "Postcode", kind: "text", column: 2 }
      ]
    },
    {
      // `<section name="sectionNationality">` stacks all three in the left cell. The
      // halving fallback used to put Section (other) on the right, away from the
      // Section it qualifies.
      label: "Nationality",
      fields: [
        { name: "nationality", label: "Nationality", kind: "enum", enumSet: "nationality", column: 1 },
        { name: "nationalitySection", label: "Section", kind: "enum", enumSet: "nationalitySection", column: 1 },
        { name: "nationalitySectionOther", label: "Section (other)", kind: "text", column: 1 }
      ]
    },
    {
      // `<section name="sectionDeclarations">` cells are row-major (I shall / I have
      // subscribed, then Date of declaration / And also my old); read column-wise that
      // is I shall + Date of declaration left, the two subscribed/old ones right.
      // Grouped by column below, as the Names section is — the array order also sets
      // the stacking order on the edit form, where column-major reads better.
      label: "Declarations",
      fields: [
        { name: "iShall", label: "I shall", kind: "multiselect", enumSet: "iShall", column: 1 },
        { name: "dateOfDeclaration", label: "Date of declaration", kind: "date", column: 1 },

        { name: "iHaveSubscribed", label: "I have subscribed", kind: "multiselect", enumSet: "iHaveSubscribed", column: 2 },
        { name: "andAlsoMyOld", label: "And also my old", kind: "multiselect", enumSet: "andAlsoMyOld", column: 2 }
      ]
    }
  ]
};

/** Referral to Master tab. */
const REFERRAL_TAB: TabDef = {
  id: "referral",
  label: "Referral to Master",
  sections: [
    {
      // Positions read out of `<section name="sectionReferral">` in the source form
      // (customizations.xml). Every field sits in the section's LEFT cell with the
      // right one empty, except the two long free-text fields, which carry
      // `colspan="2" rowspan="5"` — hence `column: 'full'`. `referToMasterChoice` has
      // no cell in this section at all (the source keeps it on the General card); it
      // is ours, per the note on APPLICATION_TAB, so it leads the left column.
      label: "Referral to Master",
      fields: [
        { name: "referToMasterChoice", label: "Refer to Master", kind: "enum", enumSet: "referToMaster", column: 1 },
        { name: "dateReadyForMaster", label: "Date ready for Master", kind: "date", column: 1 },
        { name: "dateReferred", label: "Date referred", kind: "date", column: 1 },
        { name: "referralNotes", label: "Referral notes", kind: "textarea", column: "full" },
        { name: "masterName", label: "Master name", kind: "text", column: 1 },
        { name: "masterDirections", label: "Master directions", kind: "textarea", column: "full" },
        { name: "masterDecision", label: "Master decision", kind: "enum", enumSet: "masterDecision", column: 1 },
        { name: "masterDateOfDecision", label: "Master date of decision", kind: "date", column: 1 },
        { name: "dateDecisionDirectionsReceived", label: "Date decision/directions received", kind: "date", column: 1 },
        { name: "publishToLondonGazette", label: "Publish to London Gazette", kind: "boolean", column: 1 }
      ]
    }
  ]
};

/** Signature tab. */
const SIGNATURE_TAB: TabDef = {
  id: "signature",
  label: "Signature",
  sections: [
    {
      // `<section name="signature_tab_section_1">` puts these side by side — the one
      // section on the later tabs that genuinely uses both cells. Positioned
      // explicitly because the halving fallback only happened to agree with it: a
      // third field on this tab would have moved Received under Requested.
      label: "Signature",
      fields: [
        { name: "signatureRequestedDate", label: "Signature requested date", kind: "date", column: 1 },
        { name: "signatureReceivedDate", label: "Signature received date", kind: "date", column: 2 }
      ]
    }
  ]
};

/** Enrolment tab (includes the read-only computed day count + Gazette date). */
const ENROLMENT_TAB: TabDef = {
  id: "enrolment",
  label: "Enrolment",
  sections: [
    {
      // All three occupy the left cell of `<section name="sectionEnrolment">`, right
      // cell empty — a single stack, not two columns.
      label: "Enrolment",
      fields: [
        { name: "dateEnroled", label: "Date enroled", kind: "date", column: 1 },
        { name: "daysReceivedToEnroled", label: "Days received to enroled", kind: "number", readOnly: true, column: 1 },
        { name: "dateNoticeSentToLondonGazette", label: "Date notice sent to London Gazette", kind: "date", column: 1 }
      ]
    }
  ]
};

/** Return tab. */
const RETURN_TAB: TabDef = {
  id: "return",
  label: "Return",
  sections: [
    {
      // `<section name="sectionReturn">`: both dates in the left cell, then
      // `deedpoll_returnreason` at `colspan="2" rowspan="5"` across the full width.
      // A 2000-character free-text field does not belong in a half column.
      label: "Return",
      fields: [
        { name: "dateReturned", label: "Date returned", kind: "date", column: 1 },
        { name: "daysReceivedToReturned", label: "Days received to returned", kind: "number", readOnly: true, column: 1 },
        { name: "returnReason", label: "Return reason", kind: "textarea", column: "full" }
      ]
    }
  ]
};

/** Refund tab. */
const REFUND_TAB: TabDef = {
  id: "refund",
  label: "Refund",
  sections: [
    {
      // All three in the left cell of `<section name="sectionRefund">`.
      label: "Refund",
      fields: [
        { name: "refundAmount", label: "Refund amount", kind: "number", column: 1 },
        { name: "dateOfRefund", label: "Date of refund", kind: "date", column: 1 },
        { name: "refundReference", label: "Refund reference", kind: "text", column: 1 }
      ]
    }
  ]
};

/** All form tabs in source order. Subgrid tabs carry no data-entry sections. */
export const FORM_TABS: TabDef[] = [
  APPLICATION_TAB,
  { id: "notes", label: "Notes", sections: [], subgrid: "notes" },
  REFERRAL_TAB,
  SIGNATURE_TAB,
  ENROLMENT_TAB,
  RETURN_TAB,
  REFUND_TAB,
  { id: "witnesses", label: "Witnesses", sections: [], subgrid: "witnesses" },
  { id: "documents", label: "Supporting documents", sections: [], subgrid: "documents" }
];

/** Every editable (non-readOnly) field across all data-entry sections. */
export const EDITABLE_FIELDS: FieldDef[] = FORM_TABS.flatMap((t) => t.sections.flatMap((s) => s.fields)).filter((f) => !f.readOnly);

/** Fast lookup of a field def by name (editable + read-only). */
export const FIELD_BY_NAME: Record<string, FieldDef> = Object.fromEntries(
  FORM_TABS.flatMap((t) => t.sections.flatMap((s) => s.fields)).map((f) => [f.name, f])
);
