import { DeedPollType, MaritalStatus, PostCounter, ReferToMaster, Urgency } from "@hmcts/postgres-prisma";

/**
 * A single field's UI + validation rule for a given Type, as consumed by both
 * the SSR form (show/hide/mandate) and request-time validation.
 */
export interface FieldRule {
  visible: boolean;
  required: boolean;
}

/** Field-rule map keyed by DeedPoll field name. */
export type FieldRules = Record<string, FieldRule>;

/** The fields whose visibility/mandatoriness varies by Type. */
const RULED_FIELDS = [
  "inPerson",
  "maritalStatus",
  "ageOfMinor",
  "ageOfMinorMonths",
  "parents",
  "minorDateOfBirth",
  "urgency",
  "nationality",
  "nationalitySection",
  "nationalitySectionOther",
  "oldForename",
  "oldMiddleName",
  "oldSurname",
  "dateNoticeSentToLondonGazette"
] as const;

/**
 * Fields whose mandatoriness the source relaxes to optional when Migrated
 * Record = Yes, regardless of Type (source rule "Make fields required or
 * non-required depending on Type and Migrated Record", the migrated branch).
 */
const MIGRATED_RELAXED_FIELDS = [
  "inPerson",
  "maritalStatus",
  "ageOfMinor",
  "ageOfMinorMonths",
  "parents",
  "urgency",
  "nationality",
  "nationalitySection"
] as const;

/**
 * The four fields the source's "Show hide and mandate fields for Minors" workflow
 * shows only for Type = Minor (3) or Minor Transgender (4). Its else branch hides
 * ALL FOUR — including Minor's Date of Birth, which this list previously omitted,
 * leaving that one field stranded on an Adult case's General card.
 */
const MINOR_ONLY_FIELDS = ["minorDateOfBirth", "ageOfMinor", "ageOfMinorMonths", "parents"] as const;

const MINOR_TYPES: DeedPollType[] = [DeedPollType.Minor, DeedPollType.MinorTransgender];
const ADULT_TYPES: DeedPollType[] = [DeedPollType.Adult, DeedPollType.AdultTransgender];

const isMinor = (t?: DeedPollType | null) => !!t && MINOR_TYPES.includes(t);
const isAdult = (t?: DeedPollType | null) => !!t && ADULT_TYPES.includes(t);
const isRelinquishment = (t?: DeedPollType | null) => t === DeedPollType.DeedOfRelinquishment;

/** Shape of the record subset the validator inspects. */
export interface ValidationInput {
  type?: DeedPollType | null;
  urgency?: Urgency | null;
  urgencyreason?: string | null;
  postCounter?: PostCounter | null;
  onlineApplicationReference?: string | null;
  publishToLondonGazette?: boolean | null;
  maritalStatus?: MaritalStatus | null;
  referToMasterChoice?: ReferToMaster | null;
  migratedRecord?: boolean | null;
  inPerson?: boolean | null;
  ageOfMinor?: number | null;
  ageOfMinorMonths?: number | null;
  parents?: string | null;
  nationality?: unknown;
  nationalitySection?: unknown;
  oldForename?: string | null;
  [key: string]: unknown;
}

export interface ValidationError {
  field: string;
  message: string;
}

/**
 * DeedPollValidationService — ports the 7 conditional XAML workflows into a
 * single request-time validator plus a `getFieldRules(type)` helper the SSR
 * form consumes for show/hide/mandate.
 *
 * Sources (Workflows/*.xaml):
 *   - Makefieldsrequiredornon-requireddependingonTypeand… (per-type required set)
 *   - ShowhideandmandatefieldsforMinors (minor fields)
 *   - ShowhideandmandatefieldsforDeedofRelinquishment (old-name hidden, etc.)
 *   - ShowandmandateUrgencyReason (urgencyreason gated on urgency=Urgent)
 *   - ShowOnlineApplicationreferenceandmandate (gated on postcounter=Online)
 *   - ShoworhideDateNoticeSenttoLondonGazette (gated on publishtolondongazette)
 *   - DefaultMinorDeedPolltoSingle (create default)
 */
