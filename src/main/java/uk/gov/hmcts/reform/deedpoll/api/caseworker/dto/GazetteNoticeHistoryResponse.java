package uk.gov.hmcts.reform.deedpoll.api.caseworker.dto;

import org.springframework.data.domain.Page;
import uk.gov.hmcts.reform.deedpoll.domain.gazette.GazetteNoticeSummary;

import java.util.List;

/** One page of audit batches; page numbers are 1-based, as on the case list. */
public record GazetteNoticeHistoryResponse(List<GazetteNoticeBatchResponse> batches, int page, int size,
                                          long total, int totalPages) {

    public static GazetteNoticeHistoryResponse from(Page<GazetteNoticeSummary> found) {
        return new GazetteNoticeHistoryResponse(found.stream().map(GazetteNoticeBatchResponse::from).toList(),
            found.getNumber() + 1, found.getSize(), found.getTotalElements(), found.getTotalPages());
    }
}
