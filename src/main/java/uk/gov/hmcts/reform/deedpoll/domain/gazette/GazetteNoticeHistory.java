package uk.gov.hmcts.reform.deedpoll.domain.gazette;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uk.gov.hmcts.reform.deedpoll.domain.ForbiddenError;
import uk.gov.hmcts.reform.deedpoll.domain.auth.CurrentUser;
import uk.gov.hmcts.reform.deedpoll.domain.auth.Role;
import uk.gov.hmcts.reform.deedpoll.domain.deedpoll.CasePaging;
import uk.gov.hmcts.reform.deedpoll.entity.GazetteNoticeBatch;
import uk.gov.hmcts.reform.deedpoll.entity.GazetteNoticeBatchCase;
import uk.gov.hmcts.reform.deedpoll.repository.GazetteNoticeBatchCaseRepository;
import uk.gov.hmcts.reform.deedpoll.repository.GazetteNoticeBatchRepository;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/** Admin-only audit read; references are taken from the batch, never today's case. */
@Service
public class GazetteNoticeHistory {

    private static final Logger LOG = LoggerFactory.getLogger(GazetteNoticeHistory.class);

    private final GazetteNoticeBatchRepository batches;
    private final GazetteNoticeBatchCaseRepository cases;

    public GazetteNoticeHistory(GazetteNoticeBatchRepository batches, GazetteNoticeBatchCaseRepository cases) {
        this.batches = batches;
        this.cases = cases;
    }

    /** Newest first, with a stable identity tie-breaker for equal send timestamps. */
    @Transactional(readOnly = true)
    public Page<GazetteNoticeSummary> history(CurrentUser caller, CasePaging paging) {
        try {
            caller.requireAnyRole(Role.DEEDPOLL_ADMIN);
        } catch (ForbiddenError refused) {
            LOG.warn("Gazette history refused: requires caseworker-deedpoll-admin role");
            throw refused;
        }
        Page<GazetteNoticeBatch> found = batches.findAll(PageRequest.of(paging.index(), paging.size(),
            Sort.by(Sort.Direction.DESC, "sentAt", "id")));
        List<UUID> ids = found.stream().map(GazetteNoticeBatch::getId).toList();
        Map<UUID, List<GazetteNoticeBatchCase>> members = ids.isEmpty() ? Map.of()
            : cases.findByIdBatchIdIn(ids).stream()
                .collect(Collectors.groupingBy(member -> member.getId().getBatchId()));
        return found.map(batch -> new GazetteNoticeSummary(batch.getSentAt(), batch.getSentByName(),
            batch.getCaseCount(), members.getOrDefault(batch.getId(), List.of()).stream()
                .map(GazetteNoticeBatchCase::getReferenceNumber).sorted().toList()));
    }
}
