package uk.gov.hmcts.reform.deedpoll.domain.gazette;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
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

    public static final int MAX_REFERENCES = 100;

    private final GazetteNoticeBatchRepository batches;
    private final GazetteNoticeBatchCaseRepository cases;

    public GazetteNoticeHistory(GazetteNoticeBatchRepository batches, GazetteNoticeBatchCaseRepository cases) {
        this.batches = batches;
        this.cases = cases;
    }

    /** Newest first, with a stable identity tie-breaker for equal send timestamps. */
    @Transactional(readOnly = true)
    public Page<GazetteNoticeSummary> history(CurrentUser caller, CasePaging paging) {
        caller.requireAnyRole(Role.DEEDPOLL_ADMIN);
        Page<GazetteNoticeBatch> found = batches.findAll(PageRequest.of(paging.index(), paging.size(),
            Sort.by(Sort.Direction.DESC, "sentAt", "id")));
        List<UUID> ids = found.stream().map(GazetteNoticeBatch::getId).toList();
        Map<UUID, List<GazetteNoticeBatchCase>> members = ids.isEmpty() ? Map.of()
            : cases.referencePreview(ids, MAX_REFERENCES + 1).stream()
                .collect(Collectors.groupingBy(member -> member.getId().getBatchId()));
        return found.map(batch -> {
            List<GazetteNoticeBatchCase> preview = members.getOrDefault(batch.getId(), List.of());
            return new GazetteNoticeSummary(batch.getId(), batch.getSentAt(), batch.getSentByName(),
                batch.getCaseCount(),
                preview.stream().limit(MAX_REFERENCES).map(GazetteNoticeBatchCase::getReferenceNumber).toList(),
                preview.size() > MAX_REFERENCES);
        });
    }
}
