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
 *
 * <p>{@code userId} and {@code service} are not the reference's: they are what the audit trail records as the
 * actor. A user's note carries the IDAM subject, and one with none is refused there. A note the service writes on
 * its own behalf -- the outbox's failure note, a stale referral's release -- is {@link #service}, and may still
 * stamp the name of the person whose action it reports.
 */
public record NoteAuthor(String userName, String userId, boolean service) {

    /** A caller with no name, attributed to the service account. */
    public static final NoteAuthor NONE = service(null);

    /** A note written by this user. */
    public static NoteAuthor user(String userName, String userId) {
        return new NoteAuthor(userName, userId, false);
    }

    /** A note the service writes on its own behalf, stamped with this name (or the service account's). */
    public static NoteAuthor service(String userName) {
        return new NoteAuthor(userName, null, true);
    }

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
