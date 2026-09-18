package uk.gov.hmcts.reform.deedpoll.domain.referrals;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uk.gov.hmcts.reform.deedpoll.domain.ForbiddenError;
import uk.gov.hmcts.reform.deedpoll.domain.auth.CurrentUser;
import uk.gov.hmcts.reform.deedpoll.domain.auth.Role;
import uk.gov.hmcts.reform.deedpoll.domain.deedpoll.CasePaging;
import uk.gov.hmcts.reform.deedpoll.entity.Referral;
import uk.gov.hmcts.reform.deedpoll.entity.enums.ReferralState;
import uk.gov.hmcts.reform.deedpoll.repository.ReferralRepository;

/**
 * What a judge may see, and the shared pool they work from.
 *
 * <p><strong>A separate unit from {@link ReferralService} on purpose, and it adds no transition.</strong> Every
 * state change the judicial surface performs is a call into one of that service's transitions -- {@code claim},
 * {@code touch}, {@code decide} -- which keeps one place owning how a referral changes state. What lives here
 * is the half of the judicial surface that service has no notion of: <em>who may see which referral</em>, and
 * the cross-case list a judge works from. Both are the judicial persona's, and neither is reachable by a
 * caseworker token.
 *
 * <p><strong>The role gate is on every method here, including the reads.</strong> The transitions gate
 * themselves; these have to gate themselves too, because the pool and the judicial projection of an application
 * are not a smaller version of the caseworker surface -- a caseworker has their own, richer one -- and an
 * ungated judicial read would be a second way to read case data with none of the caseworker surface's
 * case-scoping. {@link Role#JUDICIAL} is a role IDAM does not issue yet, so this whole surface fails closed for
 * every token in existence today while being fully testable from a {@code CurrentUser}.
 *
 * <p><strong>The safety boundary here is visibility, not case-scoping.</strong> {@link ReferralRepository}'s
 * rule is that a referral is named by its case, which is what makes a cross-case caseworker read inexpressible.
 * The judicial surface cannot work that way: a judge must not be able to name a case at all, so a judicial URL
 * carries a referral id and the case is resolved from it. {@link #visibleTo} is what replaces the scoping, and
 * every judicial read and write runs through it before anything is returned.
 */
@Service
public class JudicialReferralService {

    /**
     * How many referrals a judge is shown at once when they do not ask for a particular page (decision 15).
     *
     * <p>Ten rather than the case list's {@link CasePaging#DEFAULT_SIZE} of twenty-five: the judicial dashboard
     * is a list of papers to read rather than a table to scan, and ten is the screenful that was asked for.
     * <strong>It is a page size and not a cap</strong> -- the whole pool is reachable by paging, and
     * {@link ReferralPool#total()} says how much is waiting -- which is the distinction that makes a small
     * default safe.
     */
    public static final int POOL_PAGE_SIZE = 10;

    /** What a pool request that asked for no particular page means. */
    public static final CasePaging FIRST_POOL_PAGE = new CasePaging(CasePaging.FIRST_PAGE, POOL_PAGE_SIZE);

    /**
     * The order the pool is worked in: urgent referrals first, and the longest-waiting first within each
     * urgency band (decisions 15 and 16).
     *
     * <p><strong>Descending urgency is ascending {@code Urgency}</strong>, and that is not a trick: the
     * {@code "Urgency"} Postgres type is declared {@code ('Normal', 'Urgent')} in {@code V1__enums.sql} -- the
     * reference's own option-set order -- and Postgres orders an enum by its declared order, so the last member
     * is the most urgent. A third urgency added above {@code Urgent} later would sort above it for free, and one
     * added in the middle would still sort correctly, because the declaration order is the urgency order.
     * {@code ReferralRepositoryTest} asserts the resulting order against a real Postgres rather than trusting
     * that reading; {@code JudicialReferralServiceTest} asserts this service asks for exactly this sort rather
     * than fetching and reordering.
     *
     * <p>Oldest first within a band, where the case list's default is newest first, and the difference is the
     * point: a case list is a record being browsed, and a work queue is a backlog being cleared. Newest-first
     * here would leave the longest-waiting applicant waiting longest.
     */
    public static final Sort POOL_ORDER =
        Sort.by(Sort.Order.desc("urgency"), Sort.Order.asc("createdOn"));

    private final ReferralRepository referrals;
    private final ReferralService referralService;

    public JudicialReferralService(ReferralRepository referrals, ReferralService referralService) {
        this.referrals = referrals;
        this.referralService = referralService;
    }

