package uk.gov.hmcts.reform.deedpoll.entity.enums;

/**
 * Where one owed email has got to.
 *
 * <p><strong>Not a port.</strong> Like {@link ReferralState}, this has no enum in
 * {@code reference/deed-poll.prisma} -- the TypeScript store sent nothing -- so its member order
 * carries no Dynamics option-set integer and is legibility only. {@code EntityMappingTest}'s enum
 * factory walks the reference's enums, so it does not reach this one; {@code ddl-auto: validate} and
 * the round-trip test are what hold it to the {@code "NotificationState"} Postgres type from
 * {@code V11__notifications.sql}.
 *
 * <p>The member NAMES are the values that type stores, so they are spelled as the type spells them
 * rather than in Java's usual upper case -- renaming one stops it round tripping.
 *
 * <p><strong>There is deliberately no fourth member for "being sent".</strong> A drain leases a row
 * by pushing {@code nextAttemptAt} into the future in the same conditional {@code UPDATE} that
 * re-asserts the row is {@link #Pending} and due, so holding a row is a fact about the lease rather
 * than a state a crashed replica can leave a row stuck in.
 */
public enum NotificationState {

    /**
     * Owed and not yet accepted by Notify. Claimable once {@code nextAttemptAt} has passed, which is
     * immediately for a newly enqueued row and after a backoff for one whose last attempt failed
     * temporarily.
     */
    Pending,

    /**
     * Notify accepted it. Terminal.
     *
     * <p><strong>Accepted, not delivered.</strong> This plan has no delivery callback and no polling,
     * so a bounced address is a row that still says {@code Sent};
     * {@code providerNotificationId} is stored so asking Notify later is additive.
     */
    Sent,

    /**
     * It will not be sent. Terminal, and reached three ways: the taxonomy said the request was
     * permanently wrong, it said nobody predicted the answer, or a retryable failure ran out of
     * attempts. {@code failureReason} is which.
     */
    Failed;

    /**
     * Whether this state is an end state, from which no drain moves the row.
     *
     * <p>Asked rather than listed at each call site, for the reason {@link ReferralState#isTerminal()}
     * gives: the claimable predicate and the outbox's own settling have to agree about it.
     *
     * @return true for {@link #Sent} and {@link #Failed}
     */
    public boolean isTerminal() {
        return this == Sent || this == Failed;
    }
}
