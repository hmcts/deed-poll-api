package uk.gov.hmcts.reform.deedpoll.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import uk.gov.hmcts.reform.deedpoll.entity.CcdCreationAttempt;
import uk.gov.hmcts.reform.deedpoll.entity.enums.CcdCreationAttemptStatus;

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
     * {@link #findExpiredClaims}, the first {@code limit} of them: the linking sweep's bounded share.
     */
    @Query(nativeQuery = true, value = """
        SELECT *
          FROM "deedpoll_ccdcreationattempt"
         WHERE "status" = 'Claimed'
           AND "leaseExpiresAt" < :now
         ORDER BY "leaseExpiresAt"
         LIMIT :limit
        """)
    List<CcdCreationAttempt> findFirstExpiredClaims(@Param("now") LocalDateTime now, @Param("limit") int limit);

    /**
     * {@link #findAwaitingConfirmation}, the first {@code limit} of them: the linking sweep's bounded share.
     */
    @Query(nativeQuery = true, value = """
        SELECT *
          FROM "deedpoll_ccdcreationattempt"
         WHERE "status" = 'AwaitingConfirmation'
         ORDER BY "modifiedOn"
         LIMIT :limit
        """)
    List<CcdCreationAttempt> findFirstAwaitingConfirmation(@Param("limit") int limit);

    /**
     * How many attempts are in each status, {@code Linked} included: the repair view's headline. A status no
     * attempt is in is absent, and the caller fills it with zero.
     *
     * @return pairs of {@link CcdCreationAttemptStatus} and {@code Long}
     */
    @Query("select a.status, count(a) from CcdCreationAttempt a group by a.status")
    List<Object[]> countByStatus();

    /**
     * One page of the attempts in any status but {@code excluded}, longest-unmoved first: the repair view's list, which
     * excludes {@code Linked}.
     *
     * <p>The status is bound rather than written as a JPQL enum literal, because Hibernate renders that literal as a
     * cast to the unquoted type name, which Postgres folds to lower case and cannot find.
     *
     * <p>{@code "modifiedOn"} and then the id, so the order is total and a page boundary is stable between two
     * requests that change nothing. An offset rather than {@code findIdsDueForCcdLinking}'s keyset, because a person
     * is paging a list here rather than a worker taking its head.
     */
    @Query(value = """
        select new uk.gov.hmcts.reform.deedpoll.repository.CcdAttemptRow(
                   a.id, a.deedPollId, d.displayReference, a.status, a.attempts, a.failureDetail, a.modifiedOn,
                   a.submissionMayHaveReached)
          from CcdCreationAttempt a
          join DeedPoll d on d.id = a.deedPollId
         where a.status <> :excluded
         order by a.modifiedOn, a.id
        """,
        countQuery = """
        select count(a)
          from CcdCreationAttempt a
         where a.status <> :excluded
        """)
    Page<CcdAttemptRow> findRowsOtherThan(@Param("excluded") CcdCreationAttemptStatus excluded, Pageable page);

    /**
     * {@link #findRowsOtherThan}, narrowed to one status. A statement of its own rather than an optional parameter,
     * because Postgres cannot type a null bound against an enum column.
     */
    @Query(value = """
        select new uk.gov.hmcts.reform.deedpoll.repository.CcdAttemptRow(
                   a.id, a.deedPollId, d.displayReference, a.status, a.attempts, a.failureDetail, a.modifiedOn,
                   a.submissionMayHaveReached)
          from CcdCreationAttempt a
          join DeedPoll d on d.id = a.deedPollId
         where a.status = :status
         order by a.modifiedOn, a.id
        """,
        countQuery = "select count(a) from CcdCreationAttempt a where a.status = :status")
    Page<CcdAttemptRow> findRowsByStatus(@Param("status") CcdCreationAttemptStatus status, Pageable page);

    /** One attempt as the repair view shows it, if it exists. */
    @Query("""
        select new uk.gov.hmcts.reform.deedpoll.repository.CcdAttemptRow(
                   a.id, a.deedPollId, d.displayReference, a.status, a.attempts, a.failureDetail, a.modifiedOn,
                   a.submissionMayHaveReached)
          from CcdCreationAttempt a
          join DeedPoll d on d.id = a.deedPollId
         where a.id = :id
        """)
    Optional<CcdAttemptRow> findRowById(@Param("id") UUID id);

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
     * The try one claim made the attempt, read back by {@link #claim}'s caller in the same transaction, whose UPDATE
     * still holds the row. The count the caller read before claiming may be stale: a claim released since, or an
     * admin's retry from scratch, moves it.
     *
     * @return the try, or empty if the attempt is not {@code Claimed} by this claimant
     */
    @Query(nativeQuery = true, value = """
        SELECT "attempts"
          FROM "deedpoll_ccdcreationattempt"
         WHERE "id" = :id
           AND "status" = 'Claimed'
           AND "claimedBy" = :claimedBy
        """)
    Optional<Integer> findTryOfClaim(@Param("id") UUID id, @Param("claimedBy") String claimedBy);

    /**
     * Records, before {@code submitCaseCreation} is called, that this worker's submission may reach CCD (V23).
     *
     * <p>Conditional on the claim being this worker's and still live: {@code Claimed}, by this worker, at this try,
     * under a lease that has not run out. {@code "claimedBy"} is the claimant {@code CcdLinkingService} mints for
     * each claim -- the worker's name and a fresh token -- so a worker whose attempt was taken off it and claimed
     * again, even by another thread in the same pod or at the same try after an admin's retry from scratch reset
     * the count, matches nothing. {@code "leaseExpiresAt"} itself is not compared: it is stored to the millisecond
     * and the service holds it to the nanosecond.
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
     * <p>Keyed on the claim by claimant and try, as {@link #recordSubmission} is, so a worker whose claim was taken
     * off it and granted afresh -- to the same pod, even at the same try -- cannot release the fresh one. The lease
     * is not compared: it is the row's, not the caller's, and a worker whose own lease lapsed unnoticed may still say
     * that it created nothing.
     *
     * @return 1 if this statement released it, 0 if it was no longer this worker's claim at this try
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
           AND "claimedBy" = :claimedBy
           AND "attempts" = :attempts
        """)
    int release(@Param("id") UUID id,
                @Param("claimedBy") String claimedBy,
                @Param("attempts") int attempts,
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
     * Moves this worker's own claim on from {@code Claimed}, releasing it.
     *
     * <p>Keyed on the claim by claimant and try, as {@link #release} is, and for the same reason: a worker stalled
     * past its lease, across reconciliation and an admin's retry, must not settle somebody else's fresh claim --
     * whose submission, if its callback then read {@code NeedsRepair}, would create a case that nothing binds.
     *
     * @param failureDetail what went wrong, already bounded by the caller, or null to clear it
     * @return 1 if this statement moved it, 0 if it was no longer this worker's claim at this try
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
           AND "status" = 'Claimed'
           AND "claimedBy" = :claimedBy
           AND "attempts" = :attempts
        """)
    int moveClaim(@Param("id") UUID id,
                  @Param("claimedBy") String claimedBy,
                  @Param("attempts") int attempts,
                  @Param("to") String to,
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

    /**
     * An admin's "reconcile again": {@code NeedsRepair -> AwaitingConfirmation}, attributed (plan 02b, decisions 8
     * and 10).
     *
     * <p>Conditional on {@code NeedsRepair}, so of two admins acting at once exactly one moves it and the other gets
     * {@code 0}. {@code "failureDetail"} is left as it stands: it is why the attempt needed repair, and the next
     * reconciliation overwrites it with whatever it finds. {@code "modifiedOn"} moves, which restarts the settle
     * window reconciliation measures from, so a repaired attempt gets the whole window again before it can return.
     *
     * @return 1 if this statement moved it, 0 if it was not {@code NeedsRepair}
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(nativeQuery = true, value = """
        UPDATE "deedpoll_ccdcreationattempt"
           SET "status" = 'AwaitingConfirmation',
               "repairedById" = :repairedById,
               "repairedOn" = :now,
               "modifiedOn" = :now
         WHERE "id" = :id
           AND "status" = 'NeedsRepair'
        """)
    int reconcileAgain(@Param("id") UUID id,
                       @Param("repairedById") String repairedById,
                       @Param("now") LocalDateTime now);

    /**
     * An admin's "try again from scratch": {@code NeedsRepair -> Pending}, its tries reset and due now, attributed
     * (plan 02b, decisions 8 and 10).
     *
     * <p><strong>Conditional on {@code "submissionMayHaveReached" = false} as well as the status</strong>, which is
     * {@code CcdLinkingRule.mayRetryFromScratch} repeated in SQL, as the claim repeats {@code isDue}. The service
     * asks the rule first and this statement refuses anyway, so an attempt that may have reached CCD is never sent
     * back to {@code Pending}, whatever a caller read. The flag itself is not touched: it is the attempt's history.
     *
     * @return 1 if this statement moved it, 0 if it was not {@code NeedsRepair} or may have been submitted
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(nativeQuery = true, value = """
        UPDATE "deedpoll_ccdcreationattempt"
           SET "status" = 'Pending',
               "claimedBy" = NULL,
               "leaseExpiresAt" = NULL,
               "attempts" = 0,
               "notBefore" = :now,
               "repairedById" = :repairedById,
               "repairedOn" = :now,
               "modifiedOn" = :now
         WHERE "id" = :id
           AND "status" = 'NeedsRepair'
           AND "submissionMayHaveReached" = false
        """)
    int retryFromScratch(@Param("id") UUID id,
                         @Param("repairedById") String repairedById,
                         @Param("now") LocalDateTime now);
}
