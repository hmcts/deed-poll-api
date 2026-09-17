package uk.gov.hmcts.reform.deedpoll.api.caseworker.controller;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import uk.gov.hmcts.reform.deedpoll.api.caseworker.dto.CreateSavedQueryRequest;
import uk.gov.hmcts.reform.deedpoll.api.caseworker.dto.SavedQueryResponse;
import uk.gov.hmcts.reform.deedpoll.api.caseworker.dto.UpdateSavedQueryRequest;
import uk.gov.hmcts.reform.deedpoll.auth.CurrentUsers;
import uk.gov.hmcts.reform.deedpoll.domain.savedqueries.SavedQueryService;

import java.util.List;

/**
 * Saved and shared case-list queries: list, save, read, amend and delete.
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
 *   <li>A query that is neither shared nor theirs is <strong>404</strong> on all five operations, carrying the
 *       message an id that never existed gets. Deliberate, and a reviewer who has not read this will read it as
 *       a bug: it is what keeps other people's private queries undiscoverable, since a 403 would confirm the id
 *       names something.</li>
 *   <li>A query they can see but may not change is <strong>403</strong>. They can already see it, so there is
 *       nothing left to conceal.</li>
 * </ul>
 *
 * <p><strong>Selection is a separate sub-resource</strong> and not a field on the query. Amending a query is the
 * owner's right; choosing to see it is every caseworker's, and keeping them apart is what stops "add this to my
 * list" needing edit permission. Duplicating a query is likewise not an operation: a client reads one and posts
 * a new one, which needs no endpoint because there is no invariant to protect.
 *
 * <p>None of these routes is annotated as requiring authentication, and all five do:
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
     */
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
     */
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
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable("id") String id) {
        savedQueries.removeSavedQuery(id, CurrentUsers.required());
    }
}
