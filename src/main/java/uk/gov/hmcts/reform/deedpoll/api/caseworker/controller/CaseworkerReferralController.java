package uk.gov.hmcts.reform.deedpoll.api.caseworker.controller;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import uk.gov.hmcts.reform.deedpoll.api.caseworker.dto.CreateReferralRequest;
import uk.gov.hmcts.reform.deedpoll.api.caseworker.dto.ReferralResponse;
import uk.gov.hmcts.reform.deedpoll.auth.CurrentUsers;
import uk.gov.hmcts.reform.deedpoll.domain.referrals.ReferralService;

import java.util.List;

/**
 * Referrals of a case to the judiciary: the caseworker's half of the loop.
 *
 * <p>List, raise, read, withdraw and close. <strong>Claiming a referral and recording a direction are the
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
 * <p><strong>Withdrawal and closure are sub-resources, not a {@code DELETE} or a {@code PATCH}.</strong> A
 * withdrawn referral survives as {@code Withdrawn} -- distinct from {@code Closed}, which means a direction was
 * actioned -- so there is nothing to delete; and there is no in-place editing of a referral in MVP, so there is
 * no patch for a state to be smuggled through. Amending a referral is withdrawing it and raising another, which
 * is why the create is the only route here that takes a body.
 *
 * <p><strong>No role gate, deliberately.</strong> Any authenticated staff caller may raise, read, withdraw and
 * close a referral: there is no "may refer" permission and no "may close" one, because in the process this
 * replaces anybody who works a case can email a Master and action the reply. The judicial role is the one that
 * is different -- {@code Role.JUDICIAL}, which IDAM does not issue yet -- and it gates the two transitions that
 * are not on this surface at all rather than a check bolted on here. So unlike the note and witness deletes,
 * nothing below these handlers asks what the caller may do.
 *
 * <p><strong>The caller is passed to every write for authorship, not authorisation.</strong> Raising and
 * withdrawing each write a note onto the case timeline as the acting caseworker -- there is no timeline concept
 * in this domain beyond the notes grid, so the referral has to appear where caseworkers already look -- and
 * {@code ReferralService} takes the whole {@code CurrentUser} because it needs a name for the audit columns and
 * an author for that note. Closing writes no note, and still takes the caller for the audit columns: who closed
 * a referral is the record of who says the direction was actioned. The reads take none: a referral's visibility
 * is its parent's, which is in the path.
 *
 * <p>None of the three conflicts these routes can answer is stated here. The one-live-referral guard, the
 * withdraw-only-while-live guard and the close-only-when-{@code Decided} guard are all in
 * {@code ReferralService}, which throws {@code ConflictError}, and {@code DomainErrorHandler} turns that into a
 * 409 -- so the rules hold for a caller that is not a route.
 *
 * <p>None of these routes is annotated as requiring authentication, and all five do:
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
     * <p>409 for a referral that is already terminal, and deliberately not a second 204: withdrawing twice is a
     * caseworker acting on a stale screen, and a success would tell them their click did something it did not.
     * The guard is written against "non-terminal" rather than against {@code Open}, so the claimed and decided
     * states the directions plan adds need no change here.
     */
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
    @PostMapping("/{referralId}/closure")
    public ReferralResponse close(@PathVariable("id") String id,
                                  @PathVariable("referralId") String referralId) {

        return ReferralResponse.from(referrals.close(id, referralId, CurrentUsers.required()));
    }
}
