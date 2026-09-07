package uk.gov.hmcts.reform.deedpoll.domain.documents;

import uk.gov.hmcts.reform.deedpoll.domain.FieldError;
import uk.gov.hmcts.reform.deedpoll.domain.InvalidInputError;
import uk.gov.hmcts.reform.deedpoll.domain.PatchField;
import uk.gov.hmcts.reform.deedpoll.entity.enums.DocumentDirection;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Shape validation for the two document payload pairs and the multipart upload form.
 *
 * <p>Ported from {@code reference/ts/documents/schemas.ts} and the primitives it builds on
 * ({@code reference/ts/schema-primitives.ts}). Five schemas, in the reference's own order: create and
 * update for a document's metadata, the upload form, and create and update for a document type.
 *
 * <p>Three behaviours are load-bearing and reproduced exactly:
 *
 * <ol>
 *   <li><strong>{@code documentTypeId} is checked as a UUID, so a bad foreign key fails before it reaches
 *       the database.</strong> A constraint violation names a column; this names the field.</li>
 *   <li><strong>{@code direction} must be a member of the option set.</strong> The reference records that
 *       the prototype read it as a raw body primitive and an unrecognised value reached the database, so
 *       a typo answered 500 for what is a 400.</li>
 *   <li><strong>Null is accepted where the column is nullable and refused where it is not</strong> -- the
 *       reference's {@code .nullish()} versus {@code .optional()} distinction. On a document that means the
 *       three storage fields may be cleared and the other three may not; on a type, none may.</li>
 * </ol>
 *
 * <p>Unknown keys are stripped rather than reported, as elsewhere in the port: only the named fields are
 * read, and none of the input records has anywhere to put anything else.
 *
 * <p>As in {@code DeedPollSchemas}, {@code NoteSchemas} and {@code WitnessSchemas}, only the throwing half
 * of the reference's {@code parse} / {@code safeParse} pair is ported -- {@link InvalidInputError} already
 * carries the per-field failures a {@code safeParse} caller would have inspected, attributed to the field
 * so a form can render each against its own input. Every offending field is reported rather than just the
 * first.
 */
public final class DocumentSchemas {

    /**
     * The 100-character cap on a document's and a type's {@code name}, from the {@code NVarChar(100)} both
     * columns declare in {@code reference/deed-poll.prisma}.
     */
    public static final int MAX_NAME_LENGTH = 100;

    /**
     * The canonical 8-4-4-4-12 hex form of a UUID.
     *
     * <p>Deliberately more permissive than the reference's {@code z.uuid()}, which also checks the version
     * and variant nibbles. What this guards is an id the database either holds or does not: rejecting a
     * legitimately-stored id merely because it was not generated as a version 4 would 400 a lookup that
     * would have succeeded, and a seeded or migrated row is exactly where such an id comes from. The
     * reference's own test asks only that {@code "not-a-uuid"} is refused, which this does.
     *
     * <p>{@link java.util.UUID#fromString} is not used for the same reason it is not used elsewhere: it
     * accepts {@code "1-1-1-1-1"} and other short forms, so it would let through strings no database column
     * of this schema contains.
     */
    private static final Pattern UUID_FORM =
        Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    private DocumentSchemas() {
    }

    /**
     * Validates a document create payload.
     *
     * <p>Accepts metadata with no file behind it, which is what makes a row that predates its blob
     * expressible -- see {@link CreateDocumentInput}.
     *
     * @throws InvalidInputError with one entry per missing, mistyped or over-long field
     */
    public static CreateDocumentInput parseCreateDocument(Map<String, Object> raw) {
        List<FieldError> errors = new ArrayList<>();

        DocumentDirection direction = requiredDirection(raw, errors);
        String name = requiredName(raw, "name", errors);
        String documentTypeId = requiredUuid(raw, "documentTypeId", errors);
        // Absent and null both mean an empty column here, so the two collapse -- see CreateDocumentInput.
        String storageKey = optionalText(raw, "storageKey", errors);
        String originalName = optionalText(raw, "originalName", errors);
        String mimeType = optionalText(raw, "mimeType", errors);

        reject(errors, "Document payload is not valid");
        return new CreateDocumentInput(direction, name, documentTypeId, storageKey, originalName, mimeType);
    }

