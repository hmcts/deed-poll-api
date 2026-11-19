package uk.gov.hmcts.reform.deedpoll.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import uk.gov.hmcts.reform.deedpoll.domain.deletion.DeletionNote;
import uk.gov.hmcts.reform.deedpoll.domain.deletion.DeletionReason;
import uk.gov.hmcts.reform.deedpoll.domain.deletion.DeletionRequest;
import uk.gov.hmcts.reform.deedpoll.domain.deletion.DeletionStatus;
import uk.gov.hmcts.reform.deedpoll.domain.deletion.DeletionStep;
import uk.gov.hmcts.reform.deedpoll.domain.deletion.DeletionStepKind;
import uk.gov.hmcts.reform.deedpoll.domain.deletion.DeletionStepState;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * V48's {@code deedpoll_deletion_request} and {@code deedpoll_deletion_step}: the durable record of an application
 * deletion and the inventory its cleanup works through.
 *
 * <p>Plain JDBC, as V44's ledger is, because the rows are written in short statements whose counts are the answer.
 * The request has no foreign key to the case, so it is read by its own id or by the case's id after the case is gone.
 */
@Repository
public class DeletionRequestRepository {

    private static final String COLUMNS = """
        id, case_id, idempotency_key, reason, note, fingerprint, expected_last_changed_on, requested_by_subject,
        requested_by_name, requested_at, ccd_case_reference, restorable_until, status, failure, attempts, owner,
        lease_until, next_attempt_at, completed_at
        """;

    private static final RowMapper<DeletionRequest> REQUEST = (row, index) -> new DeletionRequest(
        row.getObject("id", UUID.class),
        row.getString("case_id"),
        row.getObject("idempotency_key", UUID.class),
        DeletionReason.valueOf(row.getString("reason")),
        DeletionNote.parse(row.getString("note")),
        row.getString("fingerprint"),
        local(row.getTimestamp("expected_last_changed_on")),
        row.getString("requested_by_subject"),
        row.getString("requested_by_name"),
        local(row.getTimestamp("requested_at")),
        row.getString("ccd_case_reference"),
        local(row.getTimestamp("restorable_until")),
        DeletionStatus.valueOf(row.getString("status")),
        row.getString("failure"),
        row.getInt("attempts"),
        row.getObject("owner", UUID.class),
        local(row.getTimestamp("lease_until")),
        local(row.getTimestamp("next_attempt_at")),
        local(row.getTimestamp("completed_at")));

    private static final RowMapper<DeletionStep> STEP = (row, index) -> new DeletionStep(
        row.getObject("id", UUID.class),
        row.getObject("request_id", UUID.class),
        DeletionStepKind.valueOf(row.getString("kind")),
        row.getString("item_id"),
        row.getString("locator"),
        DeletionStepState.valueOf(row.getString("state")),
        row.getString("outcome"),
        row.getInt("attempts"),
        local(row.getTimestamp("completed_at")));

    private final JdbcTemplate jdbc;

    public DeletionRequestRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** What admission writes: the fixed half of a request. */
    public record NewDeletionRequest(UUID id, String caseId, UUID idempotencyKey, DeletionReason reason,
                                     DeletionNote note, String fingerprint, LocalDateTime expectedLastChangedOn,
                                     String requestedBySubject, String requestedByName, LocalDateTime requestedAt,
                                     String ccdCaseReference, LocalDateTime restorableUntil) {

        @Override
        public String toString() {
            return "NewDeletionRequest[id=" + id + ", caseId=" + caseId + ", reason=" + reason + "]";
        }
    }

    /**
     * The request, {@link DeletionStatus#Pending} and due when its grace period ends, unless the case already has
     * one.
     *
     * <p>{@code ON CONFLICT (case_id) DO NOTHING} rather than a raised violation, so a racing admission is told by a
     * count of zero and its transaction stays usable to read the winner.
     *
     * @return whether this call wrote it
     */
    public boolean insert(NewDeletionRequest request) {
        return jdbc.update("""
            insert into deedpoll_deletion_request (id, case_id, idempotency_key, reason, note, fingerprint,
                expected_last_changed_on, requested_by_subject, requested_by_name, requested_at, ccd_case_reference,
                restorable_until, status, next_attempt_at)
            values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'Pending', ?)
            on conflict (case_id) do nothing
            """, request.id(), request.caseId(), request.idempotencyKey(), request.reason().name(),
            request.note().value(), request.fingerprint(), timestamp(request.expectedLastChangedOn()),
            request.requestedBySubject(), request.requestedByName(), timestamp(request.requestedAt()),
            request.ccdCaseReference(), timestamp(request.restorableUntil()),
            timestamp(request.restorableUntil())) == 1;
    }

    public Optional<DeletionRequest> findById(UUID id) {
        return jdbc.query("select " + COLUMNS + " from deedpoll_deletion_request where id = ?", REQUEST, id)
            .stream().findFirst();
    }

    /** The case's one request, whether or not the case still exists. */
    public Optional<DeletionRequest> findByCase(String caseId) {
        return jdbc.query("select " + COLUMNS + " from deedpoll_deletion_request where case_id = ?", REQUEST, caseId)
            .stream().findFirst();
    }

    /**
     * The request, locked {@code FOR UPDATE} until the caller's transaction ends. A restore takes it after the case's
     * own lock, in admission's order, so the two never wait on each other the other way round; the worker's claim
     * skips a locked request rather than waiting.
     */
    public Optional<DeletionRequest> lockById(UUID id) {
        return jdbc.query("select " + COLUMNS + " from deedpoll_deletion_request where id = ? for update", REQUEST,
                          id).stream().findFirst();
    }

    /**
     * Deletes the request, and with it its inventory (V48's cascade), only while it is still restorable at
     * {@code now}: the conditions {@code DeletionRestoreRule} states, repeated here so a stale read cannot pass them.
     * Deleting the request is what lifts the fence, which is an {@code EXISTS} over this table.
     *
     * @return whether this call deleted it
     */
    public boolean deleteRestorable(UUID id, LocalDateTime now) {
        return jdbc.update("""
            delete from deedpoll_deletion_request
             where id = ? and status = 'Pending' and owner is null and attempts = 0 and restorable_until > ?
            """, id, timestamp(now)) == 1;
    }

    /**
     * One inventory line, {@link DeletionStepState#Pending}.
     */
    public void insertStep(UUID id, UUID requestId, DeletionStepKind kind, String itemId, String locator) {
        jdbc.update("""
            insert into deedpoll_deletion_step (id, request_id, kind, item_id, locator, state)
            values (?, ?, ?, ?, ?, 'Pending')
            """, id, requestId, kind.name(), itemId, locator);
    }

    /** The request's inventory, in a stable order. */
    public List<DeletionStep> stepsOf(UUID requestId) {
        return jdbc.query("""
            select id, request_id, kind, item_id, locator, state, outcome, attempts, completed_at
              from deedpoll_deletion_step where request_id = ? order by kind, item_id nulls first, id
            """, STEP, requestId);
    }

    private static Timestamp timestamp(LocalDateTime value) {
        return value == null ? null : Timestamp.valueOf(value);
    }

    private static LocalDateTime local(Timestamp value) {
        return value == null ? null : value.toLocalDateTime();
    }
}
