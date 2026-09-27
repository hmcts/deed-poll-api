package uk.gov.hmcts.reform.deedpoll.api.caseworker.dto;

import uk.gov.hmcts.reform.deedpoll.ccd.CcdAttemptOverview;

import java.util.List;

/**
 * The admin repair view: the counts per status, and one page of the attempts that are not {@code Linked}.
 *
 * <p>The counts cover every status, {@code Linked} included and in lifecycle order, and ignore the list's
 * {@code status} filter, so the headline reads the same whichever page is open. The paging fields are the case
 * list's and the judicial pool's.
 *
 * @param counts every status and how many attempts are in it
 * @param attempts this page of the attempts that are not linked, longest-unmoved first
 * @param page which page, 1-based
 * @param size how many rows a page holds
 * @param total how many attempts the list has in all
 * @param totalPages how many pages of this size that is
 */
public record CcdAttemptListResponse(
    List<CcdAttemptCountResponse> counts,
    List<CcdAttemptResponse> attempts,
    int page,
    int size,
    long total,
    int totalPages) {

    /** The view as the domain resolved it. */
    public static CcdAttemptListResponse of(CcdAttemptOverview overview) {
        return new CcdAttemptListResponse(
            overview.counts().entrySet().stream()
                .map(count -> new CcdAttemptCountResponse(count.getKey(), count.getValue()))
                .toList(),
            CcdAttemptResponse.from(overview.attempts()),
            overview.page(),
            overview.size(),
            overview.total(),
            overview.totalPages());
    }
}
