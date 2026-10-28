package uk.gov.hmcts.reform.deedpoll.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import uk.gov.hmcts.reform.deedpoll.domain.masters.MasterEmailKind;
import uk.gov.hmcts.reform.deedpoll.domain.masters.MasterEmailState;
import uk.gov.hmcts.reform.deedpoll.entity.enums.WindowSlot;

import java.sql.Array;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * V44's {@code deedpoll_master_email}, with V45's {@code next_attempt_at}: the Master emails' ledger (plan D3).
 *
 * <p>Every transition after the claim is <strong>one conditional statement</strong> naming the state it leaves and,
 * where a claim is involved, the owner and the attempt it was granted for, so a worker whose claim was lost changes
 * nothing and is told so by a count of zero. The row holds no address: the recipient is read from the Masters list
 * at send time.
 */
@Repository
public class MasterEmailRepository {

    private static final RowMapper<Row> ROW = (row, index) -> new Row(
        row.getObject("id", UUID.class),
        row.getObject("master_id", UUID.class),
        MasterEmailKind.valueOf(row.getString("kind")),
        row.getString("period_key"),
        uuids(row.getArray("referral_ids")),
        MasterEmailState.valueOf(row.getString("state")),
        row.getInt("attempts"),
        row.getObject("owner", UUID.class),
        local(row.getTimestamp("lease_until")),
        local(row.getTimestamp("next_attempt_at")),
        local(row.getTimestamp("send_started_at")));

    private final JdbcTemplate jdbc;

    public MasterEmailRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** One ledger row, as delivery reads it. */
    public record Row(UUID id, UUID masterId, MasterEmailKind kind, String periodKey, List<UUID> referralIds,
                      MasterEmailState state, int attempts, UUID owner, LocalDateTime leaseUntil,
                      LocalDateTime nextAttemptAt, LocalDateTime sendStartedAt) {
    }

    /**
     * One email, unless this Master already has one of this kind for this period.
     *
     * @return whether this call wrote it
     */
    public boolean enqueue(UUID id, UUID masterId, MasterEmailKind kind, String periodKey, List<UUID> referralIds,
                           MasterEmailState state, LocalDateTime now) {
        return jdbc.update("""
            INSERT INTO deedpoll_master_email (id, master_id, kind, period_key, referral_ids, state, created_at,
                settled_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (master_id, kind, period_key) DO NOTHING
            """, id, masterId, kind.name(), periodKey, referralIds.toArray(UUID[]::new), state.name(), now,
            state == MasterEmailState.Pending ? null : now) == 1;
    }

    /** The Pending rows nobody holds and nothing defers, oldest first. */
    public List<UUID> claimable(LocalDateTime now, int limit) {
        return jdbc.queryForList("""
            SELECT id FROM deedpoll_master_email
             WHERE state = 'Pending' AND (lease_until IS NULL OR lease_until <= ?)
               AND (next_attempt_at IS NULL OR next_attempt_at <= ?)
             ORDER BY created_at, id
             LIMIT ?
            """, UUID.class, now, now, limit);
    }

    /** The row, locked until the caller's transaction ends. */
    public Optional<Row> lock(UUID id) {
        return jdbc.query("SELECT * FROM deedpoll_master_email WHERE id = ? FOR UPDATE", ROW, id)
            .stream().findFirst();
    }

    public Optional<Row> find(UUID id) {
        return jdbc.query("SELECT * FROM deedpoll_master_email WHERE id = ?", ROW, id).stream().findFirst();
    }

    /**
     * Takes a Pending row for {@code owner}, counting the attempt.
     */
    public boolean claim(UUID id, UUID owner, LocalDateTime leaseUntil) {
        return jdbc.update("""
            UPDATE deedpoll_master_email SET owner = ?, lease_until = ?, attempts = attempts + 1
             WHERE id = ? AND state = 'Pending'
            """, owner, leaseUntil, id) == 1;
    }

    /**
     * Commits send authority: {@code Sending}, with its start, for an unexpired claim of this attempt.
     */
    public boolean startSend(UUID id, UUID owner, int attempt, LocalDateTime now) {
        return jdbc.update("""
            UPDATE deedpoll_master_email SET state = 'Sending', send_started_at = ?
             WHERE id = ? AND state = 'Pending' AND owner = ? AND attempts = ? AND lease_until > ?
               AND send_started_at IS NULL
            """, now, id, owner, attempt, now) == 1;
    }

