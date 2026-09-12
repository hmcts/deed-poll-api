package uk.gov.hmcts.reform.deedpoll.api.caseworker.controller;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import uk.gov.hmcts.reform.deedpoll.api.caseworker.dto.CreateWitnessRequest;
import uk.gov.hmcts.reform.deedpoll.api.caseworker.dto.UpdateWitnessRequest;
import uk.gov.hmcts.reform.deedpoll.api.caseworker.dto.WitnessResponse;
import uk.gov.hmcts.reform.deedpoll.domain.witnesses.WitnessService;

import java.util.List;

/**
 * A case's witnesses and declarants: list, add, read and amend.
 *
 * <p><strong>Added because the readable-but-not-writable gap was the only one on this surface.</strong>
 * {@code deed-poll-admin} renders a case's witnesses from the nested list on {@code GET /caseworker/cases/{id}}
 * and has no way to record one, and the business document gives Basic User "Create, view and edit witness
 * records" -- so the capability exists and the route did not. Everything below this class was already built and
 * tested with plan 01: {@code WitnessService} has all five operations and this adds four routes over them, which
 * is the consumer the service was keeping itself for.
 *
 * <p>Shaped exactly like {@code CaseworkerNoteController}, nested under the case so every id in the URL is part
 * of the scope rather than decoration. Both ids are passed to the domain on every handler that addresses one
 * witness, which is the whole of the scoping rule: a witness id belonging to another case answers 404 instead of
 * being read or amended through the wrong parent, and {@code DeedPollWitnessRepository} has no unscoped finder
 * for exactly that reason.
 *
 * <p><strong>No role check, on either write.</strong> The capability matrix marks "Add and edit witnesses" for
 * both roles, so gating in the tidy-minded direction would remove something caseworkers are meant to have -- the
 * trap the note-deletion gate called out for note editing, where only deletion is privileged. There is nothing
 * to gate on here in any case: no operation on this class consults the caller.
 *
 * <p><strong>And no caller parameter at all</strong>, which is the visible difference from the note controller.
 * A note takes a {@code CurrentUser} because {@code deedpoll_note} stores an author; {@code deedpoll_witness}
 * stores only {@code createdOn} and {@code modifiedOn}, and Dataverse's {@code CreatedBy} is one of the platform
 * system columns deliberately not ported. {@code WitnessService} stamps the two timestamps through
 * {@code AuditClock}. Threading a caller through anyway would invite the false impression that a role check
 * happens somewhere below.
 *
 * <p><strong>There is deliberately no delete here yet.</strong> It carries a role gate of its own and is its own
 * change; {@code WitnessService.removeWitness} exists and is tested, so it is a route away when it lands.
 *
 * <p>None of these routes is annotated as requiring authentication, and all four do:
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
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public WitnessResponse create(@PathVariable("id") String id, @RequestBody CreateWitnessRequest request) {
        return WitnessResponse.from(witnesses.createWitness(id, request.toInput()));
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
    @PatchMapping("/{witnessId}")
    public WitnessResponse update(@PathVariable("id") String id,
                                  @PathVariable("witnessId") String witnessId,
                                  @RequestBody UpdateWitnessRequest request) {

        return WitnessResponse.from(witnesses.updateWitness(id, witnessId, request.toInput()));
    }
}
