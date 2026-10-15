package uk.gov.hmcts.reform.deedpoll.api.caseworker.controller;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import uk.gov.hmcts.reform.deedpoll.api.AuditedAs;
import uk.gov.hmcts.reform.deedpoll.api.caseworker.dto.CreateReferralRequest;
import uk.gov.hmcts.reform.deedpoll.api.caseworker.dto.RecordConfirmationRequest;
import uk.gov.hmcts.reform.deedpoll.api.caseworker.dto.ReferralResponse;
import uk.gov.hmcts.reform.deedpoll.auth.CurrentUsers;
import uk.gov.hmcts.reform.deedpoll.domain.audit.AuditAction;
import uk.gov.hmcts.reform.deedpoll.domain.referrals.ReferralService;

import java.util.List;

/**
 * Referrals of a case to the judiciary: the caseworker's half of the loop.
 *
 * <p>List, raise, read, withdraw, close, and record the applicant's answer to the confirmation email.
 * <strong>Claiming a referral and recording a direction are the
 * judge's half, and they are transitions on {@code ReferralService} with no route here</strong> -- the judicial
 * pool, the read-through to a case's papers and the endpoints that call those transitions are the
 * judicial-surface plan's, on their own {@code /judicial/**} surface rather than bolted onto this one. Closing
 * is here because closing is a caseworker action: it is the acknowledgement that the returned direction has been
 * actioned by the office that has to action it.
 *
 * <p>Nested under the case, like the notes and witnesses collections, so every id in the URL is part of the
 * scope rather than decoration. Both ids are passed to the domain on every handler that addresses one referral,
 * which is the whole of the scoping rule: a referral id belonging to another case answers 404 instead of being
 * read or withdrawn through the wrong parent, and {@code ReferralRepository} has no finder by referral id alone
 * for exactly that reason.
 *
 * <p><strong>Withdrawal, closure and the confirmation are sub-resources, not a {@code DELETE} or a
 * {@code PATCH}.</strong> A withdrawn referral survives as {@code Withdrawn} -- distinct from {@code Closed},
 * which means a direction was actioned -- so there is nothing to delete; and there is no in-place editing of a
 * referral in MVP, so there is no patch for a state to be smuggled through. Amending a referral is withdrawing it
 * and raising another, which is why the create and the confirmation are the only routes here that take a body --
 * and the confirmation takes one because the applicant's answer is the payload, not because a referral became
 * editable.
 *
 * <p><strong>No role gate, deliberately.</strong> Any authenticated staff caller may raise, read, withdraw and
 * close a referral, and record an applicant's confirmation: there is no "may refer" permission, no "may close" one
 * and no "may record a reply" one, because in the process this replaces anybody who works a case can email a
 * Master, action the reply, and read the applicant's answer out of the shared inbox. The judicial role is the one that
 * is different -- {@code Role.JUDICIAL}, which IDAM does not issue yet -- and it gates the two transitions that
 * are not on this surface at all rather than a check bolted on here. So unlike the note and witness deletes,
 * nothing below these handlers asks what the caller may do.
 *
 * <p><strong>The caller is passed to every write for authorship, not authorisation.</strong> All four writes
 * here -- raising, withdrawing, closing and recording a confirmation -- put a note onto the case timeline as the
 * acting caseworker, and
 * there is no timeline concept in this domain beyond the notes grid, so the referral has to appear where
 * caseworkers already look. {@code ReferralService} takes the whole {@code CurrentUser} because it needs a name
 * for the audit columns and an author for that note, and on closure the two say different things: {@code
 * decidedBy} on the row is the judge who directed it, and the note's {@code createdBy} is who in the office
 * carried it out. Closing wrote no note until
 * {@code docs/plans/referrals-api-5-lifecycle-case-notes.md} -- the row's {@code Closed} was taken to say
 * everything a note could, and business testing found it does not say <em>when</em> the office acted. The reads
 * take no caller: a referral's visibility is its parent's, which is in the path.
 *
 * <p>None of the five conflicts these routes can answer is stated here. The one-live-referral guard, the
 * withdraw-only-before-a-direction guard, the close-only-when-{@code Decided} guard and the confirmation's two --
 * no second answer, and no answer nobody asked for -- are all in {@code ReferralService}, which throws
 * {@code ConflictError}, and {@code DomainErrorHandler} turns that into a 409 -- so the rules hold for a caller
 * that is not a route.
 *
 * <p>None of these routes is annotated as requiring authentication, and all six do:
 * {@code SecurityConfiguration} authenticates {@code anyRequest()} that is not on a short infrastructure
 * allowlist, so a route is protected by having been written rather than by having been remembered.
 */
