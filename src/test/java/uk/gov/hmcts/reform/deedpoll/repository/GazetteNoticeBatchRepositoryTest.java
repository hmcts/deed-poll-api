package uk.gov.hmcts.reform.deedpoll.repository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.PersistenceException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import uk.gov.hmcts.reform.deedpoll.config.AbstractPostgresContainerTest;
import uk.gov.hmcts.reform.deedpoll.entity.DeedPoll;
import uk.gov.hmcts.reform.deedpoll.entity.GazetteNoticeBatch;
import uk.gov.hmcts.reform.deedpoll.entity.GazetteNoticeBatchCase;
import uk.gov.hmcts.reform.deedpoll.entity.GazetteNoticeBatchCaseId;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Real PostgreSQL proves the audit constraints and reads, including the frozen reference. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional
class GazetteNoticeBatchRepositoryTest extends AbstractPostgresContainerTest {

    private static final LocalDateTime SENT_AT = LocalDateTime.of(2026, 10, 6, 12, 30);

    @Autowired
    private GazetteNoticeBatchRepository batches;

    @Autowired
    private GazetteNoticeBatchCaseRepository cases;

    @PersistenceContext
    private EntityManager entityManager;

    @Test
    void roundTripsAttributionAndFrozenReferencesScopedToBatch() {
        final GazetteNoticeBatch batch = batches.saveAndFlush(batch(UUID.randomUUID()));
        final DeedPoll deedPoll = persistedCase();
        final GazetteNoticeBatchCase member = member(batch.getId(), deedPoll.getId());
        cases.saveAndFlush(member);
        final GazetteNoticeBatch other = batches.saveAndFlush(batch(UUID.randomUUID()));
        cases.saveAndFlush(member(other.getId(), deedPoll.getId()));
        deedPoll.setDisplayReference("2026/AMENDED");
        entityManager.flush();
        entityManager.clear();

        GazetteNoticeBatch reloaded = batches.findByRequestId(batch.getRequestId()).orElseThrow();
        assertThat(reloaded.getId()).isEqualTo(batch.getId());
        assertThat(reloaded.getSentAt()).isEqualTo(SENT_AT);
        assertThat(reloaded.getSentById()).isEqualTo("idam-subject");
        assertThat(reloaded.getSentByName()).isEqualTo("Ada Administrator");
        assertThat(reloaded.getCaseCount()).isEqualTo(1);
        assertThat(reloaded.getRequestId()).isEqualTo(batch.getRequestId());
        assertThat(cases.findByIdBatchId(batch.getId()))
            .singleElement().satisfies(row -> {
                assertThat(row.getId()).isEqualTo(member.getId());
                assertThat(row.getReferenceNumber()).isEqualTo("2026/ORIGINAL");
            });
        assertThat(cases.findByIdBatchId(other.getId())).hasSize(1);
        assertThat(cases.findByIdBatchId(UUID.randomUUID())).isEmpty();
        assertThat(batches.findByRequestId(UUID.randomUUID())).isEmpty();
        assertThat(entityManager.find(DeedPoll.class, deedPoll.getId()).getDisplayReference())
            .isEqualTo("2026/AMENDED");
    }

    @Test
    void databaseRefusesRepeatedRequestIdentity() {
        UUID requestId = UUID.randomUUID();
        batches.saveAndFlush(batch(requestId));
        entityManager.clear();
        // Persist a distinct batch directly: neither a repository pre-check nor merge can mask the index.
        entityManager.persist(batch(requestId));
        assertThatThrownBy(entityManager::flush)
            .isInstanceOf(PersistenceException.class)
            .hasMessageContaining("deedpoll_gazette_notice_batch_request_id_key");
    }