/**
 * Per-type field-rule matrix. Adult/Minor/Relinquishment each get a distinct
 * required + visible set; Unknown (6) and null default to everything optional
 * and visible (the source leaves them untouched).
 *
 * `migrated` mirrors the source rule's Migrated Record branch: when the record
 * was migrated from the previous Deed Poll system, the whole per-type required
 * set relaxes to optional (In Person, Marital Status, Age of Minor Years/Months,
 * Parents, Urgency, Nationality, Section). Visibility (show/hide) is unaffected —
 * only mandatoriness relaxes — so the form still hides the fields the type
 * doesn't use.
 */
export function getFieldRules(type?: DeedPollType | null, migrated = false, publishToGazette = true): FieldRules {
  const rules: FieldRules = {};
  for (const f of RULED_FIELDS) rules[f] = { visible: true, required: false };

  const setReq = (field: string, required: boolean) => {
    rules[field] = { ...rules[field], required };
  };
  const setVis = (field: string, visible: boolean) => {
    rules[field] = { ...rules[field], visible };
  };

  if (isAdult(type)) {
    // Adult / Adult Transgender: identity + classification required, no minor fields.
    setReq("inPerson", true);
    setReq("maritalStatus", true);
    setReq("urgency", true);
    setReq("nationality", true);
    setReq("nationalitySection", true);
    for (const f of MINOR_ONLY_FIELDS) {
      setVis(f, false);
    }
  } else if (isMinor(type)) {
    // Minor / Minor Transgender: everything the adult needs plus minor fields.
    setReq("inPerson", true);
    setReq("maritalStatus", true);
    setReq("ageOfMinor", true);
    setReq("ageOfMinorMonths", true);
    setReq("parents", true);
    setReq("urgency", true);
    setReq("nationality", true);
    setReq("nationalitySection", true);
  } else if (isRelinquishment(type)) {
    // Deed of Relinquishment: only In Person + Urgency required; old-name and
    // marital/nationality/section fields hidden; Gazette date hidden. The minor
    // fields are hidden too — the Minors workflow's condition is "Type = Minor or
    // Minor Transgender", so its else branch covers relinquishment as much as it
    // covers Adult.
    setReq("inPerson", true);
    setReq("urgency", true);
    for (const f of [
      "oldForename",
      "oldMiddleName",
      "oldSurname",
      "maritalStatus",
      "nationality",
      "nationalitySection",
      "nationalitySectionOther",
      "dateNoticeSentToLondonGazette",
      ...MINOR_ONLY_FIELDS
    ]) {
      setVis(f, false);
    }
  }

  // Migrated Record = Yes relaxes the entire per-type required set to optional
  // (source rule "Make fields required or non-required depending on Type and
  // Migrated Record"). Applied last so it overrides whatever the type branch
  // set; visibility is left untouched.
  if (migrated) {
    for (const f of MIGRATED_RELAXED_FIELDS) setReq(f, false);
  }

  // "Show or hide Date Notice Sent to London Gazette": when Publish to London
  // Gazette = No, the field is hidden — for EVERY type, not just relinquishment
  // (the relinquishment branch above forces publish = No, so this is consistent
  // with it). The matching CLEAR of a stored value happens on the write path in
  // applyGazetteGate().
  if (!publishToGazette) {
    setVis("dateNoticeSentToLondonGazette", false);
  }

  return rules;
}

/**
 * Write-path half of "Show or hide Date Notice Sent to London Gazette": when
 * Publish to London Gazette = No, clear any stored notice date. Returns a patch
 * to merge into the write (never mutates input); an empty patch when publish is
 * Yes/unset so an existing date is left untouched.
 */
export function applyGazetteGate(input: { publishToLondonGazette?: boolean | null; dateNoticeSentToLondonGazette?: Date | string | null }): {
  dateNoticeSentToLondonGazette?: null;
} {
  if (input.publishToLondonGazette === false) {
    return { dateNoticeSentToLondonGazette: null };
  }
  return {};
}

