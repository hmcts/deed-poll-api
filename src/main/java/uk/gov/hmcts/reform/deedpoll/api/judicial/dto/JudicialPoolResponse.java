package uk.gov.hmcts.reform.deedpoll.api.judicial.dto;

import uk.gov.hmcts.reform.deedpoll.domain.referrals.ReferralPool;

import java.util.List;

/**
 * One page of the shared pool, and how much work is waiting behind it. <strong>Always empty since plan D2</strong>,
 * when referrals began to be assigned by the office; kept, deprecated, for one release because today's admin reads it.
 *
 * <p><strong>The total is the field this envelope exists for.</strong> A judge is shown ten referrals by default
 * (decision 15), and ten is a page size rather than a cap -- so a response that was a bare array would be
 * indistinguishable from "there are ten referrals", and the number the judiciary actually needs is "sixty are
 * waiting". The whole pool is reachable by paging, and this is what says so.
 *
 * <p>{@code size} is echoed for the reason {@code CaseListResponse} echoes it: an over-cap {@code size} is
 * clamped rather than refused, so a caller that asked for a thousand rows and got a hundred has to be able to
 * tell -- otherwise it reads a full page as the whole pool and stops paging.
 *
 * <p>The four paging members are flat rather than nested under a {@code page} object, matching the caseworker
 * list's shape: there are four of them, a consumer reads {@code total} to render "3 of 7", and an envelope inside
 * an envelope would be ceremony.
 *
 * @param referrals the referrals on this page, urgent first then longest-waiting, as the database ordered them
 * @param page which page these are, 1-based
 * @param size the page size that was applied, which is not necessarily the one that was requested
 * @param total how many referrals are in the pool across every page -- the work waiting for the judiciary
 * @param totalPages how many pages of {@code size} that is, and zero when the pool is empty
 */
public record JudicialPoolResponse(
    List<JudicialReferralSummary> referrals,
    int page,
    int size,
    long total,
    int totalPages) {

    /** The pool as the domain resolved it, which since plan D2 holds no referral to map. */
    public static JudicialPoolResponse of(ReferralPool pool) {
        return new JudicialPoolResponse(
            List.of(),
            pool.page(),
            pool.size(),
            pool.total(),
            pool.totalPages());
    }
}
