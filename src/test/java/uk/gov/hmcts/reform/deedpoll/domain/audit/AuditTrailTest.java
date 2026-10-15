package uk.gov.hmcts.reform.deedpoll.domain.audit;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import uk.gov.hmcts.reform.deedpoll.config.AbstractPostgresContainerTest;
import uk.gov.hmcts.reform.deedpoll.domain.auth.CurrentUser;
import uk.gov.hmcts.reform.deedpoll.domain.deedpoll.WriteContext;
import uk.gov.hmcts.reform.deedpoll.entity.enums.DeedPollType;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link AuditTrail} against the migrated schema.
 *
 * <p>Every write test runs in a transaction that is rolled back, because V36 refuses the {@code DELETE} any other
 * cleanup would need. The one exception is {@link #readCommitsOnItsOwn()}, whose point is that its row outlives the
 * caller's rollback: it stays, under an entity id no other test uses, and every assertion here is scoped to the ids
 * its own test minted.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional
class AuditTrailTest extends AbstractPostgresContainerTest {

    private static final String SUBJECT = "caseworker-subject";
    private static final WriteContext CASEWORKER = new WriteContext(false, "A Caseworker", SUBJECT);

    @Autowired
    private AuditTrail audit;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactions;

    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();

    private String caseId;

    @BeforeEach
    void captureLogs() {
        caseId = UUID.randomUUID().toString();
        logs.start();
        ((Logger) LoggerFactory.getLogger(AuditTrail.class)).addAppender(logs);
    }

    @AfterEach
    void releaseLogs() {
        ((Logger) LoggerFactory.getLogger(AuditTrail.class)).detachAppender(logs);
    }

    @Test
    @DisplayName("writes the actor, the action, the ids, the field names and the allowlisted detail")
    void writesTheEvent() {
        audit.record(caseEntry(AuditAction.CASE_UPDATED)
                         .withFields(List.of("status", "newForename"))
                         .withDetail("fromState", "Received")
                         .withDetail("toState", "Registered"),
                     List.of());

        Map<String, Object> row = jdbc.queryForMap("""
            SELECT actor_id, actor_name, action, entity_type, entity_id, case_id,
                   array_to_string(fields, ',') AS fields, detail ->> 'fromState' AS from_state,
                   detail ->> 'toState' AS to_state, occurred_at
            FROM deedpoll_audit_event WHERE case_id = ?
            """, caseId);
        assertThat(row)
            .containsEntry("actor_id", SUBJECT)
            .containsEntry("actor_name", "A Caseworker")
            .containsEntry("action", "CASE_UPDATED")
            .containsEntry("entity_type", "DeedPoll")
            .containsEntry("entity_id", caseId)
            .containsEntry("case_id", caseId)
            .containsEntry("fields", "status,newForename")
            .containsEntry("from_state", "Received")
            .containsEntry("to_state", "Registered");
        assertThat(row.get("occurred_at")).isNotNull();
    }

    @Test
    @DisplayName("writes a value row only for a field whose value changed, in decision 5's encoding")
    void writesOnlyChangedValues() {
        audit.record(caseEntry(AuditAction.CASE_UPDATED).withFields(List.of("type", "dateReceived", "isWelsh", "x")),
                     List.of(AuditValue.of("type", DeedPollType.Minor, DeedPollType.Adult),
                             AuditValue.created("dateReceived", LocalDate.of(2026, 10, 5)),
                             AuditValue.of("isWelsh", true, null),
                             AuditValue.of("unchanged", "Ada", "Ada")));

        assertThat(values()).containsExactlyInAnyOrder(
            List.of("type", "Minor", "Adult"),
            Arrays.asList("dateReceived", null, "2026-10-05"),
            Arrays.asList("isWelsh", "true", null));
    }

    @Test
    @DisplayName("writes no value rows at all when nothing changed")
    void writesNoValuesWhenNothingChanged() {
        audit.record(caseEntry(AuditAction.CASE_UPDATED).withFields(List.of("newForename")),
                     List.of(AuditValue.of("newForename", "Ada", "Ada")));

        assertThat(eventsForTheCase()).isEqualTo(1);
        assertThat(values()).isEmpty();
    }

    @Test
    @DisplayName("records an explicitly system-initiated write as the service account, with no subject")
    void recordsASystemWrite() {
        audit.record(AuditEntry.of(AuditAction.CASE_UPDATED, AuditActor.system(), "DeedPoll", caseId, caseId),
                     List.of());

        assertThat(jdbc.queryForMap("SELECT actor_id, actor_name FROM deedpoll_audit_event WHERE case_id = ?", caseId))
            .containsEntry("actor_id", null)
            .containsEntry("actor_name", CurrentUser.SERVICE_ACCOUNT_NAME);
    }

    @Test
    @DisplayName("refuses a user write with no subject, and writes nothing")
    void refusesAUserWriteWithNoSubject() {
        AuditEntry entry = AuditEntry.of(AuditAction.CASE_UPDATED, AuditActor.of(WriteContext.DEFAULT),
                                         "DeedPoll", caseId, caseId);

        assertThatThrownBy(() -> audit.record(entry, List.of())).isInstanceOf(IllegalStateException.class);
        assertThat(eventsForTheCase()).isZero();
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("refuses a write outside a transaction, so a change and its audit row cannot commit apart")
    void refusesAWriteOutsideATransaction() {
        assertThatThrownBy(() -> audit.record(caseEntry(AuditAction.CASE_UPDATED), List.of()))
            .isInstanceOf(IllegalTransactionStateException.class);
        assertThat(eventsForTheCase()).isZero();
    }

    @Test
    @DisplayName("logs audit.write.failed with the action and entity id only, and rethrows")
    void logsAndRethrowsAFailedInsert() {
        String overlong = "f".repeat(129);

        assertThatThrownBy(() -> audit.record(caseEntry(AuditAction.CASE_UPDATED),
                                              List.of(AuditValue.created(overlong, "Private Applicant Name"))))
            .isInstanceOf(RuntimeException.class);

        assertThat(logs.list).singleElement().satisfies(event -> {
            assertThat(event.getFormattedMessage())
                .startsWith("audit.write.failed")
                .contains("CASE_UPDATED", caseId)
                .doesNotContain("Private Applicant Name", overlong);
            assertThat(event.getThrowableProxy()).isNull();
        });
    }

    @Test
    @DisplayName("refuses value rows for an entity no case owns, since nothing could ever dispose of them")
    void refusesValuesWithoutACase() {
        AuditEntry entry = AuditEntry.of(AuditAction.SAVED_QUERY_UPDATED, AuditActor.of(CASEWORKER), "SavedQuery",
                                         UUID.randomUUID().toString(), null);

        assertThatThrownBy(() -> audit.record(entry, List.of(AuditValue.of("name", "Old", "New"))))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("refuses a detail key outside the allowlist")
    void refusesAnUnlistedDetailKey() {
        assertThatThrownBy(() -> caseEntry(AuditAction.CASE_UPDATED).withDetail("newForename", "Ada"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("keeps reads and writes apart: a read action is not a write, and a write action is not a read")
    void keepsReadsAndWritesApart() {
        assertThatThrownBy(() -> audit.record(caseEntry(AuditAction.CASE_VIEWED), List.of()))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> audit.recordRead(caseEntry(AuditAction.CASE_UPDATED)))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("commits a read in its own transaction, which outlives the caller's rollback")
    void readCommitsOnItsOwn() {
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            audit.recordRead(caseEntry(AuditAction.CASE_VIEWED));
            status.setRollbackOnly();
        });

        assertThat(eventsForTheCase()).isEqualTo(1);
        assertThat(values()).isEmpty();
    }

    private AuditEntry caseEntry(AuditAction action) {
        return AuditEntry.of(action, AuditActor.of(CASEWORKER), "DeedPoll", caseId, caseId);
    }

    private int eventsForTheCase() {
        return jdbc.queryForObject("SELECT count(*) FROM deedpoll_audit_event WHERE case_id = ?",
                                   Integer.class, caseId);
    }

    private List<List<String>> values() {
        return jdbc.query("""
            SELECT v.field, v.old_value, v.new_value FROM deedpoll_audit_event_value v
            JOIN deedpoll_audit_event e ON e.id = v.event_id WHERE e.case_id = ?
            """, (row, index) -> Arrays.asList(row.getString(1), row.getString(2), row.getString(3)), caseId);
    }
}
