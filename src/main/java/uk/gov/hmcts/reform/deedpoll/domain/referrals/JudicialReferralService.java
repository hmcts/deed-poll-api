package uk.gov.hmcts.reform.deedpoll.domain.referrals;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uk.gov.hmcts.reform.deedpoll.domain.ConflictError;
import uk.gov.hmcts.reform.deedpoll.domain.ForbiddenError;
import uk.gov.hmcts.reform.deedpoll.domain.NotFoundError;
import uk.gov.hmcts.reform.deedpoll.domain.auth.CurrentUser;
import uk.gov.hmcts.reform.deedpoll.domain.auth.Role;
import uk.gov.hmcts.reform.deedpoll.domain.deedpoll.CasePaging;
import uk.gov.hmcts.reform.deedpoll.domain.documents.DocumentDownload;
import uk.gov.hmcts.reform.deedpoll.domain.documents.DocumentPreviewRead;
import uk.gov.hmcts.reform.deedpoll.domain.documents.DocumentService;
import uk.gov.hmcts.reform.deedpoll.domain.notes.NoteService;
import uk.gov.hmcts.reform.deedpoll.domain.witnesses.WitnessService;
import uk.gov.hmcts.reform.deedpoll.entity.DeedPoll;
import uk.gov.hmcts.reform.deedpoll.entity.Referral;
import uk.gov.hmcts.reform.deedpoll.entity.enums.ReferralState;
import uk.gov.hmcts.reform.deedpoll.repository.ReferralRepository;

import java.util.Optional;
import java.util.UUID;

