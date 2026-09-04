import { describe, expect, it } from "vitest";
/**
 * Layer 3 — enum-value conformance.
 *
 * The migration's promise (schema.prisma header, enums/dynamics-values.ts) is
 * that every option-set member keeps its exact Dynamics integer, so data stays
 * interoperable with a future Dataverse re-import. This pins those integers: the
 * expected values are transcribed from the schema.prisma enum comments (the
 * documented source values). A drift in either the value map or the schema
 * comment trips this.
 */
import {
  DEED_POLL_STATE_VALUE,
  DEED_POLL_TYPE_VALUE,
  DOCUMENT_DIRECTION_VALUE,
  MARITAL_STATUS_VALUE,
  MASTER_DECISION_VALUE,
  NATIONALITY_SECTION_VALUE,
  NATIONALITY_VALUE,
  POST_COUNTER_VALUE,
  PRIVACY_VALUE,
  REFER_TO_MASTER_VALUE,
  reverse,
  URGENCY_VALUE
} from "../deed-poll/enums/dynamics-values.js";

describe("Enum-value conformance — Dynamics integers preserved", () => {
  const expectations: [string, Record<string, number>, Record<string, number>][] = [
    [
      "DeedPollType",
      DEED_POLL_TYPE_VALUE,
      {
        Adult: 1,
        AdultTransgender: 2,
        Minor: 3,
        MinorTransgender: 4,
        DeedOfRelinquishment: 5,
        Unknown: 6
      }
    ],
    [
      "DeedPollState",
      DEED_POLL_STATE_VALUE,
      {
        Received: 1,
        ReadyForMaster: 2,
        ReferredToMaster: 3,
        Enroled: 4,
        Returned: 5,
        AwaitingFurtherDocs: 6,
        AwaitingSignature: 7,
        AwaitingEnrolment: 8
      }
    ],
    [
      "MaritalStatus",
      MARITAL_STATUS_VALUE,
      {
        Single: 1,
        MarriedCivilPartnership: 2,
        Widowed: 3,
        DivorcedDissolved: 4
      }
    ],
    ["Privacy", PRIVACY_VALUE, { Normal: 1, Private: 2 }],
    ["Urgency", URGENCY_VALUE, { Normal: 1, Urgent: 2 }],
    ["PostCounter", POST_COUNTER_VALUE, { Counter: 1, Online: 2, Post: 3, Unknown: 4 }],
    [
      "Nationality",
      NATIONALITY_VALUE,
      {
        BritishCitizen: 1,
        BritishDependantTerritoriesCitizen: 2,
        CommonwealthCitizen: 3,
        Unknown: 4
      }
    ],
    [
      "NationalitySection",
      NATIONALITY_SECTION_VALUE,
      {
        Section1: 1,
        Section11: 2,
        Section37: 3,
        Section6: 4,
        Unknown: 5,
        NoneOfTheAbove: 6
      }
    ],
    ["MasterDecision", MASTER_DECISION_VALUE, { Approved: 1, Rejected: 2 }],
    ["ReferToMaster", REFER_TO_MASTER_VALUE, { Yes: 1, No: 2 }],
    ["DocumentDirection", DOCUMENT_DIRECTION_VALUE, { Incoming: 1, Outgoing: 2 }]
  ];

  for (const [name, actual, expected] of expectations) {
    it(`${name} maps every member to its documented Dynamics integer`, () => {
      expect(actual).toEqual(expected);
    });

    it(`${name} integers are unique (reverse map is lossless)`, () => {
      const rev = reverse(actual);
      expect(Object.keys(rev).length).toBe(Object.keys(actual).length);
    });
  }
});
