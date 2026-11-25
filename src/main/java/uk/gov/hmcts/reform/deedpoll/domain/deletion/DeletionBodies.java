package uk.gov.hmcts.reform.deedpoll.domain.deletion;

import uk.gov.hmcts.reform.deedpoll.domain.InvalidInputError;
import uk.gov.hmcts.reform.deedpoll.domain.deletion.ApplicationDeletionService.DeletionCommand;

import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Turns the transport's header and keyed body into a {@link DeletionCommand}, strictly: nothing is coerced.
 *
 * <p>The body is closed: a key other than {@link #FIELDS} is refused, without being echoed. A value of the wrong JSON
 * type, a malformed key or an unparseable timestamp is passed on as absent, so {@link ApplicationDeletionService}
 * refuses it with the field named, in the same words as a missing one. Called only after the caller's capability is
 * checked, so a caller without it learns nothing from the body's refusal.
 */
public final class DeletionBodies {

    /** The only keys a deletion request takes. */
    public static final Set<String> FIELDS = Set.of(ApplicationDeletionService.REASON_FIELD, DeletionNote.FIELD,
                                                    ApplicationDeletionService.EXPECTED_LAST_CHANGED_ON_FIELD);

    static final String UNKNOWN_FIELDS = "A deletion request takes only reason, note and expectedLastChangedOn";

    private DeletionBodies() {
    }

    /**
     * The command this header and body describe.
     *
     * @param header the {@code Idempotency-Key} as sent, or null
     * @param fields the body's keys as sent; empty for no body
     * @throws InvalidInputError for a key outside {@link #FIELDS}
     */
    public static DeletionCommand command(String header, Map<String, Object> fields) {
        if (!FIELDS.containsAll(fields.keySet())) {
            throw new InvalidInputError(UNKNOWN_FIELDS);
        }
        return new DeletionCommand(key(header), text(fields.get(ApplicationDeletionService.REASON_FIELD)),
                                   text(fields.get(DeletionNote.FIELD)),
                                   timestamp(fields.get(ApplicationDeletionService.EXPECTED_LAST_CHANGED_ON_FIELD)));
    }

    /** A canonical UUID, or null: a near-miss spelling is not a key, nor a request id. */
    public static UUID key(String header) {
        if (header == null) {
            return null;
        }
        try {
            UUID key = UUID.fromString(header);
            return key.toString().equalsIgnoreCase(header) ? key : null;
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    private static String text(Object value) {
        return value instanceof String string ? string : null;
    }

    /**
     * ISO-8601 local date-time, as the case detail publishes {@code lastChangedOn}; anything else is null.
     */
    private static LocalDateTime timestamp(Object value) {
        if (!(value instanceof String string)) {
            return null;
        }
        try {
            return LocalDateTime.parse(string);
        } catch (DateTimeParseException invalid) {
            return null;
        }
    }
}
