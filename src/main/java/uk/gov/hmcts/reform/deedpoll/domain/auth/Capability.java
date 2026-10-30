package uk.gov.hmcts.reform.deedpoll.domain.auth;

/** Privileged business operations; ordinary casework and judicial roles remain independent. */
public enum Capability {
    OVERRIDE_PRIVACY,
    DELETE_NOTE,
    DELETE_WITNESS,
    MANAGE_SHARED_QUERIES,
    REPAIR_CCD,
    ACCESS_GAZETTE,
    MANAGE_PROCESS_CONFIG,
    /** The referral queue: seeing it, sending referrals to Masters in a window, and reassigning a sent one. */
    ALLOCATE_REFERRALS
}
