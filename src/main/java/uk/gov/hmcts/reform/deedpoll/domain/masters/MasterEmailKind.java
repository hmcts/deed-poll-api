package uk.gov.hmcts.reform.deedpoll.domain.masters;

/**
 * The emails a Master receives (plans D3 and F1), as {@code deedpoll_master_email.kind} stores them.
 *
 * <p>Not {@code EmailTemplate} members: those are a case's correspondence, sent to its applicant through the case
 * outbox, and every one of them meets a case-scoped switch in {@code CaseEmailService}. These span cases and go to
 * a Master, through their own ledger. What they share is the template map, so each kind still sent carries its key
 * under {@code notify.templates}. Never rename a member; the names are the stored form.
 */
public enum MasterEmailKind {

    /**
     * The window digest, retired by plan F1. Kept only because ledgers already hold its rows, which the entity must
     * still map: nothing writes it, it has no template key, and the drain settles a leftover {@code Pending} one
     * {@code Skipped}, never sending it.
     */
    DIGEST(null),

    /** Every referral still waiting for the Master's direction, sent when one of them falls due a reminder. */
    REMINDER("master-referral-reminder"),

    /** The referrals the office has just sent to the Master, one email per send, or a reassignment to them. */
    SENT("master-referral-sent");

    private final String templateKey;

    MasterEmailKind(String templateKey) {
        this.templateKey = templateKey;
    }

    /** Whether the kind is no longer sent at all, whatever is configured. */
    public boolean retired() {
        return templateKey == null;
    }

    /**
     * The key under {@code notify.templates}, and so the {@code NOTIFY_TEMPLATE_*} variable's name.
     *
     * @throws IllegalStateException for a {@link #retired} kind, which has none
     */
    public String templateKey() {
        if (templateKey == null) {
            throw new IllegalStateException(name() + " is retired and has no template");
        }
        return templateKey;
    }
}
