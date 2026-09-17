package uk.gov.hmcts.reform.deedpoll.entity.enums;

/**
 * Where a referral to the judiciary has got to.
 *
 * <p><strong>Not a port.</strong> Every other enum in this package maps one in
 * {@code reference/deed-poll.prisma}, so its member order is load-bearing -- each position is a
 * Dynamics 365 option-set integer. This one has no reference enum, because the source store had no
 * referral entity to give it states, so the order here is the lifecycle's and carries no meaning
 * beyond legibility. {@code EntityMappingTest}'s enum factory walks the reference's enums, so it does
 * not reach this one; {@code ddl-auto: validate} and the round-trip test are what hold it to the
 * {@code "ReferralState"} Postgres type from {@code V6__referrals.sql}.
 *
 * <p>The member NAMES are still the values that type stores, so they are spelled as the type spells
 * them rather than in Java's usual upper case -- renaming one stops it round tripping.
 *
 * <p>All five members exist from the first migration even though only {@link #Open} and
 * {@link #Withdrawn} are reached today: {@link #Claimed}, {@link #Decided} and {@link #Closed} arrive
 * with the directions plans, which then add transitions rather than an {@code ALTER TYPE}.
 *
 * <p>The split that matters to the guards is {@linkplain #isTerminal() terminal or not}. A case may
 * hold many referrals but at most one non-terminal, and a referral may only be withdrawn while it is
 * non-terminal.
 */
public enum ReferralState {

    /** Raised and unclaimed -- in the shared judicial pool. */
    Open,

    /** Picked up by a judge, who has not yet recorded a direction. */
    Claimed,

    /** A direction has been recorded, and the caseworker has not yet actioned it. */
    Decided,

    /** The direction was actioned. Terminal. */
    Closed,

    /**
     * The caseworker pulled the referral back. Terminal, and deliberately not {@link #Closed}.
     */
    Withdrawn;

    /**
     * Whether this state is an end state, from which no transition leaves.
     *
     * <p>Asked rather than listed at each call site, because the two rules that depend on it -- one
     * open referral per case, and withdraw-only-while-live -- have to agree about it, and a later
     * state added to the enum but not to one of two hand-written sets would break exactly one of
     * them.
     *
     * @return true for {@link #Closed} and {@link #Withdrawn}
     */
    public boolean isTerminal() {
        return this == Closed || this == Withdrawn;
    }
}
