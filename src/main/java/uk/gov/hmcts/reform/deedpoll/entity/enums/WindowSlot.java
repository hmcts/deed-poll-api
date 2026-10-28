package uk.gov.hmcts.reform.deedpoll.entity.enums;

/**
 * Which of a working day's two referral windows an assignment counts towards, from
 * {@code V43__referral_assignment.sql}. Not a port, and not a Postgres enum: the column is {@code varchar(2)} with
 * a check, so the names are the stored form and must not be renamed.
 */
public enum WindowSlot {
    /** The morning window. */
    AM,
    /** The afternoon window. */
    PM
}
