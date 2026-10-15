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
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import uk.gov.hmcts.reform.deedpoll.config.AbstractPostgresContainerTest;
import uk.gov.hmcts.reform.deedpoll.domain.deedpoll.DeedPollPatch;
import uk.gov.hmcts.reform.deedpoll.domain.deedpoll.DeedPollService;
import uk.gov.hmcts.reform.deedpoll.domain.deedpoll.WriteContext;
import uk.gov.hmcts.reform.deedpoll.entity.DeedPoll;
import uk.gov.hmcts.reform.deedpoll.repository.DeedPollRepository;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A case update whose audit insert fails is not committed (decision 7).
 *
 * <p>The failure is a real one, not a mock: a subject longer than {@code actor_id}'s 255 characters, which only the
 * audit table refuses -- the case itself stores the display name, never the subject. Not {@code @Transactional},
 * because the claim is about what commits. The case is removed afterwards; its audit rows stay, as V36 requires,
 * under an id no other test uses.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class AuditFailureRollbackTest extends AbstractPostgresContainerTest {

    private static final String RECEIVED = "2026-07-01T00:00:00.000Z";

    @Autowired
    private DeedPollService cases;

    @Autowired
    private DeedPollRepository deedPolls;

    @Autowired
    private JdbcTemplate jdbc;

    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();

    private String caseId;

    @BeforeEach
    void createCase() {
        logs.start();
        ((Logger) LoggerFactory.getLogger(AuditTrail.class)).addAppender(logs);
        caseId = cases.createDeedPoll(DeedPollPatch.of(Map.of("dateReceived", RECEIVED, "newForename", "Ada")))
            .deedPoll().getId();
    }

    @AfterEach
    void removeCase() {
        ((Logger) LoggerFactory.getLogger(AuditTrail.class)).detachAppender(logs);
        deedPolls.deleteById(caseId);
    }

    @Test
    @DisplayName("a failed audit insert rolls the case update back with it")
    void failedAuditInsertRollsTheUpdateBack() {
        WriteContext unrecordable = new WriteContext(false, "B Caseworker", "s".repeat(256));

        assertThatThrownBy(() -> cases.updateDeedPoll(
            caseId, DeedPollPatch.of(Map.of("newForename", "Bea")), unrecordable))
            .isInstanceOf(DataAccessException.class);

        DeedPoll stored = deedPolls.findById(caseId).orElseThrow();
        assertThat(stored.getNewForename()).isEqualTo("Ada");
        assertThat(stored.getModifiedBy()).isNotEqualTo("B Caseworker");
        assertThat(jdbc.queryForList(
            "SELECT action FROM deedpoll_audit_event WHERE case_id = ? ORDER BY id", String.class, caseId))
            .containsExactly("CASE_CREATED");
        assertThat(logs.list).extracting(ILoggingEvent::getFormattedMessage)
            .anyMatch(line -> line.startsWith("audit.write.failed action=CASE_UPDATED entityId=" + caseId));
    }
}
