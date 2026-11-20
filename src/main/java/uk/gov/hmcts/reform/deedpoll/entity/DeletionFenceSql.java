package uk.gov.hmcts.reform.deedpoll.entity;

/**
 * The deletion fence as SQL (plan 20261118-dm-application-deletion, Task 4): a case named by a Delivery Manager's
 * deletion request (V48) is not there for anything but the deletion itself.
 *
 * <p>{@link DeedPoll} and {@link Referral} carry these as Hibernate {@code @SQLRestriction}s, so every JPA read of
 * either -- by id, under {@code FOR UPDATE}, through an entity graph, a specification, a count or a join -- answers as
 * if the case were already gone, which is a 404 in the same words an unknown id gets. Native statements do not see a
 * restriction, so each one that reads cases or referrals for display, for work or for a figure appends
 * {@link #UNFENCED_CASE} or {@link #UNFENCED_BY_DEED_POLL_ID}, or reads from {@link #LIVE_CASES} itself.
 *
 * <p><strong>A restriction is a read in the statement's own snapshot.</strong> A writer that waited on admission's row
 * lock inside the statement still sees no request when it gets the row, so a write path takes the case's lock first
 * and asks {@code DeletionFence} afterwards, and V49's deferred triggers refuse whatever slips past at commit.
 */
public final class DeletionFenceSql {

    /**
     * The entity restriction on {@link DeedPoll}: Hibernate qualifies the bare {@code id} with the row's alias.
     */
    public static final String CASE_RESTRICTION =
        "NOT EXISTS (SELECT 1 FROM deedpoll_deletion_request fence WHERE fence.case_id = id)";

    /**
     * The entity restriction on {@link Referral}, by its case, and the same predicate for a native statement over any
     * table with a {@code "deedPollId"} column that does not alias it: the request table has no such column, so the
     * name resolves to the outer row.
     */
    public static final String UNFENCED_BY_DEED_POLL_ID =
        "NOT EXISTS (SELECT 1 FROM deedpoll_deletion_request fence WHERE fence.case_id = \"deedPollId\")";

    /**
     * The predicate for a native statement that names the case table {@code d}.
     */
    public static final String UNFENCED_CASE =
        "NOT EXISTS (SELECT 1 FROM deedpoll_deletion_request fence WHERE fence.case_id = d.id)";

    /**
     * The cases no deletion request names, as a derived table to read in place of {@code deedpoll_deedpoll}.
     */
    public static final String LIVE_CASES = "(SELECT * FROM deedpoll_deedpoll live WHERE NOT EXISTS "
        + "(SELECT 1 FROM deedpoll_deletion_request fence WHERE fence.case_id = live.id))";

    private DeletionFenceSql() {
    }
}