    /** Notify accepted it. */
    public boolean sent(UUID id, UUID owner, int attempt, UUID notifyId, LocalDateTime now) {
        return jdbc.update("""
            UPDATE deedpoll_master_email SET state = 'Sent', notify_id = ?, failure = NULL, owner = NULL,
                lease_until = NULL, next_attempt_at = NULL, settled_at = ?
             WHERE id = ? AND state = 'Sending' AND owner = ? AND attempts = ?
            """, notifyId, now, id, owner, attempt) == 1;
    }

    /**
     * Terminal, for this claim: {@code Failed} or {@code Skipped}, from {@code Pending} (nothing was sent) or
     * {@code Sending} (something may have been). The send's start is kept as evidence.
     */
    public boolean settle(UUID id, UUID owner, int attempt, MasterEmailState state, String failure,
                          LocalDateTime now) {
        return jdbc.update("""
            UPDATE deedpoll_master_email SET state = ?, failure = ?, owner = NULL, lease_until = NULL,
                next_attempt_at = NULL, settled_at = ?
             WHERE id = ? AND state IN ('Pending', 'Sending') AND owner = ? AND attempts = ?
            """, state.name(), failure, now, id, owner, attempt) == 1;
    }

    /**
     * Back to {@code Pending} after a known temporary refusal, or a preparation that failed before anything was
     * sent. The only statement that clears a send's start, because only a proved refusal permits another attempt.
     */
    public boolean retryLater(UUID id, UUID owner, int attempt, String failure, LocalDateTime nextAttemptAt) {
        return jdbc.update("""
            UPDATE deedpoll_master_email SET state = 'Pending', failure = ?, owner = NULL, lease_until = NULL,
                send_started_at = NULL, next_attempt_at = ?
             WHERE id = ? AND state IN ('Pending', 'Sending') AND owner = ? AND attempts = ?
            """, failure, nextAttemptAt, id, owner, attempt) == 1;
    }

    /** Terminal for a locked row the claim refuses: whatever claim it had is gone. */
    public void fail(UUID id, MasterEmailState state, String failure, LocalDateTime now) {
        jdbc.update("""
            UPDATE deedpoll_master_email SET state = ?, failure = ?, owner = NULL, lease_until = NULL,
                next_attempt_at = NULL, settled_at = ?
             WHERE id = ?
            """, state.name(), failure, now, id);
    }

    /**
     * {@code Sending} rows whose claim expired: a worker that committed send authority and never reported back.
     * The call may have reached Notify, so they are {@code Failed}, never sent again.
     *
     * @return how many
     */
    public int failAbandoned(String failure, LocalDateTime now) {
        return jdbc.update("""
            UPDATE deedpoll_master_email SET state = 'Failed', failure = ?, owner = NULL, lease_until = NULL,
                settled_at = ?
             WHERE state = 'Sending' AND lease_until <= ?
            """, failure, now, now);
    }

    /** A referral counted towards a window, and the Masters-list entry it is assigned to, if any. */
    public record WindowReferral(UUID referralId, UUID masterId) {
    }

    /**
     * V44's {@code deedpoll_window_digest_run}: takes a closed window's digests for the caller's transaction. A
     * second pod inserting the same window waits for the first's commit and then writes nothing; if the first rolls
     * back, the second writes it.
     *
     * @return whether this call wrote the row, and so owns the window's digests
     */
    public boolean recordDigestRun(LocalDate windowDate, WindowSlot windowSlot, LocalDateTime now) {
        return jdbc.update("""
            INSERT INTO deedpoll_window_digest_run (window_date, window_slot, ran_at) VALUES (?, ?, ?)
            ON CONFLICT (window_date, window_slot) DO NOTHING
            """, windowDate, windowSlot.name(), now) == 1;
    }

    /**
     * The referrals still {@code Claimed} in a window, in the Master's page order for undirected work
     * ({@code ReferralRepository.findOwnedBy}): urgent first, then the day referred, then the id. A row with no
     * {@code assignedMasterId} (a pre-V43 claim) comes back with a null Master, for the caller to count and skip.
     */
    public List<WindowReferral> claimedInWindow(LocalDate windowDate, WindowSlot windowSlot) {
        return jdbc.query("""
            SELECT "id", "assignedMasterId" FROM "deedpoll_referral"
             WHERE "state" = 'Claimed' AND "windowDate" = ? AND "windowSlot" = ?
             ORDER BY CASE WHEN "urgency" = 'Urgent' THEN 0 ELSE 1 END, "createdOn", "id"
            """, (row, index) -> new WindowReferral(row.getObject("id", UUID.class),
                                                    row.getObject("assignedMasterId", UUID.class)),
            windowDate, windowSlot.name());
    }

    private static List<UUID> uuids(Array array) throws SQLException {
        return Arrays.asList((UUID[]) array.getArray());
    }

    private static LocalDateTime local(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toLocalDateTime();
    }
}
