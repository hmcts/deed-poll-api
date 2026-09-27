package uk.gov.hmcts.reform.deedpoll.ccd;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uk.gov.hmcts.reform.deedpoll.entity.AuditClock;
import uk.gov.hmcts.reform.deedpoll.entity.CcdCreationAttempt;
import uk.gov.hmcts.reform.deedpoll.entity.DeedPoll;
import uk.gov.hmcts.reform.deedpoll.entity.enums.CcdCreationAttemptStatus;
import uk.gov.hmcts.reform.deedpoll.repository.CcdCreationAttemptRepository;
import uk.gov.hmcts.reform.deedpoll.repository.DeedPollRepository;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The durable ledger of CCD creation attempts: open one, claim it, move it on.
 *
 * <p>The one retry engine the linking operation (plan 02, task 7) and the bulk backfill (ccd-migration) share.
 * Each method is its own transaction, because decision 9 commits the attempt locally before CCD is called: a
 * caller must reach here outside any transaction it means to hold across the network, or its claim would not be
 * visible to the persistence callback that reads it on another request.
 *
 * <p>Which moves are legal is {@link CcdCreationAttemptStatus#canMoveTo}'s answer, asked before any statement
 * is issued; which of two racing workers wins is the conditional statement's answer.
 */
@Service
public class CcdCreationAttempts {

    private final CcdCreationAttemptRepository attempts;
    private final DeedPollRepository deedPolls;

    public CcdCreationAttempts(CcdCreationAttemptRepository attempts, DeedPollRepository deedPolls) {
        this.attempts = attempts;
        this.deedPolls = deedPolls;
    }

    /**
     * Opens a {@code Pending} attempt for a record, unless it already has a live one.
     *
     * @return the attempt this call opened, or empty when another is live -- read it with {@link #live}
     */
    @Transactional
    public Optional<CcdCreationAttempt> open(String deedPollId) {
        UUID id = UUID.randomUUID();
        if (attempts.open(id, deedPollId, UUID.randomUUID(), AuditClock.now()) == 0) {
            return Optional.empty();
        }
        return attempts.findById(id);
    }

    /** The record's one live attempt, if it has one. */
    @Transactional(readOnly = true)
    public Optional<CcdCreationAttempt> live(String deedPollId) {
        return attempts.findLive(deedPollId);
    }

    /** The claims whose lease has run out, which reconciliation takes off their workers. */
    @Transactional(readOnly = true)
    public List<CcdCreationAttempt> expiredClaims() {
        return attempts.findExpiredClaims(AuditClock.now());
    }

    /** The attempts waiting for reconciliation to read them. */
    @Transactional(readOnly = true)
    public List<CcdCreationAttempt> awaitingConfirmation() {
        return attempts.findAwaitingConfirmation();
    }

    /**
     * Takes a {@code Pending} attempt for one worker for the length of a lease.
     *
     * @param worker who holds it, for attribution and diagnosis -- a worker name, never a user's
     * @return true if this worker now holds it; false if another took it, or it was no longer {@code Pending}
     */
    @Transactional
    public boolean claim(UUID attemptId, String worker, Duration lease) {
        if (lease.isNegative() || lease.isZero()) {
            throw new IllegalArgumentException("A claim's lease must be positive");
        }
        LocalDateTime now = AuditClock.now();
        return attempts.claim(attemptId, worker, now, now.plus(lease)) == 1;
    }

    /**
     * Moves an attempt on, from the status the caller last saw it in.
     *
     * @param failureDetail what went wrong, or null to clear it; truncated to the column's bound, and never to be
     *     given a token, a credential or a response body
     * @return true if this call moved it; false if it was no longer in {@code from}
     * @throws IllegalStateException if the rule does not allow {@code from -> to}, or {@code to} is
     *     {@code Claimed}, which only {@link #claim} may do
     */
    @Transactional
    public boolean move(UUID attemptId,
                        CcdCreationAttemptStatus from,
                        CcdCreationAttemptStatus to,
                        String failureDetail) {
        if (to == CcdCreationAttemptStatus.Claimed) {
            throw new IllegalStateException("An attempt is claimed through claim(), which sets its lease");
        }
        if (!from.canMoveTo(to)) {
            throw new IllegalStateException("A CCD creation attempt cannot move from " + from + " to " + to);
        }
        return attempts.move(attemptId, from.name(), to.name(), bounded(failureDetail), AuditClock.now()) == 1;
    }

    /**
     * Takes a claim whose lease has run out off its worker, to {@code AwaitingConfirmation}, since nobody knows how
     * far it got.
     *
     * @return true if this call took it; false if it was no longer a claim, or its lease had been renewed by a
     *     fresh claim
     */
    @Transactional
    public boolean takeExpiredClaim(UUID attemptId, String failureDetail) {
        return attempts.takeExpiredClaim(attemptId, bounded(failureDetail), AuditClock.now()) == 1;
    }

    /**
     * Moves an attempt {@code AwaitingConfirmation} to {@code NeedsRepair} because nothing was bound -- unless,
     * under the record's lock, something now is.
     *
     * <p><strong>The lock is the point.</strong> A submission that arrives late can be binding the record at this
     * moment: {@link LinkDeedPollHandler} holds the record's row lock from its read of the attempt until its bind
     * commits. Taking the same lock here orders the two. Either the bind committed first, and this reads it and
     * moves nothing, or this committed first, and the handler reads {@code NeedsRepair} and refuses the bind.
     * Without it, a record could hold a real case while its attempt says none was bound.
     *
     * @return true if this call moved it; false if the record is now bound or the attempt had moved on
     */
    @Transactional
    public boolean repairIfUnbound(CcdCreationAttempt waiting, String failureDetail) {
        boolean bound = deedPolls.findByIdForUpdate(waiting.getDeedPollId())
            .map(DeedPoll::getCcdCaseReference)
            .isPresent();
        return !bound && move(waiting.getId(), CcdCreationAttemptStatus.AwaitingConfirmation,
            CcdCreationAttemptStatus.NeedsRepair, failureDetail);
    }

    /** A failure detail cut to what the column holds, so a verbose upstream error cannot fail the write. */
    static String bounded(String failureDetail) {
        if (failureDetail == null || failureDetail.length() <= CcdCreationAttempt.MAX_FAILURE_DETAIL) {
            return failureDetail;
        }
        int end = CcdCreationAttempt.MAX_FAILURE_DETAIL;
        // Never half a surrogate pair: the driver cannot encode one, and would write a replacement in its place.
        if (Character.isHighSurrogate(failureDetail.charAt(end - 1))) {
            end--;
        }
        return failureDetail.substring(0, end);
    }
}
