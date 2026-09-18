package uk.gov.hmcts.reform.deedpoll.api.judicial.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import uk.gov.hmcts.reform.deedpoll.api.judicial.dto.JudicialDirectionRequest;
import uk.gov.hmcts.reform.deedpoll.api.judicial.dto.JudicialPoolResponse;
import uk.gov.hmcts.reform.deedpoll.api.judicial.dto.JudicialReferralDetail;
import uk.gov.hmcts.reform.deedpoll.auth.CurrentUsers;
import uk.gov.hmcts.reform.deedpoll.domain.deedpoll.CasePaging;
import uk.gov.hmcts.reform.deedpoll.domain.referrals.JudicialReferralService;

/**
 * What a judge does, all four of it: read the shared pool, open a referral they may see, claim one, and direct
 * one they hold.
 *
 * <p><strong>The two writes add no transition logic.</strong> Claiming and directing are {@code ReferralService}
 * transitions -- api-2's, with the {@code Open}-only guard, the holder-only guard and every column they stamp --
 * and these handlers translate a path, a body and a {@code CurrentUser} into a call and the answer or its
 * {@code ConflictError} into a response. There is no third judicial write: a judge does not edit case data, add
 * notes, raise or withdraw referrals, or close one, because closing is the office's acknowledgement that the
 * direction has been actioned.
 *
 * <p>Keyed on the referral and never on the case -- see this package's documentation for why, and what replaces
 * the case-scoping the caseworker surface relies on. Every handler here passes the caller to the domain, and the
 * domain both gates the role ({@code Role.JUDICIAL}) and decides visibility: this controller resolves the
 * request, calls one method and maps the answer, exactly as the caseworker read controller does.
 *
 * <p><strong>Nothing here returns a caseworker DTO or an entity.</strong> The judicial DTOs are separate types in
 * a separate package, asserted in bytecode by {@code NamespaceSeparationSecurityTest}, because
 * {@code CaseDetailResponse} carries every field a caseworker sees and reuse would be a disclosure dressed as
 * deduplication.
 *
 * <p>No route here is annotated as requiring authentication, and all four do: {@code SecurityConfiguration}
 * authenticates {@code anyRequest()} that is not on a short infrastructure allowlist, so {@code /judicial/**} was
 * protected the moment it existed. An unauthenticated caller is a 401 before routing; a caseworker token is a 403
 * from the domain's role gate; a referral this judge may not see is a 404.
 */
@RestController
@RequestMapping("/judicial/referrals")
public class JudicialReferralController {

    private final JudicialReferralService judicial;

    public JudicialReferralController(JudicialReferralService judicial) {
        this.judicial = judicial;
    }

    /**
     * The shared pool: the referrals waiting for a judge, urgent first then longest-waiting, one page at a time.
     *
     * <p><strong>A read that writes, on purpose.</strong> The domain runs the inactivity sweep before it queries
     * (decision 22), so a claim that lapsed while nobody was looking is handed back and the referral is in the
     * page this request returns rather than in whichever later request happened to trigger a sweep. The pool is
     * the most-polled judicial page, which is why the sweep hangs off it -- {@code ReferralService}'s javadoc has
     * the full argument.
     *
     * <p>{@code page} and {@code size} bind as single strings and are read by {@code CasePaging}, which refuses
     * what it cannot understand and clamps an over-cap size rather than refusing it. The default size is the
     * pool's own {@link JudicialReferralService#POOL_PAGE_SIZE} and not the case list's twenty-five: a judicial
     * dashboard is a list of papers to read rather than a table to scan. It is a page size and not a cap -- the
     * response carries the pool's total, so the work waiting is visible on a page showing ten.
     *
     * <p>They are parsed rather than bound as {@code int}s so that an unreadable value is this service's 400 with
     * a field-addressed message, rather than Spring's type-mismatch answer about a parameter a judge did not type.
     */
    @GetMapping
    public JudicialPoolResponse pool(@RequestParam(name = "page", required = false) String page,
                                     @RequestParam(name = "size", required = false) String size) {

        return JudicialPoolResponse.of(judicial.pool(
            CurrentUsers.required(),
            CasePaging.parse(page, size, JudicialReferralService.POOL_PAGE_SIZE)));
    }

    /**
     * One referral, the application behind it and that application's notes.
     *
     * <p>404 for a referral this judge may not see -- withdrawn, closed, or held or decided by another judge --
     * in the same words as an id nothing was stored under, so no referral id is discoverable and one judge cannot
     * learn that another holds a case. There is no branch here for any of that: the domain reports it by throwing
     * {@code NotFoundError}, which {@code DomainErrorHandler} maps, so a check in this method would be
     * unreachable.
     *
     * <p>Reading a referral this judge holds refreshes the claim's inactivity clock (decision 7) and reading a
     * pool referral touches nothing. That is the domain's decision and not this handler's, which is why the
     * distinction is not visible here.
     */
    @GetMapping("/{referralId}")
    public JudicialReferralDetail referral(@PathVariable("referralId") String referralId) {
        return JudicialReferralDetail.of(judicial.referral(referralId, CurrentUsers.required()));
    }

    /**
     * A judge taking a referral out of the shared pool: it becomes theirs and drops off every other judge's.
     *
     * <p>A sub-resource and a {@code POST} rather than a {@code PATCH} carrying a state, exactly as the
     * caseworker surface's withdrawal and closure are: the state is not a field a caller sets, it is the result
     * of a transition with a guard in front of it, and a patch would let a client name any state it liked. No
     * body -- who is claiming comes from the token, and there is nothing else to say.
     *
     * <p>200 with the referral this judge now holds, carrying the application and its notes: the screen after a
     * claim is the papers, so the answer is the same document the read gives and the client needs no second call.
     *
     * <p>Three refusals, none of them written here. A caller without {@code Role.JUDICIAL} is a 403 from the
     * domain's role gate; a referral this judge may not see is a 404 in the words an unknown id gets; and a
     * referral that is not {@code Open} -- one another judge got to first, or one already decided -- is api-2's
     * 409, which is the honest answer to a stale dashboard rather than a silent re-claim.
     */
    @PostMapping("/{referralId}/claim")
    public JudicialReferralDetail claim(@PathVariable("referralId") String referralId) {
        return JudicialReferralDetail.of(judicial.claim(referralId, CurrentUsers.required()));
    }

    /**
     * The holding judge recording their direction: what they decided, what they directed, and whether the change
     * is to be published in the London Gazette.
     *
     * <p>{@code direction} rather than {@code decision} in the path, because what is recorded is the whole
     * direction and the decision is one field of it -- and the resource being created is the direction on this
     * referral, which is a sub-resource of it rather than a patch of the referral's fields.
     *
     * <p>200 with the referral carrying the direction just recorded, so a check-your-answers page can render what
     * was stored rather than what it sent. The referral is now {@code Decided} and still visible to this judge,
     * which is what lets them re-read their own direction while the office actions it.
     *
     * <p>Four answers, and all four are the domain's: 403 without the judicial role, 404 for a referral this
     * judge may not see, <strong>409 for one they do not hold</strong> -- withdrawn under them, or taken back by
     * the inactivity sweep and possibly since claimed by somebody else -- and 400 for a direction the schema
     * refuses, naming each offending field.
     */
    @PostMapping("/{referralId}/direction")
    public JudicialReferralDetail direction(@PathVariable("referralId") String referralId,
                                            @RequestBody JudicialDirectionRequest request) {

        return JudicialReferralDetail.of(
            judicial.direct(referralId, request.toInput(), CurrentUsers.required()));
    }
}