    /**
     * Whether this caller may see this referral at all -- the one predicate the whole judicial surface is
     * guarded by (decision 3).
     *
     * <p>A referral is visible to a judge when it is in the shared pool, or when it is one they picked up or
     * directed themselves:
     *
     * <ul>
     *   <li>{@link ReferralState#Open} -- in the pool, so every judge may see it. That is what the pool is;</li>
     *   <li>{@link ReferralState#Claimed} -- only the judge holding it, because a claim is exclusive and two
     *       judges reading the same papers is the duplication the pool exists to prevent;</li>
     *   <li>{@link ReferralState#Decided} -- only the judge who directed it, so they can re-read their own
     *       direction while the office actions it;</li>
     *   <li>{@link ReferralState#Closed} and {@link ReferralState#Withdrawn} -- nobody. The judiciary's
     *       involvement is over, and a judge has no standing to read a case whose referral was actioned or
     *       pulled back.</li>
     * </ul>
     *
     * <p><strong>An invisible referral is a 404 at every caller of this, never a 403.</strong> Withdrawn,
     * closed, held by another judge, and never having existed all answer identically, which is what stops a
     * referral id being probed for and stops one judge learning that another holds a case. The 403 on this
     * surface is the role gate above, which refuses a non-judicial token before any of this is asked.
     *
     * <p><strong>The identity compared is the display name</strong>, because that is what {@code claimedBy} and
     * {@code decidedBy} hold -- the claim is recorded under the name resolved from the judge's token, and
     * {@code ReferralService.touch} and {@code decide} already authorise against the same column. It is the one
     * place this codebase authorises on {@code CurrentUser.name()} rather than on the subject, and it is a
     * consequence of the columns rather than a choice made here: keying a claim on the subject is a migration,
     * which this plan does not have. Two judges sharing a display name would see each other's claimed
     * referrals, which is the residual risk and is recorded rather than hidden.
     *
     * <p>A static method, so it is provable without a database or a Spring context and so no caller can be
     * tempted to answer the question itself.
     */
    public static boolean visibleTo(Referral referral, CurrentUser caller) {
        return switch (referral.getState()) {
            case Open -> true;
            // equals on the caller's name and not on the column's, so a null column is false rather than a
            // NullPointerException: a Decided referral always has a decidedBy, and a backfilled Claimed one
            // from V7 has no claimedBy at all.
            case Claimed -> caller.name().equals(referral.getClaimedBy());
            case Decided -> caller.name().equals(referral.getDecidedBy());
            case Closed, Withdrawn -> false;
        };
    }

    /**
     * The shared pool: the referrals waiting for a judge, urgent-first then longest-waiting, one page at a
     * time.
     *
     * <p><strong>This read mutates, and that is decision 22 rather than an oversight.</strong> It runs
     * {@link ReferralService#reclaimStaleClaims()} before it queries, so a claim that lapsed while nobody was
     * looking is handed back and the referral is in the pool in the same response that observes it -- rather
     * than in whichever later request happened to trigger a sweep. The pool is the most-polled judicial page,
     * which is exactly why the sweep hangs off it; {@code ReferralService}'s class javadoc has the full
     * argument, including why there is no lock and why a scheduled sweep would be a follow-up rather than a
     * correction.
     *
     * <p>So the transaction is a read-write one, deliberately: {@code readOnly = true} here would refuse the
     * release the sweep performs. It also means the page below sees the releases this request made, which is
     * the whole point of sweeping first.
     *
     * <p><strong>{@code Open} only.</strong> The pool is the unclaimed work; a judge's own claimed and decided
     * referrals are visible to them by {@link #visibleTo} and reachable by id, and folding them into this list
     * would put a referral somebody is holding in front of every other judge.
     *
     * <p><strong>The database orders and pages, never this method.</strong> {@link #POOL_ORDER} goes to the
     * query, so the first page is the ten most urgent longest-waiting referrals of the whole pool and not the
     * ten arbitrary rows a fetch-then-sort would have reordered.
     *
     * <p>A page past the end of the pool is an empty page and not an error, as the case list's is: a judge who
     * bookmarked page 4 of a pool that has since been worked down has asked a question whose answer is
     * "nothing, out of this many", and {@link ReferralPool#total()} tells them where to look instead.
     *
     * @param caller the judge asking
     * @param paging which page and how many rows, {@link #FIRST_POOL_PAGE} for a request that asked for none
     * @throws ForbiddenError if the caller does not hold {@link Role#JUDICIAL}
     */
    @Transactional
    public ReferralPool pool(CurrentUser caller, CasePaging paging) {
        caller.requireAnyRole(Role.JUDICIAL);
        // Before the query, not after: the releases this performs are referrals that belong in the page below,
        // and sweeping afterwards would show the judge a pool one request out of date.
        referralService.reclaimStaleClaims();

        Page<Referral> found = referrals.findByState(
            ReferralState.Open, PageRequest.of(paging.index(), paging.size(), POOL_ORDER));
        return ReferralPool.of(found.getContent(), paging, found.getTotalElements());
    }
}
