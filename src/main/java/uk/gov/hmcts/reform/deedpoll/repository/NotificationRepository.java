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
import java.util.Optional;
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
 *
 * <p>There is one write here as well as the claim -- {@link #enqueue} -- and it is native for the same
 * reason: what makes it correct is the {@code ON CONFLICT} clause, which has no JPA spelling, and
 * writing it anywhere but in the statement would hide the one line that stops an applicant being told
 * twice that their application was refused.
 */
public interface NotificationRepository extends JpaRepository<Notification, UUID> {

    /** One case's notifications, newest first -- the Email tab's history. */
    List<Notification> findByDeedPollIdOrderByCreatedOnDesc(String deedPollId);

    /**
     * The row occupying the unique index for this referral and exclusion group, if there is one.
     *
     * <p>Read only after {@link #enqueue} answered {@code 0}, and by the availability check that greys the
     * option out in advance. It is not a check performed before the insert: that is the race the partial unique
     * index exists to settle, and a check-then-insert has a window this does not.
     *
     * <p><strong>Keyed on the group and not the template, which is what replaced the template finder this once
     * had.</strong> Since V15 a zero-row {@link #enqueue} has two different meanings: the caseworker clicked
     * twice on the same email, or they are asking for the email that contradicts one this referral has already
     * had. A finder keyed on the template cannot tell them apart -- it finds nothing at all in the second case --
     * and the difference is a {@code 200} saying "already owed" against a {@code 409} naming the other email. So
     * this asks the question the index actually answers, and the caller reads the template off the row.
     *
     * <p><strong>The predicate is V12's, repeated character for character, and that is the requirement rather
     * than a preference.</strong> Availability and the refusal must both use the index's definition of a
     * blocker: a {@code Failed} row with a {@code PERMANENT} verdict does not occupy the index -- Notify
     * definitely sent nothing, and a replacement is deliberately permitted -- while a pending, sent, technical,
     * exhausted-temporary or verdict-less failed row does. Native SQL so the predicate is the same text the
     * migration and {@link #enqueue} carry, where a reader can hold the three to each other.
     *
     * <p>Case-scoped like every other finder here even though the referral id alone identifies the row, and
     * {@code LIMIT 1} because the index permits at most one such row -- stated rather than assumed, since a
     * permanently refused row may sit beside the live one.
     *
     * @param deedPollId the case the caller resolved the referral through
     * @param referralId the referral whose slot is occupied
     * @param exclusionGroup the {@code EmailExclusionGroup} member name
     */
    @Query(nativeQuery = true, value = """
        SELECT *
          FROM "deedpoll_notification"
         WHERE "deedPollId" = :deedPollId
           AND "referralId" = :referralId
           AND "exclusionGroup" = :exclusionGroup
           AND ("state" <> 'Failed' OR "failureVerdict" IS DISTINCT FROM 'PERMANENT')
         ORDER BY "createdOn" DESC
         LIMIT 1
        """)
    Optional<Notification> findBlocking(@Param("deedPollId") String deedPollId,
                                        @Param("referralId") UUID referralId,
                                        @Param("exclusionGroup") String exclusionGroup);

    /**
     * The row occupying V20's case-scoped unique index for this case and exclusion group, if there is one.
     *
     * <p><strong>{@link #findBlocking}'s twin for a case-driven email, and the two are not interchangeable.</strong>
     * That one asks about a referral's slot; this one asks about a case's, which is the key V20 added because the
     * forms-for-completion emails have no referral to be keyed on -- so V15's partial index never sees their rows
     * and permits one applicant both deeds. Read after {@link #enqueue} answered {@code 0}, and by the availability
     * check that greys the option out in advance, exactly as its twin is.
     *
     * <p><strong>The predicate is V20's, repeated character for character, and the group filter is part of
     * it.</strong> {@code "referralId" IS NULL} is what makes this the case-scoped index rather than the
     * referral-scoped one, and {@code "exclusionGroup" IN (...)} is what keeps a repeat chase legitimate -- the
     * caller only asks this for a group {@code EmailExclusionGroup.exclusivePerCase()} answers true for, so the
     * {@code IN} list is not repeated here and the caller's flag is what has to agree with the migration. The
     * failure-verdict clause is V12's, unchanged: a permanently refused row does not occupy the slot.
     *
     * <p>Case-scoped like every other finder here -- which is the key's own scope this time rather than a
     * convention applied on top -- and {@code LIMIT 1} because the index permits at most one such row, stated
     * rather than assumed since a permanently refused row may sit beside the live one.
     *
     * @param deedPollId the case whose slot is occupied
     * @param exclusionGroup the {@code EmailExclusionGroup} member name, which must be one V20's index names
     */
    @Query(nativeQuery = true, value = """
        SELECT *
          FROM "deedpoll_notification"
         WHERE "deedPollId" = :deedPollId
           AND "referralId" IS NULL
           AND "exclusionGroup" = :exclusionGroup
           AND ("state" <> 'Failed' OR "failureVerdict" IS DISTINCT FROM 'PERMANENT')
         ORDER BY "createdOn" DESC
         LIMIT 1
        """)
    Optional<Notification> findBlockingForCase(@Param("deedPollId") String deedPollId,
                                               @Param("exclusionGroup") String exclusionGroup);

    /**
     * The row this case already has for this request id, if the caller has sent this request before.
     *
     * <p>Read after {@link #enqueue} answered {@code 0}, and read <em>first</em> of the two resolutions: an
     * insert can now conflict on V19's {@code ("deedPollId", "requestId")} key or on V15's exclusion group, and
     * the first is the more specific answer -- it names the very request the caller is retrying, where the second
     * only names an email of the same family. Asking in the other order would report a contradictory-template
     * conflict to a caller who was merely retrying its own POST.
     *
     * <p><strong>Not a check performed before the insert.</strong> Two replicas racing on one compose journey's
     * UUID is exactly the case the unique index exists for, and a check-then-insert has a window this does not.
     *
     * <p>Case-scoped like every other finder here, and that is the key's own scope rather than a convention
     * applied on top: the request id is a value the caller chose, so it means something only inside the case they
     * were working on. A derived query and not native SQL, because there is no predicate to keep in step with a
     * migration -- the index is total, so "the row with this request id" is all there is to ask.
     *
     * @param deedPollId the case the send named
     * @param requestId the {@code Idempotency-Key} the send carried
     */
    Optional<Notification> findByDeedPollIdAndRequestId(String deedPollId, UUID requestId);

    /**
     * Enqueues one owed email, unless this case has already had this request or this referral has already had an
     * email of this exclusion group.
     *
     * <p><strong>{@code ON CONFLICT ... DO NOTHING} rather than a caught exception, and that is a correctness
     * choice rather than a stylistic one.</strong> Idempotency here is the partial unique index, so the second
     * insert has to be refused by the database; the question is only what the refusal does to the caller. A
     * {@code DataIntegrityViolationException} would mark the caseworker's transaction rollback-only, so a
     * double-click would fail the whole request after the first click had already succeeded -- and moving the
     * insert into its own transaction to avoid that would commit the row outside the transaction that caused it,
     * which is the one thing the outbox is supposed to guarantee. This statement refuses the duplicate inside the
     * caller's transaction and leaves it usable: {@code 0} rows is the no-op to log, not an error.
     *
     * <p><strong>There is no conflict target since V19, and its absence is the correctness choice.</strong> Three
     * unique indexes can now refuse this row -- V19's {@code ("deedPollId", "requestId")}, V15's partial
     * {@code ("referralId", "exclusionGroup")} and V20's partial {@code ("deedPollId", "exclusionGroup")} -- and
     * {@code ON CONFLICT} takes at most one inferred target. A
     * statement naming either one would leave the other to raise a {@code DataIntegrityViolationException},
     * marking the caseworker's transaction rollback-only for a conflict that has a perfectly good answer. A bare
     * {@code DO NOTHING} covers every unique index on the table, which is what makes both refusals reachable as
     * {@code 0} rows.
     *
     * <p><strong>What is lost by dropping the target, and why it does not matter here.</strong> A target makes
     * Postgres verify that an index matching it exists, so a mis-typed predicate fails loudly; without one, an
     * index quietly dropped would make this statement stop deduplicating rather than fail. That guarantee is
     * bought back by assertion instead: {@code NotificationRepositoryTest} runs this statement against a real
     * Postgres for every conflict either index can produce, which is the only place either clause is executed.
     * The predicates the two indexes carry -- V11's {@code "referralId" IS NOT NULL} and V12's null-safe
     * failure-verdict expression, under which only an explicit permanent refusal leaves the exclusion slot -- are
     * unchanged and still spelled out in {@link #findBlocking}, which is what the caller resolves a refusal with.
     *
     * <p><strong>{@code 0} rows therefore has four meanings, and the caller must tell them apart.</strong> The
     * same request already sent (idempotent success), the other email of the referral's exclusion group
     * (a {@code 409}), the other email of the <em>case's</em> exclusion group (also a {@code 409}, and V20's), or
     * -- if no such row is readable -- a constraint this service cannot explain, which is a fault rather than an
     * outcome. {@code CaseEmailService.resolveRefusedInsert} resolves them in that order.
     *
     * <p>Native SQL for the reason {@link #claimNextDue} is: this <em>is</em> the statement, and the
     * {@code ON CONFLICT} clause has no JPA spelling. Identifiers are quoted because they are mixed case.
     * {@code 'Pending'} and {@code 0} are literals rather than parameters because a newly enqueued row is always
     * owed and has never been attempted -- a caller able to pass either could enqueue a row that was already
     * {@code Sent}. {@code CAST(:personalisation AS jsonb)} because the value arrives as the JSON text Jackson
     * produced and {@code jsonb} will not take a bare string.
     *
     * @param id the row id, which is also the {@code reference} the send will carry to Notify
     * @param deedPollId the case the email is about
     * @param requestId the caller's own identifier for this send, which with {@code deedPollId} is V19's key --
     *     bound explicitly rather than left to the column's default, so a row written by this release records the
     *     request the caseworker's journey actually made
     * @param referralId the referral that caused it, which with {@code exclusionGroup} is V15's key
     * @param template the {@code EmailTemplate} member name, never the Notify UUID
     * @param exclusionGroup the {@code EmailExclusionGroup} member name, bound explicitly rather than left to
     *     the column's default, so a later template in a group of its own is written as itself
     * @param recipient the address resolved at send, frozen so a later edit cannot redirect an email already owed
     * @param personalisation the resolved values and the caseworker's slots, as JSON text
     * @param now the instant to stamp, and when the row becomes claimable -- immediately
     * @param createdBy the caseworker who sent it, who authored part of the content
     * @return 1 if this statement enqueued the row, 0 if a unique index refused it -- which the caller resolves
     *     by reading the row that refused it
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(nativeQuery = true, value = """
        INSERT INTO "deedpoll_notification" (
                    "id", "deedPollId", "requestId", "referralId", "template", "exclusionGroup", "recipient",
                    "personalisation", "state", "attempts", "nextAttemptAt", "createdBy", "createdOn",
                    "modifiedOn", "modifiedBy")
             VALUES (:id, :deedPollId, :requestId, :referralId, :template, :exclusionGroup, :recipient,
                    CAST(:personalisation AS jsonb), 'Pending', 0, :now, :createdBy, :now,
                    :now, :createdBy)
        ON CONFLICT DO NOTHING
        """)
    int enqueue(@Param("id") UUID id,
                @Param("deedPollId") String deedPollId,
                @Param("requestId") UUID requestId,
                @Param("referralId") UUID referralId,
                @Param("template") String template,
                @Param("exclusionGroup") String exclusionGroup,
                @Param("recipient") String recipient,
                @Param("personalisation") String personalisation,
                @Param("now") LocalDateTime now,
                @Param("createdBy") String createdBy);

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

    /** Lock only within a short delivery transaction, never across preparation or Notify. */
    @Query(nativeQuery = true, value = """
        SELECT * FROM "deedpoll_notification" WHERE "id" = :id FOR UPDATE
        """)
    Optional<Notification> lockForDelivery(@Param("id") UUID id);

    /** Conditional ownership transfer; attempted-but-ambiguous rows can only be settled for investigation. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(nativeQuery = true, value = """
        UPDATE "deedpoll_notification"
           SET "attempts" = "attempts" + 1,
               "nextAttemptAt" = :leaseUntil,
               "claimOwner" = :owner,
               "modifiedOn" = :now,
               "modifiedBy" = :claimedBy
         WHERE "id" = :id
           AND "state" = 'Pending'
           AND "nextAttemptAt" <= :now
           AND "sendStartedAt" IS NULL
           AND "attempts" < :maxAttempts
        """)
    int claimNextDue(@Param("id") UUID id,
                     @Param("now") LocalDateTime now,
                     @Param("leaseUntil") LocalDateTime leaseUntil,
                     @Param("claimedBy") String claimedBy,
                     @Param("owner") UUID owner,
                     @Param("maxAttempts") int maxAttempts);
}
