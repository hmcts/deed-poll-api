package uk.gov.hmcts.reform.deedpoll.domain.deedpoll;

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
     * <p>The reference exports this from {@code auth/current-user.ts}, which plan 01 task 12 owns; only
     * the constant is needed here, so only the constant is ported -- and it lives on the type that uses
     * it rather than in a stub of a module nobody has written yet. It matches the service account the
     * source system records for automated and integration writes, so an unattributed write is labelled
     * as one rather than left blank.
     */
    public static final String SERVICE_ACCOUNT_NAME = "# svc_HMCTS_LCPT_Dev";

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