@RestController
@RequestMapping("/caseworker/cases/{id}/referrals")
public class CaseworkerReferralController {

    private final ReferralService referrals;

    public CaseworkerReferralController(ReferralService referrals) {
        this.referrals = referrals;
    }

    /**
     * A case's referrals, newest first -- the history and the live one in one list.
     *
     * <p>One list rather than "the current referral" and "the past ones" separately: there is at most one
     * non-terminal referral, so a client that wants it reads the first row whose state is not terminal, and a
     * referrals tab needs the whole list anyway.
     *
     * <p>404s for a case that does not exist rather than answering an empty list, for the reason the notes list
     * gives: filtering by the parent id alone gives an empty list both for a mistyped case id and for a real
     * case that has never been referred, and only one of those is true.
     */
    @GetMapping
    public List<ReferralResponse> referrals(@PathVariable("id") String id) {
        return ReferralResponse.from(referrals.findReferrals(id));
    }

    /**
     * Raises a referral on a case, unclaimed and in the shared pool.
     *
     * <p>201 with the created referral, as the note, witness and saved-query creates answer.
     *
     * <p><strong>409 if the case already holds a referral that is not terminal.</strong> Not a 400: nothing
     * about the payload is wrong, and the same request succeeds unchanged once the live referral is closed or
     * withdrawn. A case whose referrals are all terminal may be referred again.
     */
    @AuditedAs(AuditAction.REFERRAL_CREATED)
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ReferralResponse create(@PathVariable("id") String id, @RequestBody CreateReferralRequest request) {
        return ReferralResponse.from(
            referrals.createReferral(id, request.toInput(), CurrentUsers.required()));
    }

    /**
     * One referral of one case.
     *
     * <p>No check for a missing referral, for the reason the read controller gives: the domain reports absence
     * by throwing, so a branch here would be unreachable. An id that is not a UUID at all is the same 404
     * rather than a 400 -- it is an id nothing could ever have been stored under.
     */
    @GetMapping("/{referralId}")
    public ReferralResponse referral(@PathVariable("id") String id,
                                     @PathVariable("referralId") String referralId) {

        return ReferralResponse.from(referrals.findReferral(id, referralId));
    }

    /**
     * Withdraws a referral: the caseworker pulling it back before it is actioned.
     *
     * <p>200 with the withdrawn referral rather than 204, because the row survives the transition and its new
     * state is the answer -- a client re-rendering the referrals tab has it without a second read.
     *
     * <p>409 for anything a caseworker may no longer pull back, and deliberately not a second 204: withdrawing
     * twice is a caseworker acting on a stale screen, and a success would tell them their click did something it
     * did not. The guard asks {@code ReferralState.canBeWithdrawn()}, so it permits {@code Open} and
     * {@code Claimed} -- a referral a judge is merely holding may still be corrected or cancelled, and the
     * transition voids the claim -- and refuses the rest.
     *
     * <p><strong>{@code Decided} is refused, and it is not terminal.</strong> A direction has come back and the
     * office is obliged to action it rather than discard it, so the move on a decided referral is the closure
     * route below, not this one. Its 409 says so, in different words from the {@code Closed}/{@code Withdrawn}
     * one, because being sent to the closure journey and being told the referral is already finished are
     * different facts. The guard is therefore deliberately <em>not</em> written against "non-terminal": that
     * question still has its own callers -- the one-live-referral guard above and {@code currentReferralState} --
     * and answering it differently to serve this route would move both.
     */
    @AuditedAs(AuditAction.REFERRAL_WITHDRAWN)
    @PostMapping("/{referralId}/withdrawal")
    public ReferralResponse withdraw(@PathVariable("id") String id,
                                     @PathVariable("referralId") String referralId) {

        return ReferralResponse.from(
            referrals.withdrawReferral(id, referralId, CurrentUsers.required()));
    }

