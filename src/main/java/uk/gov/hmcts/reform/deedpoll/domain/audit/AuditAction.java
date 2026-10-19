package uk.gov.hmcts.reform.deedpoll.domain.audit;

/**
 * What an audit event says happened: the trail's whole vocabulary.
 *
 * <p>Stored by {@link #name()} in {@code deedpoll_audit_event.action}, a {@code varchar(64)} with no check
 * constraint, so adding an action is a code change rather than a migration. Renaming one is not free: the
 * rows already written keep the old name for seven years, and a reader of the trail has to know both.
 *
 * <p>A read action is written only by {@link AuditTrail#recordRead}, in its own transaction, and a write action
 * only by {@link AuditTrail#record}, in the change's. The flag is what lets each refuse the other's.
 */
public enum AuditAction {
    CASE_CREATED,
    CASE_UPDATED,
    CASE_DELETED,
    NOTE_CREATED,
    NOTE_UPDATED,
    NOTE_DELETED,
    WITNESS_CREATED,
    WITNESS_UPDATED,
    WITNESS_DELETED,
    DOCUMENT_UPLOADED,
    DOCUMENT_CREATED,
    DOCUMENT_UPDATED,
    DOCUMENT_DELETED,
    REFERRAL_CREATED,
    REFERRAL_WITHDRAWN,
    REFERRAL_CLOSED,
    REFERRAL_CONFIRMED,
    REFERRAL_CLAIMED,
    REFERRAL_DIRECTED,
    REFERRAL_DIRECTION_REVISED,
    REFERRAL_DIRECTION_WITHDRAWN,
    EMAIL_SENT,
    SAVED_QUERY_CREATED,
    SAVED_QUERY_UPDATED,
    SAVED_QUERY_DELETED,
    GAZETTE_SETTINGS_CHANGED,
    GAZETTE_NOTICE_SENT,
    CCD_ATTEMPT_RECONCILED,
    CCD_ATTEMPT_RETRIED,
    CASE_VIEWED(true),
    DOCUMENT_DOWNLOADED(true);

    private final boolean read;

    AuditAction() {
        this(false);
    }

    AuditAction(boolean read) {
        this.read = read;
    }

    /** Whether this records somebody looking rather than changing anything. */
    public boolean isRead() {
        return read;
    }
}
