package uk.gov.hmcts.reform.deedpoll.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import uk.gov.hmcts.reform.deedpoll.entity.GazetteNoticeBatchCase;
import uk.gov.hmcts.reform.deedpoll.entity.GazetteNoticeBatchCaseId;

import java.util.List;
import java.util.UUID;

/** Frozen case references scoped to the batch that sent them. */
public interface GazetteNoticeBatchCaseRepository
    extends JpaRepository<GazetteNoticeBatchCase, GazetteNoticeBatchCaseId> {

    List<GazetteNoticeBatchCase> findByIdBatchId(UUID batchId);
}
