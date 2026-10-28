package uk.gov.hmcts.reform.deedpoll.api.judicial.dto;

import uk.gov.hmcts.reform.deedpoll.domain.referrals.ReferralWorkload;

import java.time.Duration;
import java.util.List;

/**
 * One page of the referrals this judge has in hand, and how much of their own work is behind it.
 *
 * <p><strong>A separate envelope from {@link JudicialPoolResponse} even though the two are the same shape</strong>,
 * for the reason {@code ReferralWorkload} is a separate record from {@code ReferralPool}: these name opposite
 * collections. The pool is the shared, unclaimed work every judge is offered identically; this is one judge's own
 * claimed and decided referrals and nobody else's. A single type would put "pool" in the name of a response that is
 * the pool's complement, and a consumer holding one would have to remember which call produced it to know what it
 * meant -- which matters most in the generated client, where the type is the documentation.
 *
 * <p><strong>The rows are {@link JudicialReferralSummary}, reused deliberately.</strong> That type already publishes
 * {@code state} for exactly this list -- its documentation says so -- so a row here is self-describing: a judge sees
 * which of their referrals they are still holding and which they have directed and are waiting on the office for.
 * Nothing is added to it: no applicant names, no display reference, no case id, no direction text and no owner id.
 * Reaching the application is still the individual read, which runs the visibility check.
 *
 * <p>{@code total} is what makes the ten a page size rather than a cap, as the pool's is, and here it is also the
 * answer to the question the list exists for: how much work a judge is in the middle of.
 *
 * @param referrals the referrals on this page, the ones being held before the ones already directed and urgent-first
 *     within each, as the database ordered them
 * @param page which page these are, 1-based
 * @param size the page size that was applied, which is not necessarily the one that was requested
 * @param total how many referrals this judge has in hand across every page
 * @param totalPages how many pages of {@code size} that is, and zero when they are holding nothing
 */
public record JudicialMyReferralsResponse(
    List<JudicialReferralSummary> referrals,
    int page,
    int size,
    long total,
    int totalPages) {

    /** This judge's own work, as the domain resolved it. */
    public static JudicialMyReferralsResponse of(ReferralWorkload workload, Duration processingWindow) {
        return new JudicialMyReferralsResponse(
            JudicialReferralSummary.from(workload.referrals(), processingWindow),
            workload.page(),
            workload.size(),
            workload.total(),
            workload.totalPages());
    }
}
