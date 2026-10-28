package uk.gov.hmcts.reform.deedpoll.domain.masters;

/**
 * Where one Master email is, as {@code deedpoll_master_email.state} stores it (V44). Never rename a member; the
 * names are the stored form.
 */
public enum MasterEmailState {

    /** Waiting to be sent, or to be tried again after a known temporary refusal. */
    Pending,

    /** Send authority committed: the one Notify call may have been made. Never sent again automatically. */
    Sending,

    /** Notify accepted it. */
    Sent,

    /** Refused, exhausted, or of unknown outcome. Investigated, never resent automatically (ADR-0007). */
    Failed,

    /** Nothing was ever sent for it: the template was unprovisioned, or nothing it listed was left. */
    Skipped
}
