package uk.gov.hmcts.reform.deedpoll.domain.documents;

import java.util.List;
import java.util.Optional;

/**
 * Create payload for a supporting-document type ({@code deedpoll_supportingdocumenttype}).
 *
 * <p>Ported from the {@code CreateDocumentTypeInput} type in {@code reference/ts/documents/schemas.ts}.
 * Reference data, not case data: a type is global and documents point at it by foreign key.
 *
 * <p>The two applicability lists are multiselect option-set values stored as {@code integer[]},
 * preserving the Dynamics integers so a future Dataverse re-import still lines up --
 * {@code applicableMaritalStatuses} against {@code MaritalStatus} and {@code applicableTypes} against
 * {@code DeedPollType}. They decide which document types a given case is asked for.
 *
 * <p>{@link Optional} rather than a defaulted empty list, because the reference's schema declares
 * {@code optionSetValues} as {@code .optional()} and its test asserts that parsing {@code {name}} alone
 * returns {@code {name}} alone -- an absent list stays absent. What that means on a create is "take the
 * column's own default", which is {@code []}, so the empty list is the database's statement rather than
 * this record's. Null is refused rather than accepted as a clear, for the reason
 * {@code schema-primitives.ts} gives: the column is NOT NULL because it has a default, so a null could
 * only ever fail later.
 */
public record CreateDocumentTypeInput(
    String name,
    Optional<List<Integer>> applicableMaritalStatuses,
    Optional<List<Integer>> applicableTypes) {

    /**
     * A type with a name and no applicability lists, which takes the columns' {@code []} defaults.
     */
    public static CreateDocumentTypeInput named(String name) {
        return new CreateDocumentTypeInput(name, Optional.empty(), Optional.empty());
    }
}
