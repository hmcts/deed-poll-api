package uk.gov.hmcts.reform.deedpoll.api.judicial.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import uk.gov.hmcts.reform.deedpoll.api.judicial.dto.JudicialPoolResponse;
import uk.gov.hmcts.reform.deedpoll.api.judicial.dto.JudicialReferralDetail;
import uk.gov.hmcts.reform.deedpoll.auth.CurrentUsers;
import uk.gov.hmcts.reform.deedpoll.domain.deedpoll.CasePaging;
import uk.gov.hmcts.reform.deedpoll.domain.referrals.JudicialReferralService;

/**
 * What a judge does: read the shared pool, and open a referral they may see.
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
 * <p>Neither route is annotated as requiring authentication, and both do: {@code SecurityConfiguration}
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
}
