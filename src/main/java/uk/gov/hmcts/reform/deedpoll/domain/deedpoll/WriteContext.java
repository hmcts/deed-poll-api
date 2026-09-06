package uk.gov.hmcts.reform.deedpoll.domain.deedpoll;

import uk.gov.hmcts.reform.deedpoll.domain.auth.CurrentUser;

/**
 * Viewer context for a write.
 *
 * <p>Ported from the {@code WriteContext} type in {@code reference/ts/deed-poll/service.ts}.
 * {@code userName} is the display name recorded in {@code createdBy} / {@code modifiedBy}.
 *
 * <p>{@link #DEFAULT} is a non-admin with no name, matching the reference's
 * {@code DEFAULT_WRITE_CONTEXT}. That default is load-bearing rather than convenient: an internal caller
 * that forgets to pass a context must not get the permissive branch, and there is a ported test for
 * exactly that.
 */
public record WriteContext(boolean isAdmin, String userName) {

    /**
     * Fallback author for a write with no identified user.
     *
     * <p>The reference exports this from {@code auth/current-user.ts}, which is now ported, so it is
     * read from {@link CurrentUser} rather than restated here -- one definition, wherever an
     * unattributed write is labelled. It matches the service account the source system records for
     * automated and integration writes, so such a write is labelled as one rather than left blank.
     */
    public static final String SERVICE_ACCOUNT_NAME = CurrentUser.SERVICE_ACCOUNT_NAME;

    /** A non-admin caller with no name, attributed to the service account. */
    public static final WriteContext DEFAULT = new WriteContext(false, null);

    /** An admin caller with no name. */
    public static WriteContext admin() {
        return new WriteContext(true, null);
    }

    /** The name to stamp on this write. */
    public String author() {
        return userName == null || userName.isBlank() ? SERVICE_ACCOUNT_NAME : userName;
    }
}
