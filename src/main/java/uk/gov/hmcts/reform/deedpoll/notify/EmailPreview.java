package uk.gov.hmcts.reform.deedpoll.notify;

import uk.gov.service.notify.TemplatePreview;

/**
 * What Notify says this email will look like: its subject, its rendered HTML, and which version of the template
 * rendered it.
 *
 * <p><strong>Notify rendered this, and nothing here reproduces it.</strong> The whole content model is in the
 * Notify template -- headings, bullets, links and the GOV.UK styling -- and personalisation values are inserted
 * as plain text, so a body composed here and passed as one variable would lose all of it. Asking Notify to render
 * also means what the caseworker approves is what sends. A local renderer, a copy of the template or a mock-up
 * would each be a second copy of the content to drift; there is deliberately none.
 *
 * <p><strong>{@link #templateVersion()} is why this is worth returning rather than only displaying.</strong> It
 * is the wording the caseworker actually read, which is the question "what was this applicant told, and under
 * which wording" reduced to an integer -- and it matters most precisely when they edited the judge's reason
 * before sending.
 *
 * @param subject the email's subject line as Notify rendered it, empty for a template that somehow has none
 * @param html the rendered body, Notify's own HTML, safe to display and never assembled here
 * @param templateVersion the version of the Notify template that rendered it
 */
public record EmailPreview(String subject, String html, int templateVersion) {

    /**
     * The client's answer, with its two optionals read.
     *
     * <p>{@code getSubject()} and {@code getHtml()} are {@code Optional} on the client's own type, because the
     * preview endpoint serves text and letter templates too. An email has both; an absent one becomes an empty
     * string rather than a null, so a client rendering the preview has one less case than the library does.
     */
    public static EmailPreview from(TemplatePreview rendered) {
        return new EmailPreview(
            rendered.getSubject().orElse(""),
            rendered.getHtml().orElse(""),
            rendered.getVersion());
    }
}
