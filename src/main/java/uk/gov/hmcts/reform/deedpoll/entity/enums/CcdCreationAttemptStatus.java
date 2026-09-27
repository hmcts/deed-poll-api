package uk.gov.hmcts.reform.deedpoll.entity.enums;

import java.util.EnumSet;
import java.util.Set;

/**
 * Where an attempt to create and link one CCD case for one deed-poll record has got to.
 *
 * <p><strong>Not a port</strong>, like {@link ReferralState}: the member names are the values the
 * {@code "CcdCreationAttemptStatus"} Postgres type from {@code V22__ccd_creation_attempt.sql} stores, so they are
 * spelled as that type spells them, and the order is the lifecycle's.
 *
 * <p><strong>The whole transition rule is here, with no database in it</strong>, so that Docker-free CI runs it.
 * The conditional {@code UPDATE}s in {@code CcdCreationAttemptRepository} settle races between workers; they do not
 * decide which moves are legal, and the service refuses an illegal one before issuing any statement.
 *
 * <p>The rule's one principle is plan 02's: recovery is by reading, not retrying. Once {@code submitCaseCreation}
 * may have reached CCD, nothing may go back to {@link #Pending}, because a fresh start-event token mints a fresh
 * idempotency key and can mint a second case. From there an attempt ends {@link #Linked} or waits, visibly, as
 * {@link #NeedsRepair}.
 */
public enum CcdCreationAttemptStatus {

    /** Opened and committed locally; no worker holds it and nothing has been sent to CCD. */
    Pending,

    /** A worker holds it under a lease and is talking to CCD. */
    Claimed,

    /**
     * Submitted, or possibly submitted, with no confirmed answer -- a timeout, a lost response, a 5xx, a 404 after
     * submit, or a lease that expired mid-conversation. Reconciliation reads the local binding and CCD before
     * doing anything else.
     */
    AwaitingConfirmation,

    /** The reference, case type and record id are confirmed to agree on both sides. Terminal. */
    Linked,

    /**
     * Reconciliation could not settle it. Still live, deliberately: it may already have created a CCD case, so a
     * second attempt beside it is how a record gets two. A person resolves it by sending it back to
     * reconciliation, never by opening another.
     */
    NeedsRepair;

    /**
     * Whether an attempt in this state still occupies its record's one live slot.
     *
     * <p>{@code V22}'s partial unique index states the same predicate -- {@code "status" <> 'Linked'} -- and
     * {@code CcdCreationAttemptStatusTest} holds the two together.
     *
     * @return false for {@link #Linked} only
     */
    public boolean isLive() {
        return this != Linked;
    }

    /**
     * The states an attempt in this state may move to.
     *
     * <p>{@code Claimed -> Pending} is the one step backwards, and it is legal only because the caller asserts
     * that nothing was submitted: a claim that failed before {@code submitCaseCreation} -- no IDAM token, no S2S
     * token, a refused {@code startCase} -- has created nothing and may be tried again. A claim that expired is not
     * that caller, since nobody knows how far it got, so it goes to {@link #AwaitingConfirmation}.
     *
     * @return the legal targets, empty for {@link #Linked}
     */
    public Set<CcdCreationAttemptStatus> successors() {
        return switch (this) {
            case Pending -> EnumSet.of(Claimed);
            case Claimed -> EnumSet.of(Pending, AwaitingConfirmation, Linked, NeedsRepair);
            case AwaitingConfirmation -> EnumSet.of(Linked, NeedsRepair);
            case NeedsRepair -> EnumSet.of(AwaitingConfirmation);
            case Linked -> EnumSet.noneOf(CcdCreationAttemptStatus.class);
        };
    }

    /**
     * Whether an attempt in this state may move to {@code target}.
     */
    public boolean canMoveTo(CcdCreationAttemptStatus target) {
        return successors().contains(target);
    }
}
