package uk.gov.hmcts.reform.deedpoll.api.judicial.controller;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import uk.gov.hmcts.reform.deedpoll.api.AuditedAs;
import uk.gov.hmcts.reform.deedpoll.api.DocumentFileResponses;
import uk.gov.hmcts.reform.deedpoll.api.judicial.dto.JudicialDirectionRequest;
import uk.gov.hmcts.reform.deedpoll.api.judicial.dto.JudicialMyReferralsResponse;
import uk.gov.hmcts.reform.deedpoll.api.judicial.dto.JudicialPoolResponse;
import uk.gov.hmcts.reform.deedpoll.api.judicial.dto.JudicialReferralDetail;
import uk.gov.hmcts.reform.deedpoll.auth.CurrentUsers;
import uk.gov.hmcts.reform.deedpoll.domain.audit.AuditAction;
import uk.gov.hmcts.reform.deedpoll.domain.audit.ReadAudit;
import uk.gov.hmcts.reform.deedpoll.domain.auth.CurrentUser;
import uk.gov.hmcts.reform.deedpoll.domain.deedpoll.CasePaging;
import uk.gov.hmcts.reform.deedpoll.domain.documents.preview.DocumentPreviewService;
import uk.gov.hmcts.reform.deedpoll.domain.referrals.JudicialReferralService;
import uk.gov.hmcts.reform.deedpoll.domain.referrals.JudicialReferralView;

import java.io.IOException;

/**
 * What a judge does, all nine of it: read the shared pool, read their own outstanding work, open a referral they
 * may see, download or preview one of its supporting documents, claim one, direct one they hold, and revise or
 * withdraw a direction they gave.
 *
 * <p><strong>Five reads and four writes.</strong> The fifth operation is the personal list, and it completes the
 * judicial boundary rather than widening it: visibility always admitted a judge's own {@code Claimed} and
 * {@code Decided} referrals, and until there was a list of them a successful claim removed the referral from the
 * only page that linked to it. Its membership is the same predicate as that visibility, so the list cannot offer a
 * referral whose own link would answer 404.
 *
 * <p><strong>The writes add no transition logic.</strong> Claiming, directing, revising and withdrawing are
 * {@code ReferralService} transitions -- api-2's, with the {@code Open}-only guard, the holder-only guard and every
 * column they stamp, and the 2026-10-19 revision and withdrawal with their directing-judge guard -- and these
 * handlers translate a path, a body and a {@code CurrentUser} into a call and the answer or its
 * {@code ConflictError} into a response. A judge does not edit case data, add notes, raise or withdraw referrals,
 * or close one, because closing is the office's acknowledgement that the direction has been actioned.
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
 * <p>No route here is annotated as requiring authentication, and all nine do: {@code SecurityConfiguration}
 * authenticates {@code anyRequest()} that is not on a short infrastructure allowlist, so {@code /judicial/**} was
 * protected the moment it existed. An unauthenticated caller is a 401 before routing; a caseworker token is a 403
 * from the domain's role gate; a referral this judge may not see is a 404.
 */
@RestController
@RequestMapping("/judicial/referrals")
public class JudicialReferralController {

    private final JudicialReferralService judicial;
    private final ReadAudit readAudit;
    private final DocumentPreviewService previews;

