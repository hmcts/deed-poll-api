package uk.gov.hmcts.reform.deedpoll.domain.witnesses;

import uk.gov.hmcts.reform.deedpoll.domain.FieldError;
import uk.gov.hmcts.reform.deedpoll.domain.InvalidInputError;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Shape validation for a witness create payload and a witness update patch.
 *
 * <p>Ported from {@code reference/ts/witnesses/schemas.ts}. Three properties of that schema are
 * load-bearing and reproduced exactly:
 *
 * <ol>
 *   <li><strong>All four fields are required on create</strong>, because all four source columns are
 *       {@code NOT NULL}. A witness comes off a signed declaration, so there is no incremental-capture
 *       case to accommodate the way the deed poll itself has.</li>
 *   <li><strong>The caps mirror the source columns</strong> -- 100 / 250 / 100, the widths
 *       {@code DeedPollWitness} declares in {@code reference/deed-poll.prisma}. Rejecting here means a
 *       caseworker gets a message naming the field rather than a driver error naming the column.</li>
 *   <li><strong>{@code declarant} must be a boolean, not a truthy string.</strong> The reference asserts
 *       this specifically: {@code "true"} is refused rather than coerced, because the one string that
 *       would coerce wrongly -- {@code "false"} -- is truthy in JavaScript, and a declarant silently
 *       recorded as an ordinary witness changes what the case means.</li>
 * </ol>
 *
 * <p><strong>The three text fields are trimmed and must not be blank</strong>, as the reference's
 * {@code z.string().trim().min(1).max(...)} says since admin review finding R15: a blank witness counted
 * towards the enrolment-witness threshold while telling nobody who had witnessed anything. Whitespace alone
 * is blank, because it is trimmed first; the trimmed value is what is stored; and the caps are measured after
 * trimming, so padding a value that fits does not refuse it. The trim is JavaScript's
 * {@code String.prototype.trim}, which zod calls, rather than Java's {@code trim()} or {@code strip()}, which
 * strip different sets: the port strips exactly what admin strips before it sends. {@code declarant} is
 * unchanged.
 *
 * <p>Unknown keys are stripped rather than reported, as elsewhere in the port: only the four field names
 * are read, and neither input record has anywhere to put anything else.
 *
 * <p>As in {@code DeedPollSchemas} and {@code NoteSchemas}, only the throwing half of the reference's
 * {@code parse} / {@code safeParse} pair is ported -- {@link InvalidInputError} already carries the
 * per-field failures a {@code safeParse} caller would have inspected, attributed to the field so a form
 * can render each against its own input.
 */
public final class WitnessSchemas {

    /**
     * {@code witnessName}'s cap, from the {@code NVarChar(100)} the reference schema declares.
     */
    public static final int MAX_WITNESS_NAME_LENGTH = 100;

    /**
     * {@code witnessAddress}'s cap, from {@code NVarChar(250)}.
     */
    public static final int MAX_WITNESS_ADDRESS_LENGTH = 250;

    /**
     * {@code witnessOccupation}'s cap, from {@code NVarChar(100)}.
     */
    public static final int MAX_WITNESS_OCCUPATION_LENGTH = 100;

    /** The three text fields and their caps, in the reference schema's declaration order. */
    private static final Map<String, Integer> TEXT_FIELDS = textFields();

    /** The one boolean field, named here so the field list is stated in one place. */
    private static final String DECLARANT = "declarant";

    private WitnessSchemas() {
    }

    /**
     * Validates a create payload.
     *
     * <p>Every offending field is reported, not just the first: a caseworker retyping a declaration
     * should see all of its problems at once, and {@link InvalidInputError} carries a list for exactly
     * that.
     *
     * @throws InvalidInputError with one entry per missing, mistyped, blank or over-long field
     */
    public static CreateWitnessInput parseCreateWitness(Map<String, Object> raw) {
        List<FieldError> errors = new ArrayList<>();
        Map<String, String> text = new LinkedHashMap<>();

        TEXT_FIELDS.forEach((field, maxLength) -> {
            Object value = raw.get(field);
            if (value == null) {
                // Absent and explicitly null take the same message: what the caller has to do about it is
                // identical, and the source column can hold neither.
                errors.add(new FieldError(field, field + " is required"));
                return;
            }
            requireText(value, maxLength).ifPresentOrElse(
                accepted -> text.put(field, accepted),
                () -> errors.add(rejectedText(field, value, maxLength)));
        });

        Object declarant = raw.get(DECLARANT);
        if (declarant == null) {
            errors.add(new FieldError(DECLARANT, DECLARANT + " is required"));
        } else if (!(declarant instanceof Boolean)) {
            errors.add(new FieldError(DECLARANT, DECLARANT + " must be true or false"));
        }

        if (!errors.isEmpty()) {
            throw new InvalidInputError("Witness payload is not valid", errors);
        }
        return new CreateWitnessInput(
            text.get("witnessName"),
            text.get("witnessAddress"),
            text.get("witnessOccupation"),
            (Boolean) declarant);
    }