    @Test
    void databaseRefusesRepeatedCaseWithinBatch() {
        GazetteNoticeBatch batch = batches.saveAndFlush(batch(UUID.randomUUID()));
        DeedPoll deedPoll = persistedCase();
        cases.saveAndFlush(member(batch.getId(), deedPoll.getId()));
        entityManager.clear();
        entityManager.persist(member(batch.getId(), deedPoll.getId()));
        assertThatThrownBy(entityManager::flush)
            .isInstanceOf(PersistenceException.class)
            .hasMessageContaining("deedpoll_gazette_notice_batch_case_pkey");
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void databaseRequiresBothParents(boolean missingBatch) {
        GazetteNoticeBatch batch = batches.saveAndFlush(batch(UUID.randomUUID()));
        DeedPoll deedPoll = persistedCase();
        entityManager.flush();
        entityManager.persist(member(missingBatch ? UUID.randomUUID() : batch.getId(),
                                     missingBatch ? deedPoll.getId() : UUID.randomUUID().toString()));
        assertThatThrownBy(entityManager::flush)
            .isInstanceOf(PersistenceException.class)
            .hasMessageContaining("foreign key constraint");
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void deletingParentCannotEraseAuditMembership(boolean deleteBatch) {
        GazetteNoticeBatch batch = batches.saveAndFlush(batch(UUID.randomUUID()));
        DeedPoll deedPoll = persistedCase();
        cases.saveAndFlush(member(batch.getId(), deedPoll.getId()));
        entityManager.remove(deleteBatch ? batch : deedPoll);
        assertThatThrownBy(entityManager::flush)
            .isInstanceOf(PersistenceException.class)
            .hasMessageContaining("foreign key constraint");
    }

    @Test
    void mappedUpdatesCannotRewriteAuditEvidence() {
        final GazetteNoticeBatch batch = batches.saveAndFlush(batch(UUID.randomUUID()));
        final UUID requestId = batch.getRequestId();
        final DeedPoll deedPoll = persistedCase();
        cases.saveAndFlush(member(batch.getId(), deedPoll.getId()));
        GazetteNoticeBatchCase member = cases.findById(
            new GazetteNoticeBatchCaseId(batch.getId(), deedPoll.getId())).orElseThrow();
        member.setReferenceNumber("REWRITTEN");
        batch.setSentAt(SENT_AT.plusDays(1));
        batch.setSentById("different-subject");
        batch.setSentByName("Different name");
        batch.setRequestId(UUID.randomUUID());
        batch.setCaseCount(99);
        entityManager.flush();
        entityManager.clear();

        GazetteNoticeBatch reloaded = batches.findByRequestId(requestId).orElseThrow();
        assertThat(reloaded.getSentAt()).isEqualTo(SENT_AT);
        assertThat(reloaded.getSentById()).isEqualTo("idam-subject");
        assertThat(reloaded.getSentByName()).isEqualTo("Ada Administrator");
        assertThat(reloaded.getCaseCount()).isEqualTo(1);
        assertThat(cases.findById(member.getId()).orElseThrow().getReferenceNumber())
            .isEqualTo("2026/ORIGINAL");
    }

    private DeedPoll persistedCase() {
        DeedPoll deedPoll = new DeedPoll();
        deedPoll.setDateReceived(SENT_AT.minusDays(30));
        deedPoll.setDisplayReference("2026/ORIGINAL");
        entityManager.persist(deedPoll);
        return deedPoll;
    }

    private GazetteNoticeBatch batch(UUID requestId) {
        GazetteNoticeBatch batch = new GazetteNoticeBatch();
        batch.setSentAt(SENT_AT);
        batch.setSentById("idam-subject");
        batch.setSentByName("Ada Administrator");
        batch.setRequestId(requestId);
        batch.setCaseCount(1);
        return batch;
    }

    private GazetteNoticeBatchCase member(UUID batchId, String deedPollId) {
        GazetteNoticeBatchCase member = new GazetteNoticeBatchCase();
        member.setId(new GazetteNoticeBatchCaseId(batchId, deedPollId));
        member.setReferenceNumber("2026/ORIGINAL");
        return member;
    }
}
