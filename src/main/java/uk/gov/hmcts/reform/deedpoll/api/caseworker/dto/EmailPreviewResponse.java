package uk.gov.hmcts.reform.deedpoll.api.caseworker.dto;

import uk.gov.hmcts.reform.deedpoll.notify.EmailPreview;

/**
 * The email as GOV.UK Notify renders it, for the caseworker to read before they send it.
 *
 * <p><strong>The HTML is Notify's own and nothing in this service composes it.</strong> The structure --
 * headings, bullets, links, the GOV.UK styling and the service's branding -- lives in the Notify template, and
 * personalisation values are inserted into it as plain text. So a body assembled here and passed as one variable
 * would lose all of that, and a local copy of the template would be a second version of the content to drift.
 * What the caseworker approves in this response is what the applicant receives.
 *
 * <p><strong>No outbox row exists at this point.</strong> A preview writes nothing: it costs an outbound call to
 * Notify and changes no state, which is also why a tab should preview on an explicit action rather than on every
 * keystroke -- each one counts against the rate limit.
 *
 * @param subject the subject line as rendered
 * @param html the rendered body, safe to display as-is
 * @param templateVersion which version of the Notify template rendered it -- the wording the caseworker read,
 *     which is worth recording because they may edit the judge's reason before sending
 */
public record EmailPreviewResponse(String subject, String html, int templateVersion) {

    public static EmailPreviewResponse from(EmailPreview preview) {
        return new EmailPreviewResponse(preview.subject(), preview.html(), preview.templateVersion());
    }
}