    /**
     * Validates an update patch. An empty patch is accepted; every field that IS present gets the create
     * rule, which is what {@code createWitnessSchema.partial()} means.
     *
     * <p>A field present as null is refused rather than accepted as a clear, because the column cannot
     * hold one -- the same choice {@code DeedPollSchemas} makes for its non-nullable fields, and better a
     * validation error naming the field than a database error naming the constraint.
     *
     * @throws InvalidInputError with one entry per present-but-unacceptable field
     */
    public static UpdateWitnessInput parseUpdateWitness(Map<String, Object> raw) {
        List<FieldError> errors = new ArrayList<>();
        Map<String, Optional<String>> text = new LinkedHashMap<>();

        TEXT_FIELDS.forEach((field, maxLength) -> {
            if (!raw.containsKey(field)) {
                text.put(field, Optional.empty());
                return;
            }
            Object value = raw.get(field);
            if (value == null) {
                errors.add(new FieldError(field, field + " cannot be null"));
                text.put(field, Optional.empty());
                return;
            }
            Optional<String> accepted = requireText(value, maxLength);
            if (accepted.isEmpty()) {
                errors.add(rejectedText(field, value, maxLength));
            }
            text.put(field, accepted);
        });

        Optional<Boolean> declarant = Optional.empty();
        if (raw.containsKey(DECLARANT)) {
            Object value = raw.get(DECLARANT);
            if (value == null) {
                errors.add(new FieldError(DECLARANT, DECLARANT + " cannot be null"));
            } else if (value instanceof Boolean flag) {
                declarant = Optional.of(flag);
            } else {
                errors.add(new FieldError(DECLARANT, DECLARANT + " must be true or false"));
            }
        }

        if (!errors.isEmpty()) {
            throw new InvalidInputError("Witness payload is not valid", errors);
        }
        return new UpdateWitnessInput(
            text.get("witnessName"),
            text.get("witnessAddress"),
            text.get("witnessOccupation"),
            declarant);
    }

    /**
     * The trimmed value if it is text that is neither blank nor over its cap once trimmed, or empty if not.
     *
     * <p>Takes {@link Object} because both callers receive untrusted input: a JSON body value is untyped,
     * and two form controls sharing a name arrive as a list. A non-string is refused rather than coerced,
     * so {@code ["a", "b"]} cannot be stored as {@code "a,b"}.
     */
    private static Optional<String> requireText(Object value, int maxLength) {
        if (!(value instanceof String string)) {
            return Optional.empty();
        }
        String trimmed = trim(string);
        if (trimmed.isEmpty() || trimmed.length() > maxLength) {
            return Optional.empty();
        }
        return Optional.of(trimmed);
    }

    /** Which of the three text rules the value broke, so the message says which. */
    private static FieldError rejectedText(String field, Object value, int maxLength) {
        if (!(value instanceof String string)) {
            return new FieldError(field, field + " must be text");
        }
        if (trim(string).isEmpty()) {
            return new FieldError(field, field + " must not be blank");
        }
        return new FieldError(field, field + " must be " + maxLength + " characters or fewer");
    }

    /**
     * JavaScript's {@code String.prototype.trim}: strips the ECMAScript WhiteSpace and LineTerminator code
     * points from both ends. That is tab, vertical tab, form feed, space, no-break space, the byte order mark,
     * every other space separator, line feed, carriage return and the line and paragraph separators -- and,
     * unlike Java's {@code trim()}, no other control character.
     */
    static String trim(String value) {
        int start = 0;
        int end = value.length();
        while (start < end && isJavaScriptWhitespace(value.charAt(start))) {
            start++;
        }
        while (end > start && isJavaScriptWhitespace(value.charAt(end - 1))) {
            end--;
        }
        return value.substring(start, end);
    }

    private static boolean isJavaScriptWhitespace(char c) {
        return switch (c) {
            case '\t', '\n', '\u000B', '\f', '\r', '\uFEFF' -> true;
            default -> Character.getType(c) == Character.SPACE_SEPARATOR
                || Character.getType(c) == Character.LINE_SEPARATOR
                || Character.getType(c) == Character.PARAGRAPH_SEPARATOR;
        };
    }

    private static Map<String, Integer> textFields() {
        Map<String, Integer> fields = new LinkedHashMap<>();
        fields.put("witnessName", MAX_WITNESS_NAME_LENGTH);
        fields.put("witnessAddress", MAX_WITNESS_ADDRESS_LENGTH);
        fields.put("witnessOccupation", MAX_WITNESS_OCCUPATION_LENGTH);
        // Insertion order, not Map.copyOf: the errors are reported in the order the fields are visited,
        // and that order is the reference schema's so a form lists them the way it lays them out.
        return Collections.unmodifiableMap(fields);
    }
}
