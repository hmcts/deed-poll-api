package uk.gov.hmcts.reform.deedpoll.domain.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import uk.gov.hmcts.reform.deedpoll.entity.AuditClock;

import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.util.Collection;
import java.util.List;

/**
 * Writes the append-only audit trail ({@code V36}).
 *
 * <p>{@link #record} is {@link Propagation#MANDATORY}: it joins the transaction making the change, so a change with
 * no audit row cannot commit, and a call with no transaction to join is refused rather than committed on its own. A
 * failed insert is logged as {@code audit.write.failed}, with the action and entity id only, and rethrown, which rolls
 * the change back with it.
 *
 * <p>{@link #recordRead} is {@link Propagation#REQUIRES_NEW}, because a read has no change to join. Whether a failed
 * read audit refuses the read is the caller's decision: a case view fails open, a document download fails closed.
 *
 * <p>The log line never carries the cause's message: Postgres echoes parameters into some of its errors, and a
 * parameter here can be a value row's applicant data.
 */
@Component
public class AuditTrail {

    private static final Logger LOG = LoggerFactory.getLogger(AuditTrail.class);

    private static final String INSERT_EVENT = """
        INSERT INTO deedpoll_audit_event
            (occurred_at, actor_id, actor_name, action, entity_type, entity_id, case_id, fields, detail)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb))
        RETURNING id
        """;

    private static final String INSERT_VALUE =
        "INSERT INTO deedpoll_audit_event_value (event_id, field, old_value, new_value) VALUES (?, ?, ?, ?)";

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public AuditTrail(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /**
     * Record a change, in the change's own transaction, with a value row for each field whose value changed.
     *
     * @throws org.springframework.transaction.IllegalTransactionStateException if no transaction is active
     * @throws IllegalStateException if a user-initiated write has no subject
     * @throws IllegalArgumentException for a read action, or for values on an entity no case owns
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(AuditEntry entry, Collection<AuditValue> values) {
        if (entry.action().isRead()) {
            throw new IllegalArgumentException(entry.action() + " is a read; record it with recordRead");
        }
        List<AuditValue> changed = values.stream().filter(AuditValue::changed).toList();
        if (!changed.isEmpty() && entry.caseId() == null) {
            // Value rows are disposed by case, so one with no case could never be disposed.
            throw new IllegalArgumentException(entry.action() + " has no case, so it may not carry values");
        }
        write(entry, changed);
    }

    /**
     * Record a read, committed in its own transaction, with no values.
     *
     * @throws IllegalStateException if the reader has no subject
     * @throws IllegalArgumentException for a write action
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordRead(AuditEntry entry) {
        if (!entry.action().isRead()) {
            throw new IllegalArgumentException(entry.action() + " is a write; record it with record");
        }
        write(entry, List.of());
    }

    private void write(AuditEntry entry, List<AuditValue> values) {
        if (!entry.actor().identified()) {
            throw new IllegalStateException(entry.action() + " was initiated by a user with no subject");
        }
        String detail = detail(entry);
        try {
            Long eventId = jdbc.query(connection -> {
                PreparedStatement statement = connection.prepareStatement(INSERT_EVENT);
                statement.setTimestamp(1, Timestamp.valueOf(AuditClock.now()));
                statement.setString(2, entry.actor().id());
                statement.setString(3, entry.actor().name());
                statement.setString(4, entry.action().name());
                statement.setString(5, entry.entityType());
                statement.setString(6, entry.entityId());
                statement.setString(7, entry.caseId());
                statement.setArray(8, connection.createArrayOf("text", entry.fields().toArray()));
                statement.setString(9, detail);
                return statement;
            }, (ResultSetExtractor<Long>) results -> results.next() ? results.getLong(1) : null);
            if (!values.isEmpty()) {
                jdbc.batchUpdate(INSERT_VALUE, values.stream()
                    .map(value -> new Object[] {eventId, value.field(), value.oldValue(), value.newValue()})
                    .toList());
            }
        } catch (RuntimeException failure) {
            LOG.error("audit.write.failed action={} entityId={} cause={}",
                      entry.action(), entry.entityId(), failure.getClass().getSimpleName());
            throw failure;
        }
    }

    private String detail(AuditEntry entry) {
        if (entry.detail().isEmpty()) {
            return null;
        }
        try {
            return json.writeValueAsString(entry.detail());
        } catch (JsonProcessingException impossible) {
            throw new IllegalStateException("A map of strings could not be written as JSON", impossible);
        }
    }
}