    /**
     * Validates a document update patch. An empty patch is accepted; every field that IS present gets the
     * create rule, which is what {@code createDocumentSchema.partial()} means.
     *
     * @throws InvalidInputError with one entry per present-but-unacceptable field
     */
    public static UpdateDocumentInput parseUpdateDocument(Map<String, Object> raw) {
        List<FieldError> errors = new ArrayList<>();

        PatchField<DocumentDirection> direction = patched(raw, "direction", errors, false,
            value -> direction(value, "direction", errors));
        PatchField<String> name = patched(raw, "name", errors, false,
            value -> boundedText(value, "name", errors));
        PatchField<String> documentTypeId = patched(raw, "documentTypeId", errors, false,
            value -> uuid(value, "documentTypeId", errors));
        PatchField<String> storageKey = patched(raw, "storageKey", errors, true,
            value -> text(value, "storageKey", errors));
        PatchField<String> originalName = patched(raw, "originalName", errors, true,
            value -> text(value, "originalName", errors));
        PatchField<String> mimeType = patched(raw, "mimeType", errors, true,
            value -> text(value, "mimeType", errors));

        reject(errors, "Document payload is not valid");
        return new UpdateDocumentInput(direction, name, documentTypeId, storageKey, originalName, mimeType);
    }

    /**
     * Validates the non-file fields of an upload.
     *
     * <p>Every value arrives as text, because multipart carries no types -- so {@code direction} is matched
     * against the enum member names rather than expected to be an enum already.
     *
     * @throws InvalidInputError if the type id is missing or malformed, or either optional field is
     *     unacceptable
     */
    public static UploadFormInput parseUploadForm(Map<String, Object> raw) {
        List<FieldError> errors = new ArrayList<>();

        String documentTypeId = requiredUuid(raw, "documentTypeId", errors);
        Optional<String> name = present(raw, "name")
            ? Optional.ofNullable(boundedText(raw.get("name"), "name", errors))
            : Optional.empty();
        Optional<DocumentDirection> direction = present(raw, "direction")
            ? Optional.ofNullable(direction(raw.get("direction"), "direction", errors))
            : Optional.empty();

        reject(errors, "Upload is not valid");
        return new UploadFormInput(documentTypeId, name, direction);
    }

    /**
     * Validates a document-type create payload.
     *
     * @throws InvalidInputError with one entry per missing, mistyped or over-long field
     */
    public static CreateDocumentTypeInput parseCreateDocumentType(Map<String, Object> raw) {
        List<FieldError> errors = new ArrayList<>();

        String name = requiredName(raw, "name", errors);
        Optional<List<Integer>> maritalStatuses = optionSet(raw, "applicableMaritalStatuses", errors);
        Optional<List<Integer>> types = optionSet(raw, "applicableTypes", errors);

        reject(errors, "Document type payload is not valid");
        return new CreateDocumentTypeInput(name, maritalStatuses, types);
    }

    /**
     * Validates a document-type update patch. An empty patch is accepted; every field that IS present gets
     * the create rule.
     *
     * @throws InvalidInputError with one entry per present-but-unacceptable field
     */
    public static UpdateDocumentTypeInput parseUpdateDocumentType(Map<String, Object> raw) {
        List<FieldError> errors = new ArrayList<>();

        Optional<String> name = Optional.empty();
        if (present(raw, "name")) {
            Object value = raw.get("name");
            if (value == null) {
                errors.add(new FieldError("name", "name cannot be null"));
            } else {
                name = Optional.ofNullable(boundedText(value, "name", errors));
            }
        }
        Optional<List<Integer>> maritalStatuses = optionSet(raw, "applicableMaritalStatuses", errors);
        Optional<List<Integer>> types = optionSet(raw, "applicableTypes", errors);

        reject(errors, "Document type payload is not valid");
        return new UpdateDocumentTypeInput(name, maritalStatuses, types);
    }

    // --- required fields, for the create schemas ---

    private static DocumentDirection requiredDirection(Map<String, Object> raw, List<FieldError> errors) {
        Object value = raw.get("direction");
        if (value == null) {
            // Absent and explicitly null take the same message: what the caller has to do about it is
            // identical, and the column can hold neither.
            errors.add(new FieldError("direction", "direction is required"));
            return null;
        }
        return direction(value, "direction", errors);
    }

    private static String requiredName(Map<String, Object> raw, String field, List<FieldError> errors) {
        Object value = raw.get(field);
        if (value == null) {
            errors.add(new FieldError(field, field + " is required"));
            return null;
        }
        return boundedText(value, field, errors);
    }

