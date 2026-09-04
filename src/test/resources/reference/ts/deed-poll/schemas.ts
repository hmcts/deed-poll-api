import {
  DeedPollState,
  DeedPollType,
  MaritalStatus,
  MasterDecision,
  Nationality,
  NationalitySection,
  PostCounter,
  Privacy,
  ReferToMaster,
  Urgency
} from "@hmcts/postgres-prisma";
import { z } from "zod";
import { optionalIsoDate, optionalText, optionalTextMax, optionSetValues } from "../schema-primitives.js";

/**
 * Create payload for a DeedPoll. Every field is optional — a case is logged incrementally,
 * mirroring the Dynamics form where only a few fields are mandatory at create and the rest are
 * filled through the lifecycle.
 *
 * Type-conditional mandatoriness is NOT enforced here. That lives in `deed-poll-validation.ts`,
 * which knows which fields a given Type demands; this schema stays a pure shape and format
 * guard, exactly as the DTO it replaces did.
 *
 * `referencenumber` and `displayReference` are intentionally absent — they are allocated by
 * `reference-number.ts`. `state` is accepted only for seeding/import; normally it is derived by
 * `case-state.ts` from the populated dates.
 */
export const createDeedPollSchema = z.object({
  // --- General / classification ---
  type: z.enum(DeedPollType).nullish(),
  privacy: z.enum(Privacy).optional(),
  urgency: z.enum(Urgency).optional(),
  urgencyreason: optionalText,
  postCounter: z.enum(PostCounter).nullish(),
  inPerson: z.boolean().nullish(),
  migratedRecord: z.boolean().optional(),
  retainPermanently: z.boolean().optional(),
  maritalStatus: z.enum(MaritalStatus).nullish(),
  feeReference: optionalText,
  onlineApplicationReference: optionalText,

  // --- Applicant name (nvarchar 1000 in the source) ---
  newForename: optionalTextMax(1000),
  newMiddleName: optionalTextMax(1000),
  newSurname: optionalTextMax(1000),
  oldForename: optionalTextMax(1000),
  oldMiddleName: optionalTextMax(1000),
  oldSurname: optionalTextMax(1000),

  // --- Applicant address & contact ---
  applicantBuildingNoName: optionalText,
  applicantEmail: optionalText,
  applicantTelephone: optionalText,
  applicantAddress1: optionalText,
  applicantAddress2: optionalText,
  applicantTownCity: optionalText,
  applicantPostcode: optionalText,

  // --- Representative address & contact ---
  representativeName: optionalText,
  representativeBuildingNoName: optionalText,
  representativeCompany: optionalText,
  representativeAddress1: optionalText,
  representativeEmail: optionalText,
  representativeAddress2: optionalText,
  representativeTelephone: optionalText,
  representativeTownCity: optionalText,
  representativePostcode: optionalText,

  // --- Minor ---
  ageOfMinor: z.int().nullish(),
  ageOfMinorMonths: z.int().nullish(),
  minorDateOfBirth: optionalIsoDate,
  parents: optionalText,

  // --- Nationality ---
  nationality: z.enum(Nationality).nullish(),
  nationalitySection: z.enum(NationalitySection).nullish(),
  nationalitySectionOther: optionalText,

  // --- Declarations (multiselects, Dynamics integer values preserved) ---
  iShall: optionSetValues,
  iHaveSubscribed: optionSetValues,
  andAlsoMyOld: optionSetValues,
  dateOfDeclaration: optionalIsoDate,

  // --- Dates driving state ---
  dateOfApplication: optionalIsoDate,
  dateReceived: optionalIsoDate,
  awaitingFurtherDocsInfo: optionalIsoDate,
  signatureRequestedDate: optionalIsoDate,
  signatureReceivedDate: optionalIsoDate,
  dateReadyForMaster: optionalIsoDate,
  dateReferred: optionalIsoDate,
  dateEnroled: optionalIsoDate,
  dateReturned: optionalIsoDate,
  dateNoticeSentToLondonGazette: optionalIsoDate,

  // --- Master / referral ---
  referToMasterChoice: z.enum(ReferToMaster).nullish(),
  masterName: optionalText,
  masterDirections: optionalText,
  masterDecision: z.enum(MasterDecision).nullish(),
  masterDateOfDecision: optionalIsoDate,
  dateDecisionDirectionsReceived: optionalIsoDate,
  referralNotes: optionalText,
  publishToLondonGazette: z.boolean().optional(),

  // --- Return / refund ---
  returnReason: optionalText,
  refundAmount: z.number().nullish(),
  dateOfRefund: optionalIsoDate,
  refundReference: optionalText,

  state: z.enum(DeedPollState).nullish()
});

/** Update payload — PATCH semantics. Same rules as create, applied only to present fields. */
export const updateDeedPollSchema = createDeedPollSchema.partial();

export type CreateDeedPollInput = z.infer<typeof createDeedPollSchema>;
export type UpdateDeedPollInput = z.infer<typeof updateDeedPollSchema>;
