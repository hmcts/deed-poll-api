package uk.gov.hmcts.reform.deedpoll.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import uk.gov.hmcts.reform.deedpoll.domain.gazette.GazetteDeliveryConfiguration;
import uk.gov.hmcts.reform.deedpoll.domain.gazette.GazetteFileEvidence;
import uk.gov.hmcts.reform.deedpoll.domain.gazette.GazetteRequest;
import uk.gov.hmcts.reform.deedpoll.domain.gazette.GazetteRequestState;
import uk.gov.hmcts.reform.deedpoll.domain.gazette.GazetteSettings;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** All writes participate in the ledger's short transaction; no method calls a remote service. */
@Repository
public class GazetteRequestRepository {
    // Compile-time constants, one per variant, so no statement is assembled at runtime.
    private static final String READ = "SELECT * FROM deedpoll_gazette_request WHERE id = ?";
    private static final String READ_FOR_UPDATE = READ + " FOR UPDATE";
    private static final RowMapper<GazetteRequest> ROW = (row, index) -> new GazetteRequest(
        row.getObject("id", UUID.class), row.getObject("request_id", UUID.class), row.getString("payload_hash"),
        row.getString("submitted_by_id"), row.getString("submitted_by_name"),
        row.getObject("submitted_at", LocalDateTime.class), row.getString("recipient_email"),
        row.getInt("recipient_revision"), row.getInt("adult_count"), row.getInt("minor_count"),
        new GazetteDeliveryConfiguration(row.getObject("template_id", UUID.class),
            row.getObject("reply_to_id", UUID.class), row.getInt("retention_weeks")),
        row.getObject("owner", UUID.class), row.getObject("lease_until", LocalDateTime.class),
        GazetteRequestState.valueOf(row.getString("state")), row.getObject("send_started_at", LocalDateTime.class),
        row.getObject("provider_notification_id", UUID.class), row.getObject("template_version", Integer.class));

    private final JdbcTemplate jdbc;

    public GazetteRequestRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Same-key races wait here; hash collisions only serialize unrelated requests. */
    public void lockKey(UUID key) {
        jdbc.query("SELECT pg_advisory_xact_lock(hashtext('gazette-request'), hashtext(?))",
            (row, index) -> Boolean.TRUE, key.toString());
    }

    public Optional<GazetteRequest> byKey(UUID key) {
        return jdbc.query("SELECT * FROM deedpoll_gazette_request WHERE request_id = ?", ROW, key).stream().findFirst();
    }

    public Optional<GazetteRequest> read(UUID id, boolean lock) {
        return jdbc.query(lock ? READ_FOR_UPDATE : READ, ROW, id).stream().findFirst();
    }

    /** Candidate read only; expire locks and rechecks each row in its own short transaction. */
    public List<UUID> expired(LocalDateTime now) {
        return jdbc.queryForList("""
            SELECT id FROM deedpoll_gazette_request
            WHERE state IN ('Preparing', 'Sending') AND lease_until <= ?
            ORDER BY lease_until, id LIMIT 25
            """, UUID.class, now);
    }

