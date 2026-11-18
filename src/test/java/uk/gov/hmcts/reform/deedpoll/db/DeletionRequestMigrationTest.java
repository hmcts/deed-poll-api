package uk.gov.hmcts.reform.deedpoll.db;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import uk.gov.hmcts.reform.deedpoll.config.AbstractPostgresContainerTest;
import uk.gov.hmcts.reform.deedpoll.domain.deletion.DeletionFingerprint;
import uk.gov.hmcts.reform.deedpoll.domain.deletion.DeletionNote;
import uk.gov.hmcts.reform.deedpoll.domain.deletion.DeletionReason;
import uk.gov.hmcts.reform.deedpoll.domain.deletion.DeletionRequest;
import uk.gov.hmcts.reform.deedpoll.domain.deletion.DeletionStatus;
import uk.gov.hmcts.reform.deedpoll.domain.deletion.DeletionStep;
import uk.gov.hmcts.reform.deedpoll.domain.deletion.DeletionStepKind;
import uk.gov.hmcts.reform.deedpoll.domain.deletion.DeletionStepState;
import uk.gov.hmcts.reform.deedpoll.entity.DeedPoll;
import uk.gov.hmcts.reform.deedpoll.repository.DeedPollRepository;
import uk.gov.hmcts.reform.deedpoll.repository.DeletionRequestRepository;
import uk.gov.hmcts.reform.deedpoll.repository.DeletionRequestRepository.NewDeletionRequest;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code V48__application_deletion_requests.sql} and {@link DeletionRequestRepository}, against real Postgres.
 *
 * <p>Not transactional: each statement commits on its own, so a refusal is observed as the database raises it and
 * the next statement is unaffected. Every test mints its own case ids, and neither table refuses a delete, so nothing
 * is shared.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class DeletionRequestMigrationTest extends AbstractPostgresContainerTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 18, 10, 15, 30, 123_000_000);
    private static final DeletionNote NOTE = DeletionNote.parse("Duplicate of an application made the same day");

    @Autowired
    private DeletionRequestRepository requests;

    @Autowired
    private DeedPollRepository deedPolls;

    @Autowired
    private JdbcTemplate jdbc;

    private static NewDeletionRequest request(String caseId, UUID key, String ccdReference) {
        return new NewDeletionRequest(UUID.randomUUID(), caseId, key, DeletionReason.DUPLICATE, NOTE,
                                      DeletionFingerprint.of(DeletionReason.DUPLICATE, NOTE, NOW), NOW,
                                      "dm-subject", "Dee Manager", NOW, ccdReference);
    }

    private static String caseId() {
        return "case-" + UUID.randomUUID();
    }

    private int insertRaw(String caseId, String reason, String note, String status) {
        return jdbc.update("""
            insert into deedpoll_deletion_request (id, case_id, idempotency_key, reason, note, fingerprint,
                expected_last_changed_on, requested_by_subject, requested_by_name, requested_at, status)
            values (?, ?, ?, ?, ?, ?, ?, 'dm-subject', 'Dee Manager', ?, ?)
            """, UUID.randomUUID(), caseId, UUID.randomUUID(), reason, note, "0".repeat(64), NOW, NOW, status);
    }

    @Test
    @DisplayName("a request round-trips Pending, due at once, unclaimed, with its fixed half as admitted")
    void roundTrips() {
        NewDeletionRequest admitted = request(caseId(), UUID.randomUUID(), "1234567890123456");
        assertThat(requests.insert(admitted)).isTrue();

        DeletionRequest stored = requests.findById(admitted.id()).orElseThrow();
        assertThat(stored.caseId()).isEqualTo(admitted.caseId());
        assertThat(stored.idempotencyKey()).isEqualTo(admitted.idempotencyKey());
        assertThat(stored.reason()).isEqualTo(DeletionReason.DUPLICATE);
        assertThat(stored.note()).isEqualTo(NOTE);
        assertThat(stored.fingerprint()).isEqualTo(admitted.fingerprint());
        assertThat(stored.expectedLastChangedOn()).isEqualTo(NOW);
        assertThat(stored.requestedBySubject()).isEqualTo("dm-subject");
        assertThat(stored.requestedByName()).isEqualTo("Dee Manager");
        assertThat(stored.ccdCaseReference()).isEqualTo("1234567890123456");
        assertThat(stored.status()).isEqualTo(DeletionStatus.Pending);
        assertThat(stored.attempts()).isZero();
        assertThat(stored.owner()).isNull();
        assertThat(stored.leaseUntil()).isNull();
        assertThat(stored.nextAttemptAt()).isEqualTo(NOW);
        assertThat(stored.completedAt()).isNull();
        assertThat(stored.failure()).isNull();
        assertThat(stored.toString()).doesNotContain("same day");
        assertThat(requests.findByCase(admitted.caseId())).contains(stored);
    }

    @Test
    @DisplayName("one request per case, whatever its key; the same key on another case is another request")
    void onePerCase() {
        String caseId = caseId();
        UUID key = UUID.randomUUID();
        NewDeletionRequest first = request(caseId, key, null);
        assertThat(requests.insert(first)).isTrue();

        assertThat(requests.insert(request(caseId, key, null))).isFalse();
        assertThat(requests.insert(request(caseId, UUID.randomUUID(), null))).isFalse();
        assertThat(requests.findByCase(caseId).orElseThrow().id()).isEqualTo(first.id());

        assertThat(requests.insert(request(caseId(), key, null))).isTrue();
        assertThatThrownBy(() -> insertRaw(caseId, "DUPLICATE", NOTE.value(), "Pending"))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("the request outlives its case: no foreign key, so deleting the case leaves it")
    void outlivesTheCase() {
        DeedPoll deedPoll = new DeedPoll();
        deedPoll.setDateReceived(NOW);
        deedPoll = deedPolls.saveAndFlush(deedPoll);
        String caseId = deedPoll.getId();
        NewDeletionRequest admitted = request(caseId, UUID.randomUUID(), null);
        requests.insert(admitted);
        requests.insertStep(UUID.randomUUID(), admitted.id(), DeletionStepKind.LOCAL_DOCUMENT, "doc-1", "key/doc-1");

        assertThat(jdbc.update("delete from \"deedpoll_deedpoll\" where id = ?", caseId)).isOne();

        assertThat(requests.findByCase(caseId)).isPresent();
        assertThat(requests.stepsOf(admitted.id())).hasSize(1);
        assertThat(jdbc.queryForObject("""
            select count(*) from information_schema.table_constraints
             where table_name in ('deedpoll_deletion_request', 'deedpoll_deletion_step')
               and constraint_type = 'FOREIGN KEY'
            """, Integer.class)).isOne();
    }

    @Test
    @DisplayName("the reason is one of two, and the status one of five")
    void closedVocabularies() {
        assertThatThrownBy(() -> insertRaw(caseId(), "VOID", NOTE.value(), "Pending"))
            .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertRaw(caseId(), "duplicate", NOTE.value(), "Pending"))
            .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertRaw(caseId(), "DUPLICATE", NOTE.value(), "Voided"))
            .isInstanceOf(DataIntegrityViolationException.class);
        for (String reason : new String[] {"CREATED_IN_ERROR", "DUPLICATE"}) {
            assertThat(insertRaw(caseId(), reason, NOTE.value(), "Pending")).isOne();
        }
        for (DeletionStatus status : DeletionStatus.values()) {
            if (status != DeletionStatus.Completed) {
                assertThat(insertRaw(caseId(), "DUPLICATE", NOTE.value(), status.name())).isOne();
            }
        }
        // Completed needs its completion time, which the raw insert does not give.
        assertThatThrownBy(() -> insertRaw(caseId(), "DUPLICATE", NOTE.value(), "Completed"))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("the note is checked by the database as DeletionNote checks it")
    void noteConstraint() {
        for (String note : new String[] {"too short", " " + NOTE.value(), NOTE.value() + " ", "a".repeat(501),
            "Created\tin error", "Created in\nerror", "Created" + (char) 0x85 + " in error"}) {
            assertThatThrownBy(() -> insertRaw(caseId(), "DUPLICATE", note, "Pending"))
                .as(note.length() + " characters").isInstanceOf(DataIntegrityViolationException.class);
        }
        assertThat(insertRaw(caseId(), "DUPLICATE", "a".repeat(10), "Pending")).isOne();
        assertThat(insertRaw(caseId(), "DUPLICATE", "😀".repeat(500), "Pending")).isOne();
    }

    @Test
    @DisplayName("admission's half is immutable, the worker's half is not, and a Completed request is frozen")
    void immutability() {
        NewDeletionRequest admitted = request(caseId(), UUID.randomUUID(), "1234567890123456");
        requests.insert(admitted);
        UUID id = admitted.id();

        for (String change : new String[] {"case_id = 'other'", "idempotency_key = gen_random_uuid()",
            "reason = 'CREATED_IN_ERROR'", "note = 'A different reason entirely'", "fingerprint = repeat('1', 64)",
            "expected_last_changed_on = now()", "requested_by_subject = 'someone-else'",
            "requested_by_name = 'Someone Else'", "requested_at = now()", "ccd_case_reference = null"}) {
            assertThatThrownBy(() -> jdbc.update("update deedpoll_deletion_request set " + change + " where id = ?",
                                                 id))
                .as(change).isInstanceOf(DataAccessException.class)
                .hasMessageContaining("A deletion request cannot be rewritten");
        }

        assertThat(jdbc.update("""
            update deedpoll_deletion_request set status = 'InProgress', attempts = 1, owner = gen_random_uuid(),
                   lease_until = now() where id = ?
            """, id)).isOne();
        assertThatThrownBy(() -> jdbc.update(
            "update deedpoll_deletion_request set owner = null where id = ?", id))
            .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(
            "update deedpoll_deletion_request set failure = 'CCD_NOT_DISPOSED' where id = ?", id))
            .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(
            "update deedpoll_deletion_request set status = 'Completed', completed_at = now() where id = ?", id))
            .as("a claimed request cannot complete").isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbc.update("""
            update deedpoll_deletion_request set status = 'NeedsAttention', failure = 'CCD_NOT_DISPOSED',
                   owner = null, lease_until = null where id = ?
            """, id)).isOne();
        assertThatThrownBy(() -> jdbc.update(
            "update deedpoll_deletion_request set failure = 'not a code' where id = ?", id))
            .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbc.update("""
            update deedpoll_deletion_request set status = 'Completed', failure = null, completed_at = now()
             where id = ?
            """, id)).isOne();

        assertThatThrownBy(() -> jdbc.update(
            "update deedpoll_deletion_request set status = 'NeedsAttention', completed_at = null where id = ?", id))
            .isInstanceOf(DataAccessException.class).hasMessageContaining("A deletion request cannot be rewritten");
        assertThatThrownBy(() -> jdbc.update(
            "update deedpoll_deletion_request set attempts = 9 where id = ?", id))
            .isInstanceOf(DataAccessException.class).hasMessageContaining("A deletion request cannot be rewritten");
        assertThat(requests.findById(id).orElseThrow().status()).isEqualTo(DeletionStatus.Completed);
    }

    @Test
    @DisplayName("an inventory line keeps its locator until Done, is unique per item, and goes with its request")
    void steps() {
        NewDeletionRequest admitted = request(caseId(), UUID.randomUUID(), "1234567890123456");
        requests.insert(admitted);
        UUID stepId = UUID.randomUUID();
        requests.insertStep(stepId, admitted.id(), DeletionStepKind.CDAM_DOCUMENT, "doc-1", UUID.randomUUID()
            .toString());
        requests.insertStep(UUID.randomUUID(), admitted.id(), DeletionStepKind.CCD_DISPOSAL, null,
                            "1234567890123456");

        assertThat(requests.stepsOf(admitted.id())).extracting(DeletionStep::kind)
            .containsExactly(DeletionStepKind.CCD_DISPOSAL, DeletionStepKind.CDAM_DOCUMENT);
        assertThat(requests.stepsOf(admitted.id())).extracting(DeletionStep::state)
            .containsOnly(DeletionStepState.Pending);
        assertThat(requests.stepsOf(admitted.id()).get(1).toString()).doesNotContain("doc-1");

        assertThatThrownBy(() -> requests.insertStep(UUID.randomUUID(), admitted.id(),
                                                     DeletionStepKind.CDAM_DOCUMENT, "doc-1", "again"))
            .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> requests.insertStep(UUID.randomUUID(), admitted.id(),
                                                     DeletionStepKind.LOCAL_DOCUMENT, "doc-2", null))
            .as("unverified work must know what to remove").isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("""
            insert into deedpoll_deletion_step (id, request_id, kind, locator, state)
            values (?, ?, 'EVERYTHING', 'x', 'Pending')
            """, UUID.randomUUID(), admitted.id())).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> requests.insertStep(UUID.randomUUID(), UUID.randomUUID(),
                                                     DeletionStepKind.OWNED_STAGE, "p", "p"))
            .as("a step needs its request").isInstanceOf(DataIntegrityViolationException.class);

        assertThatThrownBy(() -> jdbc.update(
            "update deedpoll_deletion_step set state = 'Done' where id = ?", stepId))
            .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbc.update("""
            update deedpoll_deletion_step set state = 'Done', outcome = 'DISPOSED', locator = null,
                   completed_at = now() where id = ?
            """, stepId)).isOne();

        jdbc.update("delete from deedpoll_deletion_request where id = ?", admitted.id());
        assertThat(requests.stepsOf(admitted.id())).isEmpty();
    }
}
