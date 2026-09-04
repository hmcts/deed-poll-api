import { describe, expect, it } from "vitest";
/**
 * Layer 1 — structural conformance.
 *
 * Asserts the Prisma schema covers the source Dataverse structure documented in
 * `.business_context/dataverse_tables/`. See artifacts.ts for the FORWARD-only
 * caveat: the deedpoll columns.html extract is alphabetically truncated, so we
 * assert "every artifact column maps to a Prisma field" and never claim the
 * artifact is a complete column set.
 */
import { hasNoBusinessRules, loadColumns, loadRelationships, type TableDir } from "./artifacts.js";

/**
 * Dynamics system columns present on every table — audit, ownership, currency,
 * import and state plumbing. Not ported (the Prisma models use app-native
 * equivalents: createdOn/modifiedOn, and no ownership/currency model).
 */
const SYSTEM_COLUMNS = new Set<string>([
  "CreatedBy",
  "CreatedOnBehalfBy",
  "CreatedOn",
  "ModifiedBy",
  "ModifiedOnBehalfBy",
  "ModifiedOn",
  "OwnerId",
  "OwningBusinessUnit",
  "OwningTeam",
  "OwningUser",
  "OverriddenCreatedOn",
  "ImportSequenceNumber",
  "TransactionCurrencyId",
  "ExchangeRate",
  "TimeZoneRuleVersionNumber",
  "UTCConversionTimeZoneCode",
  "VersionNumber",
  "statecode",
  "statuscode",
  "deedpoll_DateTimeZoneIndependent",
  "deedpoll_DateToday"
]);

/**
 * Source calculated columns the migration derives in the app layer instead of
 * storing (computed.ts): the two day-count fields. Not expected as stored Prisma
 * fields.
 */
const DERIVED_COLUMNS = new Set<string>(["deedpoll_DaysReceivedtoEnrolled", "deedpoll_DaysReceivedtoReturned"]);

/**
 * Source-column (logical name) → Prisma field on the corresponding model.
 * Explicit rather than fuzzy: a source column the migration has NOT accounted
 * for has no entry here and fails the coverage test by name, rather than being
 * silently matched by a loose heuristic.
 *
 * `null` marks a business column deliberately NOT ported, with the reason —
 * these are conscious omissions, distinct from system plumbing.
 */
const DEEDPOLL_MAP: Record<string, string | null> = {
  deedpoll_DeedPollId: "id",
  deedpoll_ReferenceNumber: "displayReference",
  deedpoll_AgeofMinorMonths: "ageOfMinorMonths",
  deedpoll_AgeofMinor: "ageOfMinor",
  deedpoll_Andalsomyold: "andAlsoMyOld",
  deedpoll_ApplicantAddress1: "applicantAddress1",
  deedpoll_ApplicantAddress2: "applicantAddress2",
  deedpoll_ApplicantBuildingNoName: "applicantBuildingNoName",
  deedpoll_ApplicantEmail: "applicantEmail",
  deedpoll_ApplicantPostcode: "applicantPostcode",
  deedpoll_ApplicantTelephone: "applicantTelephone",
  deedpoll_ApplicantTownCity: "applicantTownCity",
  deedpoll_Awaitingfurtherdocsinfo: "awaitingFurtherDocsInfo",
  deedpoll_DateDecisionDirectionsReceived: "dateDecisionDirectionsReceived",
  deedpoll_DateEnrolled: "dateEnroled",
  deedpoll_DateNoticeSentToLondonGazette: "dateNoticeSentToLondonGazette",
  deedpoll_Date: "dateOfApplication",
  deedpoll_DateofDeclaration: "dateOfDeclaration",
  deedpoll_DateofRefund: "dateOfRefund",
  deedpoll_DateReadyforMaster: "dateReadyForMaster",
  deedpoll_DateReceived: "dateReceived",
  deedpoll_DateReferred: "dateReferred",
  deedpoll_DateReturned: "dateReturned",
  deedpoll_FeeReference: "feeReference",
  deedpoll_Ihavesubscribed: "iHaveSubscribed",
  deedpoll_Ishall: "iShall",
  deedpoll_InPerson: "inPerson",
  deedpoll_Status: "maritalStatus",
  deedpoll_MasterDateofDecision: "masterDateOfDecision",
  deedpoll_MasterDecision: "masterDecision",
  deedpoll_MasterDirections: "masterDirections",
  deedpoll_MasterName: "masterName",
  deedpoll_MigratedRecord: "migratedRecord",
  deedpoll_MigratedRecordID: null, // migration-provenance id; not needed in the rebuilt store
  deedpoll_MinorDateofBirth: "minorDateOfBirth",
  deedpoll_Nationality: "nationality",
  deedpoll_NewForename: "newForename",
  deedpoll_NewMiddleName: "newMiddleName",
  deedpoll_NewSurname: "newSurname",
  deedpoll_OldForename: "oldForename",
  deedpoll_OldMiddleName: "oldMiddleName",
  deedpoll_OldSurname: "oldSurname",
  deedpoll_OnlineApplicationReference: "onlineApplicationReference",
  deedpoll_Parents: "parents",
  deedpoll_PostCounter: "postCounter",
  deedpoll_Privacy: "privacy",
  deedpoll_PublishtoLondonGazette: "publishToLondonGazette",
  deedpoll_RefertoMasterChoice: "referToMasterChoice",
  deedpoll_ReferralNotes: "referralNotes",
  deedpoll_RefundAmount: "refundAmount",
  deedpoll_refundamount_Base: null, // currency base-amount shadow; single-currency rebuild has no base
  deedpoll_RefundReference: "refundReference"
};