    public boolean legacyKey(UUID key) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM deedpoll_gazette_notice_batch WHERE request_id = ?)", Boolean.class, key));
    }

    public GazetteSettings lockSettings() {
        return jdbc.queryForObject("SELECT * FROM deedpoll_gazette_settings WHERE id = 1 FOR SHARE",
            (row, index) -> new GazetteSettings(row.getString("recipient_email"), row.getInt("revision"),
                row.getObject("updated_at", LocalDateTime.class), row.getString("updated_by_name")));
    }

    public boolean lockCase(String id) {
        return !jdbc.query("SELECT id FROM deedpoll_deedpoll WHERE id = ? FOR NO KEY UPDATE",
            (row, index) -> row.getString(1), id).isEmpty();
    }

    public boolean reserved(String id) {
        // The legacy read also protects fixtures/imports added after the migration's backfill.
        return Boolean.TRUE.equals(jdbc.queryForObject("""
            SELECT EXISTS (SELECT 1 FROM deedpoll_gazette_reservation WHERE deed_poll_id = ?)
                OR EXISTS (SELECT 1 FROM deedpoll_gazette_notice_batch_case WHERE deed_poll_id = ?)
            """, Boolean.class, id, id));
    }

    public void insert(GazetteRequest row, String fingerprint) {
        jdbc.update("""
            INSERT INTO deedpoll_gazette_request
                (id, request_id, payload_hash, selection_fingerprint, submitted_by_id, submitted_by_name,
                 submitted_at, recipient_email, recipient_revision, adult_count, minor_count, template_id,
                 reply_to_id, retention_weeks, confirm_email, owner, lease_until, state)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, true, ?, ?, 'Preparing')
            """, row.id(), row.requestId(), row.payloadHash(), fingerprint, row.submittedById(), row.submittedByName(),
            row.submittedAt(), row.recipientEmail(), row.recipientRevision(), row.adultCount(), row.minorCount(),
            row.configuration().templateId(), row.configuration().replyToId(), row.configuration().retentionWeeks(),
            row.owner(), row.leaseUntil());
    }

    public void member(UUID batch, String id, String reference, String kind) {
        jdbc.update("INSERT INTO deedpoll_gazette_request_case VALUES (?, ?, ?, ?)", batch, id, reference, kind);
        jdbc.update("INSERT INTO deedpoll_gazette_reservation (deed_poll_id, request_batch_id) VALUES (?, ?)",
            id, batch);
    }

    public List<String> members(UUID batch) {
        return jdbc.queryForList("SELECT deed_poll_id FROM deedpoll_gazette_request_case "
            + "WHERE batch_id = ? ORDER BY deed_poll_id", String.class, batch);
    }

    public void file(UUID batch, GazetteFileEvidence file) {
        jdbc.update("INSERT INTO deedpoll_gazette_request_file VALUES (?, ?, ?, ?, ?, ?)",
            batch, file.kind(), file.filename(), file.caseCount(), file.byteSize(), file.sha256());
    }

    public void start(UUID batch, LocalDateTime now) {
        jdbc.update("UPDATE deedpoll_gazette_request SET state = 'Sending', send_started_at = ? WHERE id = ?",
            now, batch);
    }

    public void finish(UUID batch, GazetteRequestState state, String reason, LocalDateTime now) {
        jdbc.update("UPDATE deedpoll_gazette_request SET state = ?, failure_code = ?, settled_at = ? WHERE id = ?",
            state.name(), reason, now, batch);
        if (state == GazetteRequestState.NotSent) {
            jdbc.update("DELETE FROM deedpoll_gazette_reservation WHERE request_batch_id = ?", batch);
        }
    }

    public void receipt(UUID batch, UUID providerId, Integer version) {
        jdbc.update("UPDATE deedpoll_gazette_request SET provider_notification_id = ?, template_version = ? "
            + "WHERE id = ?", providerId, version, batch);
    }

    public void sentEvidence(GazetteRequest row, LocalDateTime now) {
        jdbc.update("INSERT INTO deedpoll_gazette_notice_batch VALUES (?, ?, ?, ?, ?, ?)",
            row.id(), now, row.submittedById(), row.submittedByName(), row.requestId(),
            row.adultCount() + row.minorCount());
        jdbc.update("""
            INSERT INTO deedpoll_gazette_notice_batch_case
            SELECT batch_id, deed_poll_id, reference_number FROM deedpoll_gazette_request_case WHERE batch_id = ?
            """, row.id());
        jdbc.update("""
            UPDATE deedpoll_deedpoll SET "dateNoticeSentToLondonGazette" = ?, "modifiedOn" = ?, "modifiedBy" = ?
            WHERE id IN (SELECT deed_poll_id FROM deedpoll_gazette_request_case WHERE batch_id = ?)
            """, now, now, row.submittedByName(), row.id());
    }
}
