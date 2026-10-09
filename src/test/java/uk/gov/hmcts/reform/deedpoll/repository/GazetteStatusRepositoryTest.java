package uk.gov.hmcts.reform.deedpoll.repository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import uk.gov.hmcts.reform.deedpoll.config.AbstractPostgresContainerTest;
import uk.gov.hmcts.reform.deedpoll.domain.deedpoll.CasePaging;
import uk.gov.hmcts.reform.deedpoll.domain.gazette.GazetteBatchStatus;
import uk.gov.hmcts.reform.deedpoll.domain.gazette.GazetteMember;
import uk.gov.hmcts.reform.deedpoll.domain.gazette.GazetteRequestState;
import uk.gov.hmcts.reform.deedpoll.entity.DeedPoll;
import uk.gov.hmcts.reform.deedpoll.entity.GazetteNoticeBatch;
import uk.gov.hmcts.reform.deedpoll.entity.GazetteNoticeBatchCase;
import uk.gov.hmcts.reform.deedpoll.entity.GazetteNoticeBatchCaseId;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Real PostgreSQL runs every statement variant the status reads choose between. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional
class GazetteStatusRepositoryTest extends AbstractPostgresContainerTest {

    private static final LocalDateTime EARLIER = LocalDateTime.of(2026, 10, 6, 9, 0);
    private static final LocalDateTime LATER = EARLIER.plusHours(2);
    private static final String HASH = "a".repeat(64);

    @Autowired
    private GazetteStatusRepository statuses;

    @Autowired
    private GazetteNoticeBatchRepository batches;

    @Autowired
    private GazetteNoticeBatchCaseRepository batchCases;

    @Autowired
    private JdbcTemplate jdbc;

    @PersistenceContext
    private EntityManager entityManager;

    private UUID sent;
    private UUID notSent;
    private UUID historical;

    @BeforeEach
    void seed() {
        final DeedPoll first = persistedCase("2026/0001");
        final DeedPoll second = persistedCase("2026/0002");
        sent = request(GazetteRequestState.Sent, EARLIER);
        notSent = request(GazetteRequestState.NotSent, LATER);
        member(sent, second, "2026/0002");
        member(sent, first, "2026/0001");
        GazetteNoticeBatch batch = batches.saveAndFlush(batch());
        historical = batch.getId();
        GazetteNoticeBatchCase old = new GazetteNoticeBatchCase();
        old.setId(new GazetteNoticeBatchCaseId(historical, first.getId()));
        old.setReferenceNumber("2025/0999");
        batchCases.saveAndFlush(old);
    }

    @Test
    void listsEveryRequestNewestFirstWhenNoStateIsGiven() {
        Page<GazetteBatchStatus> page = statuses.list(new CasePaging(1, 25), null);

        assertThat(page.getTotalElements()).isEqualTo(2);
        assertThat(page.getContent()).extracting(GazetteBatchStatus::batchId).containsExactly(notSent, sent);
    }

    @Test
    void listsAndCountsOnlyTheRequestedState() {
        Page<GazetteBatchStatus> page = statuses.list(new CasePaging(1, 25), GazetteRequestState.Sent);

        assertThat(page.getTotalElements()).isEqualTo(1);
        assertThat(page.getContent()).singleElement().satisfies(status -> {
            assertThat(status.batchId()).isEqualTo(sent);
            assertThat(status.sentAt()).isNotNull();
        });
    }

    @Test
    void pagesTheUnfilteredListWithoutChangingTheTotal() {
        Page<GazetteBatchStatus> second = statuses.list(new CasePaging(2, 1), null);

        assertThat(second.getTotalElements()).isEqualTo(2);
        assertThat(second.getContent()).extracting(GazetteBatchStatus::batchId).containsExactly(sent);
    }

    @Test
    void readsARequestsMembersFromTheRequestLedgerInReferenceOrder() {
        Page<GazetteMember> members = statuses.members(sent, new CasePaging(1, 25));

        assertThat(members.getTotalElements()).isEqualTo(2);
        assertThat(members.getContent()).extracting(GazetteMember::referenceNumber)
            .containsExactly("2026/0001", "2026/0002");
    }

    @Test
    void readsAHistoricalBatchsMembersFromTheNoticeBatchTable() {
        Page<GazetteMember> members = statuses.members(historical, new CasePaging(1, 25));

        assertThat(members.getTotalElements()).isEqualTo(1);
        assertThat(members.getContent()).extracting(GazetteMember::referenceNumber).containsExactly("2025/0999");
    }

    private DeedPoll persistedCase(String reference) {
        DeedPoll deedPoll = new DeedPoll();
        deedPoll.setDateReceived(EARLIER.minusDays(30));
        deedPoll.setDisplayReference(reference);
        entityManager.persist(deedPoll);
        entityManager.flush();
        return deedPoll;
    }

    private UUID request(GazetteRequestState state, LocalDateTime submittedAt) {
        UUID id = UUID.randomUUID();
        boolean isSent = state == GazetteRequestState.Sent;
        jdbc.update("""
            INSERT INTO deedpoll_gazette_request (id, request_id, payload_hash, selection_fingerprint,
                submitted_by_id, submitted_by_name, submitted_at, recipient_email, recipient_revision,
                adult_count, minor_count, template_id, reply_to_id, retention_weeks, confirm_email, owner,
                lease_until, state, send_started_at, settled_at, failure_code, provider_notification_id)
            VALUES (?, ?, ?, 'fingerprint', 'idam-subject', 'Ada Administrator', ?, 'gazette@example.com', 1,
                1, 0, ?, ?, 4, true, ?, ?, ?, ?, ?, ?, ?)
            """, id, UUID.randomUUID(), HASH, submittedAt, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
            submittedAt.plusMinutes(5), state.name(), isSent ? submittedAt : null, submittedAt.plusMinutes(1),
            isSent ? null : "PREPARATION_FAILED", isSent ? UUID.randomUUID() : null);
        return id;
    }

    private void member(UUID requestId, DeedPoll deedPoll, String reference) {
        jdbc.update("INSERT INTO deedpoll_gazette_request_case (batch_id, deed_poll_id, reference_number, kind) "
            + "VALUES (?, ?, ?, 'adult')", requestId, deedPoll.getId(), reference);
    }

    private GazetteNoticeBatch batch() {
        GazetteNoticeBatch batch = new GazetteNoticeBatch();
        batch.setSentAt(EARLIER.minusDays(7));
        batch.setSentById("idam-subject");
        batch.setSentByName("Ada Administrator");
        batch.setRequestId(UUID.randomUUID());
        batch.setCaseCount(1);
        return batch;
    }
}
