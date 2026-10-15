package uk.gov.hmcts.reform.deedpoll.domain.audit;

import uk.gov.hmcts.reform.deedpoll.domain.auth.CurrentUser;
import uk.gov.hmcts.reform.deedpoll.domain.deedpoll.WriteContext;

/**
 * Who an audit event is attributed to: the caller's IDAM subject and display name, or the service itself.
 *
 * <p>{@link #service} is a separate fact rather than inferred from a null {@link #id}, because the two mean opposite
 * things. A user-initiated write with no subject is a bug, which {@link AuditTrail} refuses; a write the service
 * makes on its own behalf has no subject by nature, and says so by being built through {@link #system()}.
 */
public record AuditActor(String id, String name, boolean service) {

    /**
     * The caller behind a case write. A missing subject is carried through for {@link AuditTrail} to refuse.
     */
    public static AuditActor of(WriteContext context) {
        return new AuditActor(context.userId(), context.author(), false);
    }

    /** The caller behind any other write or a read. */
    public static AuditActor of(CurrentUser user) {
        return new AuditActor(user.userId(), user.name(), false);
    }

    /** The service acting on its own behalf -- only for a write that no user initiated. */
    public static AuditActor system() {
        return new AuditActor(null, CurrentUser.SERVICE_ACCOUNT_NAME, true);
    }

    /** Whether this actor can be recorded: the service, or a user with a subject. */
    boolean identified() {
        return service || id != null && !id.isBlank();
    }
}
