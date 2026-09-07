package uk.gov.hmcts.reform.deedpoll.domain.auth;

import uk.gov.hmcts.reform.deedpoll.domain.ForbiddenError;
import uk.gov.hmcts.reform.deedpoll.domain.deedpoll.WriteContext;
import uk.gov.hmcts.reform.deedpoll.domain.notes.NoteAuthor;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * The authenticated caller, as far as the domain is concerned.
 *
 * <p>Ported from {@code reference/ts/auth/current-user.ts}. How it is populated is a transport concern
 * and lives in {@code uk.gov.hmcts.reform.deedpoll.auth}: a forwarded IDAM access token is validated
 * there and reduced to this by {@link IdamClaims}. Nothing here assumes that mechanism, which is what
 * lets the same reduction serve a citizen session later.
 *
 * <p>{@link #name} is only ever used as audit text ({@code createdBy} / {@code modifiedBy}), never for
 * authorisation -- that is driven entirely by {@link #roles} -- so a wrong name cannot escalate anything.
 */
public record CurrentUser(List<Role> roles, boolean isAdmin, String name) {

    /**
     * Fallback author for a write with no identified user.
     *
     * <p>The reference exports this from {@code auth/current-user.ts}, so now that the auth module is
     * ported it lives here, and {@link WriteContext} and {@link NoteAuthor} read it from here rather
     * than each carrying a copy. It matches the service account the source system records for automated
     * and integration writes, so an unattributed write is labelled as one rather than left blank.
     */
    public static final String SERVICE_ACCOUNT_NAME = "# svc_HMCTS_LCPT_Dev";

    /** Nobody in particular: no roles, not an admin, attributed to the service account. */
    public static final CurrentUser ANONYMOUS = currentUser(List.of(), null);

    public CurrentUser(List<Role> roles, boolean isAdmin, String name) {
        this.roles = List.copyOf(roles);
        this.isAdmin = isAdmin;
        this.name = name;
    }

    /**
     * Build a caller from a resolved set of roles and an optional display name.
     *
     * <p>{@code isAdmin} is derived rather than passed, exactly as the reference derives it: a caller
     * that could assert it independently of its roles is a caller that can be wrong about it.
     */
    public static CurrentUser currentUser(List<Role> roles, String name) {
        String trimmed = name == null ? "" : name.trim();
        return new CurrentUser(
            roles,
            roles.contains(Role.DEEDPOLL_ADMIN),
            trimmed.isEmpty() ? SERVICE_ACCOUNT_NAME : trimmed
        );
    }

    /**
     * Refuse the caller unless they hold at least one of the given roles.
     *
     * <p>Ported from {@code requireRoles} in {@code apps/api/src/auth/require-roles.ts}, where the
     * requirement was expressed by mounting a middleware in front of a handler. There is no Express
     * here, so it is a method on the caller instead -- but the rule is unchanged, including that
     * requiring nothing is open rather than closed, so a caller building the list dynamically cannot
     * accidentally fail closed on an empty array.
     */
    public void requireAnyRole(Role... required) {
        if (required.length == 0 || Arrays.stream(required).anyMatch(roles::contains)) {
            return;
        }

        throw new ForbiddenError("Requires one of the following roles: "
                                    + Arrays.stream(required).map(Role::claimValue).collect(Collectors.joining(", ")));
    }

    /**
     * This caller as the context a case write needs.
     *
     * <p>Kept here rather than assembled at each call site: the two types are the same two facts a write
     * is entitled to know, and a controller that built its own could quietly pass
     * {@code isAdmin = false} for an admin and silently drop the privacy fields.
     */
    public WriteContext writeContext() {
        return new WriteContext(isAdmin, name);
    }

    /**
     * This caller as the author a note write needs.
     *
     * <p>A note write has no admin-only field, so it takes the name alone -- see {@link NoteAuthor} for
     * why that type exists rather than the whole context being handed over.
     */
    public NoteAuthor noteAuthor() {
        return new NoteAuthor(name);
    }
}
