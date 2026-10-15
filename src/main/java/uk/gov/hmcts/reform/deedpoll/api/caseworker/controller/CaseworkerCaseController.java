package uk.gov.hmcts.reform.deedpoll.api.caseworker.controller;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import uk.gov.hmcts.reform.deedpoll.api.AuditedAs;
import uk.gov.hmcts.reform.deedpoll.api.caseworker.dto.CreateCaseRequest;
import uk.gov.hmcts.reform.deedpoll.api.caseworker.dto.SavedCaseResponse;
import uk.gov.hmcts.reform.deedpoll.api.caseworker.dto.UpdateCaseRequest;
import uk.gov.hmcts.reform.deedpoll.auth.CurrentUsers;
import uk.gov.hmcts.reform.deedpoll.domain.audit.AuditAction;
import uk.gov.hmcts.reform.deedpoll.domain.deedpoll.DeedPollService;

/**
 * The caseworker case writes: creating a case and amending one.
 *
 * <p>Two of the eleven operations plan 01 builds. A controller per written resource, where the four reads share
 * one: the reads are one thing to a consumer -- everything a screen needs before it can render -- and a write
 * belongs with the resource it writes, so the notes and documents writes of task 15 get their own rather than
 * accumulating here.
 *
 * <p><strong>There is deliberately no {@code DELETE} and no {@code validate}.</strong> The reference has both.
 * Neither has a consumer in {@code deed-poll-admin}'s data-access layer, and the eleven operations this plan
 * builds are the ones something actually calls; the sibling API built every route an imagined consumer might
 * want, which is the habit being broken. The domain methods exist and are tested
 * ({@code removeDeedPoll}, {@code validateCaseForSubmit}), so exposing either later is a route, not a design.
 *
 * <p>Both handlers do the same three things: validate the body against the ported schema, call the domain with
 * the caller's write context, and map the result. Every refusal is thrown rather than returned --
 * {@code InvalidInputError} for a rejected payload or contradictory dates, {@code NotFoundError} for a case
 * that is not there -- and {@code DomainErrorHandler} turns each into its status, so there is no status code
 * decided in this class beyond the 201.
 *
 * <p>Neither route is annotated as requiring authentication, and both do: {@code SecurityConfiguration}
 * authenticates {@code anyRequest()} that is not on a short infrastructure allowlist, so a route is protected
 * by having been written rather than by having been remembered.
 */
@RestController
@RequestMapping("/caseworker/cases")
public class CaseworkerCaseController {

    private final DeedPollService deedPolls;

    public CaseworkerCaseController(DeedPollService deedPolls) {
        this.deedPolls = deedPolls;
    }

    /**
     * Creates a case and allocates its reference number.
     *
     * <p>201 with the created case, as the reference's route sets explicitly and the prototype got from its
     * framework's default. No {@code Location} header: the id is in the body the caller already has to read to
     * learn its reference number, and a header naming a route this service does not otherwise advertise would
     * be a second contract to keep true.
     *
     * <p>The caller's admin status is passed rather than assumed, because it decides the create-time privacy
     * auto-default and whether a {@code privacy} the caller sent is honoured at all. Their display name is
     * audit text, recorded as {@code createdBy} and {@code modifiedBy}; {@code CurrentUser} derives both, so
     * neither is a claim this class makes.
     *
     * <p>A create with no {@code dateReceived} is a 400 attributed to that field, not a 500. Reference-number
     * allocation derives the year from it and cannot proceed without one -- the reference records this as a
     * known sharp edge, and nothing is written when it happens, because the year is derived before the counter
     * is touched.
     */
    @AuditedAs(AuditAction.CASE_CREATED)
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public SavedCaseResponse create(@RequestBody CreateCaseRequest request) {
        return SavedCaseResponse.from(
            deedPolls.createDeedPoll(request.toPatch(), CurrentUsers.required().writeContext()));
    }

    /**
     * Applies a patch to a case.
     *
     * <p>Everything that makes this more than a field copy is in the domain and stays there: the Gazette and
     * minor gates, the cross-field date rules, the privacy lock, the re-prefix when the type changes and the
     * re-derived state. {@code DeedPollService.updateDeedPoll} has the order they run in and why it matters.
     *
     * <p>No check for a missing case, for the reason the read controller gives: the domain reports absence by
     * throwing, so a branch here would be unreachable.
     */
    @AuditedAs(AuditAction.CASE_UPDATED)
    @PatchMapping("/{id}")
    public SavedCaseResponse update(@PathVariable("id") String id, @RequestBody UpdateCaseRequest request) {
        return SavedCaseResponse.from(
            deedPolls.updateDeedPoll(id, request.toPatch(), CurrentUsers.required().writeContext()));
    }
}