/**
 * Write-path half of "Show hide and mandate fields for Minors": when Type is not
 * Minor or Minor Transgender, the source's else branch clears all four minor
 * fields as well as hiding them (its Clear Age of Minor Years / Months / Date of
 * Birth / Parents steps). Without this, converting a Minor case to Adult leaves
 * orphaned minor data in the row — invisible on every page but still stored.
 *
 * Returns a patch to merge into the write (never mutates input). It clears only
 * for the types whose rules HIDE the fields — Adult, Adult Transgender and
 * Relinquishment — so hiding and clearing can never disagree. Two cases are
 * deliberately left alone:
 *
 *   - No type resolved (absent or null): a patch that does not settle the type
 *     must not be read as "not a minor" and wipe the fields.
 *   - Unknown: the source leaves this type untouched throughout the port
 *     (getFieldRules imposes nothing for it), so clearing here would delete data
 *     that the same type still displays.
 *
 * `type` must come from the MERGED record on update, not the raw patch, so that a
 * patch touching only, say, `parents` does not clear what it just set.
 */
export function applyMinorGate(input: { type?: DeedPollType | null }): {
  minorDateOfBirth?: null;
  ageOfMinor?: null;
  ageOfMinorMonths?: null;
  parents?: null;
} {
  const hidesMinorFields = isAdult(input.type) || isRelinquishment(input.type);
  if (!hidesMinorFields) return {};
  return {
    minorDateOfBirth: null,
    ageOfMinor: null,
    ageOfMinorMonths: null,
    parents: null
  };
}

/**
 * Create-time defaults ported from the workflows:
 *   - DefaultMinorDeedPolltoSingle: minor type + no marital status → Single.
 *   - Relinquishment: refer-to-master defaults Yes, publish-to-Gazette false.
 * Returns a patch to merge into the create payload (never mutates input).
 */
export function applyCreateDefaults(input: ValidationInput): Partial<ValidationInput> {
  const patch: Partial<ValidationInput> = {};
  if (isMinor(input.type) && input.maritalStatus == null) {
    patch.maritalStatus = MaritalStatus.Single;
  }
  if (isRelinquishment(input.type)) {
    if (input.referToMasterChoice == null) {
      patch.referToMasterChoice = ReferToMaster.Yes;
    }
    if (input.publishToLondonGazette == null) {
      patch.publishToLondonGazette = false;
    }
  }
  return patch;
}

/**
 * Request-time validation of a (fully-merged) record. Returns the list of
 * violations — empty when valid. The caller decides whether to hard-fail
 * (create/submit) or treat as advisory (incremental save).
 *
 * Combines the per-type required matrix (getFieldRules) with the three
 * conditional gates the source enforces field-by-field:
 *   - urgencyreason required when urgency = Urgent (2)
 *   - onlineApplicationReference required when postcounter = Online (2)
 */
export function validate(input: ValidationInput): ValidationError[] {
  const errors: ValidationError[] = [];
  const rules = getFieldRules(input.type, input.migratedRecord === true);

  const isEmpty = (v: unknown) => v == null || (typeof v === "string" && v.trim() === "");

  // Type-driven required fields (only when the type is known — Unknown/null
  // impose nothing, matching the source which leaves those untouched).
  if (input.type) {
    for (const [field, rule] of Object.entries(rules)) {
      if (rule.required && rule.visible && isEmpty(input[field])) {
        errors.push({ field, message: `${field} is required` });
      }
    }
  }

  // Conditional gate: urgency reason.
  if (input.urgency === Urgency.Urgent && isEmpty(input.urgencyreason)) {
    errors.push({
      field: "urgencyreason",
      message: "Urgency reason is required when urgency is Urgent"
    });
  }

  // Conditional gate: online application reference.
  if (input.postCounter === PostCounter.Online && isEmpty(input.onlineApplicationReference)) {
    errors.push({
      field: "onlineApplicationReference",
      message: "Online application reference is required for online applications"
    });
  }

  return errors;
}
