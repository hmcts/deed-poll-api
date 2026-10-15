package uk.gov.hmcts.reform.deedpoll.domain.audit;

/**
 * The names {@code deedpoll_audit_event.entity_type} stores, one per kind of thing the trail records.
 *
 * <p>Strings rather than an enum because the column is free text and a reader of the trail filters on it; stated
 * once here so two writers of the same entity cannot spell it two ways. Like {@link AuditAction}, a rename leaves
 * seven years of rows under the old name.
 */
public final class AuditEntities {

    public static final String CASE = "DeedPoll";
    public static final String NOTE = "Note";
    public static final String WITNESS = "Witness";
    public static final String DOCUMENT = "Document";
    public static final String REFERRAL = "Referral";
    public static final String NOTIFICATION = "Notification";
    public static final String SAVED_QUERY = "SavedQuery";
    public static final String GAZETTE_SETTINGS = "GazetteSettings";
    public static final String GAZETTE_REQUEST = "GazetteRequest";
    public static final String CCD_ATTEMPT = "CcdCreationAttempt";

    private AuditEntities() {
    }
}
