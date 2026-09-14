package uk.gov.hmcts.reform.deedpoll.domain.deedpoll;

import java.util.List;

/**
 * One page of the case list, and enough about the whole result set to page through it.
 *
 * <p><strong>Why the total is carried and not left to be inferred.</strong> A page of rows on its own
 * cannot say whether there is another one: a full page may be the last, and a caller that guesses by
 * asking for the next one and getting nothing has already made the request it was trying to avoid. The
 * total is one count query, and it is what makes "page 3 of 7" renderable.
 *
 * <p>A domain type rather than Spring Data's {@code Page}, for the reason {@link CaseQuery} is not a
 * {@code Specification}: what a caller is told about a result set should not be the shape of the thing that
 * produced it, or the api layer's response format becomes a re-export of the persistence layer's -- and
 * this surface has to survive the move to CCD's Elasticsearch indexer, which produces no {@code Page}.
 *
 * @param cases the rows on this page, in the order that was asked for
 * @param page which page these are, 1-based
 * @param size the page size that was applied, which is not necessarily the one that was requested -- see
 *     {@link CasePaging#MAX_SIZE}
 * @param total how many cases match the filter and query in total, across every page
 * @param totalPages how many pages of {@code size} that is, and zero when nothing matches
 */
public record CaseResults(List<DeedPollWithComputed> cases, int page, int size, long total, int totalPages) {

    public CaseResults {
        cases = List.copyOf(cases);
    }

    /**
     * One page, with its page count derived rather than passed in.
     *
     * <p>Derived here so there is one statement of what a page count is, and so a caller cannot report a
     * total and a page count that disagree. Zero pages for an empty result set: a caller rendering "page 1
     * of 0" says the truth about a list with nothing in it, where "page 1 of 1" claims a page that has no
     * rows on it.
     */
    public static CaseResults of(List<DeedPollWithComputed> cases, CasePaging paging, long total) {
        return new CaseResults(cases, paging.page(), paging.size(), total, pages(total, paging.size()));
    }

    /** Whether this page has anything on it, which a page past the end does not. */
    public boolean isEmpty() {
        return cases.isEmpty();
    }

    /**
     * How many pages of the given size a total needs, rounded up so a partial last page is a page.
     *
     * <p>The division is done in long arithmetic: the intermediate would otherwise overflow an int for a
     * total near {@link Integer#MAX_VALUE}, which is a row count this service will never have but not a
     * reason to write it wrongly.
     */
    private static int pages(long total, int size) {
        return (int) ((total + size - 1) / size);
    }
}
