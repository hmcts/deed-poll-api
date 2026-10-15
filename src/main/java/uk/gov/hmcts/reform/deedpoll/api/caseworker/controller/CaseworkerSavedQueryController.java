package uk.gov.hmcts.reform.deedpoll.api.caseworker.controller;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import uk.gov.hmcts.reform.deedpoll.api.AuditedAs;
import uk.gov.hmcts.reform.deedpoll.api.caseworker.dto.CreateSavedQueryRequest;
import uk.gov.hmcts.reform.deedpoll.api.caseworker.dto.SavedQueryResponse;
import uk.gov.hmcts.reform.deedpoll.api.caseworker.dto.UpdateSavedQueryRequest;
import uk.gov.hmcts.reform.deedpoll.auth.CurrentUsers;
import uk.gov.hmcts.reform.deedpoll.domain.audit.AuditAction;
import uk.gov.hmcts.reform.deedpoll.domain.savedqueries.SavedQueryService;

import java.util.List;

/**
 * Saved and shared case-list queries: list, save, read, amend and delete, plus select and unselect.
 *
 * <p><strong>Why this resource is here and not in {@code deed-poll-admin}.</strong> A saved query needs
 * somewhere to persist per-user state, and the admin app owns no database and never will -- its only local
 * state is Redis sessions, which are transport state. A <em>shared</em> query is definitively not session
 * state: one user's write is another user's read.
 *
 * <p><strong>Not nested under a case</strong>, unlike every other collection on this surface. A saved query is
 * a way of finding cases rather than a thing belonging to one, so there is no parent id to scope it by -- which
 * is exactly why the permission rules below have to be written out. The notes and witnesses routes get their
 * safety from the parent in the path; this one has to get it from the entity.
 *
 * <p><strong>Every rule about who may do what is in {@code SavedQueryService}</strong>, not here. This class
 * states none of its own, so the rules hold for any future caller rather than for this route -- the same split
 * the note and witness deletes use. What the caller sees:
 *
 * <ul>
 *   <li>A query that is neither shared nor theirs is <strong>404</strong> on the five query operations, carrying
 *       the message an id that never existed gets. Deliberate, and a reviewer who has not read this will read it
 *       as a bug: it is what keeps other people's private queries undiscoverable, since a 403 would confirm the
 *       id names something.</li>
 *   <li>A query they can see but may not change is <strong>403</strong>. They can already see it, so there is
 *       nothing left to conceal.</li>
 * </ul>
 *
 * <p><strong>Selection is a separate sub-resource</strong> and not a field on the query. Amending a query is the
 * owner's right; choosing to see it is every caseworker's, and keeping them apart is what stops "add this to my
 * list" needing edit permission. Both of its verbs are idempotent, and its {@code DELETE} answers 204 for an id
 * the caller cannot see rather than 404 -- the one place on this resource where an invisible query is not a 404,
 * for the reason given on the method. Duplicating a query is likewise not an operation: a client reads one and
 * posts a new one, which needs no endpoint because there is no invariant to protect.
 *
 * <p>None of these routes is annotated as requiring authentication, and all seven do:
 * {@code SecurityConfiguration} authenticates {@code anyRequest()} that is not on a short infrastructure
 * allowlist, so a route is protected by having been written rather than by having been remembered. Every one of
 * them passes the caller, because on this resource identity is not audit trimming -- it decides what comes back.
 */
@RestController
@RequestMapping("/caseworker/saved-queries")
public class CaseworkerSavedQueryController {

    private final SavedQueryService savedQueries;

    public CaseworkerSavedQueryController(SavedQueryService savedQueries) {
        this.savedQueries = savedQueries;
    }

    /**
     * The caller's library: every shared query plus their own, by name, each flagged with whether they have
     * selected it and whether they may change it.
     *
     * <p>Shared-or-mine rather than selected-only, because this is what a "browse the library and add one"
     * screen reads. A navigation bar renders the subset where {@code selected} is true, which is a filter the
     * client already has the data for rather than a second endpoint.
     */
    @GetMapping
    public List<SavedQueryResponse> savedQueries() {
        return SavedQueryResponse.from(savedQueries.findSavedQueries(CurrentUsers.required()));
    }

    /**
     * Saves a new query, owned by the caller.
     *
     * <p>201 with the created query, as the note and witness creates answer. {@code name} is the only required
     * field -- "everything, newest first" is a view somebody wants a tab for. The owner comes from the token, so
     * a body cannot claim a query for somebody else.
     *
     * <p>Saving is not selecting: the new query is in the library and in nobody's list, including its owner's.
     * A client that wants both makes both calls, which is the same shape as adding somebody else's query.
     *
     * <p><strong>403 when a body sets {@code shared} and the caller is not an Administrator.</strong> Saving a
     * query for yourself is every caseworker's right; publishing one to the library is not. Stated here rather
     * than gated here -- the rule is the service's, like every other rule about who may do what.
     */
    @AuditedAs(AuditAction.SAVED_QUERY_CREATED)
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public SavedQueryResponse create(@RequestBody CreateSavedQueryRequest request) {
        return SavedQueryResponse.from(
            savedQueries.createSavedQuery(request.toInput(), CurrentUsers.required()));
    }

