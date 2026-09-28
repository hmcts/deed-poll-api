package uk.gov.hmcts.reform.deedpoll.ccd;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uk.gov.hmcts.reform.deedpoll.entity.AuditClock;
import uk.gov.hmcts.reform.deedpoll.entity.CcdCreationAttempt;
import uk.gov.hmcts.reform.deedpoll.entity.DeedPoll;
import uk.gov.hmcts.reform.deedpoll.entity.enums.CcdCreationAttemptStatus;
import uk.gov.hmcts.reform.deedpoll.repository.CcdAttemptRow;
import uk.gov.hmcts.reform.deedpoll.repository.CcdCreationAttemptRepository;
import uk.gov.hmcts.reform.deedpoll.repository.DeedPollRepository;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
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
     * The next records to link, at most {@code limit}: unlinked, with no live attempt or a due {@code Pending} one,
     * oldest first and always from the head (plan 02b, decision 5).
     */
    @Transactional(readOnly = true)
    public List<String> dueForLinking(int limit) {
        return deedPolls.findIdsDueForCcdLinking(AuditClock.now(), limit);
    }

    /**
     * At most {@code limit} attempts nobody is attending to: the expired claims first, then the attempts awaiting
     * confirmation, each longest-waiting first -- the order {@link CcdLinkingService#reconcileOutstanding} reads
     * the whole of both in.
     */
    @Transactional(readOnly = true)
    public List<CcdCreationAttempt> outstanding(int limit) {
        List<CcdCreationAttempt> outstanding =
            new ArrayList<>(attempts.findFirstExpiredClaims(AuditClock.now(), limit));
        if (outstanding.size() < limit) {
            outstanding.addAll(attempts.findFirstAwaitingConfirmation(limit - outstanding.size()));
        }
        return outstanding;
    }

    /**
     * Takes a {@code Pending} attempt for one worker for the length of a lease, if it is due.
     *
     * @param worker who holds it, for attribution and diagnosis -- a worker name, never a user's
     * @return true if this worker now holds it; false if another took it, it was no longer {@code Pending}, or its
     *     {@code notBefore} has not yet passed
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
     * Commits, before {@code submitCaseCreation} is called, that this worker's submission may reach CCD -- if the
     * claim is still this worker's (plan 02b, decision 9).
     *
     * <p>The one write of {@code submissionMayHaveReached}, and it only ever writes true. A caller that gets false
     * has lost its claim, and must submit nothing: whoever holds the attempt now decides what happens to it.
     *
     * @param attempts the try this worker's claim made it, which identifies the claim
     * @return true if the flag is committed and the worker may submit
     */
    @Transactional
    public boolean recordSubmission(UUID attemptId, String worker, int attempts) {
        return this.attempts.recordSubmission(attemptId, worker, attempts, AuditClock.now()) == 1;
    }

    /**
     * Gives a claim that created nothing back as {@code Pending}, due again at {@code notBefore} (plan 02b, decision
     * 6). The only way an attempt goes from {@code Claimed} back to {@code Pending}.
     *
     * @param failureDetail what went wrong; bounded as {@link #move} bounds it
     * @return true if this call released it; false if it was no longer {@code Claimed}
     */
    @Transactional
    public boolean release(UUID attemptId, String failureDetail, LocalDateTime notBefore) {
        if (notBefore == null) {
            throw new IllegalArgumentException("A released attempt must say when it is next due");
        }
        return attempts.release(attemptId, bounded(failureDetail), notBefore, AuditClock.now()) == 1;
    }

    /**
     * Moves an attempt on, from the status the caller last saw it in.
     *
     * @param failureDetail what went wrong, or null to clear it; truncated to the column's bound, and never to be
     *     given a token, a credential or a response body
     * @return true if this call moved it; false if it was no longer in {@code from}
     * @throws IllegalStateException if the rule does not allow {@code from -> to}, if {@code to} is
     *     {@code Claimed}, which only {@link #claim} may do, if the move is {@code Claimed -> Pending}, which only
     *     {@link #release} may do because it sets when the attempt is next due, or if {@code from} is
     *     {@code NeedsRepair}, which only {@link #reconcileAgain} and {@link #retryFromScratch} may leave because
     *     each records who repaired it -- and the retry's condition, that nothing may have been submitted, is one a
     *     status-only statement cannot check
     */
    @Transactional
    public boolean move(UUID attemptId,
                        CcdCreationAttemptStatus from,
                        CcdCreationAttemptStatus to,
                        String failureDetail) {
        if (to == CcdCreationAttemptStatus.Claimed) {
            throw new IllegalStateException("An attempt is claimed through claim(), which sets its lease");
        }
        if (from == CcdCreationAttemptStatus.Claimed && to == CcdCreationAttemptStatus.Pending) {
            throw new IllegalStateException("A claim is released through release(), which sets when it is next due");
        }
        if (from == CcdCreationAttemptStatus.NeedsRepair) {
            // Every way out of NeedsRepair is an admin's, and each is attributed (plan 02b, decision 10). The retry is
            // also conditional on nothing having been submitted, which this statement, keyed on status alone, is not.
            throw new IllegalStateException(
                "An attempt leaves NeedsRepair through reconcileAgain() or retryFromScratch(), which record who "
                    + "repaired it, and the retry is also conditional on nothing having been submitted");
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

    /**
     * How many attempts are in each status, every status present and in lifecycle order -- zero for one no attempt
     * is in, so the repair view's headline has the same shape however empty the ledger is.
     */
    @Transactional(readOnly = true)
    public Map<CcdCreationAttemptStatus, Long> countsByStatus() {
        Map<CcdCreationAttemptStatus, Long> counts = new EnumMap<>(CcdCreationAttemptStatus.class);
        for (CcdCreationAttemptStatus status : CcdCreationAttemptStatus.values()) {
            counts.put(status, 0L);
        }
        for (Object[] row : attempts.countByStatus()) {
            counts.put((CcdCreationAttemptStatus) row[0], ((Number) row[1]).longValue());
        }
        return counts;
    }

    /**
     * One page of the attempts that are not {@code Linked}, or of those in one status, longest-unmoved first.
     *
     * @param status one status to narrow to, or empty for every status but {@code Linked}
     */
    @Transactional(readOnly = true)
    public Page<CcdAttemptRow> unlinked(Optional<CcdCreationAttemptStatus> status, Pageable page) {
        return status.map(only -> attempts.findRowsByStatus(only, page))
            .orElseGet(() -> attempts.findRowsOtherThan(CcdCreationAttemptStatus.Linked, page));
    }

    /** One attempt as the repair view shows it, if it exists. */
    @Transactional(readOnly = true)
    public Optional<CcdAttemptRow> row(UUID attemptId) {
        return attempts.findRowById(attemptId);
    }

    /**
     * Sends an attempt {@code NeedsRepair} back to reconciliation, recording who did (plan 02b, decision 8). The one
     * way an attempt leaves {@code NeedsRepair} for {@code AwaitingConfirmation}, so no repair goes unattributed.
     *
     * @param repairedById the admin's IDAM subject; attribution only, never published or logged
     * @return true if this call moved it; false if it was no longer {@code NeedsRepair}
     */
    @Transactional
    public boolean reconcileAgain(UUID attemptId, String repairedById) {
        return attempts.reconcileAgain(attemptId, attributed(repairedById), AuditClock.now()) == 1;
    }

    /**
     * Sends an attempt {@code NeedsRepair} back to {@code Pending}, its tries reset and due now, recording who did --
     * only if nothing may have been submitted (plan 02b, decision 8). The one way an attempt leaves
     * {@code NeedsRepair} for {@code Pending}.
     *
     * @param repairedById the admin's IDAM subject; attribution only, never published or logged
     * @return true if this call moved it; false if it was no longer {@code NeedsRepair}, or may have been submitted
     */
    @Transactional
    public boolean retryFromScratch(UUID attemptId, String repairedById) {
        return attempts.retryFromScratch(attemptId, attributed(repairedById), AuditClock.now()) == 1;
    }

    /** A repair with nobody to attribute it to is a wiring fault: the caller is an admin, and admins have subjects. */
    private static String attributed(String repairedById) {
        if (repairedById == null || repairedById.isBlank()) {
            throw new IllegalArgumentException("A repair must say who made it");
        }
        return repairedById;
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
