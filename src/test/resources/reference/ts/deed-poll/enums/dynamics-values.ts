/**
 * Dynamics option-set value maps.
 *
 * Prisma enums use readable names; Dynamics stores integers. These maps are the
 * single source of truth linking the two, so:
 *   - UI can render the human label,
 *   - a future Dataverse import/export can round-trip the exact integer,
 *   - business logic that mirrors the original (e.g. "type === 5") stays legible.
 *
 * Each map is keyed by the Prisma enum member -> Dynamics integer. Reverse maps
 * are derived for import.
 */
import type {
  DeedPollState,
  DeedPollType,
  DocumentDirection,
  MaritalStatus,
  MasterDecision,
  Nationality,
  NationalitySection,
  PostCounter,
  Privacy,
  ReferToMaster,
  Urgency
} from "@hmcts/postgres-prisma";

export const DEED_POLL_TYPE_VALUE: Record<DeedPollType, number> = {
  Adult: 1,
  AdultTransgender: 2,
  Minor: 3,
  MinorTransgender: 4,
  DeedOfRelinquishment: 5,
  Unknown: 6
};

export const DEED_POLL_STATE_VALUE: Record<DeedPollState, number> = {
  Received: 1,
  ReadyForMaster: 2,
  ReferredToMaster: 3,
  Enroled: 4,
  Returned: 5,
  AwaitingFurtherDocs: 6,
  AwaitingSignature: 7,
  AwaitingEnrolment: 8
};

export const MARITAL_STATUS_VALUE: Record<MaritalStatus, number> = {
  Single: 1,
  MarriedCivilPartnership: 2,
  Widowed: 3,
  DivorcedDissolved: 4
};

export const PRIVACY_VALUE: Record<Privacy, number> = {
  Normal: 1,
  Private: 2
};

export const URGENCY_VALUE: Record<Urgency, number> = {
  Normal: 1,
  Urgent: 2
};

export const POST_COUNTER_VALUE: Record<PostCounter, number> = {
  Counter: 1,
  Online: 2,
  Post: 3,
  Unknown: 4
};

export const NATIONALITY_VALUE: Record<Nationality, number> = {
  BritishCitizen: 1,
  BritishDependantTerritoriesCitizen: 2,
  CommonwealthCitizen: 3,
  Unknown: 4
};

export const NATIONALITY_SECTION_VALUE: Record<NationalitySection, number> = {
  Section1: 1,
  Section11: 2,
  Section37: 3,
  Section6: 4,
  Unknown: 5,
  NoneOfTheAbove: 6
};

export const MASTER_DECISION_VALUE: Record<MasterDecision, number> = {
  Approved: 1,
  Rejected: 2
};

export const REFER_TO_MASTER_VALUE: Record<ReferToMaster, number> = {
  Yes: 1,
  No: 2
};

export const DOCUMENT_DIRECTION_VALUE: Record<DocumentDirection, number> = {
  Incoming: 1,
  Outgoing: 2
};

/** Human-readable labels for UI (Phase 9). */
export const DEED_POLL_TYPE_LABEL: Record<DeedPollType, string> = {
  Adult: "Adult",
  AdultTransgender: "Adult Transgender",
  Minor: "Minor",
  MinorTransgender: "Minor Transgender",
  DeedOfRelinquishment: "Deed of Relinquishment",
  Unknown: "Unknown"
};

export const DEED_POLL_STATE_LABEL: Record<DeedPollState, string> = {
  Received: "Received",
  ReadyForMaster: "Ready for Master",
  ReferredToMaster: "Referred to Master",
  Enroled: "Enroled",
  Returned: "Returned",
  AwaitingFurtherDocs: "Awaiting Further Docs/Info",
  AwaitingSignature: "Awaiting Signature",
  AwaitingEnrolment: "Awaiting Enrolment"
};

export const MARITAL_STATUS_LABEL: Record<MaritalStatus, string> = {
  Single: "Single",
  MarriedCivilPartnership: "Married / Civil Partnership",
  Widowed: "Widowed",
  DivorcedDissolved: "Divorced / Civil Partnership Dissolved"
};

export const PRIVACY_LABEL: Record<Privacy, string> = {
  Normal: "Normal",
  Private: "Private"
};

export const URGENCY_LABEL: Record<Urgency, string> = {
  Normal: "Normal",
  Urgent: "Urgent"
};

export const POST_COUNTER_LABEL: Record<PostCounter, string> = {
  Counter: "Counter",
  Online: "Online",
  Post: "Post",
  Unknown: "Unknown"
};

export const NATIONALITY_LABEL: Record<Nationality, string> = {
  BritishCitizen: "British Citizen",
  BritishDependantTerritoriesCitizen: "British Dependant Territories Citizen",
  CommonwealthCitizen: "Commonwealth Citizen",
  Unknown: "Unknown"
};

export const NATIONALITY_SECTION_LABEL: Record<NationalitySection, string> = {
  Section1: "Section 1",
  Section11: "Section 11",
  Section37: "Section 37",
  Section6: "Section 6",
  Unknown: "Unknown",
  NoneOfTheAbove: "None of the Above"
};

export const MASTER_DECISION_LABEL: Record<MasterDecision, string> = {
  Approved: "Approved",
  Rejected: "Rejected"
};

export const REFER_TO_MASTER_LABEL: Record<ReferToMaster, string> = {
  Yes: "Yes",
  No: "No"
};

export const DOCUMENT_DIRECTION_LABEL: Record<DocumentDirection, string> = {
  Incoming: "Incoming",
  Outgoing: "Outgoing"
};

/**
 * Multiselect option sets — stored as Int[] on the record, so these maps are
 * keyed by the Dynamics integer value (mirrors deedpoll_ishall etc.).
 */
export const I_SHALL_LABEL: Record<number, string> = {
  1: "and my wife",
  2: "and my husband",
  3: "and my civil partner",
  4: "and my children",
  5: "and I alone"
};

export const I_HAVE_SUBSCRIBED_LABEL: Record<number, string> = {
  1: "Forename(s)",
  2: "Middle Name(s)",
  3: "Surname(s)"
};

export const AND_ALSO_MY_OLD_LABEL: Record<number, string> = {
  1: "Forename(s)",
  2: "Middle Name(s)",
  3: "Surname(s)"
};

/**
 * Aggregate of all label maps, keyed by a short set name the Nunjucks `label`
 * filter uses (e.g. `{{ case.type | label('type') }}`). Single source for the UI.
 */
export const LABELS = {
  type: DEED_POLL_TYPE_LABEL,
  state: DEED_POLL_STATE_LABEL,
  maritalStatus: MARITAL_STATUS_LABEL,
  privacy: PRIVACY_LABEL,
  urgency: URGENCY_LABEL,
  postCounter: POST_COUNTER_LABEL,
  nationality: NATIONALITY_LABEL,
  nationalitySection: NATIONALITY_SECTION_LABEL,
  masterDecision: MASTER_DECISION_LABEL,
  referToMaster: REFER_TO_MASTER_LABEL,
  documentDirection: DOCUMENT_DIRECTION_LABEL,
  iShall: I_SHALL_LABEL,
  iHaveSubscribed: I_HAVE_SUBSCRIBED_LABEL,
  andAlsoMyOld: AND_ALSO_MY_OLD_LABEL
} as const;

/** Build a reverse map (Dynamics int -> enum member) for imports. */
export function reverse<E extends string>(map: Record<E, number>): Record<number, E> {
  const out = {} as Record<number, E>;
  (Object.keys(map) as E[]).forEach((k) => {
    out[map[k]] = k;
  });
  return out;
}
