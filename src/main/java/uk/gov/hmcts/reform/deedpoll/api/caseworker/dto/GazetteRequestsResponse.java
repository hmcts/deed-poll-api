package uk.gov.hmcts.reform.deedpoll.api.caseworker.dto;

import org.springframework.data.domain.Page;
import uk.gov.hmcts.reform.deedpoll.domain.gazette.GazetteBatchStatus;

import java.util.List;

public record GazetteRequestsResponse(List<GazetteStatusResponse> requests, int page, int size,
                                      long total, int totalPages) {
    public static GazetteRequestsResponse from(Page<GazetteBatchStatus> found) {
        return new GazetteRequestsResponse(found.stream().map(GazetteStatusResponse::from).toList(),
            found.getNumber() + 1, found.getSize(), found.getTotalElements(), found.getTotalPages());
    }
}
