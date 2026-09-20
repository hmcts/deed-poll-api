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
 * <p><strong>Two splits matter to the guards, and they are deliberately not the same split.</strong>
 * {@linkplain #isTerminal() Terminal or not} answers how many referrals a case may hold: many over its
 * lifetime, at most one non-terminal at a time. {@linkplain #canBeWithdrawn() Withdrawable or not}
 * answers whether the office may still pull one back, and it is the narrower of the two -- {@link #Decided}
 * is non-terminal and nonetheless not withdrawable, because a judge has recorded a direction on it and the
 * office's remaining move is to action it and close it.
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
     * <p>Asked rather than listed at each call site, because the rules that depend on it -- one live
     * referral per case, and the denormalised {@code currentReferralState} column that says which one --
     * have to agree about it, and a later state added to the enum but not to one of two hand-written sets
     * would break exactly one of them.
     *
     * <p><strong>Withdrawal is no longer one of those rules, and narrowing this to make it one again would
     * move both of them.</strong> See {@link #canBeWithdrawn()}.
     *
     * @return true for {@link #Closed} and {@link #Withdrawn}
     */
    public boolean isTerminal() {
        return this == Closed || this == Withdrawn;
    }

    /**
     * Whether the office may still pull a referral in this state back.
     *
     * <p>Narrower than {@linkplain #isTerminal() non-terminal}, and a second independent predicate rather
     * than a narrowing of that one, because {@link #Decided} is deliberately both: non-terminal, so the case
     * still holds it and cannot raise a second beside it, and not withdrawable, because a direction exists
     * and withdrawing it would discard judicial work the office is obliged to action. {@code Decided ->
     * Closed} is the transition that records the office having actioned it.
     *
     * <p>Narrowing {@code isTerminal()} instead would be the plausible-looking mistake and would move two
     * unrelated rules at once: a case could hold a second referral beside a decided one, and the
     * {@code currentReferralState} column would go NULL while a direction was still waiting to be actioned,
     * dropping the case out of the "returned -- action needed" queue.
     *
     * <p>Asked rather than listed at each call site for the reason {@link #isTerminal()} is, and named after
     * the business rule rather than after the two states, so a sixth state is a decision taken here rather
     * than a case quietly missing from a controller.
     *
     * @return true for {@link #Open} and {@link #Claimed}
     */
    public boolean canBeWithdrawn() {
        return this == Open || this == Claimed;
    }
}
