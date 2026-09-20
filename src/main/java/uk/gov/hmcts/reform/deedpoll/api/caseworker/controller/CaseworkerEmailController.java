package uk.gov.hmcts.reform.deedpoll.api.caseworker.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import uk.gov.hmcts.reform.deedpoll.api.caseworker.dto.CaseEmailsResponse;
import uk.gov.hmcts.reform.deedpoll.api.caseworker.dto.EmailPreviewResponse;
import uk.gov.hmcts.reform.deedpoll.api.caseworker.dto.PreviewEmailRequest;
import uk.gov.hmcts.reform.deedpoll.api.caseworker.dto.SendEmailRequest;
import uk.gov.hmcts.reform.deedpoll.api.caseworker.dto.SentEmailResponse;
import uk.gov.hmcts.reform.deedpoll.auth.CurrentUsers;
import uk.gov.hmcts.reform.deedpoll.domain.NotFoundError;
import uk.gov.hmcts.reform.deedpoll.notify.CaseEmailService;
import uk.gov.hmcts.reform.deedpoll.notify.EmailTemplate;
import uk.gov.hmcts.reform.deedpoll.notify.NotificationOutbox;

/**
 * The emails a caseworker can send about a case: what may be sent, what one will look like, and sending it.
 *
 * <p>Three operations, which is what the Email tab needs and no more -- it has to know what it may send, show it
 * to the caseworker populated from the case, and send it. There is deliberately no fourth for editing or
 * cancelling one: an email is owed or it is not, and an owed one is either accepted by Notify or fails with a
 * reason on its row.
 *
 * <p><strong>Why a caseworker composes an email at all, rather than one being sent on a state change.</strong>
 * Three of the four templates the business supplied need text a human writes, so the send is a deliberate act by
 * somebody who has read what is going out. That is also what makes the outbox safe with no scheduler: every send
 * is a caseworker action, and the drain hangs off caseworker traffic.
 *
 * <p>Nested under the case like the notes, witnesses and referrals collections, so the case id in the path is
 * part of the scope rather than decoration. The referral the email quotes is found <em>through</em> the case, so
 * an email cannot be composed from another case's refusal -- there is no route that names a referral here at all.
 *
 * <p><strong>{@code {template}} is the key, and the {@code GET} is where a client learns the keys.</strong>
 * Matched exactly, so a near-miss is a 404 naming what would have been accepted rather than a guess at what was
 * meant.
 *
 * <p><strong>Preview is a {@code POST} despite being a read.</strong> The caseworker's slot text is the input: it
 * is prose, it may be long, and a URL would log it. It writes no outbox row and changes no state.
 *
 * <p><strong>No role gate, deliberately, and there is no "may send" permission to add.</strong> Any authenticated
 * staff caller may send, exactly as any of them may raise, withdraw and close a referral: in the process this
 * replaces, anybody who works a case writes to the applicant. The one role that gates anything in this service is
 * {@code Role.JUDICIAL}, which gates the two transitions on the judicial surface -- nothing here. So the only 403
 * on this surface is the one nothing issues.
 *
 * <p>The statuses these routes answer are the domain's, mapped once by {@code DomainErrorHandler}: 404 for a case
 * that does not exist, 409 when the case is in no state to send the email, 400 for a slot payload the schema
 * refuses, 422 for a case with no applicant email address, and 502 when Notify will not render a preview. None of
 * them is branched on here.
 *
 * <p>None of these routes is annotated as requiring authentication, and all three do:
 * {@code SecurityConfiguration} authenticates {@code anyRequest()} that is not on a short infrastructure
 * allowlist, so a route is protected by having been written rather than by having been remembered.
 */
@RestController
@RequestMapping("/caseworker/cases/{id}/emails")
public class CaseworkerEmailController {

    private final CaseEmailService emails;

    /**
     * Injected so a send can drain immediately after it has committed.
     *
     * <p>Here rather than inside {@link CaseEmailService#send}, and that is not tidiness: the drain runs in its
     * own transaction ({@code REQUIRES_NEW}), so calling it inside the enqueueing transaction would suspend that
     * transaction and the drain would not see the row it was meant to send -- the email would then wait for
     * whichever later request happened to drain. Called from here, the enqueue has committed and the send the
     * caseworker just made is the row the drain picks up.
     */
    private final NotificationOutbox outbox;