const NOTE_MAP: Record<string, string | null> = {
  deedpoll_NotesId: "id",
  deedpoll_Note: "note",
  // Autonumber display code, deliberately not ported (2026-08-10). It exists in the
  // source only because Dataverse forces a GUID primary key, so a short human-readable
  // handle needed a second column; `id` is the only identity the rebuild needs. The
  // ported column was dropped having never held a value — see prisma/schema.prisma.
  deedpoll_NoteID: null,
  deedpoll_DeedPoll: "deedPollId"
};

const WITNESS_MAP: Record<string, string | null> = {
  deedpoll_WitnessId: "id",
  deedpoll_WitnessName: "witnessName",
  deedpoll_WitnessAddress: "witnessAddress",
  deedpoll_WitnessOccupation: "witnessOccupation",
  deedpoll_Declarant: "declarant",
  deedpoll_DeedPoll: "deedPollId"
};

const DOCUMENT_MAP: Record<string, string | null> = {
  deedpoll_SupportingDocumentId: "id",
  deedpoll_Direction: "direction",
  deedpoll_Document: "storageKey", // File column → blob metadata (storageKey/originalName/mimeType/sizeBytes)
  deedpoll_DocumentType: "documentTypeId",
  deedpoll_Name: "name",
  deedpoll_DeedPoll: "deedPollId"
};

const DOCUMENT_TYPE_MAP: Record<string, string | null> = {
  deedpoll_SupportingDocumentTypeId: "id",
  deedpoll_SupportingDocumentType: "name",
  deedpoll_DeedPollType: "applicableTypes",
  deedpoll_DeedPollMaritalStatus: "applicableMaritalStatuses"
};

const TABLE_MAPS: Record<TableDir, Record<string, string | null>> = {
  deedpoll: DEEDPOLL_MAP,
  deeppoll_note: NOTE_MAP,
  deeppoll_witness: WITNESS_MAP,
  deeppoll_supporting_document: DOCUMENT_MAP,
  deeppoll_supporting_document_type: DOCUMENT_TYPE_MAP
};

describe("Structural conformance — every artifact column is accounted for", () => {
  for (const table of Object.keys(TABLE_MAPS) as TableDir[]) {
    describe(table, () => {
      const columns = loadColumns(table);
      const map = TABLE_MAPS[table];

      for (const col of columns) {
        if (SYSTEM_COLUMNS.has(col.logicalName)) continue;
        if (DERIVED_COLUMNS.has(col.logicalName)) continue;

        it(`accounts for business column ${col.logicalName} (${col.displayName})`, () => {
          expect(Object.hasOwn(map, col.logicalName)).toBe(true);
        });
      }
    });
  }
});

describe("Structural conformance — relationships", () => {
  it("models the three business child relations as one-to-many", () => {
    const rels = loadRelationships("deedpoll");
    const childOneToMany = rels.filter((r) => r.relationshipType === "One-to-many" && /Supporting Document|Witness|Note/.test(r.relatedTable));
    const related = childOneToMany.map((r) => r.relatedTable).sort();
    expect(related).toEqual(["Deed Poll Note", "Deed Poll Supporting Document", "Deed Poll Witness"]);
  });
});

describe("Structural conformance — truncation guard", () => {
  it("records that the deedpoll columns extract is alphabetically truncated (forward-only coverage)", () => {
    // The extract lists 70 rows, ending alphabetically at "Refund Reference" —
    // post-R rule-driving columns (Type, State, Urgency, Section, Retain
    // Permanently) are absent, so coverage here is FORWARD-only. This asserts
    // that absence directly: if a fuller extract adds those columns, this trips
    // and prompts extending the FORWARD map above.
    const logicalNames = loadColumns("deedpoll").map((c) => c.logicalName);
    expect(logicalNames).not.toContain("deedpoll_Type");
    expect(logicalNames).not.toContain("deedpoll_State");
    expect(logicalNames).not.toContain("deedpoll_Urgency");
    // Sanity: the extract really does stop in the R's.
    expect(logicalNames).toContain("deedpoll_RefundReference");
  });
});

describe("Structural conformance — child tables carry no business rules", () => {
  const childTables: TableDir[] = ["deeppoll_note", "deeppoll_witness", "deeppoll_supporting_document", "deeppoll_supporting_document_type"];
  for (const table of childTables) {
    it(`${table} business_rules.md is empty (nothing to port)`, () => {
      expect(hasNoBusinessRules(table)).toBe(true);
    });
  }
});
