package uk.gov.hmcts.reform.deedpoll.domain.savedqueries;

import uk.gov.hmcts.reform.deedpoll.domain.ForbiddenError;
import uk.gov.hmcts.reform.deedpoll.domain.auth.Capability;
import uk.gov.hmcts.reform.deedpoll.domain.auth.CurrentUser;
import uk.gov.hmcts.reform.deedpoll.entity.SavedQuery;

/**
 * Who may see, who may change, and who may publish a saved query. Three predicates and nothing else.
 *
 * <p><strong>The three are orthogonal and must not be folded together.</strong> Visibility and editability are
 * properties of a caller <em>and a row</em>; publishing is a property of the caller alone, which is why
 * {@link #publishableBy} takes no query. An Administrator may edit somebody else's shared query and may publish
 * their own, and those are different rules with different reasons.
 *
 * <p><strong>Separate from {@link SavedQueryService} on purpose, and the reason is about which tests run.</strong>
 * Every other service in this repo is tested against a real database, because each is a port whose reference
 * asserts the arguments a mocked Prisma client was called with and the stored row is the claim those assertions
 * stand for. Those tests are container-backed, and container-backed tests are excluded on the Jenkins agent --
 * {@code INTEGRATION_TESTS_ENABLED=false}, because the agent has no Docker. The rules here are the ones it would
 * be most expensive to get wrong and they need no database to state, so they live where a plain unit test can
 * reach them and CI actually runs them. {@code SavedQueryService} composes them; it does not restate them.
 *
 * <p>Both predicates take the caller through {@link CurrentUser#requireUserId()} rather than reading
 * {@code userId}, so a caller with no subject is refused instead of being compared against a null owner -- which
 * would otherwise make every unidentified caller the same person, each able to edit the others' queries.
 */
public final class SavedQueryPermissions {

    /** The refusal an ineligible editor gets, in one place because two operations raise it. */
    static final String NOT_YOURS = "Only the owner may change a saved query, or an Administrator if it is shared";

    /** The refusal a caller who may not publish gets. Names the rule, not the caller. */
    static final String NOT_PUBLISHABLE = "Only an Administrator may publish a saved query to the library";

    private SavedQueryPermissions() {
    }

    /**
     * Whether this caller may see this query at all: it is shared, or it is theirs.
     *
     * <p><strong>An Administrator gets no override here</strong>, and that is decision 2's refinement rather
     * than an omission. An unshared query is nobody else's business, and an orphaned private one harms nobody
     * because nobody else can see it. It is also what keeps {@link #editableBy} from needing a second rule: an
     * administrator never reaches another user's private query, so the question of whether they could edit it
     * does not arise. Do not add the override "for consistency".
     */
    public static boolean visibleTo(SavedQuery query, CurrentUser caller) {
        // Resolved before the predicate rather than inside it, so an unidentified caller is refused even for a
        // shared query. Written as `shared || requireUserId().equals(...)` the guard would short-circuit away on
        // exactly the rows where it matters least and stay on the ones where it matters most, which is a rule
        // that holds by accident.
        String userId = caller.requireUserId();
        return query.isShared() || userId.equals(query.getOwnerId());
    }

    /**
     * Whether this caller may amend or delete this query: the owner, or an Administrator if it is shared.
     *
     * <p>The administrator override exists because owner-only editing freezes a query the moment its owner goes
     * on leave, changes team or leaves, while everyone subscribed carries on relying on it -- and leave and
     * secondment are far more common than departure, so any answer that only handles leavers answers the rare
     * case.
     *
     * <p>Read by the write paths to refuse and by {@link SavedQueryView} to publish, so what a client is told
     * and what it is permitted cannot drift.
     */
    public static boolean editableBy(SavedQuery query, CurrentUser caller) {
        String userId = caller.requireUserId();
        return userId.equals(query.getOwnerId()) || (caller.can(Capability.MANAGE_SHARED_QUERIES) && query.isShared());
    }

    /**
     * Whether this caller may publish a saved query to the library: an Administrator, and nobody else.
     *
     * <p><strong>A property of the caller, not of the query</strong>, which is what makes this a third predicate
     * rather than a clause in either of the other two. Saving a query for yourself is every caseworker's;
     * publishing one, where every caseworker can find it and add it to their own list, is an Administrator's.
     * The rule came from the product owner on 2026-09-22 and is <em>not</em> in the Power App permission matrix
     * -- saved queries are new behaviour with no reference model, so there is nothing there to reconcile it
     * against. See the plan.
     *
     * <p>Takes the caller through {@link CurrentUser#requireUserId()} like the other two, so an unidentified
     * caller is refused here as well rather than being asked about its roles. The check is ordered that way
     * deliberately: a caller with no subject has no business publishing regardless of what a token claims.
     *
     * <p><strong>Unpublishing is not gated, and that is deliberate.</strong> This answers only "may they set
     * {@code shared} true"; {@link SavedQueryService#updateSavedQuery} does not consult it for a patch clearing
     * the flag. An Administrator who publishes a query and later loses the role still owns it, and withdrawing
     * it from the library is de-escalation -- gating that would freeze a published query in the library with
     * nobody able to remove it but a different Administrator.
     */
    public static boolean publishableBy(CurrentUser caller) {
        caller.requireUserId();

        return caller.can(Capability.MANAGE_SHARED_QUERIES);
    }

    /**
     * Refuses a caller who may not publish.
     *
     * <p>403 rather than 400: it is a permission and not a malformed field. Raised by the write paths before
     * anything is persisted, so a refusal never leaves a half-published query.
     *
     * @throws ForbiddenError if the caller is not an Administrator
     */
    public static void requirePublishableBy(CurrentUser caller) {
        if (!publishableBy(caller)) {
            throw new ForbiddenError(NOT_PUBLISHABLE);
        }
    }

    /**
     * Refuses a caller who may see a query but not change it.
     *
     * <p>403 rather than 404: they can already see it, so there is nothing left to conceal, and a 404 here would
     * be a lie a client cannot act on. The 404 for a query they cannot see is {@link SavedQueryService}'s, and
     * the two are reached in that order.
     *
     * @throws ForbiddenError if the caller is neither the owner nor an Administrator of a shared query
     */
    public static void requireEditableBy(SavedQuery query, CurrentUser caller) {
        if (!editableBy(query, caller)) {
            throw new ForbiddenError(NOT_YOURS);
        }
    }
}
