package uk.gov.hmcts.reform.deedpoll.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import uk.gov.hmcts.reform.deedpoll.entity.GazetteNoticeBatchCase;
import uk.gov.hmcts.reform.deedpoll.entity.GazetteNoticeBatchCaseId;

import java.util.List;
import java.util.UUID;

/** Frozen case references scoped to the batch that sent them. */
public interface GazetteNoticeBatchCaseRepository
    extends JpaRepository<GazetteNoticeBatchCase, GazetteNoticeBatchCaseId> {

    List<GazetteNoticeBatchCase> findByIdBatchId(UUID batchId);

    /** Limits each batch in PostgreSQL, before any membership is materialised in the application. */
    @Query(value = """
        SELECT member.* FROM deedpoll_gazette_notice_batch batch
        CROSS JOIN LATERAL (
            SELECT c.* FROM deedpoll_gazette_notice_batch_case c WHERE c.batch_id = batch.id
            ORDER BY c.reference_number COLLATE "C", c.deed_poll_id LIMIT :limit
        ) member
        WHERE batch.id IN (:batchIds)
        ORDER BY member.batch_id, member.reference_number COLLATE "C", member.deed_poll_id
        """, nativeQuery = true)
    List<GazetteNoticeBatchCase> referencePreview(@Param("batchIds") List<UUID> batchIds,
                                                @Param("limit") int limit);
}
