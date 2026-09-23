package uk.gov.hmcts.reform.deedpoll.entity.enums;

/**
 * What an applicant answered when asked whether they still want the deed enrolled.
 *
 * <p>The question is the {@code REQUEST_CONFIRMATION_TO_PROCEED} email: the judge has refused the
 * applicant's non-publication request, so the applicant is asked whether they want to go ahead knowing the
 * deed will be published. This is the answer, recorded by the caseworker who read their reply.
 *
 * <p><strong>Not a port.</strong> Like {@link NotificationState} and {@link ReferralState}, this has no
 * enum in {@code reference/deed-poll.prisma} -- the TypeScript store neither sent the email nor recorded a
 * reply -- so its member order carries no Dynamics option-set integer and is legibility only. That is also
 * why minting it is legitimate where a ninth {@link DeedPollState} member would not be: a
 * {@code DeedPollState}'s position IS an option-set integer the Dataverse exports hold.
 * {@code EntityMappingTest}'s enum factory walks the reference's enums, so it does not reach this one;
 * {@code ddl-auto: validate} and the round-trip test are what hold it to the {@code "ConfirmationOutcome"}
 * Postgres type from {@code V16__referral_confirmation.sql}.
 *
 * <p>The member NAMES are the values that type stores, so they are spelled as the type spells them rather
 * than in Java's usual upper case -- renaming one stops it round tripping.
 *
 * <p><strong>There is deliberately no member for "no reply yet".</strong> Silence is the ABSENCE of an
 * outcome -- a null column -- which is what the partial index in that migration indexes and what the
 * waiting list is. A member for it would make "still waiting" answerable two ways that could disagree.
 *
 * <p><strong>Neither member moves the case on by itself.</strong> Recording one does not set
 * {@code dateEnroled}, clear {@code dateReturned} or otherwise reach into the date cascade; the caseworker
 * then does whatever the process requires through the ordinary case edit, audited as their action rather
 * than as a side effect of this one.
 */
public enum ConfirmationOutcome {

    /**
     * The applicant still wants the deed enrolled, knowing it will be published in the London Gazette.
     */
    Confirmed,

    /**
     * The applicant does not want to proceed and wants a refund.
     *
     * <p>There is no case state that says so: {@code DeedPollState} has no {@code Withdrawn} member and
     * cannot grow one, so a withdrawn case looks like any other {@code Returned} case and this column is
     * the only thing distinguishing them. Reporting on withdrawals therefore reads a referral column
     * rather than a case state.
     */
    Withdrawn
}
