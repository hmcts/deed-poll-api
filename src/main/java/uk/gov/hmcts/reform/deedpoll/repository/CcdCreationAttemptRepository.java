package uk.gov.hmcts.reform.deedpoll.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import uk.gov.hmcts.reform.deedpoll.entity.CcdCreationAttempt;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The CCD creation attempts, read by the record they are for and written only by conditional statements.
 *
 * <p><strong>Every write here is a statement whose {@code WHERE} re-asserts the state it expects</strong>, the
 * shape {@code NotificationRepository.claimNextDue} has and for the same argument: two workers may race on one
 * attempt, and a single {@code UPDATE} evaluating its own predicate is atomic -- the loser blocks on the row,
 * re-evaluates against the committed values and answers {@code 0}. Nothing here decides which moves are legal;
 * {@code CcdCreationAttemptStatus.canMoveTo} does, and {@code CcdCreationAttempts} asks it first.
 *
 * <p>Native SQL, with mixed-case identifiers quoted. A status is bound as text and cast to the Postgres enum,
 * because the driver sends a {@code String} as {@code varchar} and Postgres will not compare that with an enum.
 */
public interface CcdCreationAttemptRepository extends JpaRepository<CcdCreationAttempt, UUID> {

    /** Every attempt a record has had, oldest first -- its history, including the one that linked it. */
    List<CcdCreationAttempt> findByDeedPollIdOrderByCreatedOnAsc(String deedPollId);

    /**
     * The record's one live attempt, if it has one.
     *
     * <p>The predicate is V22's partial index's, repeated character for character: live is every status but
     * {@code Linked}. At most one row can match, because that index is what says so.
     */
    @Query(nativeQuery = true, value = """
        SELECT *
          FROM "deedpoll_ccdcreationattempt"
         WHERE "deedPollId" = :deedPollId
           AND "status" <> 'Linked'
        """)
    Optional<CcdCreationAttempt> findLive(@Param("deedPollId") String deedPollId);

    /**
     * The claims whose lease has run out, oldest first: the attempts reconciliation takes off their workers, because
     * nobody knows how far each got.
     */
    @Query(nativeQuery = true, value = """
        SELECT *
          FROM "deedpoll_ccdcreationattempt"
         WHERE "status" = 'Claimed'
           AND "leaseExpiresAt" < :now
         ORDER BY "leaseExpiresAt"
        """)
    List<CcdCreationAttempt> findExpiredClaims(@Param("now") LocalDateTime now);

    /** The attempts waiting for reconciliation to read them, longest-waiting first. */
    @Query(nativeQuery = true, value = """
        SELECT *
          FROM "deedpoll_ccdcreationattempt"
         WHERE "status" = 'AwaitingConfirmation'
         ORDER BY "modifiedOn"
        """)
    List<CcdCreationAttempt> findAwaitingConfirmation();

