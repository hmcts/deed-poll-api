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
import uk.gov.hmcts.reform.deedpoll.api.AuditedAs;
import uk.gov.hmcts.reform.deedpoll.api.caseworker.dto.CreateWitnessRequest;
import uk.gov.hmcts.reform.deedpoll.api.caseworker.dto.UpdateWitnessRequest;
import uk.gov.hmcts.reform.deedpoll.api.caseworker.dto.WitnessResponse;
import uk.gov.hmcts.reform.deedpoll.auth.CurrentUsers;
import uk.gov.hmcts.reform.deedpoll.domain.audit.AuditAction;
import uk.gov.hmcts.reform.deedpoll.domain.witnesses.WitnessService;

import java.util.List;

/**
 * A case's witnesses and declarants: list, add, read, amend and -- for an Administrator -- delete.
 *
 * <p><strong>Added because the readable-but-not-writable gap was the only one on this surface.</strong>
 * {@code deed-poll-admin} renders a case's witnesses from the nested list on {@code GET /caseworker/cases/{id}}
 * and has no way to record one, and the business document gives Basic User "Create, view and edit witness
 * records" -- so the capability exists and the route did not. Everything below this class was already built and
 * tested with plan 01: {@code WitnessService} has all five operations and this class is a route over each of
 * them, which is the consumer the service was keeping itself for. Four of the five landed first; the delete is
 * the fifth route and the last of the five operations to acquire one.
 *
 * <p>Shaped exactly like {@code CaseworkerNoteController}, nested under the case so every id in the URL is part
 * of the scope rather than decoration. Both ids are passed to the domain on every handler that addresses one
 * witness, which is the whole of the scoping rule: a witness id belonging to another case answers 404 instead of
 * being read or amended through the wrong parent, and {@code DeedPollWitnessRepository} has no unscoped finder
 * for exactly that reason.
 *
 * <p><strong>No role check on create or amend, and one on delete.</strong> The capability matrix marks "Add and
 * edit witnesses" for both roles, so gating those in the tidy-minded direction would remove something caseworkers
 * are meant to have -- the trap the note-deletion gate called out for note editing, where only deletion is
 * privileged. "Delete witnesses" is the one row of that matrix marked for Administrators alone, and it is
 * enforced in {@code WitnessService.removeWitness} rather than here, so the rule holds for every caller rather
 * than for this route.
 *
 * <p><strong>Only the delete takes a caller</strong>, which is the visible difference from the note controller,
 * where every write does. A note passes a {@code CurrentUser} for authorship as well as authorisation, because
 * {@code deedpoll_note} stores an author; {@code deedpoll_witness} stores only {@code createdOn} and
 * {@code modifiedOn}, and Dataverse's {@code CreatedBy} is one of the platform system columns deliberately not
 * ported. {@code WitnessService} stamps the two timestamps through {@code AuditClock}. So the caller here is
 * passed for authorisation and nothing else, and threading it through the reads and the two open writes as well
 * would invite the false impression that a role check happens somewhere below them.
 *
 * <p>None of these routes is annotated as requiring authentication, and all five do:
 * {@code SecurityConfiguration} authenticates {@code anyRequest()} that is not on a short infrastructure
 * allowlist, so a route is protected by having been written rather than by having been remembered.
 */
@RestController
@RequestMapping("/caseworker/cases/{id}/witnesses")
public class CaseworkerWitnessController {

    private final WitnessService witnesses;

    public CaseworkerWitnessController(WitnessService witnesses) {
        this.witnesses = witnesses;
    }

    /**
     * A case's witnesses, oldest first.
     *
     * <p>Oldest first because that is the order the case detail page's subgrid renders them in, and this list is
     * the same rows -- a witness list is read as a declaration is laid out rather than as a history.
     *
     * <p><strong>404s for a case that does not exist, rather than answering an empty list.</strong> Filtering by
     * the parent id alone gives an empty list both for a mistyped case id and for a real case with no witnesses,
     * and only one of those is true. {@code WitnessService.findWitnesses} checks the parent for that, which costs
     * one primary-key lookup.
     */
    @GetMapping
    public List<WitnessResponse> witnesses(@PathVariable("id") String id) {
        return WitnessResponse.from(witnesses.findWitnesses(id));
    }

    /**
     * Adds a witness to a case.
     *
     * <p>201 with the created witness, as the note create answers. All four fields are required and the caps are
     * the source columns' -- 100 / 250 / 100 -- both enforced in {@code WitnessSchemas} rather than here, so the
     * API and any form cannot disagree about what a witness is. Values are stored as submitted and not trimmed,
     * which is the reference's own choice and is argued there.
     */
    @AuditedAs(AuditAction.WITNESS_CREATED)
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public WitnessResponse create(@PathVariable("id") String id, @RequestBody CreateWitnessRequest request) {
        return WitnessResponse.from(witnesses.createWitness(id, request.toInput(), CurrentUsers.required()));
    }

    /**
     * One witness of one case.
     *
     * <p>No check for a missing witness, for the reason the read controller gives: the domain reports absence by
     * throwing, so a branch here would be unreachable.
     */
    @GetMapping("/{witnessId}")
    public WitnessResponse witness(@PathVariable("id") String id, @PathVariable("witnessId") String witnessId) {
        return WitnessResponse.from(witnesses.findWitness(id, witnessId));
    }

    /**
     * Amends a witness.
     *
     * <p>Only the fields the body names are written; an omitted one is left as stored. An empty body is accepted
     * and re-stamps {@code modifiedOn} rather than being an error -- {@code UpdateWitnessInput.EMPTY} is a state
     * the domain already has a name for. An explicit null is a 400 instead of a clear, because none of the four
     * columns can hold one.
     */
    @AuditedAs(AuditAction.WITNESS_UPDATED)
    @PatchMapping("/{witnessId}")
    public WitnessResponse update(@PathVariable("id") String id,
                                  @PathVariable("witnessId") String witnessId,
                                  @RequestBody UpdateWitnessRequest request) {

        return WitnessResponse.from(witnesses.updateWitness(id, witnessId, request.toInput(), CurrentUsers.required()));
    }

    /**
     * Deletes a witness. <strong>Administrators only.</strong>
     *
     * <p>204 with no body, as the note delete answers: there is nothing meaningful to return, and the deleted
     * witness is not it. Both ids go to the domain, so a witness addressed through the wrong case 404s rather
     * than being deleted -- which matters more here than on the reads, because an unscoped delete is destructive
     * and silent.
     *
     * <p><strong>The caller is passed for authorisation only.</strong> {@code removeWitness} decides who may
     * delete and refuses anyone else with a 403; this handler states no rule of its own, so the rule lives in
     * exactly one place and a future caller that is not this route is held to it too. No author is recorded --
     * the row is gone, and {@code deedpoll_witness} has nowhere to record one in any case.
     *
     * <p>A refusal is a 403 and not a 404, and it is the same 403 for a witness that does not exist: the domain
     * refuses before it resolves, so a caller without the role cannot use the status code to tell one from the
     * other. Deleting twice 404s on the second call for an Administrator, because absence is reported by
     * throwing -- not idempotent in the strict HTTP sense, and consistent with the reads.
     */
    @AuditedAs(AuditAction.WITNESS_DELETED)
    @DeleteMapping("/{witnessId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable("id") String id, @PathVariable("witnessId") String witnessId) {
        witnesses.removeWitness(id, witnessId, CurrentUsers.required());
    }
}
