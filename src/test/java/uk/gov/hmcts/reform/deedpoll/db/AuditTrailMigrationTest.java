package uk.gov.hmcts.reform.deedpoll.db;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import uk.gov.hmcts.reform.deedpoll.config.AbstractPostgresContainerTest;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What {@code V36__audit_trail.sql} lets through and what it refuses, against the migrated schema.
 *
 * <p>Every test runs in a transaction that is rolled back, which is the only way this class can clean up after itself:
 * the tables refuse the {@code DELETE} any other cleanup would need. A refused statement aborts the transaction, so a
 * test that asserts a refusal makes it the last statement it sends.
 *
 * <p>The rows are dated before 2000, far earlier than anything a committed test could write, so the cutoff below
 * cannot reach a row this class did not insert.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional
class AuditTrailMigrationTest extends AbstractPostgresContainerTest {

    private static final LocalDateTime OLD = LocalDateTime.of(1998, 3, 1, 9, 0);

    private static final LocalDateTime CUTOFF = LocalDateTime.of(1999, 1, 1, 0, 0);

    private static final LocalDateTime RECENT = LocalDateTime.of(1999, 6, 1, 9, 0);

    @Autowired
    private JdbcTemplate jdbc;

    private long event(String caseId, LocalDateTime occurredAt) {
        return jdbc.queryForObject("""
            INSERT INTO deedpoll_audit_event (occurred_at, actor_id, actor_name, action, entity_type, entity_id,
                                              case_id, fields)
            VALUES (?, 'subject-1', 'A Caseworker', 'CASE_UPDATED', 'DeedPoll', ?, ?, '{newForename}')
            RETURNING id
            """, Long.class, occurredAt, caseId, caseId);
    }

    private void value(long eventId, String field) {
        jdbc.update("INSERT INTO deedpoll_audit_event_value (event_id, field, old_value, new_value)"
                        + " VALUES (?, ?, 'Old', 'New')", eventId, field);
    }

    private int eventsWithId(long id) {
        return jdbc.queryForObject("SELECT count(*) FROM deedpoll_audit_event WHERE id = ?", Integer.class, id);
    }

    private int valuesOf(long eventId) {
        return jdbc.queryForObject("SELECT count(*) FROM deedpoll_audit_event_value WHERE event_id = ?",
                                   Integer.class, eventId);
    }

    @Test
    @DisplayName("an event and its values can be inserted, and an event's fields default to none")
    void insertsIntoBothTables() {
        long id = event("case-a", RECENT);
        value(id, "newForename");
        long caseless = jdbc.queryForObject("""
            INSERT INTO deedpoll_audit_event (occurred_at, actor_name, action, entity_type, entity_id, detail)
            VALUES (?, 'Deed poll service', 'GAZETTE_SETTINGS_CHANGED', 'GazetteSettings', '1', '{"outcome": "x"}')
            RETURNING id
            """, Long.class, RECENT);

        assertThat(jdbc.queryForMap("SELECT field, old_value, new_value FROM deedpoll_audit_event_value"
                                        + " WHERE event_id = ?", id))
            .isEqualTo(Map.of("field", "newForename", "old_value", "Old", "new_value", "New"));
        assertThat(jdbc.queryForObject("SELECT cardinality(fields) FROM deedpoll_audit_event WHERE id = ?",
                                       Integer.class, caseless)).isZero();
        assertThat(jdbc.queryForMap("SELECT actor_id, case_id FROM deedpoll_audit_event WHERE id = ?", caseless))
            .containsEntry("actor_id", null).containsEntry("case_id", null);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "UPDATE deedpoll_audit_event SET actor_name = 'Someone else' WHERE id = %d",
        "DELETE FROM deedpoll_audit_event WHERE id = %d",
        "UPDATE deedpoll_audit_event_value SET new_value = 'Rewritten' WHERE event_id = %d",
        "DELETE FROM deedpoll_audit_event_value WHERE event_id = %d",
    })
    @DisplayName("an UPDATE or DELETE of either table is refused")
    void refusesUpdateAndDelete(String statement) {
        long id = event("case-a", RECENT);
        value(id, "newForename");

        assertThatThrownBy(() -> jdbc.update(statement.formatted(id))).hasMessageContaining("append-only");
    }

    @ParameterizedTest
    @ValueSource(strings = {"deedpoll_audit_event", "deedpoll_audit_event_value"})
    @DisplayName("a TRUNCATE of either table is refused, even with the disposal setting on")
    void refusesTruncate(String table) {
        event("case-a", RECENT);
        jdbc.queryForObject("SELECT set_config('deedpoll.audit_disposal', 'on', true)", String.class);

        assertThatThrownBy(() -> jdbc.execute("TRUNCATE " + table + " CASCADE"))
            .hasMessageContaining("append-only");
    }

    @Test
    @DisplayName("disposing of a case's values removes that case's value rows and nothing else")
    void disposesOfOneCasesValues() {
        long first = event("case-a", RECENT);
        value(first, "newForename");
        value(first, "newSurname");
        long second = event("case-a", RECENT);
        value(second, "newForename");
        long other = event("case-b", RECENT);
        value(other, "newForename");

        Integer deleted = jdbc.queryForObject("SELECT deedpoll_audit_dispose_case_values('case-a')", Integer.class);

        assertThat(deleted).isEqualTo(3);
        assertThat(valuesOf(first)).isZero();
        assertThat(valuesOf(second)).isZero();
        assertThat(valuesOf(other)).isOne();
        // The events themselves hold no applicant data and are kept for their own, longer period.
        assertThat(eventsWithId(first)).isOne();
        assertThat(eventsWithId(second)).isOne();
    }

    @Test
    @DisplayName("disposing of events before a cutoff removes those events and their values, and nothing later")
    void disposesOfEventsBeforeTheCutoff() {
        long old = event("case-a", OLD);
        long recent = event("case-a", RECENT);
        value(old, "newForename");
        value(recent, "newForename");

        Integer deleted = jdbc.queryForObject("SELECT deedpoll_audit_dispose_events_before(?)", Integer.class,
                                              CUTOFF);

        assertThat(deleted).isEqualTo(1);
        assertThat(eventsWithId(old)).isZero();
        assertThat(valuesOf(old)).isZero();
        assertThat(eventsWithId(recent)).isOne();
        assertThat(valuesOf(recent)).isOne();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "SELECT deedpoll_audit_dispose_case_values('case-b')",
        "SELECT deedpoll_audit_dispose_events_before('1990-01-01')",
    })
    @DisplayName("after a disposal function returns, a plain DELETE in the same transaction is still refused")
    void leavesNoDisposalWindowOpen(String disposal) {
        long id = event("case-a", RECENT);
        value(id, "newForename");

        jdbc.queryForList(disposal);

        assertThat(jdbc.queryForObject("SELECT current_setting('deedpoll.audit_disposal', true)", String.class))
            .isNotEqualTo("on");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM deedpoll_audit_event_value WHERE event_id = ?", id))
            .hasMessageContaining("append-only");
    }

    @Test
    @DisplayName("both tables are indexed for the timeline's two questions: by case and by actor, over time")
    void indexesByCaseAndByActor() {
        List<String> definitions = jdbc.queryForList(
            "SELECT indexdef FROM pg_indexes WHERE tablename = 'deedpoll_audit_event'", String.class);

        assertThat(definitions)
            .anySatisfy(definition -> assertThat(definition).contains("(case_id, occurred_at)"))
            .anySatisfy(definition -> assertThat(definition).contains("(actor_id, occurred_at)"));
    }
}