    public CaseworkerEmailController(CaseEmailService emails, NotificationOutbox outbox) {
        this.emails = emails;
        this.outbox = outbox;
    }

    /**
     * Everything the Email tab needs: availability, correspondence history and pre-populated slot defaults.
     *
     * <p>Availability still contains every type, applicable or not, with the reason on the ones that are not.
     * History and defaults are returned alongside it so the tab needs one request, and this read never calls
     * Notify.
     *
     * <p>404 for a case that does not exist rather than an empty list, for the reason the notes and referrals
     * lists give: an empty answer would mean both "no such case" and "this case can send nothing", and only one of
     * those is true.
     */
    @GetMapping
    public CaseEmailsResponse emails(@PathVariable("id") String id) {
        return CaseEmailsResponse.from(emails.overview(id));
    }

    /**
     * The email as GOV.UK Notify renders it, from the slot text the caseworker has typed so far.
     *
     * <p>Rendered by Notify and never reproduced here, so what the caseworker approves is what sends, with the
     * GOV.UK styling and the service's branding already applied. Writes nothing.
     *
     * <p><strong>Until the Notify service is provisioned this answers 502, and that is correct behaviour rather
     * than a defect.</strong> The template ID is still {@code DEED_POLL_PLACEHOLDER}, so Notify answers 400 --
     * there is no such template -- and the service reports that its dependency refused. The {@code GET} above
     * publishes {@code provisioned} so a tab can say so instead of offering a button that cannot work.
     */
    @PostMapping("/{template}/preview")
    public EmailPreviewResponse preview(@PathVariable("id") String id,
                                        @PathVariable("template") String template,
                                        @RequestBody(required = false) PreviewEmailRequest request) {

        return EmailPreviewResponse.from(emails.preview(
            id, requireTemplate(template), request == null ? null : request.fields()));
    }

    /**
     * Sends the email: resolves it, freezes it into an outbox row, and drains.
     *
     * <p>200 rather than 201, and not because a row was not created. What the caseworker did was send an email;
     * the row is how this service owes it, and a 201 with a {@code Location} would invite a client to treat the
     * outbox row as a resource it manages. There is no route to read one back by id and deliberately so -- an
     * email is not editable once sent.
     *
     * <p>Drained straight afterwards so the happy path does not wait for another request, and after the send has
     * returned rather than within it -- see {@link #outbox}. A drain with nothing to do is cheap and a failure in
     * one cannot undo the enqueue, because they are separate transactions.
     *
     * <p>422 for a case with no applicant email address, naming the column: the caseworker has chosen to send, so
     * silently skipping would leave them believing an applicant had been told something. 409 when no Master has
     * refused the application -- nothing about the request is wrong and it would succeed unchanged once one has.
     */
    @PostMapping("/{template}")
    public SentEmailResponse send(@PathVariable("id") String id,
                                  @PathVariable("template") String template,
                                  @RequestBody(required = false) SendEmailRequest request) {

        SentEmailResponse sent = SentEmailResponse.from(emails.send(
            id, requireTemplate(template), request == null ? null : request.fields(), CurrentUsers.required()));
        outbox.drain();
        return sent;
    }

    /**
     * The template the path names, or a 404.
     *
     * <p>404 rather than 400: {@code /emails/whatever} is a path nothing is served at, exactly as an unknown case
     * id is. The message names the keys that would have worked, because a client that got this wrong has no other
     * way to find out -- and the {@code GET} publishing them is what it should have read.
     *
     * <p>The rejected token is deliberately not echoed back, for the reason {@code DomainErrorHandler} gives
     * about an unconvertible parameter: it is caller-supplied text and this body is rendered by whatever is on the
     * other end. Naming the acceptable values is what makes the refusal actionable; repeating the wrong one adds
     * nothing the caller does not already have.
     */
    private static EmailTemplate requireTemplate(String template) {
        return EmailTemplate.byKey(template)
            .orElseThrow(() -> new NotFoundError("There is no such email type. One of: " + EmailTemplate.keys()));
    }
}
