package uk.gov.hmcts.reform.deedpoll.api.caseworker.dto;

import uk.gov.hmcts.reform.deedpoll.domain.deedpoll.DeedPollWithComputed;
import uk.gov.hmcts.reform.deedpoll.domain.deedpoll.ListFilters;

import java.util.List;

/**
 * The filtered case list, with the filter that was actually applied.
 *
 * <p><strong>The echoed filter key is the reason this is an object and not a bare array.</strong>
 * {@code ListFilters.filterFor} resolves an unrecognised key by falling back to "all" rather than by
 * refusing -- which is right, because the key comes from a URL a caseworker may have bookmarked against an
 * older version, and a 400 on the service's front page would be technically defensible and useless. But a
 * silent fallback that a caller cannot detect is how a filter tab renders as active while showing everything.
 * Saying which filter was applied makes the fallback observable without making it fatal.
 *
 * @param filter the key of the filter that was applied, which is not necessarily the key that was requested
 * @param cases the matching cases, newest first
 */
public record CaseListResponse(String filter, List<CaseSummaryResponse> cases) {

    /** The list as it was resolved: the filter the domain settled on, and the rows it admitted. */
    public static CaseListResponse of(ListFilters.ListFilter filter, List<DeedPollWithComputed> cases) {
        return new CaseListResponse(filter.key(), CaseSummaryResponse.from(cases));
    }
}
