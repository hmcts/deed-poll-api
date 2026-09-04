import { DeedPollState, DeedPollType, Privacy, ReferToMaster } from "@hmcts/postgres-prisma";

/** Context for building the presentation model: who is viewing and record age. */
export interface PresentationContext {
  isAdmin: boolean;
  /** True for a brand-new (unsaved) record — the source's formType === 1. */
  isNew: boolean;
}

/** Server-driven view model replacing the client-side deedpoll_javascript. */
export interface FormPresentation {
  privacy: {
    /** Effective privacy after any create-time auto-default. */
    value: Privacy;
    /** Whether the Privacy control is editable (vs locked). */
    editable: boolean;
    /** Whether to show the "This record is PRIVATE!" banner. */
    showBanner: boolean;
    bannerText?: string;
  };
  tabs: {
    referralToMaster: boolean;
    enrolment: boolean;
    return: boolean;
  };
}

const PRIVATE_BANNER = "This record is PRIVATE!";

export interface PresentationInput {
  type?: DeedPollType | null;
  privacy?: Privacy | null;
  referToMasterChoice?: ReferToMaster | null;
  state?: DeedPollState | null;
}

/**
 * FormPresentationService — ports the client-side `deedpoll_javascript` web
 * resource to a server-computed view model (the UI is SSR, so show/hide/mandate
 * is decided here rather than in the browser).
 *
 * Privacy auto-default (create only), editability, the PRIVATE banner, and the
 * three conditional tabs all mirror the source functions OnChangeType,
 * OnChangePrivacy and ShowHideTab exactly. See notes on each method.
 */
/**
 * Auto-default privacy on CREATE, matching OnChangeType:
 *   - Admin + Minor Transgender (4) → Private (2); other types unchanged.
 *   - Non-admin + Adult/AdultTG/Minor (1,2,3) → Normal (1).
 *   - Non-admin + Minor Transgender (4) → Private (2).
 *   - Non-admin + Deed of Relinquishment (5) → Normal (1).
 *   - Unknown (6) / null → unchanged (no branch in the source).
 * On an existing record the source never changes the stored value.
 */
export function defaultPrivacy(type: DeedPollType | null | undefined, current: Privacy | null | undefined, ctx: PresentationContext): Privacy {
  const fallback = current ?? Privacy.Normal;
  if (!ctx.isNew) return fallback; // existing record: value is never auto-set

  if (ctx.isAdmin) {
    return type === DeedPollType.MinorTransgender ? Privacy.Private : fallback;
  }
  // Non-admin, new record:
  switch (type) {
    case DeedPollType.Adult:
    case DeedPollType.AdultTransgender:
    case DeedPollType.Minor:
    case DeedPollType.DeedOfRelinquishment:
      return Privacy.Normal;
    case DeedPollType.MinorTransgender:
      return Privacy.Private;
    default:
      return fallback; // Unknown / null unchanged
  }
}

/**
 * Privacy editability, matching OnLoadPrivacyControl / OnChangeType:
 *   - Admin → always editable.
 *   - Non-admin + new + Deed of Relinquishment (5) → editable.
 *   - Non-admin + new + any other type → locked.
 *   - Non-admin + existing → locked.
 */
export function privacyEditable(type: DeedPollType | null | undefined, ctx: PresentationContext): boolean {
  if (ctx.isAdmin) return true;
  if (ctx.isNew) return type === DeedPollType.DeedOfRelinquishment;
  return false;
}

/** Tab visibility, matching ShowHideTab. */
export function tabVisibility(input: PresentationInput): FormPresentation["tabs"] {
  // Referral-to-Master hidden only when the choice is explicitly No (2);
  // visible for Yes (1) or when unset.
  const referralToMaster = input.referToMasterChoice !== ReferToMaster.No;

  // Enrolment/Return keyed on state: Enroled(4) shows Enrolment only,
  // Returned(5) shows Return only, everything else shows both.
  let enrolment = true;
  let ret = true;
  if (input.state === DeedPollState.Enroled) {
    enrolment = true;
    ret = false;
  } else if (input.state === DeedPollState.Returned) {
    enrolment = false;
    ret = true;
  }

  return { referralToMaster, enrolment, return: ret };
}

/** Build the full presentation model for a record + viewer context. */
export function build(input: PresentationInput, ctx: PresentationContext): FormPresentation {
  const value = defaultPrivacy(input.type, input.privacy, ctx);
  const showBanner = value === Privacy.Private;
  return {
    privacy: {
      value,
      editable: privacyEditable(input.type, ctx),
      showBanner,
      bannerText: showBanner ? PRIVATE_BANNER : undefined
    },
    tabs: tabVisibility(input)
  };
}