    /**
     * Closes a referral whose direction has been actioned.
     *
     * <p><strong>A caseworker action, and the only one on this surface that a judge cannot do.</strong> Closing
     * is not bookkeeping: it is the office saying it has done what the direction required, which is what drops
     * the referral off the "returned -- action needed" queue -- that queue is "state is {@code Decided}". So the
     * caller is the caseworker who actioned it, with no role gate, exactly as raising and withdrawing have none.
     *
     * <p>A sub-resource and a {@code POST} rather than a {@code PATCH} carrying a state, for the reason
     * withdrawal is one: the state is not a field a caller sets, it is the result of a transition with a guard
     * in front of it, and a patch would let a client name any state it liked.
     *
     * <p>200 with the closed referral, carrying the direction that was actioned -- the same answer withdrawal
     * gives and for the same reason: the row survives, so its new state is the answer, and a client
     * re-rendering the referrals tab has it without a second read.
     *
     * <p>409 for anything that is not {@code Decided}, from {@code ReferralService}. Closing an {@code Open} or
     * {@code Claimed} referral would take it out of the pool with no direction on it, and closing a
     * {@code Withdrawn} one would relabel an abandoned referral as an actioned one.
     */
    @AuditedAs(AuditAction.REFERRAL_CLOSED)
    @PostMapping("/{referralId}/closure")
    public ReferralResponse close(@PathVariable("id") String id,
                                  @PathVariable("referralId") String referralId) {

        return ReferralResponse.from(referrals.close(id, referralId, CurrentUsers.required()));
    }

    /**
     * Records what the applicant answered when the office asked whether they still want the deed enrolled.
     *
     * <p>The reply to the <em>Request confirmation to proceed</em> email, which a caseworker reads out of the
     * monitored inbox and records here: {@code Confirmed} if the applicant wants enrolment to go ahead knowing the
     * change will be published, {@code Withdrawn} if they do not and want a refund.
     *
     * <p><strong>Its own operation rather than a case edit</strong>, and scoped by the case as every referral
     * operation here is -- the answer is a fact about that referral's outcome, and a case carrying a second referral
     * would make it ambiguous anywhere else. A referral of another case is the same 404 an unknown id is, through
     * the case-scoped lookup.
     *
     * <p><strong>It does not move the case on, and that is deliberate.</strong> Recording {@code Confirmed} sets no
     * date and clears none: the caseworker then does whatever the process requires through the case edit, audited as
     * their action rather than as a side effect of this one. There is likewise no new case state for the wait --
     * a case's state is derived from its date columns by a verbatim port -- so a client shows the wait alongside the
     * state rather than inside it.
     *
     * <p>A sub-resource and a {@code POST}, like the withdrawal and the closure, and for the sharper version of
     * their reason: <strong>it is idempotent by refusal</strong>. Recording an outcome twice answers 409 naming what
     * is already stored and when, because the second caseworker needs to know the first already acted rather than
     * silently overwriting them -- and recording an answer on a referral nobody asked to confirm is a 409 too, since
     * that is a mis-click on the wrong case rather than a valid state. A {@code PATCH} carrying the column would
     * make both of those a matter of who saved last.
     *
     * <p>200 with the referral carrying its four confirmation columns, so a client re-rendering the tab has the
     * answer, its date and its author without a second read.
     */
    @AuditedAs(AuditAction.REFERRAL_CONFIRMED)
    @PostMapping("/{referralId}/confirmation")
    public ReferralResponse recordConfirmation(@PathVariable("id") String id,
                                               @PathVariable("referralId") String referralId,
                                               @RequestBody RecordConfirmationRequest request) {

        return ReferralResponse.from(
            referrals.recordConfirmation(id, referralId, request.toInput(), CurrentUsers.required()));
    }
}
