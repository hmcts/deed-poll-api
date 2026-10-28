package uk.gov.hmcts.reform.deedpoll.domain.auth;

import uk.gov.hmcts.reform.deedpoll.domain.ForbiddenError;
import uk.gov.hmcts.reform.deedpoll.domain.UnauthorizedError;
import uk.gov.hmcts.reform.deedpoll.domain.deedpoll.WriteContext;
import uk.gov.hmcts.reform.deedpoll.domain.notes.NoteAuthor;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * The authenticated caller, as far as the domain is concerned.
 *
 * <p>Ported from {@code reference/ts/auth/current-user.ts}. How it is populated is a transport concern
 * and lives in {@code uk.gov.hmcts.reform.deedpoll.auth}: a forwarded IDAM access token is validated
 * there and reduced to this by {@link IdamClaims}. Nothing here assumes that mechanism, which is what
 * lets the same reduction serve a citizen session later.
 *
 * <p>{@link #name} is audit text ({@code createdBy} / {@code modifiedBy}), and {@link #userId} is not: it is
 * the token's subject, and it is what authorisation turns on -- a saved query's permission turns on whether the
 * caller owns it, and so does a referral's claim, both keyed on the subject for the reason set out below.
 *
 * <p><strong>Nothing authorises on {@link #name} any more, and the referral's claim was the last thing that
 * did.</strong> {@code ReferralService.requireHeldBy} (who may direct a referral),
 * {@code JudicialReferralService.visibleTo} (which referrals a judge may see at all) and its {@code heldBy}
 * helper all compared this name against the referral's {@code claimedBy} / {@code decidedBy} columns, because
 * those columns held a name and keying a claim on the subject was a migration neither referral plan had.
 * {@code V14} is that migration: {@code claimedById} and {@code decidedById} hold the token's subject, all three
 * sites compare it, and {@code claimedBy} / {@code decidedBy} survive as the attribution staff read. The two
 * defects that went with it are recorded rather than deleted, because they are what those columns now exist to
 * prevent:
 *
 * <ul>
 *   <li>two judges sharing a display name could see and direct each other's claimed referrals -- and on the
 *       judicial surface that means the application and its notes, since {@code visibleTo} is the only boundary
 *       in front of a finder by referral id. A judge's personal work list keyed on a name would have enumerated
 *       a namesake's referrals rather than needing one of their ids, which is why it waited for this;</li>
 *   <li>a judge whose IDAM display name changed stopped being able to see their own claimed referral -- 404 to
 *       read, 409 to direct -- until the inactivity sweep released it, which it did, because
 *       {@code releaseStaleClaim} matches on {@code lastTouchedAt} and never on the owner.</li>
 * </ul>
 *
 * <p>So the rule is plain rather than conditional now: <strong>authorise on {@link #userId} through
 * {@link #requireUserId()}, never on {@link #name}.</strong> In particular do not fall back to the name when an
 * id is absent -- a pre-migration row has a null owner id and belongs to nobody, and a fallback would restore the
 * collision precisely on the rows whose identity is uncertain.
 *
 * <p><strong>{@link #userId} is the one component the reference interface does not have</strong>, and the
 * divergence is deliberate. The reference resolved identity from {@code x-user-roles} / {@code x-user-name}
 * headers, which carry no subject at all, and nothing in the ported domain owned a per-user resource -- a case
 * belongs to an applicant, not to the caseworker who logged it. Saved queries do belong to a caseworker, and a
 * display name is not an identity: two caseworkers can share one, and one caseworker's changes over time.
 * {@link #name} would therefore be an owner key that reassigns queries when somebody marries.
 *
 * <p><strong>{@link #email} is the second component the reference does not have, and it authorises nothing.</strong>
 * It is the address IDAM's userinfo gives, normalised, and its one reader links a Masters-list entry to the judge
 * who signs in with that address (plan D1). It is personal data with no audit purpose, so it is never logged, never
 * put in an audit {@code detail}, a note or an error message, and {@link #toString()} leaves it out, along with a
 * name or subject that holds an address, because a principal is the kind of object a debug line prints whole.
 */
public record CurrentUser(List<Role> roles, boolean isAdmin, String name, String userId, String email) {

    /**
     * Fallback author for a write with no identified user.
     *
     * <p>The reference exports this from {@code auth/current-user.ts}, so now that the auth module is
     * ported it lives here, and {@link WriteContext} and {@link NoteAuthor} read it from here rather
     * than each carrying a copy. It matches the service account the source system records for automated
     * and integration writes, so an unattributed write is labelled as one rather than left blank.
     */
    public static final String SERVICE_ACCOUNT_NAME = "# svc_HMCTS_LCPT_Dev";

    /**
     * Nobody in particular: no roles, not an admin, attributed to the service account, and with no
     * subject -- so {@link #requireUserId()} refuses rather than letting an unidentified write own
     * something.
     */
    public static final CurrentUser ANONYMOUS = currentUser(List.of(), null, null);

    /** The compatibility flag is always derived from roles; the supplied flag has no authority. */
    public CurrentUser(List<Role> roles, boolean isAdmin, String name, String userId, String email) {
        this.roles = List.copyOf(roles);
        this.isAdmin = RoleCapabilities.forRoles(this.roles).contains(Capability.OVERRIDE_PRIVACY);
        this.name = name;
        this.userId = userId;
        this.email = email;
    }

    /**
     * Build a caller from a resolved set of roles, an optional display name and the token's subject.
     *
     * <p>{@code isAdmin} is derived from the privacy capability: a caller
     * that could assert it independently of its roles is a caller that can be wrong about it.
     *
     * <p>{@code userId} gets no fallback, where {@code name} gets the service account. An unattributed
     * <em>write</em> is still a write worth recording, so a missing name is substituted; an unidentified
     * <em>owner</em> is not an owner, and substituting a placeholder would make every such caller the same
     * person -- each able to edit and delete the others' queries. It is left null, and
     * {@link #requireUserId()} is where that becomes a refusal.
     */
    public static CurrentUser currentUser(List<Role> roles, String name, String userId) {
        return currentUser(roles, name, userId, null);
    }

    /**
     * As {@link #currentUser(List, String, String)}, with the email address IDAM gives for the caller.
     *
     * <p>The address is trimmed and lower-cased, and is null if blank or without an {@code @}: like the subject, it
     * gets no fallback, because a Masters-list entry linked on a placeholder would be linked to everybody.
     */
    public static CurrentUser currentUser(List<Role> roles, String name, String userId, String email) {
        String trimmed = name == null ? "" : name.trim();
        String subject = userId == null ? "" : userId.trim();
        return new CurrentUser(
            roles,
            false,
            trimmed.isEmpty() ? SERVICE_ACCOUNT_NAME : trimmed,
            subject.isEmpty() ? null : subject,
            normalisedEmail(email)
        );
    }

    private static String normalisedEmail(String email) {
        String trimmed = email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
        return trimmed.isEmpty() || trimmed.indexOf('@') < 0 ? null : trimmed;
    }

    /**
     * The caller's stable id, refusing a caller who has none.
     *
     * <p>Every use of {@link #userId} goes through this rather than reading the component, because all of
     * them are authorisation decisions about an owned resource: whose query this is, whose selections these
     * are, and -- since {@code V14} -- which referral a judge holds, may direct and sees on their own list. A
     * null there would make all of those questions answer "the same anonymous everybody", so failing closed is
     * the only safe reading and it belongs in one place rather than at each call site.
     *
     * <p>401 rather than 403: the caller has not been identified well enough to be refused for who they
     * are. In practice a validated IDAM token always carries a subject and {@code IdamUserInfoClient}
     * checks userinfo agrees with it, so this is the guard for a path that does not exist yet -- a citizen
     * session, a batch job, {@link #ANONYMOUS} -- rather than for a token IDAM would issue.
     *
     * @throws UnauthorizedError if the caller has no subject
     */
    public String requireUserId() {
        if (userId == null) {
            throw new UnauthorizedError("The caller has no stable identifier, so this resource has no owner");
        }
        return userId;
    }

    /** Whether the caller's recognised roles grant this capability. Missing requirements fail closed. */
    public boolean can(Capability capability) {
        return capability != null && RoleCapabilities.forRoles(roles).contains(capability);
    }

    /** Refuse a missing grant directly, without the open-on-empty role-list helper. */
    public void require(Capability capability) {
        if (!can(capability)) {
            throw new ForbiddenError("Requires capability: " + capability);
        }
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
     * {@code isAdmin = false} for an admin and silently drop the privacy fields. The subject travels with them
     * so the audit trail can name the actor; it is read raw rather than through {@link #requireUserId()},
     * because whether a missing one is a refusal is the audit trail's decision, not this conversion's.
     */
    public WriteContext writeContext() {
        return new WriteContext(can(Capability.OVERRIDE_PRIVACY), name, userId);
    }

    /**
     * This caller as the author a note write needs.
     *
     * <p>A note write has no admin-only field, so it takes the name alone -- see {@link NoteAuthor} for
     * why that type exists rather than the whole context being handed over. The subject travels with it for the
     * audit trail, read raw for the reason {@link #writeContext()} gives.
     */
    public NoteAuthor noteAuthor() {
        return NoteAuthor.user(name, userId);
    }

    /**
     * Every component but {@link #email}, which is never written to a log.
     *
     * <p>{@link #name} and {@link #userId} are redacted too when they hold an address, because HMCTS IDAM puts the
     * address in {@code sub} and {@link IdamClaims} falls back to {@code sub} for both of them.
     */
    @Override
    public String toString() {
        return "CurrentUser[roles=" + roles + ", isAdmin=" + isAdmin + ", name=" + loggable(name)
            + ", userId=" + loggable(userId) + ", email=" + (email == null ? "absent" : "present") + "]";
    }

    private static String loggable(String value) {
        return value != null && value.indexOf('@') >= 0 ? "redacted" : value;
    }
}