    private static String requiredUuid(Map<String, Object> raw, String field, List<FieldError> errors) {
        Object value = raw.get(field);
        if (value == null) {
            errors.add(new FieldError(field, field + " is required"));
            return null;
        }
        return uuid(value, field, errors);
    }

    /** A nullable, uncapped text field on a create: absent or null both give null. */
    private static String optionalText(Map<String, Object> raw, String field, List<FieldError> errors) {
        Object value = raw.get(field);
        return value == null ? null : text(value, field, errors);
    }

    // --- the three value rules, each recording its own rejection ---

    private static DocumentDirection direction(Object value, String field, List<FieldError> errors) {
        if (value instanceof DocumentDirection member) {
            return member;
        }
        if (value instanceof String name) {
            for (DocumentDirection member : DocumentDirection.values()) {
                if (member.name().equals(name)) {
                    return member;
                }
            }
        }
        errors.add(new FieldError(field, field + " is not one of the accepted values"));
        return null;
    }

    private static String text(Object value, String field, List<FieldError> errors) {
        if (value instanceof String string) {
            return string;
        }
        // Refused rather than coerced: two form controls sharing a name arrive as a list, and storing
        // "a,b" would be inventing a value the caller did not send.
        errors.add(new FieldError(field, field + " must be text"));
        return null;
    }

    private static String boundedText(Object value, String field, List<FieldError> errors) {
        String string = text(value, field, errors);
        if (string == null) {
            return null;
        }
        if (string.length() > MAX_NAME_LENGTH) {
            errors.add(new FieldError(field, field + " must be " + MAX_NAME_LENGTH + " characters or fewer"));
            return null;
        }
        return string;
    }

    private static String uuid(Object value, String field, List<FieldError> errors) {
        String string = text(value, field, errors);
        if (string == null) {
            return null;
        }
        if (!UUID_FORM.matcher(string).matches()) {
            errors.add(new FieldError(field, field + " must be a UUID"));
            return null;
        }
        return string;
    }

    /**
     * A multiselect option set, matching {@code z.array(z.int()).optional()}.
     *
     * <p>The elements must be whole numbers, not text: the values are Dynamics option-set integers, and the
     * reference refuses both {@code ["1"]} and {@code [1.5]} so a client cannot get away with sending a
     * form-shaped payload to the JSON API, and so an option-set value is never silently truncated to a
     * neighbouring member.
     */
    private static Optional<List<Integer>> optionSet(Map<String, Object> raw, String field,
                                                     List<FieldError> errors) {
        if (!present(raw, field)) {
            return Optional.empty();
        }
        Object value = raw.get(field);
        if (value == null) {
            errors.add(new FieldError(field, field + " cannot be null"));
            return Optional.empty();
        }
        Collection<?> elements;
        if (value instanceof Collection<?> collection) {
            elements = collection;
        } else if (value instanceof Object[] array) {
            elements = List.of(array);
        } else {
            errors.add(new FieldError(field, field + " must be a list of whole numbers"));
            return Optional.empty();
        }
        List<Integer> numbers = new ArrayList<>();
        for (Object element : elements) {
            if (!(element instanceof Integer number)) {
                errors.add(new FieldError(field, field + " must be a list of whole numbers"));
                return Optional.empty();
            }
            numbers.add(number);
        }
        return Optional.of(List.copyOf(numbers));
    }

    // --- patch plumbing ---

    /**
     * One field of an update patch: absent if unmentioned, cleared if named as null and the column allows
     * it, otherwise whatever the rule accepts.
     *
     * <p>A rejected value still comes back as present, carrying null. That never reaches a caller -- the
     * error list is non-empty by then, so {@link #reject} throws first -- and it keeps this method from
     * having to decide what an unacceptable field means.
     */
    private static <T> PatchField<T> patched(Map<String, Object> raw, String field, List<FieldError> errors,
                                             boolean nullable, Function<Object, T> rule) {
        if (!present(raw, field)) {
            return PatchField.absent();
        }
        Object value = raw.get(field);
        if (value == null) {
            if (nullable) {
                return PatchField.cleared();
            }
            errors.add(new FieldError(field, field + " cannot be null"));
            return PatchField.cleared();
        }
        return PatchField.of(rule.apply(value));
    }

    private static boolean present(Map<String, Object> raw, String field) {
        return raw.containsKey(field);
    }

    private static void reject(List<FieldError> errors, String message) {
        if (!errors.isEmpty()) {
            throw new InvalidInputError(message, errors);
        }
    }
}