    /**
     * One saved query, if it is shared or the caller's own.
     *
     * <p>No check for a missing query, for the reason the read controller gives: the domain reports absence --
     * and inaccessibility, identically -- by throwing, so a branch here would be unreachable.
     */
    @GetMapping("/{id}")
    public SavedQueryResponse savedQuery(@PathVariable("id") String id) {
        return SavedQueryResponse.from(savedQueries.findSavedQuery(id, CurrentUsers.required()));
    }

    /**
     * Amends a saved query. The owner, or an Administrator if it is shared.
     *
     * <p>Only the fields the body names are written; an omitted one is left as stored, and a nullable one sent
     * as null is cleared. An empty body is accepted and re-stamps the audit columns.
     *
     * <p>A saved query is a live reference rather than a copy, so an edit here is visible to everyone who has
     * selected it. That is the point: a shared definition that each subscriber owns a diverging copy of is the
     * spreadsheet this service replaces.
     *
     * <p><strong>403 when a body sets {@code shared} true and the caller is not an Administrator</strong> --
     * but not when it clears the flag, which anyone who may amend the query may do. A caller who may not see
     * the query at all still gets the 404 first, so the refusal never confirms that an id names something
     * publishable.
     */
    @AuditedAs(AuditAction.SAVED_QUERY_UPDATED)
    @PatchMapping("/{id}")
    public SavedQueryResponse update(@PathVariable("id") String id,
                                     @RequestBody UpdateSavedQueryRequest request) {

        return SavedQueryResponse.from(
            savedQueries.updateSavedQuery(id, request.toInput(), CurrentUsers.required()));
    }

    /**
     * Deletes a saved query. The owner, or an Administrator if it is shared.
     *
     * <p>204 with no body, as the note and witness deletes answer. Permitted even where others have selected
     * it, and their selections go with it through the foreign key's cascade -- an orphaned selection would
     * resurface as a broken navigation entry in every one of their lists.
     *
     * <p>Deleting twice 404s on the second call rather than answering 204 again, because absence is reported by
     * throwing. Not idempotent in the strict HTTP sense, and consistent with the reads -- and deliberately
     * unlike {@code DELETE .../selection}, where unselecting something already unselected is not a mistake.
     */
    @AuditedAs(AuditAction.SAVED_QUERY_DELETED)
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable("id") String id) {
        savedQueries.removeSavedQuery(id, CurrentUsers.required());
    }

    /**
     * Adds a query to the caller's own list. 204, and a second call is another 204 rather than a conflict.
     *
     * <p>A sub-resource rather than a field on the query, because that is what keeps "add this to my list" out of
     * the owner's permission: amending a query is the owner's right, choosing to see it is every caseworker's. So
     * this is permitted on somebody else's shared query, which is the case the library exists for, and it is
     * <em>not</em> permitted on a query the caller cannot see -- that answers the same 404 as an id that never
     * existed, since selecting must not be a way to discover a private query.
     *
     * <p>{@code PUT} rather than {@code POST}: the request states the end state -- this query is in my list --
     * which is true however many times it is sent, and a client retrying a lost response must not be told it has
     * a duplicate to clean up.
     */
    @PutMapping("/{id}/selection")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void select(@PathVariable("id") String id) {
        savedQueries.addSelection(id, CurrentUsers.required());
    }

    /**
     * Removes a query from the caller's own list. 204, whether or not it was in it.
     *
     * <p>Unselecting something already unselected is not a mistake, so it is not a 404 -- deliberately unlike
     * {@code DELETE} on the query itself, where the thing being deleted is shared state and its absence is worth
     * reporting. Here the state is the caller's own and the request only says what they want to be true of it.
     *
     * <p>It also answers 204 for a query the caller cannot see, which the service explains: the row is keyed by
     * the caller, so the delete cannot reach anybody else's, and requiring visibility would trap a subscriber
     * with a stale selection for a query its owner has unshared -- the one selection they most need to clear and
     * the one they can no longer see. The answer does not vary with the id, so nothing is disclosed by it.
     */
    @DeleteMapping("/{id}/selection")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deselect(@PathVariable("id") String id) {
        savedQueries.removeSelection(id, CurrentUsers.required());
    }
}
