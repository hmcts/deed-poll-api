package uk.gov.hmcts.reform.deedpoll.domain.savedqueries;

import java.util.Optional;

/**
 * Create payload for a saved query, already validated.
 *
 * <p><strong>Not a port</strong> -- saved queries are new behaviour, so there is no
 * {@code reference/ts} module behind this and none to look for.
 *
 * <p>Only reachable through {@link SavedQuerySchemas#parseCreateSavedQuery}, which is what makes
 * "unknown keys are stripped" structural rather than a rule someone has to remember: a caller sending
 * {@code ownerId}, {@code createdOn} or {@code page} has nowhere for it to land.
 *
 * <p><strong>The owner is not expressible here</strong>, and that is the point. {@code ownerId} and
 * {@code ownerName} come from the token, so a caller cannot post a query attributed to somebody else --
 * structurally, rather than by a check in a service a later method could omit. It is the same reason
 * {@code CreateWitnessInput} cannot name a case.
 *
 * @param name what the query is called, trimmed and within {@link SavedQuerySchemas#MAX_NAME_LENGTH}
 * @param description the optional longer explanation, absent where the caller sent none or sent only
 *     whitespace
 * @param shared whether it is published to the library, which makes it findable and not present in
 *     anyone else's list. A primitive {@code boolean} because the column has a default and no null
 * @param definition the view itself
 */
public record CreateSavedQueryInput(
    String name,
    Optional<String> description,
    boolean shared,
    SavedQueryDefinition definition) {
}
