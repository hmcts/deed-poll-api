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
 * <p><strong>Values are not trimmed</strong>, and that is deliberate rather than an omission. The notes
 * payload trims because a blank note passes an emptiness check and stores a row the grid renders blank;
 * here the reference declares plain {@code z.string().max(...)} on each field, and its test asserts that
 * {@code parse} returns exactly what it was given. Trimming would make this port store something
 * different from the specification it is being asserted against, which is a worse trade than surrounding
 * whitespace on a witness address. If it should trim, that belongs in the reference first.
 *
 * <p>For the same reason there is no emptiness check: {@code z.string()} accepts {@code ""} and the
 * reference's "requires every field" test removes keys rather than blanking them. An empty witness name
 * is a data-quality question for whichever form eventually writes one, not something this port may
 * invent.
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
     * @throws InvalidInputError with one entry per missing, mistyped or over-long field
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
     * The value if it is text within its cap, or empty if it is neither.
     *
     * <p>Takes {@link Object} because both callers receive untrusted input: a JSON body value is untyped,
     * and two form controls sharing a name arrive as a list. A non-string is refused rather than coerced,
     * so {@code ["a", "b"]} cannot be stored as {@code "a,b"}.
     */
    private static Optional<String> requireText(Object value, int maxLength) {
        if (!(value instanceof String string) || string.length() > maxLength) {
            return Optional.empty();
        }
        return Optional.of(string);
    }

    /** Which of the two text rules the value broke, so the message says which. */
    private static FieldError rejectedText(String field, Object value, int maxLength) {
        if (value instanceof String) {
            return new FieldError(field, field + " must be " + maxLength + " characters or fewer");
        }
        return new FieldError(field, field + " must be text");
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
