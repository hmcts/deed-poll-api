package uk.gov.hmcts.reform.deedpoll.api.caseworker.dto;

import uk.gov.hmcts.reform.deedpoll.domain.deedpoll.CaseResults;
import uk.gov.hmcts.reform.deedpoll.domain.deedpoll.ListFilters;

import java.util.List;

/**
 * One page of the filtered case list, with the filter that was actually applied and where the page sits.
 *
 * <p><strong>The echoed filter key is the reason this is an object and not a bare array.</strong>
 * {@code ListFilters.filterFor} resolves an unrecognised key by falling back to "all" rather than by
 * refusing -- which is right, because the key comes from a URL a caseworker may have bookmarked against an
 * older version, and a 400 on the service's front page would be technically defensible and useless. But a
 * silent fallback that a caller cannot detect is how a filter tab renders as active while showing everything.
 * Saying which filter was applied makes the fallback observable without making it fatal.
 *
 * <p><strong>The size is echoed for the same reason the filter is.</strong> An over-cap {@code size} is
 * clamped rather than refused ({@code CasePaging.MAX_SIZE}), so a caller that asked for 1,000 rows and got
 * 100 has to be able to tell -- otherwise it reads a full page as the whole result set and stops paging.
 * The clamp is observable here without being fatal there.
 *
 * <p><strong>{@code referenceMatch} is stated rather than left to be inferred.</strong> A caseworker who
 * types a reference into the search box wants <em>the case</em>, not a list with one row in it -- and a
 * consumer must not have to read that intent off {@code total == 1}, which is also true of a surname only one
 * case happens to carry. api says which case the term matched as a reference; the UI decides whether to
 * redirect to it. Null whenever the term matched no reference, which includes every request that sent no
 * {@code q}.
 *
 * <p>The four paging members are flat rather than nested under a {@code page} object. There are four of them
 * and no prospect of a fifth, and a consumer reads {@code total} to render "3 of 7" -- an envelope inside an
 * envelope would be ceremony, of the kind {@code documentTypes} avoids by answering a bare array.
 *
 * @param filter the key of the filter that was applied, which is not necessarily the key that was requested
 * @param cases the matching cases on this page, in the order that was asked for
 * @param page which page these are, 1-based
 * @param size the page size that was applied, which is not necessarily the one that was requested
 * @param total how many cases match the filter and query across every page
 * @param totalPages how many pages of {@code size} that is, and zero when nothing matches
 * @param referenceMatch the id of the case whose reference the search term matched exactly, or null
 */
public record CaseListResponse(
    String filter,
    List<CaseSummaryResponse> cases,
    int page,
    int size,
    long total,
    int totalPages,
    String referenceMatch) {

    /** The list as it was resolved: the filter the domain settled on, and the page it produced. */
    public static CaseListResponse of(ListFilters.ListFilter filter, CaseResults results) {
        return new CaseListResponse(
            filter.key(),
            CaseSummaryResponse.from(results.cases()),
            results.page(),
            results.size(),
            results.total(),
            results.totalPages(),
            results.referenceMatch());
    }
}