/**
 * What a judge may see, the two lists they work from, the documents they may open, and the three things they write.
 *
 * <p><strong>A separate unit from {@link ReferralService} on purpose, and it adds no transition.</strong> Every
 * state change the judicial surface performs is a call into one of that service's transitions -- {@code claim},
 * {@code touch}, {@code decide}, {@code reviseDirection} -- which keeps one place owning how a referral changes
 * state. What lives here is the half of the judicial surface that service has no notion of: <em>who may see which
 * referral</em>, the two cross-case lists a judge works from -- the shared pool and their own outstanding work --
 * and the resolution of one referral id to the application, the notes and the documents behind it. Every method
 * here is the judicial persona's, and none is reachable by a caseworker token.
 *
 * <p><strong>{@link #claim} and {@link #direct} are that resolution in front of a transition, and nothing
 * else.</strong> Neither guards a state, stamps a column or writes a note: the {@code Open}-only rule, the
 * holder-only rule, and every column either sets are {@link ReferralService}'s, which is also where a caller
 * that is not this service -- a test, a later scheduled sweep -- meets the same rules. What they add is the step
 * a judicial caller cannot do without: turning a referral id into a case, having first established that this
 * judge may see the referral at all.
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
 * every read and write of an individual referral runs through it before anything is returned.
 *
 * <p>The two lists cannot ask it -- a predicate in Java is not a query, and filtering a page after the database
 * drew it is how a list comes to disagree with its own total -- so each states the same rule in SQL instead: the
 * pool is {@code Open} only, and {@link #mine} is the caller's own subject in the two states {@code visibleTo}
 * admits for an owner. Where a list and the boundary disagreed, the list would offer a referral whose link
 * answered 404.
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
     *
     * <p>{@link #mine} defaults to the same ten, because it is the same dashboard and the same screenful; it is
     * named for the pool because the pool is where the number was decided, and a second constant of the same
     * value would be two numbers to keep in step rather than one.
     */
    public static final int POOL_PAGE_SIZE = 10;

    /** What a pool request that asked for no particular page means. */
    public static final CasePaging FIRST_POOL_PAGE = new CasePaging(CasePaging.FIRST_PAGE, POOL_PAGE_SIZE);

    /**
     * The order the pool is worked in: urgent referrals first, and the longest-waiting first within each
     * urgency band (decisions 15 and 16).
     *
     * <p><strong>Urgent-first is <em>descending</em> the {@code Urgency} type</strong>, which is what
     * {@code desc("urgency")} below says, and it is not a trick: the
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

    /**
     * The order a judge's own referrals are listed in: unfinished work first, then the pool's own order within
     * each state, then the id so that the sequence is never arbitrary.
     *
     * <p><strong>Ascending state is {@code Claimed} before {@code Decided}, and it is the same fact about Postgres
     * that {@link #POOL_ORDER} relies on rather than a second trick.</strong> The {@code "ReferralState"} type is
     * declared {@code ('Open', 'Claimed', 'Decided', 'Closed', 'Withdrawn')} in {@code V6__referrals.sql} -- the
     * lifecycle order -- and Postgres orders an enum by its declared order, so ascending it is earliest-in-the-
     * lifecycle first. Only two of the five can appear here, so what this says is that a referral still waiting for
     * a direction sorts above one the office is already actioning: the point of the list is resuming outstanding
     * work, and a judge with a page of finished directions above it would have to scroll to find any.
     *
     * <p>Then {@link #POOL_ORDER}'s two keys, for its reasons: urgent first, longest-waiting first within each
     * urgency band.
     *
     * <p><strong>Then the id, which the pool does not have and this needs.</strong> Two referrals raised in the
     * same state, urgency and instant are common here in a way they are not in a pool of one referral per case --
     * a judge claims several in a sitting, and V7's backfilled rows share a timestamp -- and without a final key
     * Postgres may return them in either order between one page and the next, which is how a row can appear twice
     * or not at all while a judge pages through. The id is arbitrary as an order and deterministic as a tie-break,
     * which is exactly what is wanted: it is not shown, it is only stable.
     */
    public static final Sort MINE_ORDER = Sort.by(
        Sort.Order.asc("state"),
        Sort.Order.desc("urgency"),
        Sort.Order.asc("createdOn"),
        Sort.Order.asc("id"));

    private final ReferralRepository referrals;
    private final ReferralService referralService;
    private final NoteService notes;
    private final WitnessService witnesses;
    private final DocumentService documents;

    public JudicialReferralService(ReferralRepository referrals,
                                   ReferralService referralService,
                                   NoteService notes,
                                   WitnessService witnesses,
                                   DocumentService documents) {
        this.referrals = referrals;
        this.referralService = referralService;
        this.notes = notes;
        this.witnesses = witnesses;
        this.documents = documents;
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
     * <p><strong>The identity compared is the token's IDAM subject</strong> -- {@code claimedById} and
     * {@code decidedById}, which {@code V14__referral_owner_subjects.sql} added for this. It used to be the
     * display name in {@code claimedBy}/{@code decidedBy}, and that was the defect {@link CurrentUser} recorded:
     * two judges sharing a display name saw and directed each other's referrals, and a judge whose display name
     * changed lost their own. Those columns remain, as the attribution a caseworker reads; they authorise
     * nothing, here or in {@code ReferralService.requireHeldBy}.
     *
     * <p><strong>A row with no owner is visible to nobody, and there is deliberately no fall back to the
     * name.</strong> A claim made before that migration, and every referral V7 backfilled, has a null owner --
     * comparing names on exactly those rows would keep the collision precisely where identity is least certain.
     * So they fail closed: the inactivity sweep returns a lapsed one to the shared pool, where it is visible to
     * every judge again, and a pre-migration direction stays visible and actionable to caseworkers, which is
     * where a recorded direction is needed.
     *
     * <p>The subject is resolved before the switch rather than inside the {@code Claimed} and {@code Decided}
     * arms, for the reason {@code SavedQueryPermissions.visibleTo} resolves it before its predicate: written as a
     * comparison inside the arms, the guard would let an unidentified caller read the whole pool and refuse them
     * only where the answer was already no.
     *
     * <p>A static method, so it is provable without a database or a Spring context and so no caller can be
     * tempted to answer the question itself.
     *
     * @throws uk.gov.hmcts.reform.deedpoll.domain.UnauthorizedError if the caller carries no stable subject
     */
    public static boolean visibleTo(Referral referral, CurrentUser caller) {
        String owner = caller.requireUserId();
        return switch (referral.getState()) {
            case Open -> true;
            // equals on the caller's subject and not on the column's, so a null owner is false rather than a
            // NullPointerException: that is the pre-migration row above, and it is the case this must answer
            // rather than throw on.
            case Claimed -> owner.equals(referral.getClaimedById());
            case Decided -> owner.equals(referral.getDecidedById());
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

    /**
     * The referrals this judge has in hand: the ones they are holding, and the directions the office has not yet
     * actioned.
     *
     * <p><strong>Why it exists at all.</strong> Claiming a referral moves it out of {@link #pool}, which was the
     * only judicial list -- so the successful claim removed the referral from the only page that linked to it, and
     * a judge who left the papers could not find them again without the id. The judicial boundary
     * ({@link #visibleTo}) always admitted a judge's own {@code Claimed} and {@code Decided} referrals; this is the
     * list that finally matches it.
     *
     * <p><strong>Membership is the same predicate as visibility, restricted to the two states a judge owns.</strong>
     * {@code Claimed} where this caller holds it, {@code Decided} where this caller directed it, and nothing else:
     * {@code Open} belongs to the shared pool, and {@code Closed}/{@code Withdrawn} are the judiciary's involvement
     * being over, exactly as {@code visibleTo} says. Same rule in both places on purpose -- a list that offered a
     * referral its own link answered 404 for would be worse than no list.
     *
     * <p><strong>The subject is required before anything else reads or writes, and a row with no owner is on
     * nobody's list.</strong> Ownership is {@code claimedById}/{@code decidedById} and never the display name, so
     * two judges who share one have separate lists -- which matters more here than anywhere: a list keyed on a name
     * would <em>enumerate</em> a namesake's referrals, where the individual read at least needed their id. A
     * pre-migration row whose owner is null therefore appears for nobody, which is the fail-closed consequence
     * {@code V14__referral_owner_subjects.sql} documents rather than a gap to be patched with a name.
     *
     * <p><strong>It sweeps first, like the pool and for a sharper reason (decision 3 of this plan).</strong>
     * Without it, a claim that had lapsed would sit in "my referrals" until somebody else loaded the pool, and
     * opening it from there would call {@code touch} and revive an expired claim -- so the expiry would depend on
     * which page a judge happened to load. So the transaction is read-write, and the sweep may take a row out of
     * this very response and return it to the shared pool; its existing lapse note is the record of that.
     *
     * <p><strong>The database filters, orders, pages and counts</strong> -- {@link #MINE_ORDER} and
     * {@code ReferralRepository.findOwnedBy}. A Java filter over a wider read would page an arbitrary slice and
     * would have to count separately, and the total is the answer to "how much am I in the middle of".
     *
     * <p>A page past the end is an empty page and not an error, as the pool's is.
     *
     * @param caller the judge asking, whose token is the only source of whose list this is
     * @param paging which page and how many rows, {@link #FIRST_POOL_PAGE} for a request that asked for none
     * @throws ForbiddenError if the caller does not hold {@link Role#JUDICIAL}
     * @throws uk.gov.hmcts.reform.deedpoll.domain.UnauthorizedError if the caller carries no stable subject, so
     *     there is no list that is theirs
     */
    @Transactional
    public ReferralWorkload mine(CurrentUser caller, CasePaging paging) {
        caller.requireAnyRole(Role.JUDICIAL);
        // Before the sweep, which is the first thing here that writes anything: an unidentified caller owns
        // nothing, so they have no list -- and letting them release somebody else's lapsed claim on their way to a
        // 401 is the same mistake claim() refuses for the same reason.
        String owner = caller.requireUserId();
        // Before the query, for the pool's reason and one of its own: a claim that lapsed while this judge was
        // away is not theirs any more, and a sweep afterwards would leave it on this page for them to open --
        // which would touch it and revive a claim the expiry window had already ended.
        referralService.reclaimStaleClaims();

        Page<Referral> found = referrals.findOwnedBy(
            owner, ReferralState.Claimed, ReferralState.Decided,
            PageRequest.of(paging.index(), paging.size(), MINE_ORDER));
        return ReferralWorkload.of(found.getContent(), paging, found.getTotalElements());
    }

    /**
     * One referral, the application behind it and that application's notes -- what a judge opens to direct it.
     *
     * <p><strong>This is the read decision 2 inverts an api-1 rule for, so what guards it matters more than
     * what it returns.</strong> The referral is resolved by id alone, because a judicial URL carries no case id
     * -- a judge must not be able to name an application -- and every one of the three answers below is the
     * <em>same</em> 404:
     *
     * <ul>
     *   <li>an id nothing was ever stored under, including one that is not a UUID at all;</li>
     *   <li>a referral that exists and this caller may not see -- {@code Withdrawn}, {@code Closed}, or
     *       {@code Claimed}/{@code Decided} by another judge ({@link #visibleTo});</li>
     *   <li>and therefore, deliberately, no way to tell those apart. A 403 here would confirm that a referral
     *       exists, and a distinct message would tell one judge that another holds the case.</li>
     * </ul>
     *
     * <p><strong>It refreshes the claim when the caller holds it, and touches nothing when they do not</strong>
     * (decision 7). A judge reading the papers of a referral they claimed is working it, so the inactivity clock
     * restarts -- which is what stops the sweep taking a case off somebody who is plainly still engaged, and it
     * is the reason the expiry is measured from the last interaction rather than from the claim. Reading a pool
     * referral they have not claimed is a read and nothing else: it must not stamp a row every judge can see,
     * and it must not look like a claim.
     *
     * <p>Held means {@code Claimed} <em>by this caller</em>, which is narrower than visible: a judge's own
     * {@code Decided} referral is visible to them and has no claim left to refresh, and calling
     * {@link ReferralService#touch} on one would be a {@code ConflictError} on a read that is perfectly
     * legitimate. So the condition here is the one {@code touch} itself enforces, asked before it is called
     * rather than caught afterwards.
     *
     * <p>The notes come from {@link NoteService#findNotes} -- the same read the caseworker timeline uses, with no
     * judicial variant -- and a judge writes none. There is no judicial note route and no judicial author: what
     * a judge records is the direction, on the referral.
     *
     * @param referralId the referral, as it appears in the path
     * @param caller the judge asking
     * @throws ForbiddenError if the caller does not hold {@link Role#JUDICIAL}
     * @throws NotFoundError if there is no such referral, or this caller may not see it
     */
    @Transactional
    public JudicialReferralView referral(String referralId, CurrentUser caller) {
        caller.requireAnyRole(Role.JUDICIAL);
        Referral referral = visible(referralId, caller);

        DeedPoll application = referral.getDeedPoll();
        if (heldBy(referral, caller)) {
            referralService.touch(application.getId(), referralId, caller);
        }
        return view(referral, application);
    }

    /**
     * A judge taking a referral out of the shared pool -- the first of the two things a judge writes.
     *
     * <p><strong>It adds no transition logic.</strong> The state change is {@link ReferralService#claim}, which
     * owns the {@code Open}-only guard and the columns it stamps; what this method contributes is the half that
     * service cannot express, because it is the half that has no case id: resolving the referral, refusing it
     * with a 404 if this judge may not see it, and handing the transition the case it resolved.
     *
     * <p><strong>The sweep runs before the visibility check, and that ordering is the point.</strong> A claim
     * that lapsed a moment ago leaves a row that is still {@code Claimed} by the judge who abandoned it -- which
     * {@link #visibleTo} says this caller may not see -- so checking visibility first would answer 404 for a
     * referral that is, by the rule the pool is drawn from, available. Sweeping first makes the two agree: the
     * lapsed claim is released, the referral is {@code Open}, and it is visible to every judge including this
     * one. {@link ReferralService#claim} sweeps again defensively, which costs one query that finds nothing
     * inside this transaction and keeps that transition correct for a caller that is not this method.
     *
     * <p><strong>Two refusals, and they are different questions.</strong> A referral this judge may not see at
     * all is a {@link NotFoundError}, indistinguishable from an id that never existed. A referral they can see
     * but cannot claim -- one already {@code Claimed} or {@code Decided}, theirs included -- is the
     * {@code ConflictError} api-2 defined, because by then the caller is looking at it and a 404 would be a lie
     * they cannot act on. That split is the same one the saved-query surface makes between invisible and
     * unmodifiable.
     *
     * @param referralId the referral, as it appears in the path
     * @param caller the judge claiming it
     * @return the referral as this judge now holds it, with the application and notes the next screen renders
     * @throws ForbiddenError if the caller does not hold {@link Role#JUDICIAL}
     * @throws uk.gov.hmcts.reform.deedpoll.domain.UnauthorizedError if the caller carries no stable subject, so
     *     the claim it would make would have no owner
     * @throws NotFoundError if there is no such referral, or this caller may not see it
     * @throws ConflictError if the referral is not {@code Open}
     */
    @Transactional
    public JudicialReferralView claim(String referralId, CurrentUser caller) {
        caller.requireAnyRole(Role.JUDICIAL);
        // Before the sweep, which is the first thing here that writes anything: a caller IDAM has not identified
        // has nothing to own a claim with, and ReferralService.claim refuses them for that reason -- so asking
        // here as well is what keeps them from releasing somebody else's lapsed claim on their way to a 401.
        caller.requireUserId();
        // Before the resolve, for the reason the pool sweeps before it queries: a referral whose claim has just
        // lapsed belongs to the pool, and the visibility check has to be asked about the post-sweep row.
        referralService.reclaimStaleClaims();

        Referral referral = visible(referralId, caller);
        DeedPoll application = referral.getDeedPoll();
        return view(referralService.claim(application.getId(), referralId, caller), application);
    }

    /**
     * The holding judge recording their direction -- the second and last thing a judge writes.
     *
     * <p>{@link ReferralService#decide} owns all of it: that only the judge currently holding the referral may
     * direct it, that the direction is recorded atomically rather than drafted, and that {@code decidedBy} and
     * {@code decisionDate} come from the token and the clock rather than from the payload. This method resolves
     * the referral without a case id and refuses an invisible one, exactly as {@link #claim} does.
     *
     * <p><strong>No sweep here, unlike {@link #claim}, and that is deliberate.</strong> A judge submitting a
     * direction on a claim this request would have released is a judge who was working the referral, and
     * sweeping first would take it off them between their reading it and their answering it. Their claim may
     * still lapse -- another judge's pool load releases it, and their submission is then the
     * {@code ConflictError} api-2 defined -- but this request will not be the one that does it to them.
     *
     * <p>The direction is already validated when it arrives: {@link ReferralSchemas#parseDirection} is the only
     * way to build a {@link DirectionInput}, so a malformed one is a 400 before this method is reached.
     *
     * @param referralId the referral, as it appears in the path
     * @param direction what the judge decided, directed, and whether to publish in the London Gazette
     * @param caller the judge directing it
     * @return the referral carrying the direction just recorded
     * @throws ForbiddenError if the caller does not hold {@link Role#JUDICIAL}
     * @throws NotFoundError if there is no such referral, or this caller may not see it
     * @throws ConflictError if the referral is not currently claimed by this caller
     */
    @Transactional
    public JudicialReferralView direct(String referralId, DirectionInput direction, CurrentUser caller) {
        caller.requireAnyRole(Role.JUDICIAL);

        Referral referral = visible(referralId, caller);
        DeedPoll application = referral.getDeedPoll();
        return view(referralService.decide(application.getId(), referralId, direction, caller), application);
    }

    /**
     * The directing judge replacing their direction while the office has not yet closed the referral (business
     * decision 5 of the 2026-10-19 plan).
     *
     * <p>{@link ReferralService#reviseDirection} owns the rule -- {@code Decided} and directed by this caller -- and
     * asks visibility again under the row lock, so a close that commits while this request waits for the lock is the
     * same 404 as one that committed before {@link #visible} read the row. Like {@link #direct}, this resolves the
     * referral without a case id, refuses an invisible one, and does not sweep: a decided referral holds no claim.
     *
     * @param referralId the referral, as it appears in the path
     * @param direction the replacement direction, validated as the first one was
     * @param caller the judge who gave the direction
     * @return the referral carrying the revised direction
     * @throws ForbiddenError if the caller does not hold {@link Role#JUDICIAL}
     * @throws NotFoundError if there is no such referral, or this caller may not see it
     * @throws ConflictError if this caller may see it but it is not a direction of theirs
     */
    @Transactional
    public JudicialReferralView revise(String referralId, DirectionInput direction, CurrentUser caller) {
        caller.requireAnyRole(Role.JUDICIAL);

        Referral referral = visible(referralId, caller);
        DeedPoll application = referral.getDeedPoll();
        return view(referralService.reviseDirection(application.getId(), referralId, direction, caller),
                    application);
    }

    /**
     * The directing judge taking their direction back while the office has not yet closed the referral, so that
     * they can direct it again with {@link #direct} (business decision 5 of the 2026-10-19 plan).
     *
     * <p>{@link ReferralService#withdrawDirection} owns the rule and asks visibility again under the row lock, as
     * {@link #revise} does. It does not sweep: the referral becomes this judge's claim, with its inactivity clock
     * restarted, and from then on the sweep treats it as it treats any other claim.
     *
     * @param referralId the referral, as it appears in the path
     * @param caller the judge who gave the direction
     * @return the referral, {@code Claimed} by the same judge with no direction on it
     * @throws ForbiddenError if the caller does not hold {@link Role#JUDICIAL}
     * @throws NotFoundError if there is no such referral, or this caller may not see it
     * @throws ConflictError if this caller may see it but it is not a direction of theirs
     */
    @Transactional
    public JudicialReferralView withdraw(String referralId, CurrentUser caller) {
        caller.requireAnyRole(Role.JUDICIAL);

        Referral referral = visible(referralId, caller);
        DeedPoll application = referral.getDeedPoll();
        return view(referralService.withdrawDirection(application.getId(), referralId, caller), application);
    }

    /**
     * One supporting document of the application behind a referral this judge may see, recorded as
     * {@code DOCUMENT_DOWNLOADED} before it is opened (design decision 3 of the 2026-10-19 plan).
     *
     * <p><strong>The case comes from the referral, never from the caller.</strong> {@link #visible} resolves it and
     * refuses an invisible referral with the same 404 every judicial operation gives, so every judge may open the
     * documents of an {@code Open} referral (business decision 3), only the holder those of a {@code Claimed} one,
     * and nobody once it is {@code Closed} or {@code Withdrawn}. The document is then
     * {@link DocumentService#downloadDocument(String, String, CurrentUser)} scoped to that case: a document of
     * another case is the document's own 404, as on the caseworker route, and a failed audit insert opens nothing.
     *
     * <p><strong>Not {@code @Transactional}, and it touches no claim.</strong> The store may be CDAM, a remote call
     * that must not hold a database transaction open, and the referral is read join-fetched so its case id needs no
     * session. Refreshing the claim's inactivity clock is a write, and the read of the referral that lists these
     * documents has already made it.
     *
     * @param referralId the referral, as it appears in the path
     * @param documentId the document, as it appears in the path
     * @param caller the judge downloading it
     * @return the document and its open stream, which the caller closes
     * @throws ForbiddenError if the caller does not hold {@link Role#JUDICIAL}
     * @throws NotFoundError if there is no such referral, this caller may not see it, or the document is not a
     *     readable document of its case
     */
    public DocumentDownload document(String referralId, String documentId, CurrentUser caller) {
        caller.requireAnyRole(Role.JUDICIAL);

        Referral referral = visible(referralId, caller);
        return documents.downloadDocument(referral.getDeedPoll().getId(), documentId, caller);
    }

    /**
     * {@link #document}'s document, for a preview: the same role gate, the same visibility and the same case scope,
     * recorded as {@code DOCUMENT_PREVIEWED} only when the preview opens it ({@link DocumentPreviewRead#open}).
     *
     * <p>Not {@code @Transactional}, for {@link #document}'s reasons, and it touches no claim.
     *
     * @throws ForbiddenError if the caller does not hold {@link Role#JUDICIAL}
     * @throws NotFoundError as {@link #document} does, before anything is recorded or opened
     */
    public DocumentPreviewRead previewDocument(String referralId, String documentId, CurrentUser caller) {
        caller.requireAnyRole(Role.JUDICIAL);

        Referral referral = visible(referralId, caller);
        return documents.previewDocument(referral.getDeedPoll().getId(), documentId, caller);
    }

    /**
     * The referral this id names, if this caller may see it -- and the same 404 for every way they may not.
     *
     * <p>The filter is part of the lookup rather than a branch after it, so there is one exit for "you cannot
     * have this" and no second place that could answer a different status. One method rather than one per
     * operation, because the property decision 3 turns on is that <em>every</em> judicial read and write refuses
     * identically: an unknown id, an id that is not a UUID, a withdrawn or closed referral and one another judge
     * holds all produce this message, so no referral id is discoverable by comparing refusals.
     */
    private Referral visible(String referralId, CurrentUser caller) {
        return identifier(referralId)
            .flatMap(referrals::findWithCaseById)
            .filter(found -> visibleTo(found, caller))
            .orElseThrow(() -> new NotFoundError("Referral " + referralId + " not found"));
    }

    /**
     * One referral, its application and that application's timeline, witnesses and readable documents -- what
     * every method here answers with.
     *
     * <p>The same three things after a claim or a direction as after a read, because the judicial surface has one
     * representation of a referral and a transition's answer is that representation: the screen a judge sees next
     * is the papers, so a leaner write response would only cost them the read they are about to make. The
     * application is passed in rather than re-navigated, since {@code Referral.deedPoll} is {@code LAZY} and the
     * caller has already resolved it join-fetched.
     *
     * <p>The documents are {@link DocumentService#readableDocuments} rather than every row, so the list a judge is
     * shown and the download they then make apply one rule (design decision 2 of the 2026-10-19 plan): a row with
     * no file, or one another store wrote, is not offered as a link that would 404.
     */
    private JudicialReferralView view(Referral referral, DeedPoll application) {
        String caseId = application.getId();
        return new JudicialReferralView(referral, application,
            notes.findNotes(caseId), witnesses.findWitnesses(caseId), documents.readableDocuments(caseId));
    }

    /**
     * Whether this caller is the judge currently holding this referral -- a claim of theirs to refresh.
     *
     * <p>Both halves, for the reason {@code ReferralService.requireHeldBy} asks both: the state says a claim
     * exists and {@code claimedById} says whose. The same subject that method compares, so the question asked
     * before {@code touch} is called and the question {@code touch} itself enforces cannot disagree. Compared
     * against the caller's subject rather than the column's, so a {@code Claimed} row with no owner -- which V7's
     * backfill really produced, and which a claim made before the owner columns existed also is -- is false
     * rather than an exception.
     */
    private static boolean heldBy(Referral referral, CurrentUser caller) {
        return referral.getState() == ReferralState.Claimed
            && caller.requireUserId().equals(referral.getClaimedById());
    }

    /**
     * The id as a UUID, or empty for anything that is not one -- which no row can be stored under.
     *
     * <p>So a malformed id is the same 404 as an unknown one, exactly as {@code ReferralService} answers on the
     * caseworker surface: it is an id nothing could ever have been stored under, and a 400 would tell a caller
     * probing ids which of their guesses were the right shape.
     */
    private static Optional<UUID> identifier(String id) {
        try {
            return Optional.of(UUID.fromString(id));
        } catch (IllegalArgumentException notAnIdentifier) {
            return Optional.empty();
        }
    }
}
