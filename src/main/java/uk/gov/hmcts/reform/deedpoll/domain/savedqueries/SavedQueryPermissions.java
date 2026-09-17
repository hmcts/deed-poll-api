package uk.gov.hmcts.reform.deedpoll.domain.savedqueries;

import uk.gov.hmcts.reform.deedpoll.domain.ForbiddenError;
import uk.gov.hmcts.reform.deedpoll.domain.auth.CurrentUser;
import uk.gov.hmcts.reform.deedpoll.entity.SavedQuery;

/**
 * Who may see, and who may change, a saved query. Two predicates and nothing else.
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
        return userId.equals(query.getOwnerId()) || (caller.isAdmin() && query.isShared());
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
