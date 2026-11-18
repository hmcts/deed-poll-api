package uk.gov.hmcts.reform.deedpoll.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import uk.gov.hmcts.ccd.sdk.RetainAndDisposePolicy;
import uk.gov.hmcts.reform.deedpoll.domain.deletion.DeletionFailure;
import uk.gov.hmcts.reform.deedpoll.domain.deletion.DeletionStatus;
import uk.gov.hmcts.reform.deedpoll.domain.deletion.DeletionStepOutcome;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The deletion worker's statements over V48's tables, and its one read of the SDK's {@code ccd.case_data}: claims,
 * owned step and status writes, and the Retain and Dispose gates (plan 20261118-dm-application-deletion, Task 5).
 *
 * <p><strong>Every write a worker makes is fenced by its claim.</strong> {@link #holds} locks the request
 * {@code FOR UPDATE} only while the caller's owner and an unexpired lease are on it, and the caller's transaction makes
 * its step or status write after that, so a worker whose lease lapsed and was taken over writes nothing. The times are
 * the caller's, in UTC as {@code AuditClock} gives them, because admission wrote {@code next_attempt_at} that way.
 *
 * <p>Plain JDBC, as {@link DeletionRequestRepository} is. Nothing here reads {@code deedpoll_deedpoll}: a fenced case
 * is invisible to the entity layer, and the worker needs only its inventory.
 */
@Repository
public class DeletionWorkRepository {

    /** The statuses with work left, which the due index (V48) covers. */
    private static final String WORKING = "('Pending', 'InProgress', 'AwaitingDisposal')";

    /**
     * A request whose own cleanup is done and whose CCD case is still to be disposed: every filesystem document
     * verified removed first, so nothing the platform does not know about is left once CCD's copy has gone.
     */
    private static final String READY_FOR_CCD = """
        r.status in ('InProgress', 'AwaitingDisposal')
        and r.ccd_case_reference is not null
        and exists (select 1 from deedpoll_deletion_step s
                     where s.request_id = r.id and s.kind = 'CCD_DISPOSAL' and s.state = 'Pending')
        and not exists (select 1 from deedpoll_deletion_step s
                         where s.request_id = r.id and s.kind = 'LOCAL_DOCUMENT' and s.state <> 'Done')
        """;

    private final JdbcTemplate jdbc;

    public DeletionWorkRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** What the SDK's local copy of a linked case says about its disposal. */
    public record CcdCaseState(String state, boolean ttlResolved) {

        /** Marked and confirmed: the case is waiting for CCD's disposer. */
        public boolean awaitingDisposal() {
            return RetainAndDisposePolicy.DISPOSAL_STATE_ID.equals(state) && ttlResolved;
        }
    }

    /**
     * Claims the soonest due request with work left that nobody holds, or whose holder's lease has lapsed, moving a
     * {@code Pending} one to {@code InProgress}. {@code SKIP LOCKED}, so two pods never wait on, or take, one row.
     *
     * @return the claimed request's id, if any was due
     */
    public Optional<UUID> claimNext(UUID owner, LocalDateTime now, LocalDateTime leaseUntil) {
        return jdbc.query("""
            with due as (
                select id from deedpoll_deletion_request
                 where status in %s
                   and next_attempt_at <= ?
                   and (owner is null or lease_until < ?)
                 order by next_attempt_at, id
                 limit 1
                 for update skip locked)
            update deedpoll_deletion_request r
               set owner = ?, lease_until = ?, attempts = r.attempts + 1,
                   status = case when r.status = 'Pending' then 'InProgress' else r.status end
              from due
             where r.id = due.id
            returning r.id
            """.formatted(WORKING), (row, index) -> row.getObject("id", UUID.class),
            timestamp(now), timestamp(now), owner, timestamp(leaseUntil)).stream().findFirst();
    }

    /**
     * Locks the request while {@code owner} holds an unexpired claim on it, until the caller's transaction ends.
     *
     * @return whether the claim is still the caller's
     */
    public boolean holds(UUID requestId, UUID owner, LocalDateTime now) {
        return !jdbc.queryForList("""
            select id from deedpoll_deletion_request
             where id = ? and owner = ? and lease_until > ? and status in %s
             for update
            """.formatted(WORKING), UUID.class, requestId, owner, timestamp(now)).isEmpty();
    }

    /**
     * One inventory line verified removed. Only a {@code Pending} line moves, so a repeat writes nothing.
     */
    public int stepDone(UUID stepId, DeletionStepOutcome outcome, LocalDateTime now) {
        return jdbc.update("""
            update deedpoll_deletion_step set state = 'Done', outcome = ?, completed_at = ?
             where id = ? and state = 'Pending'
            """, outcome.name(), timestamp(now), stepId);
    }

    /**
     * One try at a line that did not verify its removal; the line stays {@code Pending}, its locator held.
     */
    public int stepTried(UUID stepId, DeletionStepOutcome outcome) {
        return jdbc.update("""
            update deedpoll_deletion_step set attempts = attempts + 1, outcome = ?
             where id = ? and state = 'Pending'
            """, outcome.name(), stepId);
    }

    /** A line no further try will remove; its locator is held for whoever investigates. */
    public int stepFailed(UUID stepId, DeletionStepOutcome outcome) {
        return jdbc.update("""
            update deedpoll_deletion_step set attempts = attempts + 1, outcome = ?, state = 'Failed'
             where id = ? and state = 'Pending'
            """, outcome.name(), stepId);
    }

    /**
     * Gives up the caller's claim, recording where the request has got to and when it is next due. A
     * {@link DeletionStatus#NeedsAttention} request leaves the worker's due set until somebody puts it back.
     *
     * @param failure an allowlisted code, required with {@code NeedsAttention} and refused otherwise (V48's check)
     * @return 1, or 0 if the claim was no longer the caller's
     */
    public int release(UUID requestId, UUID owner, DeletionStatus status, DeletionFailure failure,
                       LocalDateTime nextAttemptAt) {
        if (status == DeletionStatus.Completed) {
            throw new IllegalArgumentException("A release never completes a request; finalisation does");
        }
        return jdbc.update("""
            update deedpoll_deletion_request
               set status = ?, failure = ?, owner = null, lease_until = null, next_attempt_at = ?
             where id = ? and owner = ?
            """, status.name(), failure == null ? null : failure.name(), timestamp(nextAttemptAt), requestId, owner);
    }

    /** The SDK's local copy of a linked case, if it still has one. */
    public Optional<CcdCaseState> ccdCase(String reference) {
        return jdbc.query("""
            select state, resolved_ttl is not null as ttl_resolved from ccd.case_data where reference = ?
            """, (row, index) -> new CcdCaseState(row.getString("state"), row.getBoolean("ttl_resolved")),
            Long.parseLong(reference)).stream().findFirst();
    }

    /**
     * The CCD references Retain and Dispose may mark now: {@link #READY_FOR_CCD}.
     */
    public List<Long> disposalCandidates() {
        return jdbc.queryForList("""
            select r.ccd_case_reference::bigint from deedpoll_deletion_request r where %s
             order by r.ccd_case_reference
            """.formatted(READY_FOR_CCD), Long.class);
    }

    /**
     * Whether the case's deletion request is ready for its CCD case to be marked for disposal, holding the request's
     * lock until the caller's transaction ends.
     */
    public boolean lockReadyForCcdDisposal(String reference) {
        return !jdbc.queryForList("""
            select r.id from deedpoll_deletion_request r where r.ccd_case_reference = ? and %s for update of r
            """.formatted(READY_FOR_CCD), UUID.class, reference).isEmpty();
    }

    /**
     * Records, inside Retain and Dispose's own transaction, that CCD has disposed of the case: its CCD step and the
     * CDAM documents CCD's disposer removed with it are {@code Done}, and the request is due at once for
     * finalisation. Only a request still waiting for that disposal moves.
     *
     * @return the number of CCD steps recorded, which is 1 or, for a case no request is waiting on, 0
     */
    public int recordDisposed(String reference, LocalDateTime now) {
        List<UUID> request = jdbc.queryForList("""
            select r.id from deedpoll_deletion_request r where r.ccd_case_reference = ? and %s for update of r
            """.formatted(READY_FOR_CCD), UUID.class, reference);
        if (request.isEmpty()) {
            return 0;
        }
        UUID id = request.getFirst();
        int recorded = jdbc.update("""
            update deedpoll_deletion_step set state = 'Done', outcome = ?, completed_at = ?
             where request_id = ? and kind in ('CCD_DISPOSAL', 'CDAM_DOCUMENT') and state = 'Pending'
            """, DeletionStepOutcome.DISPOSED_BY_CCD.name(), timestamp(now), id);
        jdbc.update("""
            update deedpoll_deletion_request set status = 'InProgress', next_attempt_at = ? where id = ?
            """, timestamp(now), id);
        return recorded > 0 ? 1 : 0;
    }

    private static Timestamp timestamp(LocalDateTime value) {
        return value == null ? null : Timestamp.valueOf(value);
    }
}