    /**
     * Opens a {@code Pending} attempt for a record, unless it already has a live one.
     *
     * <p><strong>{@code ON CONFLICT} names V22's partial index as its target, and that is deliberate.</strong> It
     * is the one refusal that has an ordinary answer -- another worker opened the record's attempt first, and the
     * caller reads it with {@link #findLive}. A clash on the primary key or the operation id is a fault, not an
     * outcome, and naming the target leaves those to raise rather than folding them into the same {@code 0}. The
     * target's predicate must be the index's exactly, or Postgres cannot infer the index and the statement fails
     * -- loudly, which is the preferable failure. {@code CcdCreationAttemptRepositoryTest} is where it executes.
     *
     * <p>{@code 'Pending'} and {@code 0} are literals because a newly opened attempt has never been claimed; a
     * caller able to pass either could open one already {@code Linked}.
     *
     * @return 1 if this statement opened the attempt, 0 if the record already had a live one
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(nativeQuery = true, value = """
        INSERT INTO "deedpoll_ccdcreationattempt" (
                    "id", "deedPollId", "operationId", "status", "attempts", "createdOn", "modifiedOn")
             VALUES (:id, :deedPollId, :operationId, 'Pending', 0, :now, :now)
        ON CONFLICT ("deedPollId") WHERE "status" <> 'Linked' DO NOTHING
        """)
    int open(@Param("id") UUID id,
             @Param("deedPollId") String deedPollId,
             @Param("operationId") UUID operationId,
             @Param("now") LocalDateTime now);

    /**
     * Takes a {@code Pending} attempt for one worker under a lease, if it is still {@code Pending} and is due.
     *
     * <p>Exactly one of any number of concurrent callers gets {@code 1}. {@code "attempts"} is incremented here,
     * before CCD is called, so an attempt that killed the process is still counted.
     *
     * <p>The due predicate is {@code CcdLinkingRule.isDue}'s, repeated here so that a caller whose read of
     * {@code "notBefore"} is stale cannot claim a released attempt early: the service asks the rule first, and this
     * statement refuses anyway.
     *
     * @return 1 if this worker now holds it, 0 if it was no longer there to take, or is not yet due
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(nativeQuery = true, value = """
        UPDATE "deedpoll_ccdcreationattempt"
           SET "status" = 'Claimed',
               "claimedBy" = :claimedBy,
               "leaseExpiresAt" = :leaseUntil,
               "attempts" = "attempts" + 1,
               "modifiedOn" = :now
         WHERE "id" = :id
           AND "status" = 'Pending'
           AND ("notBefore" IS NULL OR "notBefore" <= :now)
        """)
    int claim(@Param("id") UUID id,
              @Param("claimedBy") String claimedBy,
              @Param("now") LocalDateTime now,
              @Param("leaseUntil") LocalDateTime leaseUntil);

    /**
     * Records, before {@code submitCaseCreation} is called, that this worker's submission may reach CCD (V23).
     *
     * <p>Conditional on the claim being this worker's and still live: {@code Claimed}, by this worker, at this try,
     * under a lease that has not run out. The try count stands for the claim, because every claim adds one, so a
     * worker whose attempt was taken off it and claimed again -- even by another thread in the same pod, which
     * shares the worker name -- matches nothing. {@code "leaseExpiresAt"} itself is not compared: it is stored to
     * the millisecond and the service holds it to the nanosecond.
     *
     * <p>Only ever set to true. The flag is about the attempt's history, so nothing resets it.
     *
     * @return 1 if the flag is committed and the worker may submit, 0 if its claim is gone and it must not
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(nativeQuery = true, value = """
        UPDATE "deedpoll_ccdcreationattempt"
           SET "submissionMayHaveReached" = true,
               "modifiedOn" = :now
         WHERE "id" = :id
           AND "status" = 'Claimed'
           AND "claimedBy" = :claimedBy
           AND "attempts" = :attempts
           AND "leaseExpiresAt" > :now
        """)
    int recordSubmission(@Param("id") UUID id,
                         @Param("claimedBy") String claimedBy,
                         @Param("attempts") int attempts,
                         @Param("now") LocalDateTime now);

    /**
     * Gives a claim that created nothing back as {@code Pending}, not to be claimed again before {@code notBefore}.
     *
     * <p>Not {@link #move}, because a release has to say when the attempt is next due, and a release that forgot
     * would be claimed again by the very next call -- which, while CCD is refusing, spends every try at once.
     *
     * @return 1 if this statement released it, 0 if it was no longer {@code Claimed}
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(nativeQuery = true, value = """
        UPDATE "deedpoll_ccdcreationattempt"
           SET "status" = 'Pending',
               "claimedBy" = NULL,
               "leaseExpiresAt" = NULL,
               "failureDetail" = :failureDetail,
               "notBefore" = :notBefore,
               "modifiedOn" = :now
         WHERE "id" = :id
           AND "status" = 'Claimed'
        """)
    int release(@Param("id") UUID id,
                @Param("failureDetail") String failureDetail,
                @Param("notBefore") LocalDateTime notBefore,
                @Param("now") LocalDateTime now);

    /**
     * Takes an expired claim off its worker for reconciliation, if it is still a claim and still expired.
     *
     * <p>Not {@link #move}, whose only condition is the status: between reading an expired claim and moving it, its
     * worker can release it to {@code Pending} and another can claim it under a fresh lease, and a move conditional
     * on {@code Claimed} alone would take that live claim off a worker still talking to CCD.
     *
     * @return 1 if this statement took it, 0 if it was no longer an expired claim
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(nativeQuery = true, value = """
        UPDATE "deedpoll_ccdcreationattempt"
           SET "status" = 'AwaitingConfirmation',
               "claimedBy" = NULL,
               "leaseExpiresAt" = NULL,
               "failureDetail" = :failureDetail,
               "modifiedOn" = :now
         WHERE "id" = :id
           AND "status" = 'Claimed'
           AND "leaseExpiresAt" < :now
        """)
    int takeExpiredClaim(@Param("id") UUID id,
                         @Param("failureDetail") String failureDetail,
                         @Param("now") LocalDateTime now);

    /**
     * Moves an attempt from the status the caller last saw to another, releasing any claim on it.
     *
     * <p>Conditional on {@code from}, so a caller whose view is stale -- another worker moved the attempt first --
     * answers {@code 0} rather than overwriting what that worker recorded. Never used to claim: the claim is
     * {@link #claim}, which is the only statement that sets a lease.
     *
     * @param failureDetail what went wrong, already bounded by the caller, or null to clear it
     * @return 1 if this statement moved it, 0 if it was not in {@code from}
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(nativeQuery = true, value = """
        UPDATE "deedpoll_ccdcreationattempt"
           SET "status" = CAST(:to AS "CcdCreationAttemptStatus"),
               "claimedBy" = NULL,
               "leaseExpiresAt" = NULL,
               "failureDetail" = :failureDetail,
               "modifiedOn" = :now
         WHERE "id" = :id
           AND "status" = CAST(:from AS "CcdCreationAttemptStatus")
        """)
    int move(@Param("id") UUID id,
             @Param("from") String from,
             @Param("to") String to,
             @Param("failureDetail") String failureDetail,
             @Param("now") LocalDateTime now);
}
