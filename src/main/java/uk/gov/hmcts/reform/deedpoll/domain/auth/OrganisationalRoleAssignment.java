package uk.gov.hmcts.reform.deedpoll.domain.auth;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * The fields of one Role Assignment Service assignment that {@link AmRoles} reads, and no others.
 *
 * <p>Strings rather than enums for {@code roleType} and {@code grantType}: RAS owns those vocabularies, and a value
 * it adds later must be an assignment this service ignores, not a response it cannot parse. Every field may be null,
 * because the document is controlled elsewhere; {@link AmRoles} treats a missing value as failing its filter.
 *
 * <p>{@code attributes} is a loose map for the same reason {@link IdamClaims} takes one: RAS attaches different
 * attributes per role, and only {@code jurisdiction} is read here.
 */
public record OrganisationalRoleAssignment(
    String roleName,
    String roleType,
    String grantType,
    Instant beginTime,
    Instant endTime,
    Map<String, ?> attributes,
    List<String> authorisations) {
}
