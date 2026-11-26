package uk.gov.hmcts.reform.deedpoll.domain.documents.deletion;

import uk.gov.hmcts.reform.deedpoll.domain.InvalidInputError;
import uk.gov.hmcts.reform.deedpoll.domain.deletion.DeletionBodies;
import uk.gov.hmcts.reform.deedpoll.domain.documents.deletion.DocumentDeletionService.DocumentDeletionCommand;

import java.util.Map;
import java.util.Set;

/**
 * Turns the transport's header and keyed body into a {@link DocumentDeletionCommand}, strictly: nothing is coerced.
 *
 * <p>The body is closed: a key other than {@link #FIELDS} is refused, without being echoed. A value of the wrong JSON
 * type is passed on as absent, so {@link DocumentDeletionService} refuses it with the field named, in the words a
 * missing one gets: a reason must be a JSON string, and a version a JSON integer ({@code 3}, never {@code "3"} or
 * {@code 3.0}). The key is a canonical UUID or nothing, as application deletion's is.
 */
public final class DocumentDeletionBodies {

    /** The only keys a document deletion request takes. */
    public static final Set<String> FIELDS = Set.of(DocumentDeletionReason.FIELD,
                                                    DocumentDeletionService.EXPECTED_DOCUMENT_VERSION_FIELD);

    static final String UNKNOWN_FIELDS = "A document deletion request takes only reason and expectedDocumentVersion";

    private DocumentDeletionBodies() {
    }

    /**
     * The command this header and body describe.
     *
     * @param header the {@code Idempotency-Key} as sent, or null
     * @param fields the body's keys as sent; empty for no body
     * @throws InvalidInputError for a key outside {@link #FIELDS}
     */
    public static DocumentDeletionCommand command(String header, Map<String, Object> fields) {
        if (!FIELDS.containsAll(fields.keySet())) {
            throw new InvalidInputError(UNKNOWN_FIELDS);
        }
        Object reason = fields.get(DocumentDeletionReason.FIELD);
        Object version = fields.get(DocumentDeletionService.EXPECTED_DOCUMENT_VERSION_FIELD);
        return new DocumentDeletionCommand(DeletionBodies.key(header), reason instanceof String text ? text : null,
                                           version(version));
    }

    /**
     * A JSON integer that fits a {@code long}; Jackson binds those as {@link Integer} or {@link Long} only.
     */
    private static Long version(Object value) {
        if (value instanceof Integer number) {
            return number.longValue();
        }
        if (value instanceof Long number) {
            return number;
        }
        return null;
    }
}
