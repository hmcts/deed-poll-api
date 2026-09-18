package uk.gov.hmcts.reform.deedpoll.repository;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import uk.gov.hmcts.reform.deedpoll.entity.Notification;
import uk.gov.hmcts.reform.deedpoll.entity.enums.NotificationState;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * The notification outbox, read two ways: by the case that owns a row, and by the drain.
 *
 * <p><strong>The drain's two methods are not case-scoped, and they are the same exception the referral
 * inactivity sweep is.</strong> {@link ReferralRepository}'s rule -- every finder names its parent, so
 * a cross-case read is not expressible -- is about reading and writing <em>a</em> case's rows, which a
 * caller names by case. Emptying an outbox is by definition about every case at once and there is no
 * case id to scope it to. It stays inside the spirit of the rule the way {@code findStaleClaims} does:
 * {@link #findClaimable} returns ids and no case data, and neither method is reachable from a request
 * path that names a case -- {@code NotificationOutbox} is the only caller of either.
 *
 * <p>{@link #findByDeedPollIdOrderByCreatedOnDesc} is the case-scoped half, for the Email tab's
 * history, and it is scoped like every other finder in this package.
 */
public interface NotificationRepository extends JpaRepository<Notification, UUID> {

    /** One case's notifications, newest first -- the Email tab's history. */
    List<Notification> findByDeedPollIdOrderByCreatedOnDesc(String deedPollId);

    /**
     * The ids of the rows a drain could take right now, oldest due first.
     *
     * <p>Ids rather than entities, for the reason {@code findStaleClaims} returns a projection: a
     * candidate is not yet this caller's to work on, and every one of these has to survive the
     * conditional {@link #claimNextDue} before anything is read off it. Loading entities here would
     * also load rows another replica is about to win.
     *
     * <p>The state is a parameter rather than baked into the JPQL so that the caller says out loud
     * which state it is draining, matching {@link #claimNextDue}'s hard-coded {@code 'Pending'}: the
     * two predicates have to agree, and a state named in one place and implied in the other is how they
     * would come to disagree. {@code ReferralRepository.findStaleClaims} takes its state for the same
     * reason.
     *
     * <p>Oldest due first so a row that has been waiting through several backoffs is not starved by
     * newly enqueued ones, and the page is the caller's limit -- a drain hangs off an HTTP read, so it
     * takes a handful of rows rather than however many are owed.
     *
     * @param state the state an owed notification is in, {@link NotificationState#Pending}
     * @param now   the instant to judge dueness against, the same one the claim will re-assert with
     * @param page  how many candidates to consider, and the offset, which is always the first page
     * @return the candidate ids, oldest due first, empty when nothing is owed
     */
    @Query("""
        select n.id
          from Notification n
         where n.state = :state
           and n.nextAttemptAt <= :now
         order by n.nextAttemptAt asc, n.createdOn asc
        """)
    List<UUID> findClaimable(@Param("state") NotificationState state,
                             @Param("now") LocalDateTime now,
                             Pageable page);

    /**
     * Takes one owed notification for this drain, if it is still owed and still due when the statement
     * runs.
     *
     * <p><strong>The conditions in the {@code WHERE} are what make the drain safe across replicas with
     * no lock, and they are not redundant with the query that found the row.</strong> This is
     * {@code ReferralRepository.releaseStaleClaim} in shape and for the same argument, which is worth
     * restating because the consequence here is worse than a duplicated note: between the candidate
     * list and this statement another replica's drain may have taken the row, sent it and marked it
     * {@code Sent}, or a previous attempt's lease may have moved {@code nextAttemptAt} into the future.
     * Each of those makes the predicate false, so this answers {@code 0}, the caller sends nothing, and
     * an applicant is told once rather than twice. Exactly one caller gets {@code 1} for a given
     * attempt. There is no lock and none is needed: a single {@code UPDATE} evaluating its own
     * predicate is atomic, and a concurrent one blocks on the row until this transaction commits and
     * then re-evaluates against the committed values.
     *
     * <p><strong>Pushing {@code "nextAttemptAt"} forward is the lease, not tidiness</strong> -- it is
     * what stops the same row being claimed again while this caller is still talking to Notify, and it
     * is what makes a replica that dies mid-send recoverable rather than either stuck or duplicated:
     * the row is {@code Pending} with its attempt counted, and becomes claimable again when the lease
     * expires. The outcome handler overwrites it with the real backoff once the outcome is known.
     *
     * <p><strong>{@code "attempts"} is incremented here rather than by the outcome</strong>, so an
     * attempt that never returned still counts. A counter advanced only on a completed attempt would
     * let one poisonous row be retried forever.
     *
     * <p>Native SQL and {@code {@literal @}Modifying}: this <em>is</em> the statement, so it is written
     * where a reader can check it against the rule rather than assembled by a translator. Identifiers
     * are quoted because they are mixed case, as {@code ReferenceCounterRepository} explains.
     * {@code 'Pending'} is an untyped literal, which Postgres resolves against the
     * {@code "NotificationState"} column it is compared to. {@code clearAutomatically} because a
     * statement Hibernate did not route through the session leaves any already-loaded copy of that row
     * stale -- and the caller reads the row straight afterwards, so a stale {@code attempts} would be
     * the value the backoff was computed from. {@code flushAutomatically} so a pending write by the
     * caller, such as the previous row's outcome, cannot be lost behind it.
     *
     * @param id         the candidate to take
     * @param now        the same instant the candidate list was taken with, so a row cannot be claimed
     *                   against a dueness it did not qualify under
     * @param leaseUntil when this row becomes claimable again if this attempt never reports back
     * @param claimedBy  who to record as having written it, the service account -- no person drained
     * @return 1 if this statement took the row, 0 if it was no longer there to take
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(nativeQuery = true, value = """
        UPDATE "deedpoll_notification"
           SET "attempts" = "attempts" + 1,
               "nextAttemptAt" = :leaseUntil,
               "modifiedOn" = :now,
               "modifiedBy" = :claimedBy
         WHERE "id" = :id
           AND "state" = 'Pending'
           AND "nextAttemptAt" <= :now
        """)
    int claimNextDue(@Param("id") UUID id,
                     @Param("now") LocalDateTime now,
                     @Param("leaseUntil") LocalDateTime leaseUntil,
                     @Param("claimedBy") String claimedBy);
}
