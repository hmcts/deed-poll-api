package uk.gov.hmcts.reform.deedpoll.domain.notes;

import uk.gov.hmcts.reform.deedpoll.domain.deedpoll.WriteContext;

/**
 * Who is writing a note.
 *
 * <p>Ported from the {@code NoteAuthor} interface in {@code reference/ts/notes/service.ts}. Kept as its
 * own type rather than folded into {@code WriteContext}, exactly as the reference keeps it: a note write
 * has no admin-only field, so nothing here should be able to ask whether the caller is an admin.
 *
 * <p>The name is optional throughout -- an omitted one is stamped as the service account, so an
 * unattributed write is labelled as one rather than left blank. The constant is read from
 * {@link WriteContext} rather than restated, so the case and its notes cannot disagree about what an
 * unattributed write is called.
 */
public record NoteAuthor(String userName) {

    /** A caller with no name, attributed to the service account. */
    public static final NoteAuthor NONE = new NoteAuthor(null);

    /**
     * The name to stamp on this write.
     *
     * <p>A blank name is treated as no name, which is what {@link WriteContext#author()} does and one more
     * case than the reference's {@code ?? SERVICE_ACCOUNT_NAME} covers: a form posting an empty author
     * field must not stamp an empty string into a column the grid then renders as "Not recorded" anyway.
     */
    public String author() {
        return userName == null || userName.isBlank() ? WriteContext.SERVICE_ACCOUNT_NAME : userName;
    }
}