    public JudicialReferralController(JudicialReferralService judicial, ReadAudit readAudit,
                                      DocumentPreviewService previews) {
        this.judicial = judicial;
        this.readAudit = readAudit;
        this.previews = previews;
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
     * My referrals: the ones this judge is holding and the directions the office has not yet actioned.
     *
     * <p><strong>A literal segment rather than a query parameter, and that is the security decision here.</strong>
     * {@code ?owner=} or {@code ?claimedBy=} would make whose list this is something a caller types, and a judge who
     * typed somebody else's would be enumerating another judge's work -- which is the disclosure the whole surface's
     * 404s exist to prevent. Ownership comes from the token's subject and from nowhere else, so this handler takes
     * no argument identifying a judge and there is none to forge.
     *
     * <p><strong>{@code /mine} wins over {@link #referral} rather than being parsed as a referral id.</strong> A
     * literal path segment is more specific than a template one, so Spring routes it here and never to the detail
     * read -- which would have answered 404 for an id that is not a UUID. The word is reserved by this mapping
     * existing, and {@code JudicialReferralControllerTest} asserts the dispatch rather than trusting it.
     *
     * <p>A read that writes, for the pool's reason and a sharper one of its own: the domain runs the inactivity
     * sweep first, so a claim that lapsed while this judge was away leaves this page in the response that observed
     * it instead of sitting here to be opened -- which would touch it and revive a claim the expiry window had
     * already ended. {@code JudicialReferralService#mine} has the full argument.
     *
     * <p>{@code page} and {@code size} bind and are parsed exactly as the pool's are, and default to the same
     * {@link JudicialReferralService#POOL_PAGE_SIZE}: the same dashboard and the same screenful.
     *
     * <p>Two refusals, neither of them written here: a caller without {@code Role.JUDICIAL} is the domain's 403, and
     * a judicial token carrying no subject is its 401 -- an unidentified caller owns nothing, so there is no list
     * that is theirs. There is no 404, because the collection always exists; a judge holding nothing gets an empty
     * page and a total of zero.
     */
    @GetMapping("/mine")
    public JudicialMyReferralsResponse mine(@RequestParam(name = "page", required = false) String page,
                                            @RequestParam(name = "size", required = false) String size) {

        return JudicialMyReferralsResponse.of(judicial.mine(
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
     *
     * <p><strong>Recorded as {@code CASE_VIEWED}</strong> (design decision 4 of the 2026-10-19 plan), because the
     * judge now sees the whole application. After the read, so a refused referral records nothing, and outside its
     * transaction, as the caseworker case view records it. It fails open: a view that could not be recorded is still
     * answered -- see {@link ReadAudit}. The claim answers with the same papers and does not record it again.
     */
    @Operation(summary = "Read a referral and the whole application behind it",
        description = "Requires the judicial role. The referral, the whole application (details, witnesses and "
            + "the documents a download would serve, but no case id and no payment or refund data), and its notes. "
            + "Recorded as CASE_VIEWED. 404, in the words an unknown id gets, for a referral this judge may not "
            + "see: one held or directed by another judge, and every referral once it is closed or withdrawn.")
    @GetMapping("/{referralId}")
    public JudicialReferralDetail referral(@PathVariable("referralId") String referralId) {
        CurrentUser caller = CurrentUsers.required();
        JudicialReferralView view = judicial.referral(referralId, caller);
        readAudit.caseViewed(view.application().getId(), caller);
        return JudicialReferralDetail.of(view);
    }

    /**
     * Streams one of the application's supporting documents, reached through the referral and never through the
     * case.
     *
     * <p>The document ids are the ones the referral's {@code application.documents} lists. The domain resolves the
     * referral, refuses one this judge may not see with the referral read's own 404, and serves only a readable
     * document of that referral's case: another case's document is the document's 404, exactly as on the caseworker
     * route. So any judge may open the documents of an {@code Open} referral, the holder those of a {@code Claimed}
     * one, the directing judge those of a {@code Decided} one, and nobody once the office closes it.
     *
     * <p>The response and its headers are {@link DocumentFileResponses#of}, the caseworker download's own. Recorded
     * as {@code DOCUMENT_DOWNLOADED} before the file is opened, and refused if it cannot be: it fails closed.
     */
    @Operation(summary = "Download one of the application's supporting documents",
        description = "Requires the judicial role. The documentId is one of the referral's "
            + "application.documents. Any judge may open the documents of an Open referral, the holder those of a "
            + "Claimed one and the directing judge those of a Decided one. Recorded as DOCUMENT_DOWNLOADED before "
            + "the file is opened, and refused if that cannot be recorded. Served with the caseworker download's "
            + "headers: the recorded MIME type, its disposition and Cache-Control: no-store. 404 for a referral "
            + "this judge may not see, and for a document that is not this referral's case's or has no readable "
            + "file.")
    @GetMapping("/{referralId}/documents/{documentId}/file")
    public ResponseEntity<Resource> document(@PathVariable("referralId") String referralId,
                                             @PathVariable("documentId") String documentId) {

        return DocumentFileResponses.of(judicial.document(referralId, documentId, CurrentUsers.required()));
    }

    /**
     * A read-only PDF rendition of one of the application's supporting documents, reached as {@link #document} is.
     *
     * <p>The same role gate and visibility as the download, through {@code JudicialReferralService.previewDocument},
     * so every refusal is the download's: the referral's 404, the document's 404 and the judicial 403. Recorded as
     * {@code DOCUMENT_PREVIEWED}, fail-closed, before the original is opened; the rendition is never stored.
     */
    @Operation(operationId = "previewReferralDocument",
        summary = "Preview one of the application's supporting documents as a PDF",
        description = "Requires the judicial role. The documentId is one of the referral's application.documents, "
            + "and who may preview it is exactly who may download it from /file: any judge for an Open referral, "
            + "the holder for a Claimed one and the directing judge for a Decided one. A read-only rendition as "
            + "one complete PDF; the original stays on /file, unchanged. PDF passes through after checks, JPEG and "
            + "PNG become one page, a TIFF one page per frame in order (a JPEG-compressed TIFF, Compression 6 or 7, "
            + "is deliberately not previewed and answers PREVIEW_INVALID_DOCUMENT), and DOCX is converted. "
            + "Recorded as "
            + "DOCUMENT_PREVIEWED, not DOCUMENT_DOWNLOADED, once per request before the original is opened, and "
            + "refused if that cannot be recorded. 404 for a referral this judge may not see, and for a document "
            + "that is not this referral's case's or has no readable file. No range support: a Range header is "
            + "ignored and the whole PDF is sent. A preview that is not produced is one of four fixed codes, and "
            + "leaves /file working.")
    @GetMapping("/{referralId}/documents/{documentId}/preview")
    public void preview(@PathVariable("referralId") String referralId,
                        @PathVariable("documentId") String documentId,
                        HttpServletResponse response) throws IOException {

        DocumentFileResponses.writePreview(
            previews.judicialPreview(referralId, documentId, CurrentUsers.required(), previews.budget()), response);
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
    @AuditedAs(AuditAction.REFERRAL_CLAIMED)
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
    @AuditedAs(AuditAction.REFERRAL_DIRECTED)
    @Operation(summary = "Record a direction on a referral this judge holds",
        description = "Requires the judicial role. The referral must be Claimed by this judge, else 409, and "
            + "becomes Decided. 404 for a referral this judge may not see; 400 for a body the schema refuses, "
            + "naming each field. Also how a judge directs again after withdrawing their direction.")
    @PostMapping("/{referralId}/direction")
    public JudicialReferralDetail direction(@PathVariable("referralId") String referralId,
                                            @RequestBody JudicialDirectionRequest request) {

        return JudicialReferralDetail.of(
            judicial.direct(referralId, request.toInput(), CurrentUsers.required()));
    }

    /**
     * The judge who directed a referral replacing that direction, until the office closes it.
     *
     * <p>A {@code PUT} on the direction the {@code POST} created, with the same body and the same validation: what
     * changes is the whole direction, so the replacement is the whole of it too. The decision date moves to now, the
     * signature stays the original judge's, and {@code directionChangedAt} is set so the caseworker can see the
     * direction changed. The first direction stays on the audit trail.
     *
     * <p>Four answers, all the domain's: 403 without the judicial role, 404 for a referral this judge may not see --
     * including one the office closed, even a moment before this request took the row lock -- 409 for one they can
     * see that is not a direction of theirs (the pool, or their own claim), and 400 for a body the schema refuses,
     * in the {@code POST}'s words.
     */
    @AuditedAs(AuditAction.REFERRAL_DIRECTION_REVISED)
    @Operation(summary = "Revise the direction this judge gave",
        description = "Requires the judicial role. Permitted while the referral is Decided and this judge directed "
            + "it, which is until the office closes it; a visible referral that is not their direction (the pool, "
            + "or their own claim) is 409, and a closed one is 404. Takes the direction POST's body and refuses "
            + "it in the same words (400). Replaces the decision, directionsText and publishToGazette, moves "
            + "decisionDate to now, keeps decidedBy as first signed, and sets directionChangedAt. The original "
            + "direction stays on the audit trail; the caseworker is told by a case note and directionChangedAt.")
    @PutMapping("/{referralId}/direction")
    public JudicialReferralDetail reviseDirection(@PathVariable("referralId") String referralId,
                                                  @RequestBody JudicialDirectionRequest request) {

        return JudicialReferralDetail.of(
            judicial.revise(referralId, request.toInput(), CurrentUsers.required()));
    }

    /**
     * The judge who directed a referral taking that direction back, so that they can give a new one with the
     * direction {@code POST}, until the office closes it.
     *
     * <p>No body: the referral goes back to {@code Claimed}, held by the same judge, with no direction on it, and
     * {@code directionChangedAt} is set. <strong>A withdrawn direction is a live claim again</strong>, so if the judge
     * does not direct it again the inactivity sweep returns it to the shared pool, as it would any other claim.
     *
     * <p>The same answers as revising: 403 without the judicial role, 404 for a referral this judge may not see --
     * including one the office closed -- and 409 for one they can see that is not a direction of theirs.
     */
    @AuditedAs(AuditAction.REFERRAL_DIRECTION_WITHDRAWN)
    @Operation(summary = "Withdraw the direction this judge gave, to direct again",
        description = "Requires the judicial role. No body. Permitted while the referral is Decided and this judge "
            + "directed it; otherwise 409, or 404 once closed. The referral returns to Claimed, held by the same "
            + "judge, with the decision, directionsText, publishToGazette, decisionDate and decidedBy cleared, and "
            + "directionChangedAt set. The judge directs again with POST .../direction. A withdrawn direction is "
            + "a live claim again: if the judge does not direct it within the claim's inactivity window, the "
            + "inactivity sweep returns it to the shared pool, where any judge may claim it.")
    @PostMapping("/{referralId}/direction/withdraw")
    public JudicialReferralDetail withdrawDirection(@PathVariable("referralId") String referralId) {
        return JudicialReferralDetail.of(judicial.withdraw(referralId, CurrentUsers.required()));
    }
}
