package uk.gov.hmcts.reform.deedpoll.domain.deedpoll;

/**
 * Who is viewing, and how old the record is.
 *
 * <p>Ported from the {@code PresentationContext} interface in
 * {@code reference/ts/deed-poll/form-presentation.ts}. {@code isNew} is true for a brand-new (unsaved)
 * record -- the source's {@code formType === 1}.
 */
public record PresentationContext(boolean isAdmin, boolean isNew) {
}
