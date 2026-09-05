package uk.gov.hmcts.reform.deedpoll.domain.documents;

import java.util.List;
import java.util.Optional;

/**
 * Update payload for a document type: the create rules, applied only to the fields that are present.
 *
 * <p>Ported from the {@code UpdateDocumentTypeInput} type in {@code reference/ts/documents/schemas.ts},
 * which is {@code createDocumentTypeSchema.partial()}.
 *
 * <p>{@link Optional} throughout rather than nullable fields, and unlike {@link UpdateDocumentInput} there
 * is no third state to keep: all three columns are NOT NULL -- {@code name} outright, the two lists by
 * having a {@code []} default -- so {@link DocumentSchemas#parseUpdateDocumentType} refuses an explicit
 * null rather than storing one. Emptying an applicability list is {@code []}, not null, and that is a
 * value like any other.
 */
public record UpdateDocumentTypeInput(
    Optional<String> name,
    Optional<List<Integer>> applicableMaritalStatuses,
    Optional<List<Integer>> applicableTypes) {

    /** An empty patch, which is accepted and changes nothing. */
    public static final UpdateDocumentTypeInput EMPTY = new UpdateDocumentTypeInput(
        Optional.empty(), Optional.empty(), Optional.empty());

    /** True when the patch names no field at all, so a caller can tell a no-op from a change. */
    public boolean isEmpty() {
        return name.isEmpty() && applicableMaritalStatuses.isEmpty() && applicableTypes.isEmpty();
    }
}
