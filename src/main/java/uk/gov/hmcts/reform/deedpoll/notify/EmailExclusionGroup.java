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
 * B excludes C, A and C are fine" has no spelling here. Nothing in the proposed template set needs it -- the
 * two referral-outcome emails are one group and the two forms-for-completion emails would be another -- and a
 * template that half-overlapped two others would need the pair table after all.
 *
 * <p><strong>A template alone in its group behaves exactly as it did before the re-keying</strong>, because a
 * group of one makes {@code ("referralId", "exclusionGroup")} and {@code ("referralId", "template")} the same
 * key. This is a generalisation of the existing rule rather than a second mechanism beside it.
 */
public enum EmailExclusionGroup {

    /**
     * What the judge decided about a referral, told to the applicant once.
     *
     * <p>Holds both referral-driven templates. The name is stored on every outbox row and is the migration's
     * column default, so renaming this member is a data migration -- exactly as renaming an
     * {@link EmailTemplate} member is.
     */
    REFERRAL_OUTCOME,

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
     */
    DOCUMENT_CHASE
}
