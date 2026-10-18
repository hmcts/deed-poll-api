package uk.gov.hmcts.reform.deedpoll.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import uk.gov.hmcts.reform.deedpoll.entity.GazetteNoticeBatch;

import java.util.Optional;
import java.util.UUID;

/** Audit batches, with a globally unique caller-supplied request identity. */
public interface GazetteNoticeBatchRepository extends JpaRepository<GazetteNoticeBatch, UUID> {

    Optional<GazetteNoticeBatch> findByRequestId(UUID requestId);
}
