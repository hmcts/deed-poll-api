package uk.gov.hmcts.reform.deedpoll.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uk.gov.hmcts.reform.deedpoll.domain.auth.AmRoles;
import uk.gov.hmcts.reform.deedpoll.domain.auth.Role;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.Collection;
import java.util.HexFormat;
import java.util.Set;

/**
 * The roles a caller's organisational role assignments in AM grant, as a second source beside IDAM's.
 *
 * <p><strong>It can only ever add nothing on failure.</strong> RAS unreachable, refusing, or answering something
 * unreadable is a WARN and an empty set, so the caller continues with exactly the IDAM roles they had before AM
 * existed (plan 20261014, decision 4). Failing the request instead would make a RAS outage a deed poll outage; failing
 * open is not on offer, because there is nothing to fail open to.
 *
 * <p><strong>Two callers never ask.</strong> With the switch off, nobody does, so deploying this changes nothing. The
 * CCD system user, recognised by {@code deedpoll.ccd.system-role} among its IDAM roles, never does either: it is not
 * a person, has no organisational roles, and calls on every linking operation.
 *
 * <p><strong>The warning names a hash of the subject, never the subject, the token or RAS's answer.</strong> The hash
 * is enough to correlate repeated failures for one user. A {@link RoleAssignmentUnavailable}'s cause is not logged
 * either: a client exception's message names the request URL, and the URL carries the subject.
 *
 * <p>Not cached, for {@link IdamUserInfoClient}'s reason: one lookup per authenticated request.
 */
public class AmRoleSource {

    private static final Logger LOG = LoggerFactory.getLogger(AmRoleSource.class);

    private final RoleAssignmentProperties properties;
    private final RoleAssignmentClient client;
    private final String systemRole;
    private final Clock clock;

    public AmRoleSource(RoleAssignmentProperties properties, RoleAssignmentClient client, String systemRole,
                        Clock clock) {
        this.properties = properties;
        this.client = client;
        this.systemRole = systemRole;
        this.clock = clock;
    }

    /**
     * The roles AM grants this caller here and now, or none.
     *
     * @param token         the caller's raw access token, forwarded to RAS as their own credential
     * @param subject       the caller's IDAM subject, which is their RAS actor id
     * @param idamRoleNames every role IDAM asserts for the caller, recognised or not, so the system user can be told
     */
    public Set<Role> rolesFor(String token, String subject, Collection<String> idamRoleNames) {
        if (!properties.enabled() || idamRoleNames.contains(systemRole)) {
            return Set.of();
        }
        try {
            return AmRoles.rolesFrom(client.assignments(token, subject), properties.jurisdiction(),
                                     properties.gazetteSkill(), clock.instant());
        } catch (RoleAssignmentUnavailable e) {
            LOG.warn("AM roles unavailable for subject {} (status {}): {}; continuing with IDAM roles only",
                     hash(subject),
                     e.status().isPresent() ? String.valueOf(e.status().getAsInt()) : "none",
                     e.getMessage());
            return Set.of();
        }
    }

    /** The first 12 hex digits of the subject's SHA-256: enough to correlate, not enough to be the subject. */
    static String hash(String subject) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(String.valueOf(subject).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, 12);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
