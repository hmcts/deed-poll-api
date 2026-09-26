package uk.gov.hmcts.reform.deedpoll.notify;

/**
 * A set of emails one referral may have at most one of, enforced by the outbox's unique index.
 *
 * <p><strong>Why a group at all.</strong> {@code APPLICATION_REJECTED} and
 * {@code REQUEST_CONFIRMATION_TO_PROCEED} are both sent off a {@code Rejected} referral and they say opposite
 * things: one tells the applicant enrolment is refused, the other asks whether they still want the deed
 * enrolled. A key on {@code ("referralId", "template")} stops a second copy of either but happily permits one
 * of each, which is the contradiction. So the key is widened to {@code ("referralId", "exclusionGroup")} and
 * the two share {@link #REFERRAL_OUTCOME}: Postgres decides which one a referral gets, atomically, rather than
 * a service check with a race in it.
 *
 * <p><strong>Why the grouping is declared on {@link EmailTemplate} rather than in a table of incompatible
 * pairs.</strong> A table is data, and data cannot force a decision: somebody adds a fifth template, forgets
 * its rows, and it silently excludes nothing. A group as a required constructor argument on the enum member has
 * the property this package already relies on in {@code CaseEmailService.cause()} -- a new template does not
 * compile until somebody says what it is. The database still does the enforcing; the enum only decides the
 * grouping.
 *
 * <p><strong>A group is a strict partition, which is the one thing it cannot express.</strong> "A excludes B,
 * B excludes C, A and C are fine" has no spelling here. Nothing in the template set needs it -- the two
 * referral-outcome emails are one group and the two forms-for-completion emails are another -- and a template
 * that half-overlapped two others would need the pair table after all.
 *
 * <p><strong>A template alone in its group behaves exactly as it did before the re-keying</strong>, because a
 * group of one makes {@code ("referralId", "exclusionGroup")} and {@code ("referralId", "template")} the same
 * key. This is a generalisation of the existing rule rather than a second mechanism beside it.
 *
 * <p><strong>{@link #exclusivePerCase()} is the second axis, and it is a required constructor argument for the
 * same reason the group itself is one.</strong> V15's index is partial on {@code "referralId" IS NOT NULL}, so a
 * case-driven email sits outside it entirely -- which is right for the chase, where a second reminder is
 * legitimate, and wrong for the forms emails, where the two send one applicant two different legal instruments to
 * swear. So V20 adds the case-scoped key and names the groups it applies to; this flag is what the service reads
 * to know whether a zero-row insert has a row to find, and {@code EmailExclusionGroupTest} holds the two to each
 * other so a group added here without the migration fails a test rather than turning an ordinary conflict into a
 * 500.
 */
public enum EmailExclusionGroup {

    /**
     * What the judge decided about a referral, told to the applicant once.
     *
     * <p>Holds both referral-driven templates. The name is stored on every outbox row and is the migration's
     * column default, so renaming this member is a data migration -- exactly as renaming an
     * {@link EmailTemplate} member is.
     */
    REFERRAL_OUTCOME(false),

    /**
     * Asking the applicant for something the office is still waiting for.
     *
     * <p><strong>A group of one, and one the index cannot act on -- which is the honest answer rather than a
     * gap.</strong> The chase is case-driven, so its rows carry no {@code referralId}, and the unique index is
     * partial on {@code "referralId" IS NOT NULL}: these rows never reach it. Two chases on one case are
     * <em>legitimate</em> -- an applicant who has not answered the first one has to be chased again -- so there
     * is nothing here for a database key to refuse, and widening the key to the case would forbid the reminder
     * this email exists to be.
     *
     * <p>Declared all the same, because the column is {@code NOT NULL} and the constructor argument is
     * required. Its own member rather than {@link #REFERRAL_OUTCOME}: a chase is not an alternative to telling
     * an applicant what a Master decided, and sharing that group would make the two mutually exclusive on any
     * future row that did carry a referral.
     *
     * <p><strong>Not exclusive per case either, and V20 had to be told so.</strong> A case-scoped key over every
     * group with no referral would have made the second chase impossible -- the reminder this email exists to be
     * -- so V20's index names the groups it applies to and this is not one of them.
     */
    DOCUMENT_CHASE(false),

    /**
     * The forms an applicant has to print, sign and swear: one deed per case, and so one of these emails.
     *
     * <p><strong>Holds both forms-for-completion templates, and is the first group whose exclusion is per
     * case.</strong> ① carries LOC020 and ② carries LOC022, and an applicant gets one change of name deed -- so
     * two of these would send one person two different legal instruments to sign in front of witnesses, of which
     * at most one is the deed the court will seal. Neither is referral-driven: no judge has decided anything and
     * the case's own state is what entitles it, so the rows carry no {@code referralId} and V15's index never sees
     * them. That is what V20 is for.
     *
     * <p>The name is stored on every outbox row and is what V20's partial index names in SQL, so renaming this
     * member is a migration as well as a data migration.
     */
    FORMS_FOR_COMPLETION(true);

    private final boolean exclusivePerCase;

    EmailExclusionGroup(boolean exclusivePerCase) {
        this.exclusivePerCase = exclusivePerCase;
    }

    /**
     * Whether a case gets at most one email of this group, irrespective of any referral.
     *
     * <p><strong>What it decides is whether a refused insert has a row to find.</strong>
     * {@code NotificationRepository.enqueue} names no conflict target, so a zero-row answer has to be explained
     * by reading -- and for a case-driven email there is only something to read where this is true. Asking with
     * this false would be a query whose answer is always empty and one that read as though the database had an
     * opinion about a repeat, which for the chase it deliberately does not.
     *
     * <p><strong>It does not make anything exclusive on its own.</strong> V20's partial index does the enforcing,
     * and it names the groups in SQL; this is the service's copy of the same fact, tested against the migration
     * text rather than trusted.
     */
    public boolean exclusivePerCase() {
        return exclusivePerCase;
    }
}
