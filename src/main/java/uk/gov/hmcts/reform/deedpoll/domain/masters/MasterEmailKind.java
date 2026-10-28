package uk.gov.hmcts.reform.deedpoll.domain.masters;

/**
 * The two emails a Master receives (plan D3), as {@code deedpoll_master_email.kind} stores them.
 *
 * <p>Not {@code EmailTemplate} members: those are a case's correspondence, sent to its applicant through the case
 * outbox, and every one of them meets a case-scoped switch in {@code CaseEmailService}. These span cases and go to
 * a Master, through their own ledger. What they share is the template map, so each kind carries its key under
 * {@code notify.templates}. Never rename a member; the names are the stored form.
 */
public enum MasterEmailKind {

    /** The referrals counted towards a referral window, sent once the window closes. */
    DIGEST("master-referral-digest"),

    /** Every referral still waiting for the Master's direction, sent when one of them falls due a reminder. */
    REMINDER("master-referral-reminder");

    private final String templateKey;

    MasterEmailKind(String templateKey) {
        this.templateKey = templateKey;
    }

    /**
     * The key under {@code notify.templates}, and so the {@code NOTIFY_TEMPLATE_*} variable's name.
     */
    public String templateKey() {
        return templateKey;
    }
}
